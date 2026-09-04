# dashboard — what Mission Control owns itself

Nouns with no home inside hermes and nothing to read from the daemon. Also the two boundaries:
the secret envelope, and the generated route contract.

| Card | One line |
|---|---|
| [api-contract](api-contract.md) | generated. Both sides assert against it. **Never hand-edit.** |
| [secret](secret.md) | the four rules every stored credential obeys. Shared by two packages for a reason. |
| [terminal-session](terminal-session.md) | one xterm ↔ one `docker exec`. Read the architecture doc first. |
| [scrim](scrim.md) | the backdrop behind every dialog. Click-outside and Escape, in one directive. |
| [confirm](confirm.md) | the one question before a delete. A service any page asks; the dialog lives in the shell. |
| [board-task](board-task.md) | kanban card, scoped to a container id. |
| [prompt](prompt.md) | operator's text library. Nothing in a container reads it. |
| [skill-library](skill-library.md) | skills the dashboard holds and deploys. `hub` is a pointer, `local` owns the files. |
| [guide](guide.md) | prose composing several skills + MCP servers. Deploys as an umbrella SKILL.md the agent reads. |
| [skill group](skill-group.md) | how the library is filed: a named set of skills, optionally pointing at the guide that explains it. No deploy. |
| [prompt group](prompt-group.md) | the same filing over prompts. No guide link, no behaviour at all. |

Stubs with no card body: `ServerLogBuffer` / `/api/server/logs`, `RuntimeConfigController`
(`/config.js`), `AppProperties`, `SchemaUpgrades`, `StartupSummary`, notifications/toasts.
See [../_index.md](../_index.md).
