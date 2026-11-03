import { randomUUID } from "node:crypto";
import type { Application, Request, RequestHandler } from "express";
import type { Pool, PoolClient, QueryResultRow } from "pg";
import { z } from "zod";
import { authenticate, createSession, makePasswordHash, normalizeUsername, passwordMatches, requireRoles } from "./auth";
import { asyncRoute } from "./async-route";
import { HttpError, notFound } from "./errors";
import type { Principal } from "./types";
import type { EventPublisher } from "./app";

interface RouteOptions {
  minFaceMatchScore: number;
  sessionTtlHours: number;
  events: EventPublisher;
  authLimiter: RequestHandler;
}

const roleSchema = z.enum(["PARENT", "DRIVER", "ADMIN"]);
const usernameSchema = z.string().trim().min(1).max(80).transform(normalizeUsername);
const passwordSchema = z.string().min(8).max(256);
const idSchema = z.string().uuid();
const dateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine((value) => {
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
});
const coordinatesSchema = {
  latitude: z.number().finite().min(-90).max(90).optional(),
  longitude: z.number().finite().min(-180).max(180).optional()
};
const childFields = z.object({
  name: z.string().trim().min(1).max(160),
  studentId: z.string().trim().min(1).max(80),
  className: z.string().trim().min(1).max(120),
  route: z.string().trim().min(1).max(160),
  photo: z.string().min(1).max(8_000_000),
  parentConsentObtained: z.literal(true),
  parentUsername: usernameSchema.optional()
}).strict();
const tripFields = z.object({
  route: z.string().trim().min(1).max(160),
  vehicle: z.string().trim().min(1).max(100),
  driver: z.string().trim().min(1).max(160),
  tripType: z.string().trim().min(1).max(160)
}).strict();
const scanFields = z.object({
  studentId: idSchema,
  tripId: idSchema,
  phase: z.string().trim().min(1).max(160),
  scanDate: dateSchema.optional(),
  faceMatchPassed: z.boolean(),
  scanMethod: z.enum(["FACE_MATCH", "MANUAL"]).default("FACE_MATCH"),
  faceMatchScore: z.number().finite().min(-1).max(1).optional(),
  scannedFacePhoto: z.string().max(2_000_000).optional(),
  ...coordinatesSchema,
  accuracy: z.number().finite().min(0).max(100_000).optional()
}).strict();
const locationFields = z.object({
  latitude: z.number().finite().min(-90).max(90),
  longitude: z.number().finite().min(-180).max(180),
  accuracy: z.number().finite().min(0).max(100_000),
  timestamp: z.number().int().positive().optional()
}).strict();
const emergencyCreateFields = z.object({
  vehicle: z.string().trim().min(1).max(100),
  driver: z.string().trim().min(1).max(160),
  route: z.string().trim().min(1).max(160),
  description: z.string().trim().min(1).max(2000),
  ...coordinatesSchema
}).strict();
const emergencyUpdateFields = z.object({
  description: z.string().trim().min(1).max(2000).optional(),
  status: z.enum(["OPEN", "RESOLVED", "CLOSED"]).optional(),
  latitude: coordinatesSchema.latitude,
  longitude: coordinatesSchema.longitude
}).strict().refine((value) => Object.keys(value).length > 0, "Provide at least one field to update");
const linkFields = z.object({
  parentId: idSchema,
  childId: idSchema
}).strict();

const childSelect = `
  SELECT c.id, c.student_id AS "studentId", c.name,
         c.class_name AS "className", c.route, c.photo,
         c.parent_consent_obtained AS "parentConsentObtained",
         c.created_at AS "createdAt"
  FROM children c`;
const tripSelect = `
  SELECT t.id, t.route, t.vehicle, t.driver, t.trip_type AS "tripType", t.status,
         t.latitude, t.longitude, t.accuracy,
         t.location_updated_at AS "locationUpdatedAt",
         t.created_at AS "createdAt", t.ended_at AS "endedAt" FROM trips t`;
const scanSelect = `
  SELECT s.id, s.student_id AS "studentId", c.student_id AS "schoolStudentId",
         c.name AS "studentName", s.trip_id AS "tripId", t.route,
         s.phase, s.scan_date AS "scanDate", s.face_match_passed AS "faceMatchPassed",
         s.face_match_score AS "faceMatchScore", s.scan_method AS "scanMethod",
         s.scanned_face_photo AS "scannedFacePhoto", s.latitude, s.longitude,
         s.accuracy, s.scanned_at AS "scannedAt"
  FROM scans s JOIN children c ON c.id = s.student_id
  JOIN trips t ON t.id = s.trip_id`;
