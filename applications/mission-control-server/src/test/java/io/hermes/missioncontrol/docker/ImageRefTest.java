package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageRefTest {

  /** The tags nousresearch/hermes-agent actually publishes, deliberately shuffled. */
  private static final List<String> PUBLISHED = List.of(
      "v2026.4.16", "v2026.7.7", "latest", "v2026.5.29", "v2026.8.3", "v2026.4.3",
      "v2026.7.7.2", "main", "v2026.6.5", "v2026.4.30", "v2026.5.29.2", "v2026.7.30",
      "v2026.4.13", "v2026.7.1", "v2026.5.16", "v2026.6.19", "v2026.4.23", "v2026.7.20",
      "v2026.5.28", "v2026.5.7", "v2026.4.8");

  @Test
  void ranksCalendarTagsNewestFirstIncludingFourComponentReleases() {
    List<String> sorted = new ArrayList<>(PUBLISHED);
    sorted.sort(ImageRef::compareTags);

    assertEquals(List.of(
        "latest",
        "v2026.8.3", "v2026.7.30", "v2026.7.20", "v2026.7.7.2", "v2026.7.7", "v2026.7.1",
        "v2026.6.19", "v2026.6.5", "v2026.5.29.2", "v2026.5.29", "v2026.5.28", "v2026.5.16",
        "v2026.5.7", "v2026.4.30", "v2026.4.23", "v2026.4.16", "v2026.4.13", "v2026.4.8",
        "v2026.4.3",
        "main"), sorted);
  }

  @Test
  void aFourthComponentOutranksTheReleaseItPatches() {
    // the bug this guards: a 3-component cap made v2026.7.7.2 unparseable, so it
    // sorted behind every parseable tag instead of directly after v2026.7.7
    assertTrue(ImageRef.compareTags("v2026.7.7.2", "v2026.7.7") < 0);
    assertTrue(ImageRef.compareTags("v2026.7.7", "v2026.7.7.2") > 0);
    assertTrue(ImageRef.compareTags("v2026.7.7.2", "v2026.7.20") > 0);
  }

  @Test
  void parsesVersionsOfAnyLengthAndRejectsNonVersions() {
    assertEquals(4, ImageRef.parseVersion("v2026.7.7.2").length);
    assertEquals(2026, ImageRef.parseVersion("v2026.7.7.2")[0]);
    assertEquals(2, ImageRef.parseVersion("v2026.7.7.2")[3]);
    assertEquals(1, ImageRef.parseVersion("7").length);
    assertNull(ImageRef.parseVersion("main"));
    assertNull(ImageRef.parseVersion("latest"));
    assertNull(ImageRef.parseVersion("sha-9f2c1"));
    assertNull(ImageRef.parseVersion(null));
  }

  @Test
  void missingComponentsCountAsZero() {
    assertEquals(0, ImageRef.compareVersions(new int[]{1, 2}, new int[]{1, 2, 0}));
    assertTrue(ImageRef.compareVersions(new int[]{1, 2, 1}, new int[]{1, 2}) > 0);
  }

  @Test
  void numericComponentsBeatLexicographicOrder() {
    assertTrue(ImageRef.compareTags("v0.10.0", "v0.9.0") < 0);   // newest first
  }

  @Test
  void floatingTagsAreRecognised() {
    assertTrue(ImageRef.isFloating("latest"));
    assertTrue(ImageRef.isFloating("main"));
    assertTrue(ImageRef.isFloating("LATEST"));
    assertFalse(ImageRef.isFloating("v2026.8.3"));
  }

  @Test
  void dockerHubPathResolvesOfficialAndNamespacedRepositories() {
    assertEquals("nousresearch/hermes-agent", ImageRef.dockerHubPath("nousresearch/hermes-agent"));
    assertEquals("nousresearch/hermes-agent",
        ImageRef.dockerHubPath("docker.io/nousresearch/hermes-agent"));
    assertEquals("nousresearch/hermes-agent",
        ImageRef.dockerHubPath("nousresearch/hermes-agent:v2026.8.3"));
    assertEquals("library/hermes-agent", ImageRef.dockerHubPath("hermes-agent"));
  }

  @Test
  void dockerHubPathRejectsForeignRegistries() {
    assertNull(ImageRef.dockerHubPath("ghcr.io/nousresearch/hermes-agent"));
    assertNull(ImageRef.dockerHubPath("registry.local:5000/hermes"));
    assertNull(ImageRef.dockerHubPath("localhost:5000/hermes"));
    assertNull(ImageRef.dockerHubPath("registry.local/team/nested/hermes"));
    assertNull(ImageRef.dockerHubPath(""));
    assertNull(ImageRef.dockerHubPath(null));
  }

  @Test
  void splitImageDoesNotMistakeARegistryPortForATag() {
    assertEquals("registry.local:5000/nous/hermes-agent",
        ImageRef.splitImage("registry.local:5000/nous/hermes-agent")[0]);
    assertEquals("latest", ImageRef.splitImage("registry.local:5000/nous/hermes-agent")[1]);
    assertEquals("v2026.8.3", ImageRef.splitImage("nousresearch/hermes-agent:v2026.8.3")[1]);
  }

  @Test
  void normalizeRepositoryStripsHubPrefixesAndTags() {
    assertEquals("nousresearch/hermes-agent",
        ImageRef.normalizeRepository("index.docker.io/NousResearch/Hermes-Agent:v2026.8.3"));
    assertEquals("", ImageRef.normalizeRepository(null));
  }

  // --- digest references --------------------------------------------------------------
  //
  // A digest contains its own ':' ("@sha256:<hex>"), so scanning for the last ':' lands
  // inside it. DockerGateway compares normalizeRepository() against the configured Hermes
  // repository to decide what belongs in the fleet view, so getting this wrong makes a
  // digest-pinned agent disappear from /api/containers entirely.

  private static final String DIGEST =
      "@sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Test
  void aDigestPinnedReferenceNormalizesToTheSameRepositoryAsATaggedOne() {
    assertEquals(
        ImageRef.normalizeRepository("nousresearch/hermes-agent:v2026.8.3"),
        ImageRef.normalizeRepository("nousresearch/hermes-agent" + DIGEST));
  }

  @Test
  void aDigestPinnedReferenceKeepsItsRepositoryAndDoesNotReportTheDigestAsATag() {
    String[] split = ImageRef.splitImage("nousresearch/hermes-agent" + DIGEST);

    assertEquals("nousresearch/hermes-agent", split[0]);
    // there is no tag in a digest reference; the hex is not one
    assertEquals("latest", split[1]);
  }

  @Test
  void aDigestOnAReferenceThatAlsoCarriesARegistryPortIsHandled() {
    String[] split = ImageRef.splitImage("registry.local:5000/nous/hermes-agent" + DIGEST);

    assertEquals("registry.local:5000/nous/hermes-agent", split[0]);
    assertEquals("latest", split[1]);
    assertEquals("registry.local:5000/nous/hermes-agent",
        ImageRef.normalizeRepository("registry.local:5000/nous/hermes-agent" + DIGEST));
  }

  @Test
  void dockerHubPathResolvesADigestReference() {
    assertEquals("nousresearch/hermes-agent",
        ImageRef.dockerHubPath("nousresearch/hermes-agent" + DIGEST));
    assertEquals("library/postgres", ImageRef.dockerHubPath("postgres" + DIGEST));
  }
}
