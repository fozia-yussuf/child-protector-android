import cors from "cors";
import express, { type ErrorRequestHandler } from "express";
import rateLimit from "express-rate-limit";
import helmet from "helmet";
import type { Pool } from "pg";
import { ZodError } from "zod";
import { HttpError } from "./errors";
import { registerRoutes } from "./routes";

export interface EventPublisher {
  publish(type: string, data: Record<string, unknown>): void;
}

const noEvents: EventPublisher = { publish: () => undefined };

export function createApp(
  pool: Pool,
  options: {
    corsOrigin?: string;
    minFaceMatchScore?: number;
    sessionTtlHours?: number;
    events?: EventPublisher;
  } = {}
) {
  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", 1);
  app.use(helmet());
  app.use(cors({
    origin: options.corsOrigin === "*" || !options.corsOrigin
      ? true
      : options.corsOrigin.split(",").map((origin) => origin.trim()),
    credentials: false
  }));
  app.use(express.json({ limit: "10mb", strict: true }));

  const authLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    limit: 20,
    standardHeaders: "draft-7",
    legacyHeaders: false,
    message: { error: "Too many authentication attempts. Try again later." }
  });

  app.get("/health", async (_request, response, next) => {
    try {
      await pool.query("SELECT 1");
      response.json({ status: "ok" });
    } catch (error) {
      next(error);
    }
  });

  registerRoutes(app, pool, {
    minFaceMatchScore: options.minFaceMatchScore ?? 0.363,
    sessionTtlHours: options.sessionTtlHours ?? Number(process.env.SESSION_TTL_HOURS ?? 24),
    events: options.events ?? noEvents,
    authLimiter
  });

  const errorHandler: ErrorRequestHandler = (error: unknown, request, response, _next) => {
    if (error instanceof ZodError) {
      response.status(400).json({
        error: "Validation failed",
        details: error.issues.map((issue) => ({
          path: issue.path.join("."),
          message: issue.message
        }))
      });
      return;
    }
    if (error instanceof HttpError) {
      response.status(error.status).json({ error: error.message, message: error.message });
      return;
    }
    if (
      typeof error === "object" &&
      error !== null &&
      "code" in error &&
      (error as { code?: string }).code === "23505"
    ) {
      response.status(409).json({ error: "Conflict", message: "A record with these values already exists" });
      return;
    }
    if (typeof error === "object" && error !== null && "type" in error &&
      (error as { type?: string }).type === "entity.too.large") {
      response.status(413).json({ error: "Request body is too large" });
      return;
    }
    console.error("request_failed", {
      method: request.method,
      path: request.path,
      status: 500,
      errorType: error instanceof Error ? error.name : "UnknownError"
    });
    response.status(500).json({ error: "Internal server error" });
  };
  app.use(errorHandler);
  return app;
}
