# Deployment — Tailscale (private remote access)

Run Mission Control reachable **only from your tailnet**, over real TLS.

One tailscale container per stack owns the tailnet identity and the certificate.
The app container owns nothing about networking: it publishes no host port, has
its own network namespace, and is reachable only as a compose-internal backend
of Tailscale Serve. If the dashboard is reachable any other way, the deployment
is wrong — it mounts the host docker socket and has no authentication of its own.

Files live in [deploy/](../deploy).

```
deploy/
  compose.yml          the stack — no host ports, userspace sidecar
  compose.local.yml    opt-in loopback host port, merged on top (./mc start --local)
  .env                 compose interpolation ONLY; gitignored, mode 0600
  .env.example
  acl.hujson           the tailnet policy that guards this node
  tailscale/           mounted as /config — a DIRECTORY, not a single file
    serve-https.json   :443 + cert, tailnet only            (TS_SERVE_MODE=https)
    serve-funnel.json  the same, plus the public internet   (TS_SERVE_MODE=funnel)
```

## Prerequisites

- A [tailscale](https://tailscale.com) account with **MagicDNS** enabled
  (admin console → DNS).
- **HTTPS Certificates** enabled for the tailnet (admin console → DNS → HTTPS
  Certificates). This is not optional and there is no plaintext fallback: the
  serve configs key their handler on `${TS_CERT_DOMAIN}`, which containerboot
  derives from the node's cert domain. With certificates off there is nothing to
  substitute, the handler matches no incoming Host, and the node comes up
  **healthy while serving nothing**.
- Docker with the compose plugin on the host that runs your Hermes containers.

The Mission Control image also contains the Docker CLI and Compose plugin used
to control `mission-control-mcp`. The host only needs the Engine/socket already
required by the dashboard; generated stack files and catalog state persist in
the `mission-control-data` volume.

## Deploy

```bash
# 1. configure
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
#    fill in TS_AUTHKEY (admin console → Settings → Keys → auth key;
#    REUSABLE + tag:server), TS_IMAGE_TAG (pin it; `latest` moves) and
#    TS_TAILNET (your MagicDNS suffix, e.g. tailnet-name.ts.net).

# 2. write the ACL before the first `up`, not after
#    paste deploy/acl.hujson into https://login.tailscale.com/admin/acls/file

# 3. build + bring it up (tailscale is ./mc's default flavor)
./mc start --build
# On first start ./mc creates .mission-control.env (mode 0600) containing the
# persistent application encryption key. Back it up with other deployment state.
```

Subsequent deploys are just `./mc start` (add `--build` to pick up code changes).

### The two flavors are one service

`--ts=off` is not a second deployment. It runs the **same** `mission-control` compose service
and simply does not start the sidecar, so it inherits the healthcheck, `init`, `cpus`,
`mem_limit`, `pids_limit` and — importantly — the same data volume.

It used to be a separate `docker run`, and that copy had drifted: a different data volume (so
switching flavors silently switched you to a different database), and no `mem_limit` at all,
which matters more than it sounds. The Dockerfile pins `-XX:MaxRAMPercentage=50.0` against
compose's 512m; with no cgroup limit the JVM reads the host's RAM instead:

```
compose  (mem_limit 512m)          MaxHeapSize =   256 MiB
old --ts=off (no limit, 21 GiB host)  MaxHeapSize = 10992 MiB
```

`deploy/.env` is deliberately not read in this mode — the point of `--ts=off` is that it runs
with no tailnet, so the file holding the auth key should not be needed or even opened. The
tailscale-only interpolations get dummy values instead.

`--no-socket` is gone. Compose merges `volumes:` by target path and cannot remove an entry,
so making the docker socket optional would need its own inverted override file; it was not
worth one. Use `--ts=off` on a host you trust, or put a restricted socket proxy in front.

> **One-time note:** the old flavor's volume `mission-control-data` still exists on this host
> with its own database, now unreferenced. Nothing reads it. Remove it when you are sure you
> do not want it: `docker volume rm mission-control-data`.

### Flags

| flag | effect |
|---|---|
| `--local` / `--no-local` | overrides `LOCAL_PORT_ENABLED`; appends `-f deploy/compose.local.yml` |
| `--serve=https\|funnel` | overrides `TS_SERVE_MODE` for this invocation |
| `--ts=off` | the same compose service with the sidecar not started, plus a published port — no tailnet needed |

`--local` publishes `LOCAL_BIND:LOCAL_PORT` straight to the app container. That
path does not pass through Serve, so it is not covered by the tailnet ACL and
carries no identity headers. It is for debugging on the host; keep `LOCAL_BIND`
on loopback.

`./mc status` reports **observed** state, not what `.env` claims: the node's
backend state, its real MagicDNS URL, the serve handlers the daemon actually
loaded, and a warning if a host port is published or funnel is on.

### Verify

```bash
# static — every automatable check in one run
scripts/audit.sh deploy     # from the tailscale-sidecar skill

# what the daemon actually loaded
docker compose -p mission-control -f deploy/compose.yml \
  exec tailscale tailscale serve status
# https://mission-control.<tailnet>.ts.net (tailnet only)
# |-- / proxy http://mission-control:8080
```

Static checks cannot prove reachability. Two things you have to do yourself:

- from another tailnet device: `curl -sSf https://mission-control.<tailnet>.ts.net`
  succeeds;
- from a machine **off** the tailnet: the same URL must fail to connect. If it
  answers, something is published that you did not intend — most likely a host
  port, or funnel left on.

## Why TS_TAILNET is required

The app's CORS allowlist is built from `TS_HOSTNAME` + `TS_TAILNET`, and it has to be,
because **same-origin is not exempt from CORS**. Per the Fetch spec a browser attaches an
`Origin` header to every request whose method is not GET or HEAD — same-origin requests
included — and Spring answers 403 to any unlisted `Origin` before the handler runs.

Get it wrong and the failure is quiet in the worst way: the page loads, every GET works, and
every button 403s. It reads as a broken dashboard, not as an allowlist. Making the variable
required means that failure happens at `./mc start` instead of on your first click.

The rendered value is exact origins, never `https://*.ts.net`:

```bash
docker compose -p mission-control -f deploy/compose.yml config | grep MC_CORS_ORIGINS
# MC_CORS_ORIGINS: https://mission-control.<tailnet>.ts.net,http://localhost:8080,http://127.0.0.1:8080
```

A wildcard would let a page on *any* tailnet node make cross-origin calls to a dashboard
that has no authentication and mounts the host docker socket. The loopback entries are there
for `./mc start --local`; `--ts=off` reuses them, passing `PORT`/`BIND_ADDRESS` through as
`LOCAL_PORT`/`LOCAL_BIND`.

`/ws/terminal` is deliberately not covered by this list — it compares `Origin` against the
`Host` header instead, so it admits same-origin traffic on any deployment without being
configured. That endpoint hands out an interactive shell, and widening it by editing an env
var should not be possible.

## Access

From any device on your tailnet:

```
https://mission-control.<tailnet>.ts.net
```

MagicDNS resolves the name and Serve presents a real Let's Encrypt certificate
for it — no warning page, and Secure cookies and HSTS are available to the app
if it ever wants them. Note that WireGuard already encrypts peer-to-peer; that
is *not* the same as encrypting to the browser, which is what this provides.

**Phone / iPad:** install the Tailscale app, sign in to the same tailnet, open
the URL. No VPN config, no port forwarding.

## Security notes

- **No host ports published.** The app has its own network namespace and only
  `expose`s 8080 on the compose network. `--local` is the single, explicit,
  loopback-bound exception.

- **Userspace, not kernel networking.** `TS_USERSPACE=true` with no
  `/dev/net/tun`, no `NET_ADMIN`, no `NET_RAW`. The older shape here was kernel
  mode plus `network_mode: service:tailscale`, which is **looser**, not tighter:
  the app then listens inside the tailnet node's own namespace, so every tailnet
  peer could hit `:8080` directly and skip Serve entirely. Serve now proxies to
  the compose service name `http://mission-control:8080`. (The `tailscale serve`
  *CLI* refuses non-local targets; that restriction belongs to the CLI, not to
  Serve — `TS_SERVE_CONFIG` is loaded straight into the daemon.)

- **The ACL is half the configuration, and its port must match
  `TS_SERVE_MODE`.** `https` → `tag:server:443`. A mismatch is the most common
  self-inflicted outage here and it fails in the worst direction: you are locked
  out, assume the rule is too tight, and loosen it globally. See
  [deploy/acl.hujson](../deploy/acl.hujson).

- **⚠ The node deployed today is UNTAGGED.** `tailscale status --json` reports
  `"Tags": null`, so *no* `tag:server` rule matches it — access is whatever your
  tailnet's default policy allows — and its key expires on **2026-12-09**, after
  which the dashboard silently drops off the tailnet. Tagged nodes never expire.
  To fix, re-auth the node with the tag (this is a deliberate, interactive step;
  do it while you can still reach the admin console):

  ```bash
  # 1. tagOwners must already list tag:server — apply deploy/acl.hujson's
  #    tagOwners block first, and keep a rule that still lets you in.
  # 2. mint a REUSABLE key with tag:server, put it in deploy/.env
  # 3. re-auth in place
  docker compose -p mission-control -f deploy/compose.yml \
    exec tailscale tailscale up \
      --authkey "$(sed -n 's/^TS_AUTHKEY=//p' deploy/.env | tail -n1)" \
      --advertise-tags=tag:server --reset
  # 4. confirm, then apply the full acl.hujson
  docker compose -p mission-control -f deploy/compose.yml \
    exec tailscale tailscale status --json | grep -A3 '"Tags"'
  ```

  `TS_EXTRA_ARGS=--advertise-tags=tag:server` in `deploy/.env` covers the *next*
  fresh authentication; it does nothing to a node that is already logged in,
  which is why the step above is explicit.

- **Funnel stays off.** `TS_SERVE_MODE=funnel` publishes this to the open
  internet. Mission Control has no authentication and mounts the host docker
  socket, and Serve sends no identity headers on funnel requests, so there is
  nothing the app could gate on even once it learns to. `./mc start` warns
  loudly and non-optionally if it is set.

- **docker.sock is root-equivalent.** The compose file mounts
  `/var/run/docker.sock`, so anyone who reaches the dashboard can manage the
  host's daemon, including creating the dedicated MCP Compose project. Treat the
  ACL as mandatory, or front the socket with a restricted proxy that exposes
  every Engine operation Mission Control needs (see
  [architecture.md](architecture.md)).

- **Identity is separate from access, and it is already on the wire.** An ACL
  authenticates *machines*, not people, so the stack today is one shared
  anonymous surface with nothing to log. Serve injects a verified
  `Tailscale-User-Login` header (plus name and avatar) on every proxied request
  and **strips any inbound header of the same name**, so it cannot be spoofed.
  Mission Control does not read it yet; when it does, gate on the *value* and
  fail closed when it is absent — presence is not membership (external users who
  accepted a share have it set), and it is absent for funnel traffic and for
  tagged devices such as CI runners. This only holds while the app is
  unreachable except through Serve: `--local` and the `--ts=off` flavor both
  bypass it.

- **Key hygiene.** The key is spent **once** — node identity lives in the
  `tailscale-state` volume and `TS_AUTH_ONCE=true` stops the container
  re-authenticating on every start, so rotating `TS_AUTHKEY` needs no redeploy
  and a revoked key never breaks a running node. Revoke and re-mint if it leaks.
  Never use an ephemeral key here: ephemeral nodes are deleted on disconnect,
  which fights the state volume and burns a new node (`mission-control-1`, `-2`,
  …) on every restart, each one holding the MagicDNS name your URL and ACL point
  at.

- **Teardown frees the name.** `./mc down --volumes` runs `tailscale logout`
  *before* removing the containers, while the node can still reach the
  coordination server. Skipping that leaves a machine entry that is offline
  forever, still counted against your device limit, still matching `tag:server`
  in every ACL rule — and still holding `mission-control`, so the next deploy
  comes up as `mission-control-1`. A plain `./mc down` keeps the state volume,
  so the node returns intact and is deliberately *not* logged out.

- **Application encryption key.** `.mission-control.env` is generated once,
  ignored by git, and read by both deployment flavors. It is kept out of
  `deploy/.env` on purpose: that file is compose interpolation and holds
  `TS_AUTHKEY`, which the app must never see — an `env_file: ./.env` on the app
  service would hand it the credential that joins nodes to your tailnet,
  readable from `/proc/self/environ`. Verify the separation holds after any edit:

  ```bash
  docker compose -p mission-control -f deploy/compose.yml config | grep -c TS_AUTHKEY
  # 1
  ```

  Keep its permissions at `0600` and back it up; losing it makes stored
  profile-template and MCP-server secrets unrecoverable. During rotation, keep
  the old value as `MC_SECRET_KEY_PREVIOUS` until stored secrets have been
  resaved.

## Failure modes

| Symptom | Cause |
|---|---|
| Node is up, URL refuses connection | Serve config never loaded — `TS_SERVE_MODE` names a file that is not in `deploy/tailscale/`, or the mount reverted to a single file. `tailscale serve status` prints empty. |
| `https://` fails, no certificate | HTTPS Certificates not enabled for the tailnet. There is no plaintext fallback. |
| Worked yesterday, container now restarts | `TS_AUTH_ONCE` missing and the key was rotated, revoked or expired. |
| `502` from Serve | Backend name wrong for the mode (`127.0.0.1` in userspace), or `depends_on` lost `condition: service_healthy`. |
| Serve can't resolve `mission-control` | `TS_ACCEPT_DNS` not `false`; MagicDNS took over `/etc/resolv.conf`. |
| Connection times out from one device only | ACL port does not match `TS_SERVE_MODE`. |
| Node re-appears as `mission-control-1` | Torn down without `tailscale logout`, or an ephemeral key, or the state volume was not persisted. |
