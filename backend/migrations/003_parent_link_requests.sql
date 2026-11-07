CREATE TABLE IF NOT EXISTS parent_link_requests (
  id UUID PRIMARY KEY,
  parent_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  requested_student_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reviewed_at TIMESTAMPTZ,
  UNIQUE (parent_id, requested_student_id)
);
CREATE INDEX IF NOT EXISTS parent_link_requests_status_idx
  ON parent_link_requests(status, created_at);
