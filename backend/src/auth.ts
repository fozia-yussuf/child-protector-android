import { createHash, randomBytes, randomUUID, scrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";
import type { Pool } from "pg";
import type { NextFunction, Request, Response } from "express";
import { HttpError } from "./errors";
import type { Principal, Role } from "./types";

const scryptAsync = promisify(scrypt);
const HASH_BYTES = 64;

export function normalizeUsername(username: string): string {
  return username.trim().toLowerCase();
}

export function hashSessionToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

export async function makePasswordHash(password: string, salt = randomBytes(16).toString("hex")) {
  const hash = (await scryptAsync(password, salt, HASH_BYTES)) as Buffer;
  return { salt, hash: hash.toString("hex") };
}

export async function passwordMatches(
  password: string,
  salt: string,
  storedHashHex: string
): Promise<boolean> {
  const expected = Buffer.from(storedHashHex, "hex");
  const actual = (await scryptAsync(password, salt, HASH_BYTES)) as Buffer;
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

export async function ensureAdminAccount(pool: Pool, password: string): Promise<void> {
  const existing = await pool.query<{ id: string; password_salt: string; password_hash: string }>(
    "SELECT id, password_salt, password_hash FROM accounts WHERE username = 'admin'"
  );
  const credentials = await makePasswordHash(password);
  if (existing.rowCount) {
    await pool.query(
      "UPDATE accounts SET password_salt = $1, password_hash = $2, role = 'ADMIN' WHERE id = $3",
      [credentials.salt, credentials.hash, existing.rows[0].id]
    );
    return;
  }
  await pool.query(
    `INSERT INTO accounts (id, username, role, password_salt, password_hash)
     VALUES ($1, 'admin', 'ADMIN', $2, $3)`,
    [randomUUID(), credentials.salt, credentials.hash]
  );
}

export async function createSession(
  pool: Pool,
  accountId: string,
  ttlHours: number
): Promise<{ token: string; expiresAt: Date }> {
  const token = randomBytes(32).toString("base64url");
  const expiresAt = new Date(Date.now() + ttlHours * 60 * 60 * 1000);
  await pool.query(
    "INSERT INTO sessions (token_hash, account_id, expires_at) VALUES ($1, $2, $3)",
    [hashSessionToken(token), accountId, expiresAt]
  );
  return { token, expiresAt };
}

export async function principalForToken(pool: Pool, token: string): Promise<Principal | null> {
  const result = await pool.query<{
    id: string;
    username: string;
    role: Role;
    token_hash: string;
    expires_at: Date;
  }>(
    `SELECT a.id, a.username, a.role, s.token_hash, s.expires_at
     FROM sessions s
     JOIN accounts a ON a.id = s.account_id
     WHERE s.token_hash = $1 AND s.expires_at > NOW()`,
    [hashSessionToken(token)]
  );
  const row = result.rows[0];
  if (!row) return null;
  return {
    id: row.id,
    username: row.username,
    role: row.role,
    sessionHash: row.token_hash,
    expiresAt: new Date(row.expires_at)
  };
}

export function bearerToken(request: Request): string | null {
  const authorization = request.header("authorization");
  if (!authorization) return null;
  const match = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(authorization);
  return match?.[1] ?? null;
}

export function authenticate(pool: Pool) {
  return async (request: Request, _response: Response, next: NextFunction) => {
    try {
      const token = bearerToken(request);
      if (!token) throw new HttpError(401, "Authentication required");
      const principal = await principalForToken(pool, token);
      if (!principal) throw new HttpError(401, "Session expired");
      (request as Request & { principal: Principal }).principal = principal;
      next();
    } catch (error) {
      next(error);
    }
  };
}

export function requireRoles(...roles: Role[]) {
  return (request: Request, _response: Response, next: NextFunction) => {
    const principal = (request as Request & { principal?: Principal }).principal;
    if (!principal || !roles.includes(principal.role)) {
      next(new HttpError(403, "Forbidden"));
      return;
    }
    next();
  };
}
