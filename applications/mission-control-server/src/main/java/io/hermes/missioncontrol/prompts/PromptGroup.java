package io.hermes.missioncontrol.prompts;

import java.util.List;

/**
 * A named set of library prompts — how the prompt library is filed.
 *
 * <p>Organization only, and nothing more: a group has no behaviour, reaches nothing, and
 * deleting one leaves every prompt it named in the library.
 *
 * <p>Not the same axis as a prompt's {@code category} or its {@code tags}. A category is one
 * word on one prompt and a tag is a loose label; neither is a record, so neither can be
 * renamed, described, or reordered as a unit. A group is a row, and it can hold prompts that
 * disagree about their category.
 *
 * <p>Deliberately a near-twin of {@code skills.SkillGroup} rather than a shared abstraction.
 * The two hold ids from different tables, and this package is already the duplicated shape
 * {@code SkillRepository} copied on purpose — one polymorphic {@code groups} table with a
 * {@code kind} column would trade two small clear records for one that is neither.
 */
public record PromptGroup(
    String id,
    String name,
    String description,
    List<String> promptIds,
    long createdAt,
    long updatedAt) {
}
