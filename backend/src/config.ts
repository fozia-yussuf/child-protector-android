import "dotenv/config";
import { z } from "zod";

const configSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  PORT: z.coerce.number().int().positive().default(3000),
  DATABASE_URL: z.string().min(1),
  ADMIN_PASSWORD: z.string().min(1),
  SESSION_TTL_HOURS: z.coerce.number().positive().default(24),
  MIN_FACE_MATCH_SCORE: z.coerce.number().min(-1).max(1).default(0.363),
  CORS_ORIGIN: z.string().default("*")
});

export type AppConfig = z.infer<typeof configSchema>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return configSchema.parse(env);
}
