package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.secrets.StoredSecret;
import java.util.List;

/**
 * A reusable agent blueprint, owned by the dashboard (stored in mission-control's
 * SQLite DB). Distinct from {@link AgentProfileDto}, which is a live agent instance
 * inside a container. A template can be applied when deploying a new agent.
 *
 * <p>Three lists name what a deploy installs, and each resolves differently:
 * {@link #skills} are Skills Hub ids hermes installs by name; {@link #librarySkillIds} and
 * {@link #guideIds} name rows in the dashboard's own skill and guide libraries, looked up at
 * deploy time — a deploy fails on one that is gone rather than silently leaving it out.
 */
public record ProfileTemplate(
    String id,
    String name,
    /** Key of a built-in glyph the UI draws beside the name; blank for the default. */
    String icon,
    String description,
    String category,
    String provider,
    String model,
    String baseUrl,
    String cwd,
    String soul,
    String memory,
    List<String> skills,
    List<String> librarySkillIds,
    List<String> guideIds,
    List<McpServerSpec> mcpServers,
    List<StoredSecret> secrets,
    long createdAt,
    long updatedAt) {

  /** The shape a capture off a running agent produces: it can read the agent's skills back,
   *  but nothing on an agent says which library row or guide put them there. */
  public ProfileTemplate(
      String id,
      String name,
      String icon,
      String description,
      String category,
      String provider,
      String model,
      String baseUrl,
      String cwd,
      String soul,
      String memory,
      List<String> skills,
      List<McpServerSpec> mcpServers,
      List<StoredSecret> secrets,
      long createdAt,
      long updatedAt) {
    this(id, name, icon, description, category, provider, model, baseUrl, cwd, soul, memory,
        skills, List.of(), List.of(), mcpServers, secrets, createdAt, updatedAt);
  }
}
