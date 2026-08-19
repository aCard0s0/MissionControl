package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Resolving a profile's skills.
 *
 * <p>A skill's identity is not its directory: SKILL.md frontmatter may declare a different
 * display name, and the tree is flat ({@code skills/<x>/SKILL.md}) or category-nested
 * ({@code skills/<category>/<x>/SKILL.md}). Every operation the UI offers — enable, read,
 * edit, uninstall — has to find the same directory from the name the user clicked, so a
 * renamed or nested skill that cannot be resolved is silently unmanageable.
 */
class HermesSkillsTest {

  private static final String SKILLS = "/opt/data/profiles/ops/skills";

  private HermesSkills skills(FakeContainer container) {
    return new HermesSkills(container.files(), new HermesConfigEditor());
  }

  // ── listing ────────────────────────────────────────────────────────────────

  @Test
  void flatAndCategoryNestedSkillsAreBothListed() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.2", "Reads PDFs"))
        .file(SKILLS + "/office/docx/SKILL.md", frontmatter("docx", "0.9", "Reads Word files"))
        .onCommand("-name SKILL.md",
            SKILLS + "/pdf/SKILL.md\n" + SKILLS + "/office/docx/SKILL.md\n");

    List<SkillDto> listed = skills(container).list(HOST, CONTAINER, "ops", Map.of());

