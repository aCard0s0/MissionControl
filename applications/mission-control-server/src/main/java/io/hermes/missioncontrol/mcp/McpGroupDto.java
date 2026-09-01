package io.hermes.missioncontrol.mcp;

import java.util.List;

/**
 * A group as the page reads it: what it holds, and which agents it currently reaches.
 *
 * <p>{@link #agents} is derived on every read from {@code mcp_agent_links} and stored nowhere
 * — see {@link McpGroup} for why. It answers "where is this group" without a second source of
 * truth that could disagree with the links.
 */
public record McpGroupDto(
    String id,
    String name,
    String description,
    List<String> serverIds,
    List<McpGroupAgentDto> agents,
    long createdAt,
    long updatedAt) {

  /**
   * One agent this group reaches, and how completely.
   *
   * <p>{@code linked} counts how many of the group's servers that agent is connected to. It is
   * the whole point of deriving rather than storing: a group of four showing {@code 2} is an
   * agent someone half-disconnected, and no stored association could have told you that.
   */
  public record McpGroupAgentDto(
      String hostId,
      String containerId,
      String profile,
      int linked) {
  }
}
