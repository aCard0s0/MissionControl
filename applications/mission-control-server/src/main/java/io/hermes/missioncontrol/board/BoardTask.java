package io.hermes.missioncontrol.board;

import java.util.List;

/** Dashboard-owned kanban state — the one concept with no Hermes home. */
public record BoardTask(
    String id,
    String containerId,
    String agentId,
    String title,
    String column,        // queued | running | review | done
    String priority,      // low | med | high
    List<String> tags,
    long createdAt) {
}
