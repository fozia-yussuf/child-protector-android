import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import path from "node:path";
import { newDb } from "pg-mem";
import request from "supertest";
import type { Pool } from "pg";
import WebSocket from "ws";
import { createApp } from "./app";
import { ensureAdminAccount, hashSessionToken } from "./auth";
import { runMigrations } from "./migrate";
import { RealtimeHub } from "./realtime";

const ADMIN_PASSWORD = "backend-admin-test-password";
const SCORE = 0.363;
const today = () => new Date().toISOString().slice(0, 10);

interface Fixture {
  pool: Pool;
  app: ReturnType<typeof createApp>;
  adminToken: string;
  driverToken: string;
  parentToken: string;
  secondParentToken: string;
  events: { publish: jest.Mock };
}

async function createFixture(): Promise<Fixture> {
  process.env.SESSION_TTL_HOURS = "24";
  const memoryDb = newDb({ autoCreateForeignKeyIndices: true });
  const adapter = memoryDb.adapters.createPg();
  const pool = new adapter.Pool() as unknown as Pool;
  await runMigrations(pool, path.resolve(__dirname, "../migrations"));
  await ensureAdminAccount(pool, ADMIN_PASSWORD);
  const events = { publish: jest.fn() };
  const app = createApp(pool, { minFaceMatchScore: SCORE, events });

  const adminToken = await login(app, "admin", ADMIN_PASSWORD, "ADMIN");
  const driverToken = await createUser(app, "driver1", "DRIVER");
  const parentToken = await createUser(app, "parent1", "PARENT");
  const secondParentToken = await createUser(app, "parent2", "PARENT");
  return { pool, app, adminToken, driverToken, parentToken, secondParentToken, events };
}

async function createUser(
  app: ReturnType<typeof createApp>,
  username: string,
  role: "PARENT" | "DRIVER"
): Promise<string> {
  const password = "test-password-123";
  await request(app).post("/api/auth/signup").send({ username, password, role }).expect(201);
  return login(app, username, password, role);
}

async function login(
  app: ReturnType<typeof createApp>,
  username: string,
  password: string,
  role: "PARENT" | "DRIVER" | "ADMIN"
): Promise<string> {
  const response = await request(app)
    .post("/api/auth/login")
    .send({ username, password, role })
    .expect(200);
  return response.body.data.session as string;
}

async function createChild(
  fixture: Fixture,
  fields: Partial<{
    studentId: string;
    name: string;
    className: string;
    route: string;
    parentUsername: string;
  }> = {}
): Promise<Record<string, unknown>> {
  const response = await request(fixture.app)
    .post("/api/children")
    .set("Authorization", `Bearer ${fixture.adminToken}`)
    .send({
      studentId: fields.studentId ?? `student-${randomUUID()}`,
      name: fields.name ?? "Sample Student",
      className: fields.className ?? "Class 4",
      route: fields.route ?? "Route A",
      photo: "data:image/jpeg;base64,ZmFrZS1pbWFnZQ==",
      parentConsentObtained: true,
      ...(fields.parentUsername ? { parentUsername: fields.parentUsername } : {})
    })
    .expect(201);
  return response.body.data as Record<string, unknown>;
}

async function createTrip(
  fixture: Fixture,
  token = fixture.driverToken,
  route = "Route A"
): Promise<Record<string, unknown>> {
  const response = await request(fixture.app)
    .post("/api/trips")
    .set("Authorization", `Bearer ${token}`)
    .send({ route, vehicle: "BUS-01", driver: "driver1", tripType: "Morning" })
    .expect(201);
  return response.body.data as Record<string, unknown>;
}

function scanBody(studentId: string, tripId: string, overrides: Record<string, unknown> = {}) {
  return {
    studentId,
    tripId,
    phase: "MORNING",
    scanDate: today(),
    faceMatchPassed: true,
    faceMatchScore: SCORE,
    scanMethod: "FACE_MATCH",
    scannedFacePhoto: "c2Nhbm5lZC1mcmFtZQ==",
    ...overrides
  };
}

function authed(fixture: Fixture, token: string) {
  const authorization = `Bearer ${token}`;
  return {
    get: (url: string) => request(fixture.app).get(url).set("Authorization", authorization),
    post: (url: string) => request(fixture.app).post(url).set("Authorization", authorization),
    put: (url: string) => request(fixture.app).put(url).set("Authorization", authorization),
    delete: (url: string) => request(fixture.app).delete(url).set("Authorization", authorization)
  };
}

