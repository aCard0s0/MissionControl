---
type: object
cluster: mcp
universe: live
status: verified
verified: main @ 640da14 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/ManagedMcpStack.java
---

# Managed MCP stack

The real Compose project that managed [MCP server entries](mcp-server-entry.md) are rendered
into, one per [Docker host](../docker/docker-host.md). `ManagedMcpStack` holds the names;
`ComposeStackRenderer` writes the YAML; `ComposeStackManager` runs Compose;
`McpComposeLifecycle` sequences it.

## Why this shape

The class exists for the same reason as [`ManagedContainer`](../docker/container.md), and its
comment names the two escapes it closed (`mcp/ManagedMcpStack.java:5`):

- **The network name had escaped the package.** `agents/` held its own copy of the literal to
  attach an Agent container to — so renaming it here would have left every Agent attaching to a
  network no managed server is on, *and the health probe would still have passed*, because it
  reads the renderer's constant.
- **The owner label was written and read from two separate literals.** A rename turns every
  ownership guard into "exists but is not owned by Mission Control MCP".

**Compose operations are serialized per host** by a `ReentrantLock` per `hostId`
(`mcp/ComposeStackManager.java:42`) and run **without a shell**. That lock is why
[`definition` vs `live`](mcp-server-entry.md) matters: a chatty read path contends with every
real operation.

## Shape

`mcp/ManagedMcpStack.java:19`:

| Constant | Value |
|---|---|
| `PROJECT` | `mission-control-mcp` |
| `NETWORK` | `mission-control-mcp-net` |
| `OWNER_LABEL` | `io.hermes.mission-control.owner` |
| `SERVER_ID_LABEL` | `io.hermes.mission-control.mcp-server-id` |

Generated Compose files live below `MC_MCP_STACK_DIR` (`/data/mcp-stacks` in the image).
**SQLite is the source of truth; the file can be regenerated.**

Agents are attached to `NETWORK` on demand so a managed server resolves by its Compose service
name; Mission Control attaches *itself* for the same reason before probing one.

## Connected to

- **owns:** the Compose project's containers, networks and volumes on one host
- **owned-by:** a [Docker host](../docker/docker-host.md)
- **joins:** each [MCP server entry](mcp-server-entry.md) by `SERVER_ID_LABEL`; the Compose
  service name is the entry's `service_key`
- **looks-like-but-is-not:** the user-owned project named **`mcp`**. Never adopted, never
  modified. Project-label collisions **fail closed** rather than deleting unknown containers.

## If you change this

- **Hits:** `ComposeStackRenderer`, `ComposeStackManager`, `McpComposeLifecycle`,
  `McpHealthProbe`, `McpStartupReconciler`, `McpServerDeletion`; and
  `agents/AgentMcpCatalogService.java:260`, which is where the duplicated network literal used to
  be and now reads `ManagedMcpStack.NETWORK`. Renaming the constant is safe *because* of that;
  re-introducing a literal anywhere is the failure this class exists to prevent.
- **Does not hit:** any Hermes container's own deployment. Agents are *attached* to the network
  after deploy; note that the upgrade must re-attach user-defined networks or the link is
  silently lost (see [upgrade-image](../../processes/upgrade-image.md)).

## Surfaces

| Surface | Role |
|---|---|
| Docker Compose CLI plugin, inside the MC image | writes, under the per-host lock |
| `MC_MCP_STACK_DIR` | generated YAML |
| Hermes containers | attached to `NETWORK` on demand |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/mcp/ManagedMcpStack.java`
