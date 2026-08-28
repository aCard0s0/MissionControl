---
type: object
cluster: agents
universe: live
status: verified
verified: main @ 640da14 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/templates/
---

# Profile template

A reusable recipe for creating a [profile](profile.md): provider, model, SOUL, memory, skills,
MCP servers and its own API keys. `ProfileTemplate` / `ProfileTemplateDto`, `profile_templates`
table, served at **`/api/profile-templates`**.

## Why this shape

The one dashboard-owned concept in this cluster — a template has no home inside hermes, so it
lives in SQLite. It is also why [`ProfileSpec`](profile.md) exists: the template deploy path
creates a profile without ever serving an HTTP request.

**Template secrets are encrypted at rest** with the same key as MCP config values
(`MC_SECRET_KEY`). `TemplateSecrets` holds the shapes; the rules live in
`secrets/SecretsAtRest` — shared, because this package and `mcp/McpConfigStore` had implemented
all four of them separately and they **had drifted** (`agents/templates/TemplateSecrets.java:11`).
The drift that mattered: a blank submission means "I did not touch this secret", because the
editor never received the ciphertext to send back (`encryptOrKeep`, `:40`).

## Shape

`profile_templates` — `schema.sql:34`. Note two columns added **after the table shipped**,
`icon` and `category`, handled by `config/SchemaUpgrades.java` rather than by editing the
CREATE statement — that is the pattern for any further column.

JSON-encoded columns: `skills` (skill ids), `mcp_servers` (`McpServerSpec`), `secrets`
(`{key, enc}`, AES-GCM ciphertext).

## Connected to

- **owns:** its encrypted secrets and its MCP snapshot (`TemplateMcpSnapshots`,
  `TemplateMcpConfigValue`)
- **owned-by:** the dashboard
- **joins:** [Provider](../models/provider-registry.md) by `provider`;
  [MCP server entry](../mcp/mcp-server-entry.md) via `mcp_servers` snapshots;
  produces a [profile](profile.md) through `TemplateApplier`
- **looks-like-but-is-not:** a profile. A template is inert until deployed.

## If you change this

- **Hits:** `TemplateApplier`, `ProfileTemplateService`, `ProfileTemplatesController`,
  `pages/profile-deploy-dialog.ts`, `core/store/template-store.ts`; `schema.sql` **and**
  `SchemaUpgrades` for any column; `secrets/SecretsAtRest` if you touch a secret's shape —
  which also hits MCP config values, since they share it.
- **Does not hit:** existing profiles. A template edit never reaches a profile already deployed
  from it; there is no back-reference.

## Surfaces

| Surface | Role |
|---|---|
| `/api/profile-templates` | reads / writes |
| SQLite `profile_templates` | stored |
| `MC_SECRET_KEY` | encrypts the secrets column |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/templates/`
- Key rotation (`MC_SECRET_KEY_PREVIOUS`): `docs/architecture.md`, environment variables
