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
    Long startedAt,       // epoch ms, null when not running
    Double sizeRootFsGb,  // null when the daemon did not report size
    List<String> profiles) {
}