const emergencySelect = `
  SELECT e.id, e.vehicle, e.driver, e.route, e.description, e.status,
         e.latitude, e.longitude, e.created_at AS "createdAt",
         e.updated_at AS "updatedAt" FROM emergencies e`;

function principal(request: Request): Principal {
  return (request as Request & { principal: Principal }).principal;
}

function jsonBody<T>(schema: z.ZodType<T>, request: Request): T {
  return schema.parse(request.body);
}

function idParam(request: Request): string {
  return idSchema.parse(request.params.id);
}

function currentDate(): string {
  return new Date().toISOString().slice(0, 10);
}

function validateDate(value: unknown): string {
  if (value === undefined) return currentDate();
  return dateSchema.parse(value);
}

function data(response: import("express").Response, value: unknown, status = 200) {
  response.status(status).json({ data: value });
}

async function assertDriverOwnsTrip(
  pool: Pool,
  user: Principal,
  tripId: string
): Promise<void> {
  if (user.role === "ADMIN") return;
  const result = await pool.query(
    `SELECT 1 FROM trips
     WHERE id = $1 AND (created_by = $2 OR lower(driver) = lower($3))`,
    [tripId, user.id, user.username]
  );
  if (!result.rowCount) throw notFound("Trip not found");
}

async function assertParentLinkedChild(
  pool: Pool,
  parentId: string,
  childId: string
): Promise<void> {
  const result = await pool.query(
    "SELECT 1 FROM parent_links WHERE parent_id = $1 AND child_id = $2",
    [parentId, childId]
  );
  if (!result.rowCount) throw notFound("Child not found");
}

