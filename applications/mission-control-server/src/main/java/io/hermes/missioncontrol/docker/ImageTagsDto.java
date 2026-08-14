package io.hermes.missioncontrol.docker;

import java.util.List;

/**
 * Hermes image tags as seen from one Docker host: the local image store merged
 * with the registry's published tags.
 *
 * <p>{@code tags} keeps its original meaning — every known tag, newest first —
 * so existing callers that only pick a version from a list keep working.
 *
 * @param registryStatus ok | cached | unavailable | unsupported | disabled
 */
public record ImageTagsDto(
    String repository,
    List<String> tags,
    List<ImageTagDto> entries,
    String newest,             // top-ranked non-floating tag, null when there is none
    String registryStatus,
    String registryDetail,
    Long registryCheckedAt) {
}
