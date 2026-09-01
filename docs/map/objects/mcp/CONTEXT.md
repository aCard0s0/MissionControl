# mcp — a catalog, a real Compose project, and the links between them

| Card | One line |
|---|---|
| [mcp-server-entry](mcp-server-entry.md) | the catalog row. `managed \| external \| stdio`. Two read modes, and one is expensive. |
| [managed-mcp-stack](managed-mcp-stack.md) | the `mission-control-mcp` Compose project and its per-host lock. |
| [agent-mcp-link](agent-mcp-link.md) | profile ↔ entry, with the revision that shows drift. |
| [mcp-group](mcp-group.md) | a set of entries with a deploy. Stores no agents: coverage is derived from the links. |

Stubs with no card body: `RetainedResourceDto` (volumes kept when a server is deleted),
`SupportServiceDto`, `VolumeSpec`, `HealthcheckSpec`, `McpConfigStore`, `McpCatalogSeeder`.
See [../_index.md](../_index.md).

## Two things that bite

**The per-host Compose lock is the scarce resource.** Every managed operation takes it, and so
does any read that refreshes runtime state. `definition(id)` avoids it; `live(id)` does not. A
new call site on a poll path must take `definition`.

**Ownership is by label and fails closed.** A project-label collision refuses rather than
deleting unknown containers. The user-owned project named `mcp` is not ours and is never touched.

**"Group" means one thing on that page now.** The roster's arrangement by kind and host is
`mcpServerSections` (`pages/mcp-server-sections.ts`); a **group** is the operator-made set in
[mcp-group](mcp-group.md). The code said `serverGroups` for the first one until the second
existed.
