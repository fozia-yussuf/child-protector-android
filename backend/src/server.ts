import { createServer } from "node:http";
import { createApp } from "./app";
import { ensureAdminAccount } from "./auth";
import { loadConfig } from "./config";
import { createPool } from "./db";
import { runMigrations } from "./migrate";
import { RealtimeHub } from "./realtime";
import { ZodError } from "zod";

async function main(): Promise<void> {
  const config = loadConfig();
  const pool = createPool(config.DATABASE_URL);
  await runMigrations(pool);
  await ensureAdminAccount(pool, config.ADMIN_PASSWORD);

  const realtime = new RealtimeHub(pool);
  const app = createApp(pool, {
    corsOrigin: config.CORS_ORIGIN,
    minFaceMatchScore: config.MIN_FACE_MATCH_SCORE,
    sessionTtlHours: config.SESSION_TTL_HOURS,
    events: realtime
  });
  const server = createServer(app);
  realtime.attach(server);

  server.listen(config.PORT, () => {
    console.info(`Child Protector API listening on port ${config.PORT}`);
  });

  const shutdown = () => {
    realtime.close();
    server.close(() => {
      void pool.end().finally(() => process.exit(0));
    });
  };
  process.once("SIGTERM", shutdown);
  process.once("SIGINT", shutdown);
}

void main().catch((error: unknown) => {
  if (error instanceof ZodError) {
    console.error("Service startup failed: configuration validation error", error.issues.map((issue) => ({ field: issue.path.join("."), message: issue.message })));
  } else {
    console.error("Service startup failed", error instanceof Error ? error.message : "UnknownError");
  }
  process.exitCode = 1;
});
