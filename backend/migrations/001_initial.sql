CREATE TABLE IF NOT EXISTS accounts (
  id UUID PRIMARY KEY,
  username TEXT NOT NULL UNIQUE,
  role TEXT NOT NULL CHECK (role IN ('ADMIN', 'DRIVER', 'PARENT')),
  password_salt TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS children (
  id UUID PRIMARY KEY,
  student_id TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  class_name TEXT NOT NULL,
  route TEXT NOT NULL,
  photo TEXT NOT NULL,
  parent_consent_obtained BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS parent_links (
  parent_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  child_id UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (parent_id, child_id)
);
CREATE INDEX IF NOT EXISTS parent_links_child_idx ON parent_links(child_id);

CREATE TABLE IF NOT EXISTS trips (
  id UUID PRIMARY KEY,
  route TEXT NOT NULL,
  vehicle TEXT NOT NULL,
  driver TEXT NOT NULL,
  trip_type TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED')),
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  accuracy REAL,
  location_updated_at TIMESTAMPTZ,
  created_by UUID NOT NULL REFERENCES accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ended_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS trips_route_status_idx ON trips(route, status);
CREATE INDEX IF NOT EXISTS trips_creator_idx ON trips(created_by);

CREATE TABLE IF NOT EXISTS scans (
  id UUID PRIMARY KEY,
  student_id UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  phase TEXT NOT NULL,
  scan_date DATE NOT NULL,
  face_match_passed BOOLEAN NOT NULL,
  face_match_score DOUBLE PRECISION,
  scan_method TEXT NOT NULL CHECK (scan_method IN ('FACE_MATCH', 'MANUAL')),
  scanned_face_photo TEXT,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  accuracy REAL,
  scanned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by UUID NOT NULL REFERENCES accounts(id),
  CONSTRAINT scans_daily_unique UNIQUE (student_id, trip_id, phase, scan_date)
);
CREATE INDEX IF NOT EXISTS scans_date_idx ON scans(scan_date);
CREATE INDEX IF NOT EXISTS scans_trip_idx ON scans(trip_id);

CREATE TABLE IF NOT EXISTS emergencies (
  id UUID PRIMARY KEY,
  vehicle TEXT NOT NULL,
  driver TEXT NOT NULL,
  route TEXT NOT NULL,
  description TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN'
    CHECK (status IN ('OPEN', 'RESOLVED', 'CLOSED')),
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  created_by UUID NOT NULL REFERENCES accounts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS emergencies_route_idx ON emergencies(route);
