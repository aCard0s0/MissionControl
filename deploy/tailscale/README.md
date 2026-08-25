# Serve configs

Mounted as the directory `/config` (read-only) in the tailscale sidecar.
`TS_SERVE_MODE` in `../.env` picks which file the daemon loads:
`TS_SERVE_CONFIG=/config/serve-${TS_SERVE_MODE:-https}.json`.

The directory is mounted, not the individual file. Tailscale watches the parent
directory for change events; a single-file bind mount is one inode that never
fires one, so edits are silently ignored until the container is recreated.

| file | listener | audience |
|---|---|---|
| `serve-https.json` | `:443` with a real cert | tailnet only |
| `serve-funnel.json` | `:443` with a real cert | tailnet **and the public internet** |

`${TS_CERT_DOMAIN}` is the only substitution containerboot performs — it is
derived from the node's cert domain, so both files need **HTTPS Certificates**
enabled for the tailnet (admin console -> DNS). With certificates off there is
nothing to substitute, the `Web` handler key matches no incoming Host, and the
node comes up healthy while serving nothing. That is why there is no plaintext
variant here.

`Proxy` names the compose **service** (`mission-control`), not `127.0.0.1`: the
sidecar runs in userspace mode and the app has its own network namespace. The
`tailscale serve` CLI refuses non-local targets, but that restriction belongs to
the CLI — `TS_SERVE_CONFIG` is loaded straight into the daemon. Confirm what it
actually loaded with:

    docker compose -p mission-control -f ../compose.yml exec tailscale tailscale serve status

Funnel is a loaded gun here: Mission Control has no authentication of its own
and mounts the host docker socket. Do not switch `TS_SERVE_MODE` to `funnel`.
