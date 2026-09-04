---
type: process
status: verified
verified: main @ 976a9c9 · 2026-08-28
consumes: [mcp-server-entry, docker-host, secret]
produces: [managed-mcp-stack]
---

# mcp-apply

Render the [catalog](../objects/mcp/mcp-server-entry.md) into a real Compose project and apply
it — serialized per host, and never through a shell.

## Input → Movement → Output

A managed catalog entry and its host go in. The per-host Compose file is rendered from SQLite,
secret values are materialized at execution time, and `docker compose` runs under that host's
lock. Out comes a running (or stopped) service in the `mission-control-mcp` project, and an
`applied_revision` that has caught up with `revision`.

## Why this shape

**SQLite is the source of truth; the generated file can be regenerated.** That is what makes the
stack directory (`MC_MCP_STACK_DIR`, `/data/mcp-stacks`) disposable rather than state.

**Secrets are passed to Compose at execution time, not written into the YAML.** The generated
file is therefore safe to keep on disk and to regenerate. Their runtime values remain visible to
anyone with daemon access, as with all container environment variables — encryption at rest is
not encryption in use.

**One lock per host** (`mcp/ComposeStackManager.java:42`), held by mutations only. The reads
that refresh runtime state deliberately skip it, so a catalog listing does not wait out an
image pull. A refresh still costs a CLI fork plus a full container listing, which is why
[`definition(id)` exists next to `live(id)`](../objects/mcp/mcp-server-entry.md): the Agent read
path was forking `docker compose ps` per linked entry, per profile, on a 12-second poll, to
reach one column.

**Ownership is by label and fails closed.** A project-label collision refuses rather than
deleting unknown containers, and the user-owned project named `mcp` is never adopted or modified.
The two label literals were each written in one class and read in another; both now live in
`ManagedMcpStack` (`mcp/ManagedMcpStack.java:5`) because a rename that missed one turned every
ownership guard into "exists but is not owned by Mission Control MCP".

## Steps

1. Validate the request against the allowlist — image, list-form command, environment, ports,
   support services, named volumes. Host binds, host networking, privileged mode, devices,
   capabilities and Docker-socket mounts are rejected (`mcp/McpRequestValidator.java:106`).
2. Bump `revision` on the row. `pendingChanges` is now true, being simply
   `revision > appliedRevision` (`mcp/McpServerDtoMapper.java:36`).
3. `ComposeStackRenderer` writes the host's file: project `mission-control-mcp`, network
   `mission-control-mcp-net`, owner and server-id labels (`mcp/ManagedMcpStack.java:19`).
4. Materialize secret env and headers inside the backend trust boundary
   (`mcp/McpRegistryService.java:117`).
5. `ComposeStackManager` takes the host lock and runs Compose without a shell (`:42`).
6. Set `applied_revision = revision`, and persist the refreshed runtime state.
7. On boot, `McpStartupReconciler` re-runs desired-vs-actual so a restart converges.

## If you change this

- **Hits:** `ComposeStackRenderer`, `ComposeStackManager`, `McpComposeLifecycle`,
  `McpHealthProbe`, `McpStartupReconciler`, `McpServerDeletion`, `McpOperationsConfig` (the
  executor these run on — a failure there is logged as **ERROR**, because no request ever sees
  it); `core/mcp/catalog-rules.ts`, which mirrors the validation on the FE.
- **Does not hit:** any Agent's configuration. Applying a stack does not connect anything —
  that is an [MCP agent link](../objects/mcp/agent-mcp-link.md), and cross-host connections need
  an explicit `crossHostUrl` because the shared network spans one daemon only.

## Surfaces

| Surface | Role |
|---|---|
| `/api/mcp-servers/**` | entry |
| `MC_MCP_STACK_DIR` | generated, regenerable |
| Docker Compose plugin inside the MC image | writes, under the lock |

## See

- Objects: [MCP server entry](../objects/mcp/mcp-server-entry.md),
  [managed MCP stack](../objects/mcp/managed-mcp-stack.md), [secret](../objects/dashboard/secret.md)
- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/`
- `docs/architecture.md`, "MCP server catalog and Compose projects"
