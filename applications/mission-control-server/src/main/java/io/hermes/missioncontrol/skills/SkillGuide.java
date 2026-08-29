package io.hermes.missioncontrol.skills;

import java.util.List;

/**
 * A guide: prose that teaches how to use several skills together, with the MCP servers they
 * need, and the ids of both.
 *
 * <p>Deploying one is three things at once — every skill onto the agent, every MCP server
 * linked to it, and the prose itself written into the agent's skills directory as an
 * umbrella {@code SKILL.md}. That last part is what makes a guide more than a note: hermes'
 * own curator authors umbrella skills exactly like this, so the agent reads the guide too
 * and knows when to reach for the set rather than for one of the parts.
 *
 * <p>{@link #name} is therefore a directory name as well as a label, and carries the same
 * charset rule as a skill's.
 *
 * <p>The two id lists are not foreign keys — see the note on the table in
 * {@code schema.sql}. A guide resolves them at deploy time and reports what is gone.
 */
public record SkillGuide(
    String id,
    String name,
    String description,
    String body,
    String category,
    List<String> skillIds,
    List<String> mcpServerIds,
    long createdAt,
    long updatedAt) {
}
