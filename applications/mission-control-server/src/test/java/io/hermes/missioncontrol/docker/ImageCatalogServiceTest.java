package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImageCatalogServiceTest {

  private static ImageTagDto remote(String tag) {
    return new ImageTagDto(tag, false, true, 1L, 2L, "sha256:" + tag);
  }

  private static ImageTagDto find(List<ImageTagDto> entries, String tag) {
    return entries.stream().filter(e -> tag.equals(e.tag())).findFirst().orElseThrow();
  }

  @Test
  void marksTagsAsPulledRemoteOrBoth() {
    List<ImageTagDto> merged = ImageCatalogService.merge(
        Set.of("v2026.7.20", "v2026.1.1-local"),
        List.of(remote("v2026.8.3"), remote("v2026.7.20")));

    assertTrue(find(merged, "v2026.7.20").pulled());
    assertTrue(find(merged, "v2026.7.20").remote());

    assertFalse(find(merged, "v2026.8.3").pulled());
    assertTrue(find(merged, "v2026.8.3").remote());

    // a locally built or hand-tagged image the registry has never heard of
    assertTrue(find(merged, "v2026.1.1-local").pulled());
    assertFalse(find(merged, "v2026.1.1-local").remote());
  }

  @Test
  void keepsRegistryMetadataOnMergedEntries() {
    List<ImageTagDto> merged =
        ImageCatalogService.merge(Set.of("v2026.8.3"), List.of(remote("v2026.8.3")));

    assertEquals("sha256:v2026.8.3", find(merged, "v2026.8.3").digest());
    assertEquals(2L, find(merged, "v2026.8.3").sizeBytes());
  }

  @Test
  void ordersNewestFirstRegardlessOfInputOrder() {
    List<ImageTagDto> merged = ImageCatalogService.merge(
        Set.of("v2026.4.3"),
        List.of(remote("v2026.7.7"), remote("latest"), remote("v2026.8.3"), remote("v2026.7.7.2")));

    assertEquals(
        List.of("latest", "v2026.8.3", "v2026.7.7.2", "v2026.7.7", "v2026.4.3"),
        merged.stream().map(ImageTagDto::tag).toList());
  }

  @Test
  void doesNotDuplicateATagKnownBothLocallyAndRemotely() {
    List<ImageTagDto> merged =
        ImageCatalogService.merge(Set.of("v2026.8.3"), List.of(remote("v2026.8.3")));
    assertEquals(1, merged.size());
  }

  @Test
  void newestSkipsFloatingTags() {
    List<ImageTagDto> merged = ImageCatalogService.merge(
        Set.of(), List.of(remote("latest"), remote("main"), remote("v2026.8.3")));

    // 'latest' sorts first but names a stream — reporting it as newest would tell
    // every pinned container it is permanently out of date
    assertEquals("latest", merged.get(0).tag());
    assertEquals("v2026.8.3", ImageCatalogService.newest(merged));
  }

  @Test
  void newestIsNullWhenNothingIsPinned() {
    assertNull(ImageCatalogService.newest(
        ImageCatalogService.merge(Set.of(), List.of(remote("latest"), remote("main")))));
    assertNull(ImageCatalogService.newest(List.of()));
  }
}
