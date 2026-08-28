---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 640da14 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/secrets/SecretsAtRest.java
---

# Secret

A credential the dashboard stores encrypted: a [profile template's](../agents/profile-template.md)
API keys, and an [MCP server entry's](../mcp/mcp-server-entry.md) secret environment values and
headers. `SecretsAtRest` owns the rules; `SecretCipher` owns the `enc:v1:` envelope.

Not an API resource — there is no `/api/secrets`. It is a **trust boundary**, and both storing
packages route through it.

## Why this shape

Four rules, spelled out at `secrets/SecretsAtRest.java:7`. Paraphrasing loses the point, so
read them there; the load-bearing ones are:

- **Seal on the way in, keep on blank.** No editor ever receives ciphertext, so a blank
  submission means "keep what you hold". Submitting nothing with nothing to keep is an **error**,
  not a silent no-op.
- **A save is a rotation opportunity.** Keeping a value re-seals it under the current key, so
  `MC_SECRET_KEY_PREVIOUS` stops being load-bearing once each secret has been through one save.
- **An unopenable envelope is preserved, never destroyed.** A wrong key or corrupt ciphertext is
  the only copy of the credential; an unrelated edit must not overwrite it.
- **Degrade, do not fail.** One unreadable secret must not take a whole read, render or deploy
  with it (`openOrNull`).

**Centralised for a live defect.** `mcp/McpConfigStore` and `agents/templates/TemplateSecrets`
each implemented all four over their own value record, and had already drifted: a *new* secret
submitted blank was a **400 in the catalog and a silent omission in a template** — which loses
the credential and reports success. Rule 2 had drifted the other way: template MCP snapshots
documented themselves as "matching the behavior of template-owned API keys", which did not
re-seal at all (`secrets/SecretsAtRest.java:26`).

Deliberately narrow: the class knows nothing about `StoredValue`, `TemplateMcpConfigValue`, or
which DTO a redaction becomes. **It owns one envelope at a time**, and the wire and storage
shapes stay with their owners.

## Shape

- Envelope prefix `enc:v1:` — `secrets/SecretCipher.java`
- Key: `MC_SECRET_KEY` (AES). **Startup fails fast without it** unless `mc.allow-dev-key=true`
  / `MC_ALLOW_DEV_KEY=true`, which logs a warning and uses a built-in dev key
  (`secrets/SecretCipher.java:59`). `./mc` generates and reuses a real key in the gitignored
  `.mission-control.env`.
- `MC_SECRET_KEY_PREVIOUS` accepts the prior key during rotation.
- API: `seal`, `sealOrKeep`, `reseal`, `open`, `openOrNull`, `isRecoverable`.

## Connected to

- **owns:** the envelope format
- **owned-by:** nothing — it is the boundary
- **joins:** [Profile template](../agents/profile-template.md) `secrets` column;
  [MCP server entry](../mcp/mcp-server-entry.md) secret env + headers, passed to Compose at
  execution time and never written into generated YAML
- **looks-like-but-is-not:** a [webhook](../agents/webhook-subscription.md) HMAC secret. Hermes
  generates and stores those in plaintext; they never pass through here.

## If you change this

- **Hits:** both storing packages at once — `mcp/McpConfigStore` **and**
  `agents/templates/TemplateSecrets`. That is the entire reason the class exists; changing one
  caller's behaviour without the other is how the drift happened.
- **Does not hit:** runtime visibility. Encryption at rest is not encryption in use — container
  environment values remain visible to any principal with Docker-daemon access, and the
  architecture doc says so plainly.

## Surfaces

| Surface | Role |
|---|---|
| `MC_SECRET_KEY` / `_PREVIOUS` | the key material |
| `.mission-control.env` (gitignored) | where `./mc` keeps it |
| SQLite `profile_templates.secrets`, `mcp_servers.config_json` | ciphertext at rest |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/secrets/`
