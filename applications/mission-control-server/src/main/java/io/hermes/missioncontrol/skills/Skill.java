package io.hermes.missioncontrol.skills;

import java.util.List;

/**
 * One entry in the skill library — dashboard-owned, and deployable onto any agent.
 *
 * <p>Not to be confused with {@code agents.api.SkillDto}, which is a skill already
 * installed <em>on</em> a profile, read through from that container's disk. This is a row
 * the dashboard holds so a skill can be kept, edited and pushed to an agent that does not
 * have it yet.
 *
 * <p>{@link #kind} decides what the row holds and how it deploys, and the two halves are
 * mutually exclusive:
 *
 * <ul>
 *   <li>{@code hub} — a pointer. {@link #name} is the id {@code hermes skills install}
 *       resolves against the Skills Hub, and {@link #files} is empty because the Hub owns
 *       the content.
 *   <li>{@code local} — the row owns its files, because there is nothing to resolve:
 *       hermes has no {@code skills create}, so a skill authored here or written by an
 *       agent's own curator has no hub id and can only be deployed by writing it out.
 * </ul>
 */
public record Skill(
    String id,
    String kind,
    String name,
    String description,
    String category,
    String repoUrl,
    String version,
    List<SkillFile> files,
    long createdAt,
    long updatedAt) {

  public static final String HUB = "hub";
  public static final String LOCAL = "local";

  /** The one file hermes needs to see a skill at all. */
  public static final String SKILL_MD = "SKILL.md";
}
