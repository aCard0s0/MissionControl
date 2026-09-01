package io.hermes.missioncontrol.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Renders a guide as the {@code SKILL.md} an agent reads.
 *
 * <p>The operator writes prose. The frontmatter is generated, because it is the part that has
 * to be machine-valid — {@code HermesSkills.parseSkillMeta} parses it, and a guide whose
 * description happened to contain a colon would otherwise produce a skill hermes cannot read.
 * snakeyaml does the quoting rather than this class guessing at it.
 *
 * <p>The parts list is generated too, and is the point of the document. A guide that only
 * said "use these together" would leave the agent to work out what "these" are; naming the
 * skills and MCP servers it composes is what turns the prose into something actionable at
 * the moment the agent is choosing.
 */
final class GuideDocument {

  private GuideDocument() {}

  /**
   * @param skills the skills that resolved, in the guide's own order
   * @param mcpAliases the MCP server aliases that were linked, in the guide's own order
   */
  static String render(SkillGuide guide, List<String> skills, List<String> mcpAliases) {
    StringBuilder out = new StringBuilder();
    out.append("---\n").append(frontmatter(guide)).append("---\n\n");
    out.append("# ").append(guide.name()).append("\n\n");

    String body = guide.body() == null ? "" : guide.body().strip();
    if (!body.isEmpty()) {
      out.append(body).append("\n\n");
    }

    if (!skills.isEmpty()) {
      out.append("## Skills this composes\n\n");
      for (String skill : skills) {
        out.append("- `").append(skill).append("`\n");
      }
      out.append('\n');
    }
    if (!mcpAliases.isEmpty()) {
      out.append("## MCP servers this needs\n\n");
      for (String alias : mcpAliases) {
        out.append("- `").append(alias).append("`\n");
      }
      out.append('\n');
    }

    out.append("<!-- Written by Mission Control from the guide of the same name. "
        + "Edits here are overwritten by the next deploy of that guide. -->\n");
    return out.toString();
  }

  /**
   * The YAML block, dumped rather than concatenated.
   *
   * <p>Block style with no document start marker, so the result drops straight between the
   * two {@code ---} fences that hermes' own parser looks for.
   */
  private static String frontmatter(SkillGuide guide) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("name", guide.name());
    // the controller already trimmed this, and snakeyaml quotes whatever is left — a
    // colon, a quote or a newline in an operator's sentence all survive as one scalar
    String description = guide.description();
    meta.put("description",
        description == null || description.isBlank() ? "a Mission Control guide" : description);
    // declares its own origin, so `resolveSkillSource` does not have to infer it from the
    // bundled manifest and report a guide as an agent-authored "user" skill
    meta.put("source", "guide");

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setSplitLines(false);   // a wrapped description would read as a second key
    return new Yaml(options).dump(meta);
  }
}
