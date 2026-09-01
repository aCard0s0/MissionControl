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

-- Self-hosted inference endpoints an operator registered: ollama, or anything
-- OpenAI-compatible. Only the URL is stored — which protocol answers there is a
-- property of the server and is probed, so it is never written down. Shipped as
-- `model_providers`; SchemaUpgrades moves an older database across.
CREATE TABLE IF NOT EXISTS inference_endpoints (
  id         TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  url        TEXT NOT NULL UNIQUE,
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

-- A named set of catalog entries, deployable onto an agent in one action. The only group
-- table in this schema whose noun does something: the other two file a library, this one
-- also has a deploy.
--
-- It records NO agents, deliberately. Deploying a group connects each of its servers to one
-- agent, and every one of those connections is already a row in mcp_agent_links. Which agents
-- a group reaches is derived from those links, so the count can only ever say what the links
-- say. A stored group-to-agent association would be a second source of truth: disconnect one
-- server on the agent's own MCP tab and it would still claim the group was connected.
--
-- Many-to-many in both directions falls out of that with no table saying so — the same group
-- deploys onto as many agents as you like, an agent's links may come from several groups and
-- from servers connected individually, and a server in two groups counts toward both.
--
-- `server_ids` holds ids and not foreign keys, unlike mcp_agent_links.server_id: this is a
-- JSON list, so there is nothing for a REFERENCES clause to attach to. An entry deleted from
-- the catalog is reported as skipped by a deploy rather than silently dropped.
CREATE TABLE IF NOT EXISTS mcp_groups (
  id          TEXT PRIMARY KEY,
  -- a label. Unlike an alias in mcp_agent_links it never reaches a profile's config, so it
  -- carries no charset rule. NOCASE UNIQUE because two headers reading the same is the one
  -- way this list becomes unreadable.
  name        TEXT NOT NULL COLLATE NOCASE UNIQUE,
  description TEXT,
  server_ids  TEXT,   -- JSON array of mcp_servers.id
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

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

-- How the prompt library is filed: a named set of prompts. Organization only — nothing here
-- has behaviour, and dropping a group leaves every prompt it named in the library.
--
-- A different axis from prompts.category and prompts.tags, which are a word and a loose label
-- on one row: a group is a record, so it can be renamed, described and emptied as a unit.
--
-- `prompt_ids` holds ids, not foreign keys — same reason as skill_groups: production runs
-- with `PRAGMA foreign_keys` off, so a CASCADE would be decoration. Resolved on read, and
-- what is gone is dropped.
CREATE TABLE IF NOT EXISTS prompt_groups (
  id          TEXT PRIMARY KEY,
  -- a label, and nothing writes it anywhere, so no charset rule. NOCASE UNIQUE because two
  -- headers reading the same is the one way this list becomes unreadable.
  name        TEXT NOT NULL COLLATE NOCASE UNIQUE,
  description TEXT,
  prompt_ids  TEXT,   -- JSON array of prompts.id
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

-- Records that the sample prompt has already been seeded, so one an operator
-- deleted does not come back on the next boot.
CREATE TABLE IF NOT EXISTS prompt_meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- The skill library: dashboard-owned rows an operator deploys onto one agent.
-- Two origins holding different things. A `hub` row is a pointer — the Skills Hub
-- owns the content, so a deploy shells `hermes skills install`. A `local` row owns
-- its files: authored here, or imported off an agent, and a deploy writes them into
-- the profile's skills dir. Storing a copy of a hub skill would be a second source
-- of truth that goes stale silently, which is the whole reason for the split.
CREATE TABLE IF NOT EXISTS skills (
  id          TEXT PRIMARY KEY,
  kind        TEXT NOT NULL CHECK (kind IN ('hub', 'local')),
  -- what `hermes skills install` is handed for a hub row, and the directory name a
  -- local row is written into. Same charset rule as a profile name
  -- (ProfileSpec.NAME_PATTERN), because both end up in an argv and in a container
  -- path. NOCASE unique so `pdf` and `PDF` cannot both address skills/pdf on a
  -- case-insensitive filesystem.
  name        TEXT NOT NULL COLLATE NOCASE UNIQUE,
  description TEXT,
  category    TEXT NOT NULL,
  -- Provenance, and what the on-demand update check will read. Nothing is ever
  -- cloned or fetched to produce a deploy — the URL is a link.
  repo_url    TEXT,
  version     TEXT,
  files       TEXT,   -- JSON array of {path, body}; NULL for a hub row
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_skills_category ON skills (category);

-- A guide: prose that teaches how to use several skills together, with the MCP servers
-- they need. Deploying one puts every skill on the agent, links every MCP server, and
-- writes the prose itself into the agent's skills dir as an umbrella SKILL.md — which is
-- what hermes' own curator authors, and the reason a guide is more than a note: the agent
-- reads it too, so it knows when to reach for the set rather than the parts.
--
-- The id lists are deliberately not foreign keys. Production runs with sqlite's default
-- `PRAGMA foreign_keys` off, so a CASCADE here would be decoration; a guide resolves its
-- ids at deploy time and reports the ones that are gone instead.
CREATE TABLE IF NOT EXISTS skill_guides (
  id             TEXT PRIMARY KEY,
  -- also the directory name the umbrella skill is written into, so it carries the same
  -- charset rule as a skill name (ProfileSpec.NAME_PATTERN)
  name           TEXT NOT NULL COLLATE NOCASE UNIQUE,
  description    TEXT,
  body           TEXT NOT NULL,   -- markdown; becomes the umbrella SKILL.md's body
  category       TEXT NOT NULL,
  skill_ids      TEXT,   -- JSON array of skills.id
  mcp_server_ids TEXT,   -- JSON array of mcp_servers.id
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_skill_guides_category ON skill_guides (category);

-- How the skill library is filed: a named set of skills, and optionally the guide that
-- explains them. Organization only — nothing here reaches an agent, there is no deploy, and
-- dropping a group leaves every skill it named in the library.
--
-- A different axis from skills.category, which is one word on one skill: a group is a row, so
-- it can be renamed, described, pointed at a guide, and hold skills that disagree about their
-- category.
--
-- `skill_ids` and `guide_id` are ids and not foreign keys, for the reason the note on
-- skill_guides gives — production runs with `PRAGMA foreign_keys` off, so a CASCADE would be
-- decoration. Both are resolved on read and what is gone is marked.
CREATE TABLE IF NOT EXISTS skill_groups (
  id          TEXT PRIMARY KEY,
  -- a label, not a directory name: nothing writes a group to disk, so unlike a skill's or a
  -- guide's name this carries no charset rule. NOCASE UNIQUE because two headers reading the
  -- same is the one way this list becomes unreadable.
  name        TEXT NOT NULL COLLATE NOCASE UNIQUE,
  description TEXT,
  skill_ids   TEXT,   -- JSON array of skills.id
  guide_id    TEXT,   -- skill_guides.id, or NULL: the association is the optional half
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
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

