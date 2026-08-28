---
type: process
status: verified
verified: main @ 976a9c9 · 2026-08-28
consumes: [container, image, profile, profile-template]
produces: [container, profile]
---

# deploy-agent

Create a Hermes container, its data volume, and its seed profiles — as one transaction that
rolls back.

## Input → Movement → Output

A [host](../objects/docker/docker-host.md), an [image](../objects/docker/image.md) tag and a list
of profile names (optionally from a [template](../objects/agents/profile-template.md)) go in.
A volume is created, one or more one-shot bootstrap containers seed it, then the gateway
container is created and waited on. Out comes a running
[container](../objects/docker/container.md) with named [profiles](../objects/agents/profile.md)
in it — or nothing at all.

## Why this shape

`HermesDeployer` is split out of `DockerGateway` because **a deploy is a multi-resource
transaction**: a volume, one or more one-shot bootstrap containers, then the gateway itself.
Everything after the volume creation runs inside a rollback guard, and keeping that guard
readable is the point of the split (`docker/HermesDeployer.java:25`).

The current Hermes image initializes the default profile on first boot, so readiness is *waited
on* rather than assumed — and if readiness or named-profile creation fails, **the container and
volume are rolled back**.

## Steps

1. Resolve the host to a `DockerHostRef` at the edge — nothing downstream resolves hosts
   (`docker/ContainerUpdateService.java:37` explains the same rule).
2. Create the `mc-hermes-<name>` volume (`docker/ManagedContainer.java:42`).
3. Run bootstrap one-shots, labelled `mc.bootstrap` (`:38`), to seed the volume.
4. Create the gateway container with `gateway run`, the volume at `/opt/data`, restart policy
   `unless-stopped`, and the `mc.*` labels (`docker/ManagedContainer.java:82`).
5. Bounded readiness checks (`docker/DeploymentReadiness.java`), then create the requested named
   profiles.
6. On any failure after step 2: roll back the container **and** the volume
   (`docker/HermesDeployer.java:49`).

## If you change this

- **Hits:** `ManagedContainer` labels — and therefore `ContainerUpgrader`, `ContainerLifecycle`
  and `ContainerInventory`, which all read them; `TemplateApplier` when deploying from a
  template; `pages/agent-create-dialog.ts`, `profile-deploy-dialog.ts`.
- **Does not hit:** port publishing. **Agent containers publish no ports at all** — exposing a
  profile's [webhook listener](../objects/agents/webhook-subscription.md) is the operator's own
  `docker run -p`, and a deploy-time publish could only guess at a port that is decided long
  after. Also does not attach the MCP network: that happens when a
  [link](../objects/mcp/agent-mcp-link.md) is made.

## Surfaces

| Surface | Role |
|---|---|
| `POST /api/containers` | entry |
| the Docker daemon | volume, containers, labels |
| `hermes gateway status` | readiness |

## See

- Objects: [container](../objects/docker/container.md), [profile](../objects/agents/profile.md)
- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/docker/HermesDeployer.java`
- `docs/architecture.md`, "Container deploy defaults"
