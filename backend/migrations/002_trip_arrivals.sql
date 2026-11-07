CREATE TABLE IF NOT EXISTS trip_arrivals (
  id UUID PRIMARY KEY,
  student_id UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
  trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  destination TEXT NOT NULL CHECK (destination IN ('SCHOOL', 'HOME')),
  arrival_date DATE NOT NULL,
  confirmed_by UUID NOT NULL REFERENCES accounts(id),
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  accuracy REAL,
  arrived_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT trip_arrivals_daily_unique UNIQUE (student_id, trip_id, destination, arrival_date)
);
CREATE INDEX IF NOT EXISTS trip_arrivals_date_idx ON trip_arrivals(arrival_date);
CREATE INDEX IF NOT EXISTS trip_arrivals_trip_idx ON trip_arrivals(trip_id);