    assertEquals(List.of("pdf", "docx"), listed.stream().map(SkillDto::name).toList());
    assertEquals("1.2", listed.getFirst().version());
    assertEquals("Reads Word files", listed.get(1).description());
  }

  @Test
  void frontmatterNameWinsOverTheDirectoryName() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf-tools/SKILL.md", frontmatter("pdf", "1.0", "Reads PDFs"))
        .onCommand("-name SKILL.md", SKILLS + "/pdf-tools/SKILL.md\n");

    assertEquals("pdf", skills(container).list(HOST, CONTAINER, "ops", Map.of()).getFirst().name());
  }

  @Test
  void aSkillWithNoFrontmatterFallsBackToItsDirectoryName() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/scratch/SKILL.md", "no frontmatter here\n")
        .onCommand("-name SKILL.md", SKILLS + "/scratch/SKILL.md\n");

    SkillDto listed = skills(container).list(HOST, CONTAINER, "ops", Map.of()).getFirst();
    assertEquals("scratch", listed.name());
    assertEquals("", listed.version());
  }

  @Test
  void anEmptySkillFileIsSkippedRatherThanListedNameless() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/broken/SKILL.md", "")
        .onCommand("-name SKILL.md", SKILLS + "/broken/SKILL.md\n");

    assertEquals(List.of(), skills(container).list(HOST, CONTAINER, "ops", Map.of()));
  }

  @Test
  void theBundledManifestSeparatesShippedSkillsFromAgentAuthoredOnes() {
    // anything on disk but absent from the manifest was written locally — by the agent
    // itself or the curator, which authors umbrella skills
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/.bundled_manifest", "pdf:abc123\ndocx:def456\n")
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .file(SKILLS + "/homegrown/SKILL.md", frontmatter("homegrown", "0.1", ""))
        .onCommand("-name SKILL.md",
            SKILLS + "/pdf/SKILL.md\n" + SKILLS + "/homegrown/SKILL.md\n");

    List<SkillDto> listed = skills(container).list(HOST, CONTAINER, "ops", Map.of());

    assertEquals("bundled", listed.getFirst().source());
    assertEquals("user", listed.get(1).source());
  }

  @Test
  void anExplicitFrontmatterSourceOverridesTheManifest() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/.bundled_manifest", "pdf:abc123\n")
        .file(SKILLS + "/pdf/SKILL.md",
            "---\nname: pdf\nsource: vendor\n---\nbody\n")
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n");

    assertEquals("vendor",
        skills(container).list(HOST, CONTAINER, "ops", Map.of()).getFirst().source());
  }

  @Test
  void bothTheGlobalAndTheCliDisabledListsTurnASkillOff() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .file(SKILLS + "/docx/SKILL.md", frontmatter("docx", "1.0", ""))
        .file(SKILLS + "/xlsx/SKILL.md", frontmatter("xlsx", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n" + SKILLS + "/docx/SKILL.md\n"
            + SKILLS + "/xlsx/SKILL.md\n");
    Map<?, ?> config = yaml("""
        skills:
          disabled: [pdf]
          platform_disabled:
            cli: [docx]
            slack: [xlsx]
        """);

    Map<String, Boolean> enabled = skills(container).list(HOST, CONTAINER, "ops", config).stream()
        .collect(java.util.stream.Collectors.toMap(SkillDto::name, SkillDto::enabled));

    assertFalse(enabled.get("pdf"), "a globally disabled skill is off");
    assertFalse(enabled.get("docx"), "a cli-disabled skill is off");
    assertTrue(enabled.get("xlsx"), "another platform's disabled list does not apply to cli");
  }

  // ── resolution ─────────────────────────────────────────────────────────────

  @Test
  void readingContentResolvesASkillRenamedByItsFrontmatter() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf-tools/SKILL.md", frontmatter("pdf", "1.0", "Reads PDFs"))
        .onCommand("-name SKILL.md", SKILLS + "/pdf-tools/SKILL.md\n")
        .onCommand("-maxdepth 3 -type f", "SKILL.md\nreferences/spec.md\n");

    SkillContentDto content = skills(container).readContent(HOST, CONTAINER, "ops", "pdf");

    assertEquals(SKILLS + "/pdf-tools", content.path());
    assertTrue(content.body().contains("name: pdf"));
    assertEquals(List.of("SKILL.md", "references/spec.md"), content.files());
  }

  @Test
  void aDirectoryMatchIsPreferredOverScanningFrontmatter() {
    // the common case must not cost a read of every SKILL.md in the tree
    FakeContainer container = new FakeContainer()
        .dir(SKILLS + "/pdf")
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""));

    skills(container).readContent(HOST, CONTAINER, "ops", "pdf");

    assertTrue(container.executed().stream()
            .noneMatch(argv -> argv.stream().anyMatch(a -> a.contains("-name SKILL.md"))),
        "the frontmatter scan ran even though the directory name matched");
  }

  @Test
  void anUnknownSkillIsRejectedRatherThanActedOnBlindly() {
    FakeContainer container = new FakeContainer().onCommand("-name SKILL.md", "");
    HermesSkills skills = skills(container);

    assertThrows(IllegalArgumentException.class,
        () -> skills.readContent(HOST, CONTAINER, "ops", "absent"));
    assertThrows(IllegalArgumentException.class,
        () -> skills.uninstall(HOST, CONTAINER, "ops", "absent"));
    assertThrows(IllegalArgumentException.class,
        () -> skills.updateContent(HOST, CONTAINER, "ops", "absent", "body"));
  }

  @Test
  void uninstallRemovesTheResolvedDirectoryAsAPositionalArgument() {
    // `hermes skills uninstall` prompts and exits 0 on failure, so the directory is
    // removed directly — which makes the path the only thing standing between a
    // mistyped name and an rm -rf of something else
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf-tools/SKILL.md", frontmatter("pdf", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf-tools/SKILL.md\n");

    skills(container).uninstall(HOST, CONTAINER, "ops", "pdf");

    List<String> removal = container.executed().stream()
        .filter(argv -> argv.size() > 2 && argv.get(2).contains("rm -rf"))
        .findFirst().orElseThrow();
    assertEquals(List.of("sh", "-lc"), removal.subList(0, 2));
    assertTrue(removal.get(2).contains("\"$1\""));
    assertEquals(SKILLS + "/pdf-tools", removal.getLast());
  }

  // ── name validation ────────────────────────────────────────────────────────

  @Test
  void aSkillNameThatCouldEscapeTheSkillsDirectoryIsRejected() {
    // skill ids also arrive from user-authored templates, not just the UI
    HermesSkills skills = skills(new FakeContainer());
    for (String name : new String[] {"../../etc", "a/b", "", null, ".hidden"}) {
      assertThrows(IllegalArgumentException.class,
          () -> skills.readContent(HOST, CONTAINER, "ops", name), "name=" + name);
      assertThrows(IllegalArgumentException.class,
          () -> skills.install(HOST, CONTAINER, "ops", name), "name=" + name);
      assertThrows(IllegalArgumentException.class,
          () -> skills.uninstall(HOST, CONTAINER, "ops", name), "name=" + name);
    }
  }

  @Test
  void installPassesTheSkillIdToHermesRatherThanBuildingAPath() {
    FakeContainer container = new FakeContainer();

    skills(container).install(HOST, CONTAINER, "ops", "pdf");

    assertEquals(List.of("hermes", "-p", "ops", "skills", "install", "pdf", "--force"),
        container.executed().getFirst());
  }

  @Test
  void aMissingBodyIsRejectedBeforeTheSkillIsResolved() {
    FakeContainer container = new FakeContainer()
        .dir(SKILLS + "/pdf")
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""));

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).updateContent(HOST, CONTAINER, "ops", "pdf", null));
  }

  // ── enable / disable ───────────────────────────────────────────────────────

  @Test
  void disablingAddsToTheCliListAndEnablingRemovesItAgain() {
    FakeContainer container = new FakeContainer()
        .dir("/opt/data/profiles/ops")
        .file("/opt/data/profiles/ops/config.yaml", "model: nous/Hermes-4-405B\n");
    HermesSkills skills = skills(container);

    skills.setEnabled(HOST, CONTAINER, "ops", "pdf", false);
    String disabled = writtenConfig(container);
    assertEquals(List.of("pdf"), cliDisabled(disabled));
    // an unmodelled sibling key survives the rewrite
    assertEquals("nous/Hermes-4-405B", yaml(disabled).get("model"));

    FakeContainer reenabling = new FakeContainer()
        .dir("/opt/data/profiles/ops")
        .file("/opt/data/profiles/ops/config.yaml", disabled);
    skills(reenabling).setEnabled(HOST, CONTAINER, "ops", "pdf", true);
    assertEquals(List.of(), cliDisabled(writtenConfig(reenabling)));
  }

  @Test
  void disablingTwiceDoesNotListTheSkillTwice() {
    FakeContainer container = new FakeContainer()
        .dir("/opt/data/profiles/ops")
        .file("/opt/data/profiles/ops/config.yaml",
            "skills:\n  platform_disabled:\n    cli: [pdf]\n");

    skills(container).setEnabled(HOST, CONTAINER, "ops", "pdf", false);

    assertEquals(List.of("pdf"), cliDisabled(writtenConfig(container)));
  }

  @Test
  void anEditAgainstAProfileThatDoesNotExistIsRefused() {
    // writeFile mkdir -p's its parent, so without the guard a mistyped name mints a profile
    FakeContainer container = new FakeContainer();

    assertThrows(java.util.NoSuchElementException.class,
        () -> skills(container).setEnabled(HOST, CONTAINER, "tpyo", "pdf", false));
  }

  @Test
  void aBlankSkillNameIsRejectedBeforeTheConfigIsRead() {
    FakeContainer container = new FakeContainer()
        .dir("/opt/data/profiles/ops")
        .file("/opt/data/profiles/ops/config.yaml", "model: x\n");

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).setEnabled(HOST, CONTAINER, "ops", "  ", false));
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  private static String frontmatter(String name, String version, String description) {
    return "---\nname: " + name + "\nversion: " + version
        + "\ndescription: " + description + "\n---\nbody\n";
  }

  private static Map<?, ?> yaml(String text) {
    return (Map<?, ?>) new Yaml().load(text);
  }

  /** The config content the collaborator wrote back, taken from the write argv. */
  private static String writtenConfig(FakeContainer container) {
    return container.executed().stream()
        .filter(argv -> argv.size() > 2 && argv.get(2).contains("printf"))
        .reduce((first, second) -> second)
        .orElseThrow()
        .getLast();
  }

  private static List<?> cliDisabled(String config) {
    Map<?, ?> skills = (Map<?, ?>) yaml(config).get("skills");
    Map<?, ?> platform = (Map<?, ?>) skills.get("platform_disabled");
    return (List<?>) platform.get("cli");
  }

  // ── frontmatter and source resolution ────────────────────────────────────

  @Test
  void aSkillWithNoUsableFrontmatterFallsBackToItsDirectoryName() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/plain/SKILL.md", "# no frontmatter here\n")
        .file(SKILLS + "/unterminated/SKILL.md", "---\nname: never-closed\n")
        .file(SKILLS + "/empty-meta/SKILL.md", "---\n\n---\nbody\n")
        .onCommand("-name SKILL.md", SKILLS + "/plain/SKILL.md\n"
            + SKILLS + "/unterminated/SKILL.md\n" + SKILLS + "/empty-meta/SKILL.md\n");

    List<SkillDto> listed = skills(container).list(HOST, CONTAINER, "ops", Map.of());

    assertEquals(List.of("plain", "unterminated", "empty-meta"),
        listed.stream().map(SkillDto::name).toList());
  }

  @Test
  void frontmatterThatDeclaresItsSourceWinsOverTheBundledManifest() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/.bundled_manifest", "pdf:abc123\n")
        .file(SKILLS + "/pdf/SKILL.md",
            "---\nname: pdf\nversion: 1.0\ndescription: d\nsource: curator\n---\nbody\n")
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n");

    assertEquals("curator", skills(container).list(HOST, CONTAINER, "ops", Map.of())
        .getFirst().source());
  }

  @Test
  void aManifestLineWithNoHashStillNamesABundledSkill() {
    // the manifest is "name:hash" per line, but an older hermes wrote bare names
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/.bundled_manifest", "pdf\n\n   \ndocx:abc123\n")
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .file(SKILLS + "/docx/SKILL.md", frontmatter("docx", "1.0", ""))
        .file(SKILLS + "/local/SKILL.md", frontmatter("local", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n"
            + SKILLS + "/docx/SKILL.md\n" + SKILLS + "/local/SKILL.md\n");

    List<SkillDto> listed = skills(container).list(HOST, CONTAINER, "ops", Map.of());

    assertEquals("bundled", listed.get(0).source());
    assertEquals("bundled", listed.get(1).source());
    assertEquals("user", listed.get(2).source(), "anything not in the manifest was authored locally");
  }

  @Test
  void aSkillWithAnEmptySkillMdIsSkippedEntirely() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/blank/SKILL.md", "   ")
        .file(SKILLS + "/real/SKILL.md", frontmatter("real", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/blank/SKILL.md\n" + SKILLS + "/real/SKILL.md\n");

    assertEquals(List.of("real"),
        skills(container).list(HOST, CONTAINER, "ops", Map.of()).stream().map(SkillDto::name).toList());
  }

  // ── which skills count as disabled ───────────────────────────────────────

  @Test
  void aConfigWithNoSkillsSectionDisablesNothing() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n");

    // no config at all, a config whose skills key is a scalar, and one with an unrelated shape
    for (Map<?, ?> config : List.of(Map.of(), Map.of("skills", "all"), Map.of("skills", List.of("pdf")))) {
      assertTrue(skills(container).list(HOST, CONTAINER, "ops", config).getFirst().enabled());
    }
    assertTrue(skills(container).list(HOST, CONTAINER, "ops", null).getFirst().enabled());
  }

  @Test
  void bothTheGlobalDisabledListAndThePlatformOneCount() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .file(SKILLS + "/docx/SKILL.md", frontmatter("docx", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n" + SKILLS + "/docx/SKILL.md\n");
    Map<?, ?> config = yaml("""
        skills:
          disabled: [pdf, '  ']
          platform_disabled:
            cli: [docx]
            web: [something-else]
        """);

    List<SkillDto> listed = skills(container).list(HOST, CONTAINER, "ops", config);

    assertFalse(listed.get(0).enabled(), "disabled globally");
    assertFalse(listed.get(1).enabled(), "disabled for this platform");
  }

  @Test
  void aPlatformDisabledSectionOfTheWrongShapeIsIgnored() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n");

    // platform_disabled as a scalar, and cli as a scalar rather than a list
    assertTrue(skills(container)
        .list(HOST, CONTAINER, "ops", yaml("skills:\n  platform_disabled: none\n"))
        .getFirst().enabled());
    assertTrue(skills(container)
        .list(HOST, CONTAINER, "ops", yaml("skills:\n  platform_disabled:\n    cli: pdf\n"))
        .getFirst().enabled());
  }

  // ── name guards on the write paths ───────────────────────────────────────

  @Test
  void enablingASkillWithNoNameIsRefusedBeforeAnyConfigRead() {
    FakeContainer container = new FakeContainer();

    for (String name : List.of("", "   ")) {
      assertEquals("missing skill name", assertThrows(IllegalArgumentException.class,
          () -> skills(container).setEnabled(HOST, CONTAINER, "ops", name, false)).getMessage());
    }
    assertEquals("missing skill name", assertThrows(IllegalArgumentException.class,
        () -> skills(container).setEnabled(HOST, CONTAINER, "ops", null, false)).getMessage());
    assertTrue(container.executed().isEmpty());
  }

  @Test
  void writingSkillContentRefusesABadNameOrAMissingBody() {
    FakeContainer container = new FakeContainer();

    assertEquals("invalid skill name", assertThrows(IllegalArgumentException.class,
        () -> skills(container).updateContent(HOST, CONTAINER, "ops", "../escape", "body")).getMessage());
    assertEquals("missing skill body", assertThrows(IllegalArgumentException.class,
        () -> skills(container).updateContent(HOST, CONTAINER, "ops", "pdf", null)).getMessage());
    assertTrue(container.executed().isEmpty());
  }

  @Test
  void aSkillIsFoundByItsFrontmatterNameEvenWhenTheDirectoryIsCalledSomethingElse() {
    // the UI sends the display name; a renamed or nested skill has to resolve to its directory
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf-tools/SKILL.md", frontmatter("pdf", "1.0", ""))
        .file(SKILLS + "/office/xlsx/SKILL.md", frontmatter("xlsx", "1.0", ""))
        .onCommand("-name SKILL.md",
            SKILLS + "/pdf-tools/SKILL.md\n" + SKILLS + "/office/xlsx/SKILL.md\n");

    SkillContentDto content = skills(container).readContent(HOST, CONTAINER, "ops", "pdf");

    assertTrue(content.path().endsWith("/pdf-tools"), content.path());
  }

  @Test
  void aSkillDirectoryThatMatchesTheNameDirectlyIsUsedWithoutReadingAnyFrontmatter() {
    FakeContainer container = new FakeContainer()
        .dir(SKILLS + "/pdf")
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("something-else", "1.0", ""));

    SkillContentDto content = skills(container).readContent(HOST, CONTAINER, "ops", "pdf");

    assertTrue(content.path().endsWith("/pdf"));
    assertTrue(container.executed().stream().noneMatch(argv -> argv.contains("-name")),
        "the direct hit needs no tree walk");
  }

  @Test
  void aSkillThatCannotBeResolvedAtAllIsReportedAsUnknown() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n");

    assertThrows(RuntimeException.class,
        () -> skills(container).readContent(HOST, CONTAINER, "ops", "nowhere"));
  }

  @Test
  void aSkillMdThatIsBlankIsSkippedWhileResolvingByName() {
    // an empty file has no frontmatter to match, and must not stop the walk
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/empty/SKILL.md", "   ")
        .file(SKILLS + "/real/SKILL.md", frontmatter("wanted", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/empty/SKILL.md\n" + SKILLS + "/real/SKILL.md\n");

    assertTrue(skills(container).readContent(HOST, CONTAINER, "ops", "wanted").path().endsWith("/real"));
  }

  @Test
  void aSkillsTreeWithNoBundledManifestReportsEverythingAsUserAuthored() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/local/SKILL.md", frontmatter("local", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/local/SKILL.md\n");

    assertEquals("user", skills(container).list(HOST, CONTAINER, "ops", Map.of()).getFirst().source());
  }

  @Test
  void frontmatterWithABlankNameFallsBackToTheDirectory() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/pdf/SKILL.md", "---\nname: '  '\nversion: 1.0\n---\nbody\n")
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n");

    assertEquals("pdf", skills(container).list(HOST, CONTAINER, "ops", Map.of()).getFirst().name());
  }

  @Test
  void aManifestOfNothingButBlanksNamesNoBundledSkill() {
    FakeContainer container = new FakeContainer()
        .file(SKILLS + "/.bundled_manifest", "\n   \n:\n")
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""))
        .onCommand("-name SKILL.md", SKILLS + "/pdf/SKILL.md\n");

    assertEquals("user", skills(container).list(HOST, CONTAINER, "ops", Map.of()).getFirst().source());
  }

  @Test
  void aSkillsTreeWithNothingInItListsNothing() {
    FakeContainer container = new FakeContainer().onCommand("-name SKILL.md", "");

    assertTrue(skills(container).list(HOST, CONTAINER, "ops", Map.of()).isEmpty());
  }

  @Test
  void writingSkillContentReachesTheSkillsOwnDirectory() {
    FakeContainer container = new FakeContainer()
        .dir(SKILLS + "/pdf")
        .file(SKILLS + "/pdf/SKILL.md", frontmatter("pdf", "1.0", ""));

    skills(container).updateContent(HOST, CONTAINER, "ops", "pdf", "# rewritten\n");

    assertTrue(container.executed().stream().anyMatch(argv ->
        argv.stream().anyMatch(arg -> arg != null && arg.endsWith(SKILLS + "/pdf/SKILL.md"))),
        container.executed().toString());
  }
}
