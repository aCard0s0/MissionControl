package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * What a container would lose if it were stopped right now.
 *
 * <p>Read on demand — before a stop, a restart or a replace — rather than carried by the
 * container inventory, which polls the whole fleet and has no business execing into every
 * profile of every container to find out.
 *
 * @param activeAgents turns in flight across every profile in the container
 * @param busyProfiles the profiles those turns belong to, so the confirmation can name them
 * @param pausedProfiles profiles already held by {@code hermes pause}; a stop there is safe
 * @param unreadable profiles whose gateway state could not be read at all — a stop might be
 *     safe, and the operator is told rather than reassured
 * @param creatingProfiles profiles whose create has run {@code hermes profile create} but not
 *     yet finished configuring. They are on disk, so the inventory would list them, but a stop
 *     now fails a step and the create rolls the profile back — the operator is told it will go
 */
public record ContainerActivityDto(
    int activeAgents,
    List<String> busyProfiles,
    List<String> pausedProfiles,
    List<String> unreadable,
    List<String> creatingProfiles) {
}
