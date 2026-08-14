package io.hermes.missioncontrol.docker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Joins a host's local Hermes images with the registry's published tags. */
@Service
public class ImageCatalogService {

  private final DockerGateway docker;
  private final RegistryTagService registry;

  public ImageCatalogService(DockerGateway docker, RegistryTagService registry) {
    this.docker = docker;
    this.registry = registry;
  }

  public ImageTagsDto tags(String url, boolean includeRemote) {
    String repository = docker.hermesImageRepository();
    Set<String> local = docker.localImageTags(url);
    RegistryTagService.RemoteTags remote = includeRemote
        ? registry.tags(repository)
        : new RegistryTagService.RemoteTags(List.of(), RegistryTagService.DISABLED, null, null);

    List<ImageTagDto> entries = merge(local, remote.tags());
    return new ImageTagsDto(
        repository,
        entries.stream().map(ImageTagDto::tag).toList(),
        entries,
        newest(entries),
        remote.status(),
        remote.detail(),
        remote.checkedAt());
  }

  /** One entry per distinct tag, newest first, flagged with where it is known from. */
  static List<ImageTagDto> merge(Set<String> local, List<ImageTagDto> remote) {
    Map<String, ImageTagDto> byTag = new LinkedHashMap<>();
    for (ImageTagDto entry : remote) {
      byTag.put(entry.tag(), new ImageTagDto(
          entry.tag(), local.contains(entry.tag()), true,
          entry.lastUpdated(), entry.sizeBytes(), entry.digest()));
    }
    for (String tag : local) {
      byTag.computeIfAbsent(tag, t -> new ImageTagDto(t, true, false, null, null, null));
    }
    List<ImageTagDto> merged = new ArrayList<>(byTag.values());
    merged.sort(Comparator.comparing(ImageTagDto::tag, ImageRef::compareTags));
    return List.copyOf(merged);
  }

  /**
   * The newest pinned release. Floating tags are skipped: they name a stream, so
   * calling one "newest" would tell a pinned container it is out of date forever.
   */
  static String newest(List<ImageTagDto> entries) {
    return entries.stream()
        .map(ImageTagDto::tag)
        .filter(tag -> !ImageRef.isFloating(tag))
        .filter(tag -> ImageRef.parseVersion(tag) != null)
        .findFirst()
        .orElse(null);
  }
}
