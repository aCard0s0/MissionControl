---
type: object
cluster: agents
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/HermesWebhooks.java
---

# Webhook subscription

An inbound route on one [profile's](profile.md) hermes webhook listener. `WebhookSubscriptionDto`
/ `WebhookPlatformDto` / `WebhooksDto`, served at
**`/api/agents/{hostId}/{containerId}/{name}/webhooks`**.

## Why this shape

Same rule as [Cron job](cron-job.md) — read the file, write through the CLI — plus three
constraints that only exist here.

**Mission Control never carries webhook traffic, and publishes a port only when a deploy asks
for one.** The mapping that makes a route reachable is create-time: the *host access* group on
the deploy form (its webhook preset publishes 8644 on `127.0.0.1`), or the operator's own
`docker run -p` when recreating by hand. Nothing is published on demand, and `docs/architecture.md`
argues why: the listener is per profile and every profile defaults to 8644, Docker cannot add a
port mapping to a running container, and host ports are one namespace per host. Proxying through
the dashboard was rejected separately — Mission Control has no authentication of any kind, so a
proxy route would be an unauthenticated public trigger for agent runs.

Three consequences, all implemented:

- **`WebhookPlatformDto.published` is read from the daemon**, never remembered: `PublishedPorts`
  inspects the container's port bindings, so a port mapped by hand counts the same as one the
  form asked for. Until then the page shows the route URL with the listener's own port, not the
  `localhost` hermes prints, and says the route is unreachable.
- **A manual `-p` survives an image update** — port bindings, exposed ports and
  `PublishAllPorts` are copied onto the replacement container. Without that, moving to a newer
  tag would silently un-expose the listener with nothing on any page to say hooks had stopped.
- **One listener port per container, not per profile.** Profiles share one network namespace, so
  a second listener on 8644 never binds — and hermes reports that only in the gateway log of a
  profile nobody has open. Enabling a listener therefore refuses a port another profile in the
  same container holds and walks a defaulted one up from 8644 (`DEFAULT_PORT`,
  `agents/HermesWebhooks.java:59`) to the first free port. This is why
  [`ProfileInventory`](profile.md) is a shared component.

**Hermes generates each route's HMAC secret**, so no secret travels through the dashboard to
reach it. Hermes stores it in plaintext and the sending provider needs it, so the listing carries
only a masked tail; revealing it in full is a separate, deliberate request.

## Shape

- File: `/opt/data/profiles/<name>/webhook_subscriptions.json`, keyed by route name —
  `agents/ProfilePaths.java:61`
- A route needs the profile's `platforms.webhook` listener enabled first, which is a **config
  write**, not a webhook write.

## Connected to

- **owns:** nothing
- **owned-by:** a [profile](profile.md), and through it the container's port namespace
- **joins:** [Container](../docker/container.md) port bindings — the only thing that makes a
  route reachable, and the reason the upgrade copies them
- **looks-like-but-is-not:** an outbound webhook (`OutboundWebhookDto`, route `/outbound`). Same
  package, opposite direction.

## If you change this

- **Hits:** `HermesWebhooks`, `AgentWebhooksController`, `pages/webhooks.ts`,
  `core/store/webhook-store.ts`, `core/hermes-hook-events.ts`; and the port-copying branch of
  [upgrade-image](../../processes/upgrade-image.md) — breaking that silently un-exposes listeners.
- **Does not hit:** reachability. Nothing you change here publishes a port; the map claims none.
  Also does not hit inbound traffic handling — Mission Control is never on that path.

## Surfaces

| Surface | Role |
|---|---|
| `…/{name}/webhooks` | reads / writes |
| `hermes webhook …` | the only writer |
| the operator's `docker run -p` | the only thing that exposes it |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/agents/HermesWebhooks.java`
- The full argument: `docs/architecture.md`, "Exposing a webhook listener is the operator's job, deliberately"
