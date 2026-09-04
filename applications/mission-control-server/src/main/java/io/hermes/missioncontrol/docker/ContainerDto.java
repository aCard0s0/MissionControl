package io.hermes.missioncontrol.docker;

import java.util.List;

/** Container inventory entry — stats are fetched separately per container. */
public record ContainerDto(
    String id,
    String shortId,
    String name,
    String hostId,
    String status,        // running | stopped | unhealthy | unknown
    String image,
    String version,
    /** Registry manifest digest of the image this container runs, or null when it was never
     *  pulled from a registry. The only evidence that a container on a floating tag such as
     *  `latest` is behind — the tag string alone always looks current. */
    String imageDigest,
    /** The Hermes release the image carries, as hermes reports it (`2026.8.19`) — the version
     *  to show when `version` is a floating tag. Null when the container is not running or
     *  the image does not say. */
    String release,
    Long startedAt,       // epoch ms, null when not running
    Double sizeRootFsGb,  // null when the daemon did not report size
    List<String> profiles) {
}
