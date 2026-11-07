import type { Server } from "node:http";
import { WebSocket, WebSocketServer } from "ws";
import type { Pool } from "pg";
import { principalForToken } from "./auth";
import type { EventPublisher } from "./app";
import type { Principal } from "./types";

interface SocketClient {
  socket: WebSocket;
  principal: Principal;
}

function eventId(data: Record<string, unknown>, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = data[key];
    if (typeof value === "string" && value.length > 0) return value;
  }
  return null;
}

export class RealtimeHub implements EventPublisher {
  private readonly clients = new Set<SocketClient>();
  private webSocketServer: WebSocketServer | null = null;

  constructor(private readonly pool: Pool) {}

  attach(server: Server): void {
    const webSocketServer = new WebSocketServer({ noServer: true, maxPayload: 16 * 1024 });
    this.webSocketServer = webSocketServer;
    server.on("upgrade", (request, socket, head) => {
      const requestUrl = new URL(request.url ?? "/", "http://localhost");
      if (requestUrl.pathname !== "/ws") {
        socket.destroy();
        return;
      }
      void this.acceptUpgrade(webSocketServer, request, socket, head);
    });
  }

  private async acceptUpgrade(
    webSocketServer: WebSocketServer,
    request: import("node:http").IncomingMessage,
    socket: import("node:stream").Duplex,
    head: Buffer
  ): Promise<void> {
    try {
      const authorization = request.headers.authorization;
      const match = typeof authorization === "string"
        ? /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authorization)
        : null;
      const token = match?.[1] ?? null;
      const principal = token ? await principalForToken(this.pool, token) : null;
      if (!principal) {
        socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
        socket.destroy();
        return;
      }
      webSocketServer.handleUpgrade(request, socket, head, (webSocket) => {
        const client = { socket: webSocket, principal };
        this.clients.add(client);
        webSocket.on("close", () => this.clients.delete(client));
        webSocket.on("error", () => this.clients.delete(client));
        webSocket.on("message", () => {
          webSocket.close(1008, "Client messages are not supported");
        });
      });
    } catch {
      socket.destroy();
    }
  }

  publish(type: string, data: Record<string, unknown>): void {
    const event = JSON.stringify({ type, data });
    for (const client of this.clients) {
      if (client.socket.readyState !== WebSocket.OPEN) {
        this.clients.delete(client);
        continue;
      }
      if (client.principal.expiresAt.getTime() <= Date.now()) {
        client.socket.close(1008, "Session expired");
        this.clients.delete(client);
        continue;
      }
      void this.isVisible(client.principal, type, data).then((visible) => {
        if (visible && client.socket.readyState === WebSocket.OPEN) client.socket.send(event);
      }).catch(() => {
        client.socket.close(1011, "Event authorization failed");
        this.clients.delete(client);
      });
    }
  }

  private async isVisible(
    principal: Principal,
    type: string,
    data: Record<string, unknown>
  ): Promise<boolean> {
    if (principal.role === "ADMIN") return true;

    if (principal.role === "DRIVER") {
      if (type === "arrival") {
        const arrivalId = eventId(data, "id");
        if (!arrivalId) return false;
        const result = await this.pool.query(
          "SELECT 1 FROM trip_arrivals WHERE id = $1 AND confirmed_by = $2",
          [arrivalId, principal.id]
        );
        return Boolean(result.rowCount);
      }
      if (type === "location" || type.startsWith("trip-")) {
        const tripId = eventId(data, "tripId", "id");
        if (!tripId) return false;
        const result = await this.pool.query(
          `SELECT 1 FROM trips
           WHERE id = $1 AND (created_by = $2 OR lower(driver) = lower($3))`,
          [tripId, principal.id, principal.username]
        );
        return Boolean(result.rowCount);
      }
      if (type === "scan") {
        const scanId = eventId(data, "id");
        if (!scanId) return false;
        const result = await this.pool.query(
          "SELECT 1 FROM scans WHERE id = $1 AND created_by = $2",
          [scanId, principal.id]
        );
        return Boolean(result.rowCount);
      }
      if (type === "emergency") {
        const emergencyId = eventId(data, "id");
        if (!emergencyId) return false;
        const result = await this.pool.query(
          "SELECT 1 FROM emergencies WHERE id = $1 AND created_by = $2",
          [emergencyId, principal.id]
        );
        return Boolean(result.rowCount);
      }
      return false;
    }

    if (type === "scan" || type === "arrival") {
      const studentId = eventId(data, "studentId");
      if (!studentId) return false;
      const result = await this.pool.query(
        "SELECT 1 FROM parent_links WHERE parent_id = $1 AND child_id = $2",
        [principal.id, studentId]
      );
      return Boolean(result.rowCount);
    }

    const route = typeof data.route === "string" ? data.route : null;
    if (!route) return false;
    const result = await this.pool.query(
      `SELECT 1 FROM parent_links pl JOIN children c ON c.id = pl.child_id
       WHERE pl.parent_id = $1 AND lower(c.route) = lower($2) LIMIT 1`,
      [principal.id, route]
    );
    return Boolean(result.rowCount);
  }

  close(): void {
    for (const client of this.clients) client.socket.close(1001, "Server shutting down");
    this.clients.clear();
    this.webSocketServer?.close();
    this.webSocketServer = null;
  }
}
