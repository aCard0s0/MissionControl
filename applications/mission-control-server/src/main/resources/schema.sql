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

CREATE TABLE IF NOT EXISTS model_providers (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  url        TEXT NOT NULL UNIQUE,
  kind       TEXT NOT NULL CHECK (kind IN ('ollama')),
  created_at INTEGER NOT NULL
);

-- Reusable agent blueprints (soul, memory, skills, MCP servers, encrypted keys)
-- applied when deploying a new agent. Distinct from a live in-container profile.
CREATE TABLE IF NOT EXISTS profile_templates (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  description TEXT,
  provider    TEXT,
  model       TEXT,
  base_url    TEXT,
  cwd         TEXT,
  soul        TEXT,
  memory      TEXT,
  skills      TEXT,   -- JSON array of skill ids
  mcp_servers TEXT,   -- JSON array of McpServerSpec
  secrets     TEXT,   -- JSON array of {key, enc} (AES-GCM ciphertext)
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