async function withTransaction<T>(pool: Pool, action: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await action(client);
    await client.query("COMMIT");
    return result;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

function rows<T extends QueryResultRow>(result: { rows: T[] }): T[] {
  return result.rows;
}

export function registerRoutes(app: Application, pool: Pool, options: RouteOptions): void {
  app.post("/api/auth/signup", options.authLimiter, asyncRoute(async (request, response) => {
    const raw = request.body as Record<string, unknown> | undefined;
    if (raw?.role === "ADMIN") throw new HttpError(403, "Admin signup is not allowed");
    const input = z.object({
      username: usernameSchema,
      password: passwordSchema,
      role: z.enum(["PARENT", "DRIVER"])
    }).strict().parse(request.body);
    if (input.username === "admin") throw new HttpError(403, "Admin signup is not allowed");
    const credentials = await makePasswordHash(input.password);
    const result = await pool.query(
      `INSERT INTO accounts (id, username, role, password_salt, password_hash)
       VALUES ($1, $2, $3, $4, $5)
       RETURNING id, username, role`,
      [randomUUID(), input.username, input.role, credentials.salt, credentials.hash]
    );
    data(response, result.rows[0], 201);
  }));

  app.post("/api/auth/login", options.authLimiter, asyncRoute(async (request, response) => {
    const input = z.object({
      username: usernameSchema,
      password: z.string().min(1).max(256),
      role: roleSchema
    }).strict().parse(request.body);
    const result = await pool.query<{
      id: string;
      username: string;
      role: Principal["role"];
      password_salt: string;
      password_hash: string;
    }>(
      `SELECT id, username, role, password_salt, password_hash
       FROM accounts WHERE username = $1`,
      [input.username]
    );
    const account = result.rows[0];
    const validPassword = account
      ? await passwordMatches(input.password, account.password_salt, account.password_hash)
      : await passwordMatches(
          input.password,
          "child-protector-login-padding",
          "0".repeat(128)
        );
    if (!account || !validPassword || account.role !== input.role) {
      throw new HttpError(401, "Invalid username, password, or role");
    }
    await pool.query("DELETE FROM sessions WHERE expires_at <= NOW()");
    const session = await createSession(pool, account.id, options.sessionTtlHours);
    data(response, {
      username: account.username,
      role: account.role,
      session: session.token,
      expiresAt: session.expiresAt.toISOString()
    });
  }));

  const auth = authenticate(pool);
  app.use("/api", auth);

  app.get("/api/parent/children", requireRoles("PARENT"), asyncRoute(async (request, response) => {
    const user = principal(request);
    const result = await pool.query(
      `${childSelect}
       JOIN parent_links pl ON pl.child_id = c.id
       WHERE pl.parent_id = $1 ORDER BY c.name`,
      [user.id]
    );
    data(response, rows(result));
  }));

  app.get("/api/parent/trips", requireRoles("PARENT"), asyncRoute(async (request, response) => {
    const user = principal(request);
    const result = await pool.query(
      `${tripSelect.replace("SELECT", "SELECT DISTINCT")}
       JOIN children c ON lower(c.route) = lower(t.route)
       JOIN parent_links pl ON pl.child_id = c.id
       WHERE pl.parent_id = $1
       ORDER BY t.created_at DESC`,
      [user.id]
    );
    data(response, rows(result));
  }));

  app.get("/api/parent/scans", requireRoles("PARENT"), asyncRoute(async (request, response) => {
    const date = validateDate(request.query.date);
    const result = await pool.query(
      `${scanSelect}
       JOIN parent_links pl ON pl.child_id = s.student_id
       WHERE pl.parent_id = $1 AND s.scan_date = $2
       ORDER BY s.scanned_at DESC`,
      [principal(request).id, date]
    );
    data(response, rows(result));
  }));

  app.get("/api/parent/emergencies", requireRoles("PARENT"), asyncRoute(async (request, response) => {
    const result = await pool.query(
      `${emergencySelect.replace("SELECT", "SELECT DISTINCT")}
       JOIN children c ON lower(c.route) = lower(e.route)
       JOIN parent_links pl ON pl.child_id = c.id
       WHERE pl.parent_id = $1
       ORDER BY e.created_at DESC`,
      [principal(request).id]
    );
    data(response, rows(result));
  }));

  app.get("/api/admin/overview", requireRoles("ADMIN"), asyncRoute(async (_request, response) => {
    const [students, activeTrips, todayScans, openEmergencies] = await Promise.all([
      pool.query("SELECT COUNT(*)::int AS count FROM children"),
      pool.query("SELECT COUNT(*)::int AS count FROM trips WHERE status = 'ACTIVE'"),
      pool.query("SELECT COUNT(*)::int AS count FROM scans WHERE scan_date = $1", [currentDate()]),
      pool.query("SELECT COUNT(*)::int AS count FROM emergencies WHERE status = 'OPEN'")
    ]);
    const counts = {
      students: Number(students.rows[0].count),
      studentCount: Number(students.rows[0].count),
      activeTrips: Number(activeTrips.rows[0].count),
      todayScans: Number(todayScans.rows[0].count),
      openEmergencies: Number(openEmergencies.rows[0].count)
    };
    data(response, { counts, ...counts });
  }));

  app.get("/api/admin/parents", requireRoles("ADMIN"), asyncRoute(async (_request, response) => {
    const result = await pool.query(
      `SELECT a.id, a.username, a.role,
         COUNT(pl.child_id)::int AS "childCount"
       FROM accounts a LEFT JOIN parent_links pl ON pl.parent_id = a.id
       WHERE a.role = 'PARENT'
       GROUP BY a.id, a.username, a.role ORDER BY a.username`
    );
    data(response, rows(result));
  }));

  app.get("/api/admin/parent-links", requireRoles("ADMIN"), asyncRoute(async (_request, response) => {
    const result = await pool.query(
      `SELECT pl.parent_id AS "parentId", a.username AS "parentUsername",
         pl.child_id AS "childId", c.name AS "childName", c.route,
         pl.created_at AS "createdAt"
       FROM parent_links pl
       JOIN accounts a ON a.id = pl.parent_id
       JOIN children c ON c.id = pl.child_id
       ORDER BY a.username, c.name`
    );
    data(response, rows(result));
  }));

  app.post("/api/admin/parent-links", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const input = jsonBody(linkFields, request);
    const parent = await pool.query(
      "SELECT id FROM accounts WHERE id = $1 AND role = 'PARENT'",
      [input.parentId]
    );
    if (!parent.rowCount) throw notFound("Parent account not found");
    const child = await pool.query("SELECT id FROM children WHERE id = $1", [input.childId]);
    if (!child.rowCount) throw notFound("Child not found");
    await pool.query(
      "INSERT INTO parent_links (parent_id, child_id) VALUES ($1, $2)",
      [input.parentId, input.childId]
    );
    data(response, { parentId: input.parentId, childId: input.childId }, 201);
  }));

  app.delete("/api/admin/parent-links", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const input = jsonBody(linkFields, request);
    const result = await pool.query(
      "DELETE FROM parent_links WHERE parent_id = $1 AND child_id = $2 RETURNING parent_id",
      [input.parentId, input.childId]
    );
    if (!result.rowCount) throw notFound("Parent link not found");
    data(response, {});
  }));

  app.get("/api/children", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const result = await pool.query(`${childSelect} ORDER BY c.name`);
    data(response, rows(result));
  }));

  app.post("/api/children", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const input = jsonBody(childFields, request);
    const childId = randomUUID();
    const result = await withTransaction(pool, async (client) => {
      const child = await client.query(
        `INSERT INTO children
           (id, student_id, name, class_name, route, photo, parent_consent_obtained)
         VALUES ($1, $2, $3, $4, $5, $6, TRUE)
         RETURNING id, student_id AS "studentId", name, class_name AS "className",
                   route, photo, parent_consent_obtained AS "parentConsentObtained"`,
        [childId, input.studentId, input.name, input.className, input.route, input.photo]
      );
      if (input.parentUsername) {
        const parent = await client.query(
          "SELECT id FROM accounts WHERE username = $1 AND role = 'PARENT'",
          [input.parentUsername]
        );
        if (!parent.rowCount) throw notFound("Parent account not found");
        await client.query(
          "INSERT INTO parent_links (parent_id, child_id) VALUES ($1, $2)",
          [parent.rows[0].id, childId]
        );
      }
      return child.rows[0];
    });
    data(response, result, 201);
  }));

  app.get("/api/children/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const id = idParam(request);
    const result = await pool.query(`${childSelect} WHERE c.id = $1`, [id]);
    if (!result.rowCount) throw notFound("Child not found");
    data(response, result.rows[0]);
  }));

  app.put("/api/children/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const id = idParam(request);
    const input = z.object({
      name: z.string().trim().min(1).max(160),
      className: z.string().trim().min(1).max(120),
      route: z.string().trim().min(1).max(160),
      photo: z.string().min(1).max(8_000_000).optional(),
      parentConsentObtained: z.literal(true).optional()
    }).strict().parse(request.body);
    if (input.photo && input.parentConsentObtained !== true) {
      throw new HttpError(400, "Parent consent is required before updating a student photo");
    }
    const result = await pool.query(
      `UPDATE children SET name = $2, class_name = $3, route = $4,
       photo = COALESCE($5, photo),
       parent_consent_obtained = CASE WHEN $5::text IS NULL
         THEN parent_consent_obtained ELSE TRUE END
       WHERE id = $1
       RETURNING id, student_id AS "studentId", name, class_name AS "className",
                 route, photo, parent_consent_obtained AS "parentConsentObtained"`,
      [id, input.name, input.className, input.route, input.photo ?? null]
    );
    if (!result.rowCount) throw notFound("Child not found");
    data(response, result.rows[0]);
  }));

  app.delete("/api/children/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const result = await pool.query(
      "DELETE FROM children WHERE id = $1 RETURNING id",
      [idParam(request)]
    );
    if (!result.rowCount) throw notFound("Child not found");
    data(response, {});
  }));

  app.get("/api/trips", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const user = principal(request);
    const result = user.role === "ADMIN"
      ? await pool.query(`${tripSelect} ORDER BY t.created_at DESC`)
      : await pool.query(
          `${tripSelect}
           WHERE t.created_by = $1 OR lower(t.driver) = lower($2)
           ORDER BY t.created_at DESC`,
          [user.id, user.username]
        );
    data(response, rows(result));
  }));

  app.post("/api/trips", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const input = jsonBody(tripFields, request);
    const result = await pool.query(
      `INSERT INTO trips (id, route, vehicle, driver, trip_type, status, created_by)
       VALUES ($1, $2, $3, $4, $5, 'ACTIVE', $6)
       RETURNING id, route, vehicle, driver, trip_type AS "tripType", status,
                 latitude, longitude, accuracy,
                 location_updated_at AS "locationUpdatedAt"`,
      [randomUUID(), input.route, input.vehicle, input.driver, input.tripType, principal(request).id]
    );
    const trip = result.rows[0] as Record<string, unknown>;
    data(response, trip, 201);
    options.events.publish("trip-start", trip);
  }));

  app.put("/api/trips/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const input = jsonBody(tripFields, request);
    const result = await pool.query(
      `UPDATE trips SET route = $2, vehicle = $3, driver = $4, trip_type = $5
       WHERE id = $1
       RETURNING id, route, vehicle, driver, trip_type AS "tripType", status,
                 latitude, longitude, accuracy,
                 location_updated_at AS "locationUpdatedAt"`,
      [idParam(request), input.route, input.vehicle, input.driver, input.tripType]
    );
    if (!result.rowCount) throw notFound("Trip not found");
    data(response, result.rows[0]);
  }));

  app.post("/api/trips/:id/end", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const id = idParam(request);
    await assertDriverOwnsTrip(pool, principal(request), id);
    const result = await pool.query(
      `UPDATE trips SET status = 'COMPLETED', ended_at = NOW()
       WHERE id = $1 AND status = 'ACTIVE'
       RETURNING id, route, vehicle, driver, trip_type AS "tripType", status,
                 latitude, longitude, accuracy,
                 location_updated_at AS "locationUpdatedAt"`,
      [id]
    );
    if (!result.rowCount) throw notFound("Active trip not found");
    data(response, result.rows[0]);
    options.events.publish("trip-end", result.rows[0] as Record<string, unknown>);
  }));

  app.delete("/api/trips/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const result = await pool.query("DELETE FROM trips WHERE id = $1 RETURNING id", [idParam(request)]);
    if (!result.rowCount) throw notFound("Trip not found");
    data(response, {});
  }));

  app.put("/api/trips/:id/location", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const id = idParam(request);
    const input = jsonBody(locationFields, request);
    await assertDriverOwnsTrip(pool, principal(request), id);
    const result = await pool.query(
      `UPDATE trips SET latitude = $2, longitude = $3, accuracy = $4,
       location_updated_at = NOW()
       WHERE id = $1 AND status = 'ACTIVE'
       RETURNING id, route, vehicle, driver, trip_type AS "tripType", status,
                 latitude, longitude, accuracy,
                 location_updated_at AS "locationUpdatedAt"`,
      [id, input.latitude, input.longitude, input.accuracy]
    );
    if (!result.rowCount) throw notFound("Active trip not found");
    const trip = result.rows[0] as Record<string, unknown>;
    data(response, trip);
    options.events.publish("location", {
      tripId: trip.id,
      route: trip.route,
      latitude: trip.latitude,
      longitude: trip.longitude,
      accuracy: trip.accuracy,
      timestamp: trip.locationUpdatedAt
    });
  }));

  app.get("/api/scans", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const date = validateDate(request.query.date);
    const route = typeof request.query.route === "string" ? request.query.route.trim() : "";
    const user = principal(request);
    const result = user.role === "ADMIN"
      ? await pool.query(
          `${scanSelect} WHERE s.scan_date = $1
           AND ($2 = '' OR lower(t.route) LIKE lower('%' || $2 || '%'))
           ORDER BY s.scanned_at DESC`,
          [date, route]
        )
      : await pool.query(
          `${scanSelect} WHERE s.scan_date = $1 AND s.created_by = $2
           AND ($3 = '' OR lower(t.route) LIKE lower('%' || $3 || '%'))
           ORDER BY s.scanned_at DESC`,
          [date, user.id, route]
        );
    data(response, rows(result));
  }));

  app.post("/api/scan", requireRoles("DRIVER"), asyncRoute(async (request, response) => {
    const input = jsonBody(scanFields, request);
    const user = principal(request);
    const saved = await withTransaction(pool, async (client) => {
      const studentResult = await client.query<{ id: string; route: string; name: string }>(
        "SELECT id, route, name FROM children WHERE id = $1",
        [input.studentId]
      );
      const student = studentResult.rows[0];
      if (!student) throw notFound("Student not found");

      const tripResult = await client.query<{ id: string; route: string; status: string }>(
        "SELECT id, route, status FROM trips WHERE id = $1",
        [input.tripId]
      );
      const trip = tripResult.rows[0];
      if (!trip) throw notFound("Trip not found");
      if (trip.status !== "ACTIVE") throw new HttpError(409, "Trip is not active");
      await assertDriverOwnsTrip(pool, user, trip.id);
      if (student.route.trim().toLowerCase() !== trip.route.trim().toLowerCase()) {
        throw new HttpError(409, "Student route does not match the trip route");
      }

      const scanDate = currentDate();
      const duplicate = await client.query(
        `SELECT 1 FROM scans
         WHERE student_id = $1 AND trip_id = $2 AND phase = $3 AND scan_date = $4`,
        [student.id, trip.id, input.phase, scanDate]
      );
      if (duplicate.rowCount) throw new HttpError(409, "Student already scanned for this trip and phase today");

      if (
        input.scanMethod !== "MANUAL" &&
        (!input.faceMatchPassed ||
          input.faceMatchScore === undefined ||
          input.faceMatchScore < options.minFaceMatchScore)
      ) {
        throw new HttpError(422, `Face match must pass with a score of at least ${options.minFaceMatchScore}`);
      }

      const result = await client.query(
        `INSERT INTO scans
          (id, student_id, trip_id, phase, scan_date, face_match_passed,
           face_match_score, scan_method, scanned_face_photo,
           latitude, longitude, accuracy, created_by)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
         RETURNING id, student_id AS "studentId", trip_id AS "tripId",
           phase, scan_date AS "scanDate", face_match_passed AS "faceMatchPassed",
           face_match_score AS "faceMatchScore", scan_method AS "scanMethod",
           latitude, longitude, accuracy, scanned_at AS "scannedAt"`,
        [
          randomUUID(), student.id, trip.id, input.phase, scanDate,
          input.scanMethod === "MANUAL" ? false : input.faceMatchPassed,
          input.faceMatchScore ?? null, input.scanMethod, input.scannedFacePhoto ?? null,
          input.latitude ?? null, input.longitude ?? null, input.accuracy ?? null, user.id
        ]
      );
      return result.rows[0] as Record<string, unknown>;
    });
    data(response, saved, 201);
    options.events.publish("scan", saved);
  }));

  app.get("/api/scans/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const result = await pool.query(
      `${scanSelect} WHERE s.id = $1`,
      [idParam(request)]
    );
    if (!result.rowCount) throw notFound("Scan not found");
    data(response, result.rows[0]);
  }));

  app.delete("/api/scans/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const result = await pool.query("DELETE FROM scans WHERE id = $1 RETURNING id", [idParam(request)]);
    if (!result.rowCount) throw notFound("Scan not found");
    data(response, {});
  }));

  app.get("/api/emergencies", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const user = principal(request);
    const result = user.role === "ADMIN"
      ? await pool.query(`${emergencySelect} ORDER BY e.created_at DESC`)
      : await pool.query(
          `${emergencySelect} WHERE e.created_by = $1 ORDER BY e.created_at DESC`,
          [user.id]
        );
    data(response, rows(result));
  }));

  app.post("/api/emergencies", requireRoles("ADMIN", "DRIVER"), asyncRoute(async (request, response) => {
    const input = jsonBody(emergencyCreateFields, request);
    const result = await pool.query(
      `INSERT INTO emergencies
        (id, vehicle, driver, route, description, latitude, longitude, created_by)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
       RETURNING id, vehicle, driver, route, description, status, latitude, longitude,
                 created_at AS "createdAt", updated_at AS "updatedAt"`,
      [
        randomUUID(), input.vehicle, input.driver, input.route, input.description,
        input.latitude ?? null, input.longitude ?? null, principal(request).id
      ]
    );
    data(response, result.rows[0], 201);
    options.events.publish("emergency", result.rows[0] as Record<string, unknown>);
  }));

  app.put("/api/emergencies/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const id = idParam(request);
    const input = jsonBody(emergencyUpdateFields, request);
    const result = await pool.query(
      `UPDATE emergencies SET
         description = COALESCE($2, description),
         status = COALESCE($3, status),
         latitude = COALESCE($4, latitude),
         longitude = COALESCE($5, longitude),
         updated_at = NOW()
       WHERE id = $1
       RETURNING id, vehicle, driver, route, description, status, latitude, longitude,
                 created_at AS "createdAt", updated_at AS "updatedAt"`,
      [id, input.description ?? null, input.status ?? null, input.latitude ?? null, input.longitude ?? null]
    );
    if (!result.rowCount) throw notFound("Emergency not found");
    data(response, result.rows[0]);
  }));

  app.delete("/api/emergencies/:id", requireRoles("ADMIN"), asyncRoute(async (request, response) => {
    const result = await pool.query(
      "DELETE FROM emergencies WHERE id = $1 RETURNING id",
      [idParam(request)]
    );
    if (!result.rowCount) throw notFound("Emergency not found");
    data(response, {});
  }));
}
