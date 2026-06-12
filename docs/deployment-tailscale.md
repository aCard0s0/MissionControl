# Deployment — Tailscale (private remote access)

Run Mission Control reachable **only from your tailnet**: the app container
shares the tailscale sidecar's network namespace, so no host port is ever
published — nothing on the LAN or the internet can reach it. Tailscale serve
forwards tailnet traffic to the app on `127.0.0.1:8080`.

Files live in [deploy/tailscale](../deploy/tailscale).

## Prerequisites

- A [tailscale](https://tailscale.com) account with MagicDNS enabled (admin console → DNS).
- Docker with the compose plugin on the host that runs your Hermes containers.

## Deploy

```bash
# 1. configure the auth key
cp deploy/tailscale/.env.example deploy/tailscale/.env
                              # then paste your tskey-auth-… key
                              # (admin console → Settings → Keys → auth key;
                              #  reusable + tag:server recommended)

# 2. build + bring it up (tailscale is ./mc's default flavor)
./mc start --build
```

Subsequent deploys are just `./mc start` (add `--build` to pick up code
changes); `./mc status` shows tailscale health and the exact URL. `./mc start
--ts=off` switches to a plain docker container with a published port instead —
the two flavors never run side by side.

## Access

From any device on your tailnet:

```
http://mission-control.<tailnet>.ts.net
```

MagicDNS resolves the name.

Verify the proxy is wired up:

```bash
docker compose -p mission-control -f deploy/tailscale/docker-compose.yml \
  exec tailscale tailscale serve status --json
# { "TCP": { "80": { "TCPForward": "127.0.0.1:8080" } } }
```

## Security notes

- **No host ports published.** `network_mode: service:tailscale` means the
  app only listens inside the tailscale network namespace — it is unreachable
  from the LAN and the internet by construction.
- **Funnel stays off.** This deployment is tailnet-only by design; don't run
  `tailscale funnel` against it.
- **Restrict who can reach it with ACLs.** The dashboard has no auth of its
  own yet (see [api.md](api.md) roadmap), so the tailnet ACL is the access
  control. Example — only you may reach the dashboard:

  ```jsonc
  {
    "acls": [
      {
        "action": "accept",
        "src":    ["andre.fmarques.cardoso@gmail.com"],
        "dst":    ["tag:server:443"]
      }
    ],
    "tagOwners": {
      "tag:server": ["andre.fmarques.cardoso@gmail.com"]
    }
  }
  ```

- **docker.sock is root-equivalent.** The compose file mounts
  `/var/run/docker.sock`, so anyone who reaches the dashboard can manage the
  host's daemon. Treat the ACL above as mandatory, or front the socket with a
  restricted proxy (see [architecture.md](architecture.md)).
- **Key hygiene.** Use a tagged auth key (tagged nodes don't expire); if you
  use an untagged key, disable key expiry for the node in the admin console
  or plan to re-auth. Revoke and re-mint the key if it leaks — the node state
  persists in the `tailscale-state` volume, so a rotated key is only needed
  on first start or after `tailscale logout`.
- **Phone / iPad access.** Install the Tailscale app from the App Store /
  Play Store, sign in to the same tailnet, and open the
  `http://mission-control.<tailnet>.ts.net` URL — no VPN config, no port
  forwarding.
