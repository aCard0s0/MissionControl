package io.hermes.missioncontrol.skills;

import java.util.List;

/**
 * A named set of library skills — how the library is filed, and optionally what a
 * {@link SkillGuide} is about.
 *
 * <p>Organization only. Nothing here reaches an agent: a group has no deploy, and deleting
 * one leaves every skill it named in the library. That is the whole difference between this
 * and a guide, which names a set for the purpose of pushing it somewhere.
 *
 * <p>Not the same axis as a skill's {@code category}. A category is one word on one skill,
 * so a skill has exactly one and nothing owns the set. A group is a record: it can be
 * renamed, described, pointed at a guide, and can hold skills that disagree about their
 * category.
 *
 * <p>{@link #guideId} is the optional half — null when a group is filing and nothing more.
 * When it is set, it is an id and not a foreign key, for the reason the note on
 * {@code skill_groups} in {@code schema.sql} gives: the guide can be deleted afterwards, and
 * the group says so on read rather than vanishing with it.
 *
 * <p>{@link #name} is a label, unlike a skill's or a guide's. Nothing writes a group to
 * disk, so it carries no directory charset rule.
 */
public record SkillGroup(
    String id,
    String name,
    String description,
    List<String> skillIds,
    String guideId,
    long createdAt,
    long updatedAt) {
}
