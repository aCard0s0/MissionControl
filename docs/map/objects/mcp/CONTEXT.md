# mcp — a catalog, a real Compose project, and the links between them

| Card | One line |
|---|---|
| [mcp-server-entry](mcp-server-entry.md) | the catalog row. `managed \| external \| stdio`. Two read modes, and one is expensive. |
| [managed-mcp-stack](managed-mcp-stack.md) | the `mission-control-mcp` Compose project and its per-host lock. |
| [agent-mcp-link](agent-mcp-link.md) | profile ↔ entry, with the revision that shows drift. |

Stubs with no card body: `RetainedResourceDto` (volumes kept when a server is deleted),
`SupportServiceDto`, `VolumeSpec`, `HealthcheckSpec`, `McpConfigStore`, `McpCatalogSeeder`.
See [../_index.md](../_index.md).

## Two things that bite

**The per-host Compose lock is the scarce resource.** Every managed operation takes it, and so
does any read that refreshes runtime state. `definition(id)` avoids it; `live(id)` does not. A
new call site on a poll path must take `definition`.

**Ownership is by label and fails closed.** A project-label collision refuses rather than
deleting unknown containers. The user-owned project named `mcp` is not ours and is never touched.
