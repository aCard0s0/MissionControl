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
    Long startedAt,       // epoch ms, null when not running
    Double sizeRootFsGb,  // null when the daemon did not report size
    List<String> profiles) {
}
