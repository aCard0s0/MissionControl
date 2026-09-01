package io.hermes.missioncontrol.agents.api;

import java.util.List;
import java.util.Map;

/**
 * A skill's whole file set read off an agent, for import into the library.
 *
 * <p>Distinct from {@link SkillContentDto}, which carries SKILL.md plus a list of the
 * other file <em>names</em>. This one carries their contents, keyed by the path relative
 * to the skill directory.
 *
 * @param skipped files left out because they hold a NUL byte — the exec pipe is UTF-8, so
 *     a binary asset cannot round-trip through it, and the importer says so rather than
 *     storing the corruption
 */
public record SkillFilesDto(Map<String, String> files, List<String> skipped) {
}
