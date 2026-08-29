package io.hermes.missioncontrol.skills;

/**
 * One file inside a library skill.
 *
 * @param path relative to the skill's own directory — {@code SKILL.md},
 *     {@code scripts/run.sh}. Validated segment by segment by
 *     {@code ProfilePaths.skillFile} before it is ever concatenated into a container path.
 */
public record SkillFile(String path, String body) {
}
