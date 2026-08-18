package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImageCatalogServiceTagsTest {

  private final DockerGateway docker = mock(DockerGateway.class);
  private final RegistryTagService registry = mock(RegistryTagService.class);
  private final ImageCatalogService catalog = new ImageCatalogService(docker, registry);

  @Test
  void localAndRemoteTagsAreJoinedIntoOneNewestFirstList() {
    stubRepository("hermes/image");
    when(docker.localImageTags("unix:///sock")).thenReturn(Set.of("v2026.7.1"));
    stubRemote(remote("latest"), remote("v2026.8.3"));

    ImageTagsDto dto = catalog.tags("unix:///sock", true);

    assertEquals(List.of("latest", "v2026.8.3", "v2026.7.1"), dto.tags());
    assertEquals(dto.tags(), dto.entries().stream().map(ImageTagDto::tag).toList());

    // pulled is what tells an operator whether deploying a tag costs a download
    assertFalse(entry(dto, "v2026.8.3").pulled());
    assertTrue(entry(dto, "v2026.8.3").remote());
    assertTrue(entry(dto, "v2026.7.1").pulled());
    assertFalse(entry(dto, "v2026.7.1").remote());
    assertEquals("sha256:v2026.8.3", entry(dto, "v2026.8.3").digest());
  }

  @Test
  void remoteStatusDetailAndTimestampReachTheDto() {
    stubRepository("hermes/image");
    when(docker.localImageTags("unix:///sock")).thenReturn(Set.of("v2026.7.1"));
    when(registry.tags("hermes/image")).thenReturn(new RegistryTagService.RemoteTags(
        List.of(), RegistryTagService.UNAVAILABLE, "connect timed out", 1234L));

    ImageTagsDto dto = catalog.tags("unix:///sock", true);

    // the badge shows the status, but only the detail and the reading's age let the
    // UI explain why the remote half of the catalog is missing
    assertEquals(RegistryTagService.UNAVAILABLE, dto.registryStatus());
    assertEquals("connect timed out", dto.registryDetail());
    assertEquals(1234L, dto.registryCheckedAt());
    // a registry outage degrades the answer; the local image store is still served
    assertEquals(List.of("v2026.7.1"), dto.tags());
  }

  @Test
  void skippingTheRegistryReportsDisabledAndNeverCallsIt() {
    stubRepository("hermes/image");
    when(docker.localImageTags("unix:///sock")).thenReturn(Set.of("v2026.7.1"));

    ImageTagsDto dto = catalog.tags("unix:///sock", false);

    assertEquals(RegistryTagService.DISABLED, dto.registryStatus());
    // no lookup was attempted, so there is no reading to date
    assertNull(dto.registryCheckedAt());
    assertEquals(List.of("v2026.7.1"), dto.tags());
    assertFalse(entry(dto, "v2026.7.1").remote());
    verifyNoInteractions(registry);
  }

  @Test
  void theRepositoryReportedIsTheBareRepositoryTheFrontendComparesAgainst() {
    stubRepository("nousresearch/hermes-agent");
    when(docker.localImageTags("unix:///sock")).thenReturn(Set.of());
    // stubbed for the bare repository only: looking the registry up under anything else
    // (a tagged MC_HERMES_IMAGE, say) yields no RemoteTags at all
    when(registry.tags("nousresearch/hermes-agent")).thenReturn(new RegistryTagService.RemoteTags(
        List.of(remote("v2026.8.3")), RegistryTagService.OK, null, 99L));

    ImageTagsDto dto = catalog.tags("unix:///sock", true);

    // the frontend gates every update badge on this matching ContainerDto.image, which is
    // always a bare repository — echoing a tagged reference retires the badge fleet-wide
    assertEquals("nousresearch/hermes-agent", dto.repository());
  }

  @Test
  void theNewestPinnedReleaseSkipsFloatingTags() {
    stubRepository("hermes/image");
    when(docker.localImageTags("unix:///sock")).thenReturn(Set.of());
    stubRemote(remote("latest"), remote("v2026.8.3"), remote("v2026.7.1"));

    ImageTagsDto dto = catalog.tags("unix:///sock", true);

    // 'latest' heads the list but names a stream, not a release: calling it newest would
    // tell a pinned container it is out of date forever
    assertEquals("latest", dto.tags().get(0));
    assertEquals("v2026.8.3", dto.newest());
  }

  @Test
  void aCatalogWithNothingPinnedReportsNoNewestRelease() {
    stubRepository("hermes/image");
    when(docker.localImageTags("unix:///sock")).thenReturn(Set.of());
    stubRemote(remote("latest"), remote("main"), remote("edge"));

    ImageTagsDto dto = catalog.tags("unix:///sock", true);

    assertNull(dto.newest());
    // the catalog is not empty — every tag it holds is simply a moving one
    assertEquals(List.of("latest", "main", "edge"), dto.tags());
  }

  private static ImageTagDto entry(ImageTagsDto dto, String tag) {
    return dto.entries().stream().filter(e -> tag.equals(e.tag())).findFirst().orElseThrow();
  }

  private static ImageTagDto remote(String tag) {
    return new ImageTagDto(tag, false, true, 1L, 2L, "sha256:" + tag);
  }

  private void stubRepository(String repository) {
    when(docker.hermesImageRepository()).thenReturn(repository);
  }

  private void stubRemote(ImageTagDto... tags) {
    when(registry.tags("hermes/image")).thenReturn(new RegistryTagService.RemoteTags(
        List.of(tags), RegistryTagService.OK, null, 1770000000000L));
  }
}
