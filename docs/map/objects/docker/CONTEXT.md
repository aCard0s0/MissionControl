# docker — the daemon layer

The layer that owns nothing. The daemon and the containers are the source of truth; the backend
is read-through and persists none of it (see [../../../architecture.md](../../../architecture.md)).
The only rows SQLite holds here are [docker hosts](docker-host.md) — connections, not state.

| Card | One line |
|---|---|
| [docker-host](docker-host.md) | a daemon we can reach. `dh-local` always exists and cannot be removed. |
| [container](container.md) | a container, and the `mc.*` label vocabulary that marks ours. |
| [image](image.md) | tags: what is pulled, what is published, how they order. |

The browser never talks to Docker — it physically cannot (unix socket) and must not (daemon
access is root-equivalent). The backend is the only gateway.
