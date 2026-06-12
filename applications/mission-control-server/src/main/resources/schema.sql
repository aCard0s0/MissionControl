-- Dashboard-owned state only. Hermes containers, profiles, jobs, and files
-- live in the containers themselves and are always read through.

CREATE TABLE IF NOT EXISTS docker_hosts (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  url        TEXT NOT NULL UNIQUE,
  kind       TEXT NOT NULL CHECK (kind IN ('local', 'remote')),
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS board_tasks (
  id           TEXT PRIMARY KEY,
  container_id TEXT NOT NULL,
  agent_id     TEXT,
  title        TEXT NOT NULL,
  col          TEXT NOT NULL CHECK (col IN ('queued', 'running', 'review', 'done')),
  priority     TEXT NOT NULL CHECK (priority IN ('low', 'med', 'high')),
  tags         TEXT,
  created_at   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_board_tasks_container ON board_tasks (container_id);
