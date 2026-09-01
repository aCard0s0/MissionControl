package io.hermes.missioncontrol.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The umbrella SKILL.md a guide deploys.
 *
 * <p>The frontmatter is the part worth testing: hermes parses it, and this is the only place
 * in the application that generates YAML rather than editing YAML someone else wrote. A
 * description carrying a colon, a quote or a newline is the ordinary case — operators write
 * sentences — and each of them breaks a document that was concatenated by hand.
 */
class GuideDocumentTest {

  private static SkillGuide guide(String description, String body) {
    return new SkillGuide("g-1", "pdf-workflow", description, body, "docs",
        List.of("s-1"), List.of("m-1"), 1_000L, 2_000L);
  }

  /** The frontmatter block, parsed back the way HermesSkills.parseSkillMeta reads it. */
  private static Map<?, ?> frontmatter(String document) {
    assertTrue(document.startsWith("---"), "no frontmatter fence: " + document);
    int end = document.indexOf("\n---", 3);
    assertTrue(end > 0, "unterminated frontmatter: " + document);
    return new Yaml().loadAs(document.substring(3, end), Map.class);
  }

  @Test
  void theFrontmatterSurvivesADescriptionThatWouldBreakHandWrittenYaml() {
    for (String description : List.of(
        "reads PDFs: quickly",                 // a colon — the classic
        "say \"hello\" first",                 // double quotes
        "it's a 'quoted' word",                // single quotes
        "line one\nline two",                  // a newline would forge a second key
        "  padded  ",
        "- looks like a list item",
        "#not a comment",
        "{braces} and [brackets]",
        "trailing backslash \\",
        "unicode — em dash, emoji 🎯")) {
      String document = GuideDocument.render(guide(description, "body"), List.of(), List.of());

      Map<?, ?> meta = frontmatter(document);
      assertEquals("pdf-workflow", meta.get("name"), "description=" + description);
      assertEquals("guide", meta.get("source"), "description=" + description);
      // exactly what went in comes back out: snakeyaml picks the quoting, and nothing here
      // rewrites an operator's sentence to make the quoting easier
      assertEquals(description, meta.get("description"), "description=" + description);
      assertEquals(3, meta.size(), "the description leaked a key: " + meta);
    }
  }

  @Test
  void aHorizontalRuleInTheProseDoesNotForgeAFrontmatterFence() {
    // `---` is ordinary markdown and operators write it. It parses correctly because
    // parseSkillMeta takes the FIRST `\n---`, which is this document's closing fence — so
    // do not "fix" this by stripping it from the body: that would corrupt the prose to
    // solve a problem the real parser does not have.
    String document = GuideDocument.render(
        guide("d", "before\n\n---\n\nafter\n\nname: forged"), List.of(), List.of());

    Map<?, ?> meta = frontmatter(document);
    assertEquals("pdf-workflow", meta.get("name"));
    assertEquals("d", meta.get("description"));
    assertEquals(3, meta.size(), "the body leaked keys into the frontmatter: " + meta);
    assertTrue(document.contains("after"), "the body was mangled: " + document);
  }

  @Test
  void aGuideWithNoDescriptionStillParses() {
    Map<?, ?> meta = frontmatter(GuideDocument.render(guide(null, "body"), List.of(), List.of()));

    assertEquals("a Mission Control guide", meta.get("description"));
  }

  @Test
  void theDocumentNamesTheSkillsAndServersThatActuallyLanded() {
    String document = GuideDocument.render(
        guide("composes two skills", "Use these together when triaging."),
        List.of("pdf-tools", "log-reader"), List.of("postgres"));

    assertTrue(document.contains("Use these together when triaging."));
    assertTrue(document.contains("`pdf-tools`"));
    assertTrue(document.contains("`log-reader`"));
    assertTrue(document.contains("`postgres`"));
  }

  @Test
  void aPartThatDidNotLandIsNotAdvertisedToTheAgent() {
    // telling an agent to reach for a skill that failed to deploy is worse than silence
    String document = GuideDocument.render(
        guide("d", "body"), List.of("pdf-tools"), List.of());

    assertFalse(document.contains("MCP servers this needs"));
    assertTrue(document.contains("Skills this composes"));
  }

  @Test
  void anEmptyGuideStillProducesALoadableSkill() {
    String document = GuideDocument.render(guide("d", ""), List.of(), List.of());

    assertEquals("pdf-workflow", frontmatter(document).get("name"));
    assertTrue(document.contains("# pdf-workflow"));
  }

  @Test
  void theDocumentSaysItIsGeneratedSoAnEditIsNotSilentlyLost() {
    String document = GuideDocument.render(guide("d", "body"), List.of(), List.of());

    assertTrue(document.contains("overwritten by the next deploy"));
  }
}
