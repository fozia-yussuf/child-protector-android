import "dotenv/config";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import type { Pool } from "pg";

export async function runMigrations(
  pool: Pool,
  directory = path.resolve(__dirname, "../migrations")
): Promise<void> {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      name TEXT PRIMARY KEY,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `);
  const migrationFiles = (await readdir(directory))
    .filter((name) => /^\d+_.+\.sql$/.test(name))
    .sort();
  for (const name of migrationFiles) {
    const alreadyApplied = await pool.query(
      "SELECT 1 FROM schema_migrations WHERE name = $1",
      [name]
    );
    if (alreadyApplied.rowCount) continue;

    const sql = await readFile(path.join(directory, name), "utf8");
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      await client.query(sql);
      await client.query("INSERT INTO schema_migrations (name) VALUES ($1)", [name]);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }
}

if (require.main === module) {
  void (async () => {
    const { createPool } = await import("./db");
    const pool = createPool();
    try {
      await runMigrations(pool);
      console.info("Database migrations applied");
    } finally {
      await pool.end();
    }
  })().catch((error: unknown) => {
    console.error("Database migration failed", error instanceof Error ? error.name : "UnknownError");
    process.exitCode = 1;
  });
}
