---
type: object
cluster: docker
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/docker/ManagedContainer.java
---

# Container

A Docker container on a [host](docker-host.md). Two kinds matter: one **Mission Control
deployed** (labelled, owns a data volume) and everything else it can merely see. Code names
`ContainerDto` (wire), `ManagedContainer` (the label vocabulary), `ManagedContainerSpec`
(what an upgrade must copy). Served at **`/api/containers`**.

## Why this shape

`ManagedContainer` is centralised for a defect, and its class comment records it
(`docker/ManagedContainer.java:7`): four classes in the package had independently
re-implemented the same label vocabulary from string literals — the deployer wrote them, the
upgrader validated them, lifecycle read them to decide whether a removal drops a volume, and
inventory read them to decide what belongs in the fleet. The "managed, and its volume name
looks right" check existed **twice with no guarantee the two agreed** — a disagreement that
would either strand a data volume or refuse an upgrade.

The same comment explains why this is *not* a package move: the Hermes-specific knowledge in
`docker/` is not liftable into a "provisioning" package, so only the shared vocabulary was
centralised.

## Shape

The label vocabulary — `docker/ManagedContainer.java:29`:

| Constant | Value | Meaning |
|---|---|---|
| `MANAGED_LABEL` | `mc.managed` | set on everything we deploy |
| `DATA_VOLUME_LABEL` | `mc.dataVolume` | names the volume holding profiles, souls, skills, history |
| `PROFILES_LABEL` | `mc.profiles` | profiles seeded **at deploy time** — not what exists now |
| `BOOTSTRAP_LABEL` | `mc.bootstrap` | the short-lived one-shot that seeds a volume |
| `DATA_VOLUME_PREFIX` | `mc-hermes-` | distinguishes our volume from one another tool labelled by copying ours |
| `DATA_MOUNT` | `/opt/data` | mount point inside the container |

Wire record — `docker/ContainerDto.java:6`. Two fields are easy to misread:

- `imageDigest` — the registry manifest digest, null if never pulled from a registry. **The only
  evidence a container on a floating tag such as `latest` is behind**; the tag string always
  looks current (`docker/ContainerDto.java:15`).
- `profiles` — read live, not from the `mc.profiles` label.

## Connected to

- **owns:** its `mc-hermes-<name>` data volume; the [Profiles](../agents/profile.md) inside it
- **owned-by:** a [Docker host](docker-host.md)
- **joins:** `board_tasks.container_id`, `mcp_agent_links.container_id` — both are repointed
  when an upgrade mints a new container id (see [upgrade-image](../../processes/upgrade-image.md))
- **looks-like-but-is-not:** an Agent. The UI's "Agent" is a *profile*, and one container holds
  several — see [Profile](../agents/profile.md).

## If you change this

- **Hits:** `HermesDeployer` (writes labels), `ContainerUpgrader` (validates and copies them),
  `ContainerLifecycle` (volume removal decision), `ContainerInventory` (fleet filtering) — the
  four classes the vocabulary was extracted from. Renaming a label or the volume prefix means
  all four, which is the whole point of the class existing.
- **Does not hit:** the `MC_CONTAINER_FILTER` substring, which is a separate visibility rule
  (`?all=true` bypasses it). Also does not hit host-config customizations applied out of band
  (`docker update`, CPU/memory limits) — those are outside the copied set and are **not**
  preserved by an upgrade.

## Surfaces

| Surface | Role |
|---|---|
| `/api/containers` | reads inventory, stats, logs, lifecycle |
| the Docker daemon | source of truth; never cached or persisted |
| FE `core/store/container-store.ts`, `container-lifecycle.ts` | reads / writes |
| `/ws/terminal` | execs into it |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/docker/`
- Deploy defaults and the image-update contract: [../../../architecture.md](../../../architecture.md)
