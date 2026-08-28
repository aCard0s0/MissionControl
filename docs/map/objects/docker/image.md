---
type: object
cluster: docker
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
entity: applications/mission-control-server/src/main/java/io/hermes/missioncontrol/docker/ImageRef.java
---

# Image

A Hermes image and its tags — what a [container](container.md) runs and what it could be moved
onto. Code names `ImageRef` (parsing and ordering), `ImageStore` (pull/list against a daemon),
`RegistryTagService` (published tags from Docker Hub), `ImageCatalogService` (the merged view).
Served at **`/api/images`**.

## Why this shape

Two sources answer "what tags exist", and they fail differently. The **daemon** knows what is
pulled; **Docker Hub** knows what is published but needs the network. `MC_REGISTRY_TAGS=false`
turns the second off for air-gapped installs, after which tag listing shows only pulled images
(`docker/RegistryTagService.java:98`, property `mc.registry-tags`).

Tag ordering is a real function, not a string sort: `compareTags` puts `latest` first, then
descending version, then unparseable tags last (`docker/ImageRef.java:89`).

`isFloating` exists because a floating tag is the case where the tag string tells you nothing
about whether you are current — which is why [Container](container.md) carries `imageDigest`.

## Shape

- `FLOATING = {latest, main, edge, nightly, dev}` — `docker/ImageRef.java:16`
- `normalizeRepository` strips a digest and a trailing tag, being careful that a `:` inside a
  registry host with a port is not a tag — `docker/ImageRef.java:40`
- `dockerHubPath` returns null for anything with more than two path segments, i.e. not a Hub
  image — `docker/ImageRef.java:60`
- Wire: `ImageTagDto`, `ImageTagsDto`
- Default repository: `MC_HERMES_IMAGE`, `nousresearch/hermes-agent`

## Connected to

- **owns:** nothing persisted
- **owned-by:** the registry, and each [host's](docker-host.md) daemon independently
- **joins:** [Container](container.md) by `image` + `imageDigest`;
  [upgrade-image](../../processes/upgrade-image.md) consumes a target tag
- **looks-like-but-is-not:** the image of an [MCP server](../mcp/mcp-server-entry.md), which is
  allowlisted config on a catalog row and goes through Compose, not through this catalog.

## If you change this

- **Hits:** the version picker and upgrade flow; `core/store/image-catalog-store.ts` — note it
  polls on a **300 s** period, by far the slowest (`core/store/live-sync.ts:26`), because each
  lookup probes the daemon and published tags change on the order of days.
- **Does not hit:** a running container. Docker cannot swap the image of a running container —
  changing tags is a *replacement*, which is [upgrade-image](../../processes/upgrade-image.md),
  not an image concern.

## Surfaces

| Surface | Role |
|---|---|
| `/api/images` | reads |
| Docker Hub | read, when `mc.registry-tags` is on |
| each host's daemon | read; written by a pull |

## See

- Source: `applications/mission-control-server/src/main/java/io/hermes/missioncontrol/docker/ImageRef.java`