describe("Child Protector API", () => {
  let fixture: Fixture;

  beforeEach(async () => {
    fixture = await createFixture();
  });

  afterEach(async () => {
    await fixture.pool.end();
  });

  test("health endpoint reports service health", async () => {
    await request(fixture.app).get("/health").expect(200, { status: "ok" });
  });

  describe("authentication", () => {
    test("signup and login create a bearer session stored only as a SHA-256 hash", async () => {
      await request(fixture.app)
        .post("/api/auth/signup")
        .send({ username: " NewUser ", password: "new-user-password", role: "PARENT" })
        .expect(201);
      const response = await request(fixture.app)
        .post("/api/auth/login")
        .send({ username: "newuser", password: "new-user-password", role: "PARENT" })
        .expect(200);
      const token = response.body.data.session as string;
      expect(token).toHaveLength(43);
      expect(response.body.data).toMatchObject({ username: "newuser", role: "PARENT" });
      const sessions = await fixture.pool.query<{ token_hash: string }>(
        "SELECT token_hash FROM sessions"
      );
      expect(sessions.rows.some((row) => row.token_hash === token)).toBe(false);
      expect(sessions.rows.every((row) => /^[a-f0-9]{64}$/.test(row.token_hash))).toBe(true);
    });

    test("admin can sign in only with the configured password and ADMIN role", async () => {
      const response = await request(fixture.app)
        .post("/api/auth/login")
        .send({ username: "admin", password: ADMIN_PASSWORD, role: "ADMIN" })
        .expect(200);
      expect(response.body.data.role).toBe("ADMIN");
      await request(fixture.app)
        .post("/api/auth/login")
        .send({ username: "admin", password: "wrong-password", role: "ADMIN" })
        .expect(401);
      await request(fixture.app)
        .post("/api/auth/login")
        .send({ username: "admin", password: ADMIN_PASSWORD, role: "DRIVER" })
        .expect(401);
    });

    test("admin signup is rejected with 403", async () => {
      await request(fixture.app)
        .post("/api/auth/signup")
        .send({ username: "someone", password: "password-for-test", role: "ADMIN" })
        .expect(403);
    });

    test("protected routes reject missing, malformed, and expired sessions", async () => {
      await request(fixture.app).get("/api/trips").expect(401);
      await request(fixture.app)
        .get("/api/trips")
        .set("Authorization", "Bearer invalid")
        .expect(401);
      const expiredToken = "e".repeat(43);
      await fixture.pool.query(
        `INSERT INTO sessions (token_hash, account_id, expires_at)
         SELECT $1, id, NOW() - INTERVAL '1 hour' FROM accounts WHERE username = 'driver1'`,
        [hashSessionToken(expiredToken)]
      );
      await request(fixture.app)
        .get("/api/trips")
        .set("Authorization", `Bearer ${expiredToken}`)
        .expect(401);
    });
  });

  describe("role enforcement", () => {
    test("driver cannot use admin overview or register a child", async () => {
      await authed(fixture, fixture.driverToken).get("/api/admin/overview").expect(403);
      await authed(fixture, fixture.driverToken)
        .post("/api/children")
        .send({})
        .expect(403);
    });

    test("parent cannot use admin or driver-only routes", async () => {
      await authed(fixture, fixture.parentToken).get("/api/admin/parents").expect(403);
      await authed(fixture, fixture.parentToken).get("/api/children").expect(403);
      await authed(fixture, fixture.parentToken)
        .post("/api/scan")
        .send({})
        .expect(403);
    });
  });

  describe("boarding scan validation", () => {
    test("checks student existence before trip existence", async () => {
      const trip = await createTrip(fixture);
      const response = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(randomUUID(), String(trip.id)))
        .expect(404);
      expect(response.body.message).toBe("Student not found");
    });

    test("rejects a missing trip after the student exists", async () => {
      const child = await createChild(fixture);
      const response = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), randomUUID()))
        .expect(404);
      expect(response.body.message).toBe("Trip not found");
    });

    test("rejects a trip that is not active", async () => {
      const child = await createChild(fixture);
      const trip = await createTrip(fixture);
      await authed(fixture, fixture.driverToken)
        .post(`/api/trips/${trip.id}/end`)
        .send({})
        .expect(200);
      const response = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id)))
        .expect(409);
      expect(response.body.message).toBe("Trip is not active");
    });

    test("rejects a student whose route does not match the trip", async () => {
      const child = await createChild(fixture, { route: "Route B" });
      const trip = await createTrip(fixture, fixture.driverToken, "Route A");
      const response = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id)))
        .expect(409);
      expect(response.body.message).toContain("route does not match");
    });

    test("checks duplicate scan before the face score rule", async () => {
      const child = await createChild(fixture);
      const trip = await createTrip(fixture);
      await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id), {
          scanMethod: "MANUAL",
          faceMatchPassed: false,
          faceMatchScore: undefined
        }))
        .expect(201);
      const response = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id), {
          faceMatchPassed: false,
          faceMatchScore: 0
        }))
        .expect(409);
      expect(response.body.message).toContain("already scanned");
    });

    test("requires a passed face check and score at or above the configured minimum", async () => {
      const child = await createChild(fixture);
      const trip = await createTrip(fixture);
      const failed = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id), { faceMatchPassed: false }))
        .expect(422);
      expect(failed.body.message).toContain("Face match must pass");
      const low = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id), { faceMatchScore: SCORE - 0.001 }))
        .expect(422);
      expect(low.body.message).toContain(String(SCORE));
      const missing = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id), { faceMatchScore: undefined }))
        .expect(422);
      expect(missing.body.message).toContain("Face match must pass");
    });

    test("accepts a score exactly at the minimum and broadcasts a scan without raw image data", async () => {
      const child = await createChild(fixture);
      const trip = await createTrip(fixture);
      const response = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id)))
        .expect(201);
      expect(response.body.data.faceMatchScore).toBe(SCORE);
      expect(fixture.events.publish).toHaveBeenCalledWith(
        "scan",
        expect.objectContaining({ id: response.body.data.id })
      );
      const scanEvent = fixture.events.publish.mock.calls.find(([type]) => type === "scan");
      expect(scanEvent?.[1]).not.toHaveProperty("scannedFacePhoto");
    });

    test("allows an explicitly audited manual scan to bypass automatic face matching", async () => {
      const child = await createChild(fixture);
      const trip = await createTrip(fixture);
      const response = await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id), {
          scanMethod: "MANUAL",
          faceMatchPassed: false,
          faceMatchScore: undefined
        }))
        .expect(201);
      expect(response.body.data).toMatchObject({
        scanMethod: "MANUAL",
        faceMatchPassed: false,
        faceMatchScore: null
      });
    });
  });

  describe("parent isolation", () => {
    test("parent endpoints return only linked children, route trips, scans, and emergencies", async () => {
      const childA = await createChild(fixture, { route: "Route A" });
      const childB = await createChild(fixture, { route: "Route B" });
      await authed(fixture, fixture.adminToken)
        .post("/api/admin/parent-links")
        .send({ parentId: await parentId(fixture, "parent1"), childId: childA.id })
        .expect(201);
      const tripA = await createTrip(fixture, fixture.driverToken, "Route A");
      const tripB = await createTrip(fixture, fixture.driverToken, "Route B");
      await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(childA.id), String(tripA.id), { scanMethod: "MANUAL" }))
        .expect(201);
      await authed(fixture, fixture.driverToken)
        .post("/api/scan")
        .send(scanBody(String(childB.id), String(tripB.id), { scanMethod: "MANUAL" }))
        .expect(201);
      await authed(fixture, fixture.driverToken)
        .post("/api/emergencies")
        .send({
          vehicle: "BUS-A", driver: "driver1", route: "Route A",
          description: "Route A alert", latitude: 10, longitude: 20
        })
        .expect(201);
      await authed(fixture, fixture.driverToken)
        .post("/api/emergencies")
        .send({
          vehicle: "BUS-B", driver: "driver1", route: "Route B",
          description: "Route B alert", latitude: 30, longitude: 40
        })
        .expect(201);

      const children = await authed(fixture, fixture.parentToken)
        .get("/api/parent/children").expect(200);
      expect(children.body.data.map((row: { id: string }) => row.id)).toEqual([childA.id]);

      const trips = await authed(fixture, fixture.parentToken)
        .get("/api/parent/trips").expect(200);
      expect(trips.body.data.map((row: { route: string }) => row.route)).toEqual(["Route A"]);

      const scans = await authed(fixture, fixture.parentToken)
        .get(`/api/parent/scans?date=${today()}`).expect(200);
      expect(scans.body.data.map((row: { studentId: string }) => row.studentId)).toEqual([childA.id]);

      const emergencies = await authed(fixture, fixture.parentToken)
        .get("/api/parent/emergencies").expect(200);
      expect(emergencies.body.data.map((row: { route: string }) => row.route)).toEqual(["Route A"]);

      const secondParentChildren = await authed(fixture, fixture.secondParentToken)
        .get("/api/parent/children").expect(200);
      expect(secondParentChildren.body.data).toEqual([]);
    });
  });

  describe("trip location", () => {
    test("driver can update an owned active trip location and broadcasts the location", async () => {
      const trip = await createTrip(fixture);
      const response = await authed(fixture, fixture.driverToken)
        .put(`/api/trips/${trip.id}/location`)
        .send({ latitude: 1.25, longitude: 103.8, accuracy: 5, timestamp: Date.now() })
        .expect(200);
      expect(response.body.data).toMatchObject({
        latitude: 1.25,
        longitude: 103.8,
        accuracy: 5
      });
      expect(response.body.data.locationUpdatedAt).toBeTruthy();
      expect(fixture.events.publish).toHaveBeenCalledWith(
        "location",
        expect.objectContaining({ tripId: trip.id, latitude: 1.25, longitude: 103.8 })
      );
    });

    test("driver cannot update another driver's trip", async () => {
      const trip = await createTrip(fixture);
      const otherDriver = await createUser(fixture.app, "driver2", "DRIVER");
      await authed(fixture, otherDriver)
        .put(`/api/trips/${trip.id}/location`)
        .send({ latitude: 1, longitude: 2, accuracy: 3 })
        .expect(404);
    });

    test("driver cannot scan a trip owned by another driver", async () => {
      const child = await createChild(fixture);
      const trip = await createTrip(fixture);
      const otherDriver = await createUser(fixture.app, "driver2", "DRIVER");
      await authed(fixture, otherDriver)
        .post("/api/scan")
        .send(scanBody(String(child.id), String(trip.id)))
        .expect(404);
    });

    test("driver can use an admin-created trip assigned to their username", async () => {
      const trip = await createTrip(fixture, fixture.adminToken, "Route A");
      await fixture.pool.query(
        "UPDATE trips SET driver = 'driver1' WHERE id = $1",
        [trip.id]
      );
      const ownTrips = await authed(fixture, fixture.driverToken).get("/api/trips").expect(200);
      expect(ownTrips.body.data.map((row: { id: string }) => row.id)).toContain(trip.id);
      await authed(fixture, fixture.driverToken)
        .put(`/api/trips/${trip.id}/location`)
        .send({ latitude: 1, longitude: 2, accuracy: 3 })
        .expect(200);
    });
  });

  describe("emergencies", () => {
    test("supports driver SOS creation and admin emergency update, resolve, and delete", async () => {
      const created = await authed(fixture, fixture.driverToken)
        .post("/api/emergencies")
        .send({
          vehicle: "BUS-01", driver: "driver1", route: "Route A",
          description: "SOS alert", latitude: 1, longitude: 2
        })
        .expect(201);
      const id = created.body.data.id as string;
      expect(created.body.data.status).toBe("OPEN");
      expect(created.body.data.latitude).toBe(1);
      expect(fixture.events.publish).toHaveBeenCalledWith(
        "emergency",
        expect.objectContaining({ id })
      );
      await authed(fixture, fixture.adminToken)
        .put(`/api/emergencies/${id}`)
        .send({ description: "Responding", status: "RESOLVED" })
        .expect(200)
        .expect(({ body }) => {
          expect(body.data.status).toBe("RESOLVED");
          expect(body.data.description).toBe("Responding");
        });
      await authed(fixture, fixture.adminToken)
        .delete(`/api/emergencies/${id}`).expect(200);
      await authed(fixture, fixture.adminToken)
        .get("/api/emergencies").expect(200)
        .expect(({ body }) => expect(body.data).toEqual([]));
    });

    test("driver cannot edit or delete emergencies", async () => {
      const emergency = await authed(fixture, fixture.driverToken)
        .post("/api/emergencies")
        .send({
          vehicle: "BUS-01", driver: "driver1", route: "Route A", description: "SOS"
        })
        .expect(201);
      await authed(fixture, fixture.driverToken)
        .put(`/api/emergencies/${emergency.body.data.id}`)
        .send({ status: "CLOSED" }).expect(403);
      await authed(fixture, fixture.driverToken)
        .delete(`/api/emergencies/${emergency.body.data.id}`).expect(403);
    });
  });

  describe("parent-link administration", () => {
    test("creates and removes a link and rejects non-parent accounts", async () => {
      const child = await createChild(fixture);
      const parentIdValue = await parentId(fixture, "parent1");
      const link = { parentId: parentIdValue, childId: child.id };
      await authed(fixture, fixture.adminToken)
        .post("/api/admin/parent-links").send(link).expect(201);
      const list = await authed(fixture, fixture.adminToken)
        .get("/api/admin/parent-links").expect(200);
      expect(list.body.data).toEqual(
        expect.arrayContaining([expect.objectContaining({ ...link, parentUsername: "parent1" })])
      );
      await authed(fixture, fixture.adminToken)
        .delete("/api/admin/parent-links").send(link).expect(200);
      const parents = await authed(fixture, fixture.adminToken)
        .get("/api/admin/parents").expect(200);
      expect(parents.body.data).toEqual(
        expect.arrayContaining([expect.objectContaining({ id: parentIdValue, username: "parent1" })])
      );
      await authed(fixture, fixture.adminToken)
        .post("/api/admin/parent-links")
        .send({ parentId: await accountId(fixture, "driver1"), childId: child.id })
        .expect(404);
    });

    describe("websocket authentication and filtering", () => {
      test("requires a bearer token and only forwards location events to the linked parent and trip driver", async () => {
        const child = await createChild(fixture, { route: "Route A" });
        await authed(fixture, fixture.adminToken)
          .post("/api/admin/parent-links")
          .send({ parentId: await parentId(fixture, "parent1"), childId: child.id })
          .expect(201);
        const trip = await createTrip(fixture);

        const hub = new RealtimeHub(fixture.pool);
        const server = createServer(fixture.app);
        hub.attach(server);
        await new Promise<void>((resolve) => server.listen(0, resolve));
        const address = server.address();
        if (!address || typeof address === "string") throw new Error("Missing test server address");
        const url = `ws://127.0.0.1:${address.port}/ws`;
        const connect = (token?: string) => new WebSocket(url, {
          headers: token ? { Authorization: `Bearer ${token}` } : {}
        });
        const open = (socket: WebSocket) => new Promise<void>((resolve, reject) => {
          socket.once("open", () => resolve());
          socket.once("error", reject);
        });
        const parent = connect(fixture.parentToken);
        const unrelatedParent = connect(fixture.secondParentToken);
        const driver = connect(fixture.driverToken);
        try {
          await Promise.all([open(parent), open(unrelatedParent), open(driver)]);
          const received: string[] = [];
          parent.on("message", (message) => received.push(`parent:${message.toString()}`));
          unrelatedParent.on("message", (message) => received.push(`unrelated:${message.toString()}`));
          driver.on("message", (message) => received.push(`driver:${message.toString()}`));
          hub.publish("location", {
            tripId: trip.id,
            route: "Route A",
            latitude: 1.25,
            longitude: 103.8
          });
          await new Promise((resolve) => setTimeout(resolve, 100));
          expect(received.some((message) => message.startsWith("parent:"))).toBe(true);
          expect(received.some((message) => message.startsWith("driver:"))).toBe(true);
          expect(received.some((message) => message.startsWith("unrelated:"))).toBe(false);
          const unauthorized = connect();
          await expect(open(unauthorized)).rejects.toBeDefined();
          unauthorized.terminate();
        } finally {
          parent.terminate();
          unrelatedParent.terminate();
          driver.terminate();
          hub.close();
          await new Promise<void>((resolve) => server.close(() => resolve()));
        }
      });
    });
  });
});

async function parentId(fixture: Fixture, username: string): Promise<string> {
  return accountId(fixture, username);
}

async function accountId(fixture: Fixture, username: string): Promise<string> {
  const result = await fixture.pool.query<{ id: string }>(
    "SELECT id FROM accounts WHERE username = $1",
    [username]
  );
  return result.rows[0].id;
}
