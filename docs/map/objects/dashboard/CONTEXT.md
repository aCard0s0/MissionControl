# dashboard — what Mission Control owns itself

Nouns with no home inside hermes and nothing to read from the daemon. Also the two boundaries:
the secret envelope, and the generated route contract.

| Card | One line |
|---|---|
| [api-contract](api-contract.md) | generated. Both sides assert against it. **Never hand-edit.** |
| [secret](secret.md) | the four rules every stored credential obeys. Shared by two packages for a reason. |
| [terminal-session](terminal-session.md) | one xterm ↔ one `docker exec`. Read the architecture doc first. |
| [board-task](board-task.md) | kanban card, scoped to a container id. |
| [prompt](prompt.md) | operator's text library. Nothing in a container reads it. |

Stubs with no card body: `ServerLogBuffer` / `/api/server/logs`, `RuntimeConfigController`
(`/config.js`), `AppProperties`, `SchemaUpgrades`, `StartupSummary`, notifications/toasts.
See [../_index.md](../_index.md).
