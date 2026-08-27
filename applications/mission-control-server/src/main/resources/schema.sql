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
  kind       TEXT NOT NULL CHECK (kind IN ('ollama','openai')),
  created_at INTEGER NOT NULL
);

-- Reusable agent blueprints (soul, memory, skills, MCP servers, encrypted keys)
-- applied when deploying a new agent. Distinct from a live in-container profile.
CREATE TABLE IF NOT EXISTS profile_templates (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  icon        TEXT,   -- added after this table shipped; see SchemaUpgrades
  description TEXT,
  category    TEXT,   -- added after this table shipped; see SchemaUpgrades
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

-- Reusable MCP server catalog. Deployment details remain structured JSON; secret
-- values inside it are AES-GCM envelopes produced by SecretCipher.
CREATE TABLE IF NOT EXISTS mcp_servers (
  id               TEXT PRIMARY KEY,
  name             TEXT NOT NULL COLLATE NOCASE UNIQUE,
  description      TEXT,
  kind             TEXT NOT NULL CHECK (kind IN ('managed', 'external', 'stdio')),
  host_id          TEXT,
  service_key      TEXT UNIQUE,
  config_json      TEXT NOT NULL,
  desired_state    TEXT NOT NULL,
  runtime_state    TEXT NOT NULL,
  operation_state  TEXT NOT NULL,
  operation_error  TEXT,
  revision         INTEGER NOT NULL,
  applied_revision INTEGER NOT NULL,
  seed_key         TEXT UNIQUE,
  check_status     TEXT,
  check_error      TEXT,
  checked_at       INTEGER,
  latency_ms       INTEGER,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  CHECK ((kind = 'managed' AND host_id IS NOT NULL AND service_key IS NOT NULL)
      OR (kind <> 'managed' AND host_id IS NULL AND service_key IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_mcp_servers_host ON mcp_servers (host_id);

-- Records that prevent deleted managed-server data volumes from becoming
-- invisible/orphaned. Purging is an explicit, separately confirmed API action.
CREATE TABLE IF NOT EXISTS mcp_retained_resources (
  id          TEXT PRIMARY KEY,
  server_id   TEXT NOT NULL,
  server_name TEXT NOT NULL,
  host_id     TEXT NOT NULL,
  type        TEXT NOT NULL CHECK (type IN ('volume')),
  name        TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  UNIQUE (host_id, type, name)
);

CREATE INDEX IF NOT EXISTS idx_mcp_retained_server ON mcp_retained_resources (server_id);

CREATE TABLE IF NOT EXISTS mcp_registry_meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- A catalog link augments, but never replaces, the Agent's own materialized MCP
-- configuration. Disabling an Agent entry therefore leaves this row and its
-- connection details available for a later reconnect.
CREATE TABLE IF NOT EXISTS mcp_agent_links (
  host_id         TEXT NOT NULL,
  container_id    TEXT NOT NULL,
  profile         TEXT NOT NULL,
  alias           TEXT NOT NULL,
  server_id       TEXT NOT NULL,
  synced_revision INTEGER NOT NULL,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  PRIMARY KEY (host_id, container_id, profile, alias),
  FOREIGN KEY (server_id) REFERENCES mcp_servers(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_mcp_agent_links_server ON mcp_agent_links (server_id);

-- The prompt library: dashboard-owned text an operator keeps for later, with a
-- category, notes and tags. Nothing inside a Hermes container reads it.
CREATE TABLE IF NOT EXISTS prompts (
  id         TEXT PRIMARY KEY,
  title      TEXT NOT NULL,
  body       TEXT NOT NULL,
  category   TEXT NOT NULL,
  notes      TEXT,
  tags       TEXT,   -- JSON array of strings
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_prompts_category ON prompts (category);

-- Records that the sample prompt has already been seeded, so one an operator
-- deleted does not come back on the next boot.
CREATE TABLE IF NOT EXISTS prompt_meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- Model ids fetched from a provider's own API by the background refresh, so the
-- picker offers what the provider actually serves today rather than the curated
-- list this app shipped with. Only providers whose listing endpoint needs no
-- credential are refreshed; see ModelCatalogService.PUBLIC_CATALOGS.
--
-- `position` keeps the provider's own ordering. Both the curated list and a live
-- read preserve their source's order, and a refreshed list that alpha-sorted
-- itself would put the picker in a different order depending on where its
-- contents came from.
CREATE TABLE IF NOT EXISTS model_catalog (
  provider   TEXT NOT NULL,
  model_id   TEXT NOT NULL,
  position   INTEGER NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY (provider, model_id)
);

CREATE INDEX IF NOT EXISTS idx_model_catalog_provider ON model_catalog (provider, position);

