package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Image;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The configured Hermes image: how its reference is resolved, which of its tags a host
 * already holds, and how a missing one is pulled.
 *
 * <p>Split out of {@link DockerGateway} because every other concern needs the same answer to
 * "what repository are we running?" — and a tag accidentally left on that answer has broken
 * the fleet's update badges and produced {@code repo:tag:tag} references before.
 */
@Component
public class ImageStore {

  private final DockerClients clients;
  private final AppProperties props;

  public ImageStore(DockerClients clients, AppProperties props) {
    this.clients = clients;
    this.props = props;
  }

  /**
   * Repository half of MC_HERMES_IMAGE. The property is documented as a
   * repository, but the filtering paths tolerate a tag on it — so the paths that
   * build a reference must strip one rather than emit 'repo:tag:tag'.
   */
  String hermesRepository() {
    return ImageRef.splitImage(props.hermesImage())[0];
  }

  /** The configured repository, normalized for comparison against a container's image. */
  String normalizedHermesRepository() {
    return ImageRef.normalizeRepository(props.hermesImage());
  }

  /** A {@code repository:tag} reference for the requested tag, defaulting to {@code latest}. */
  String reference(String version) {
    return hermesRepository() + ":" + tagOf(version);
  }

  static String tagOf(String version) {
    return version == null || version.isBlank() ? "latest" : version;
  }

  /**
   * The repository half of the configured Hermes image, as the catalog reports it.
   *
   * <p>Tag-stripped like every sibling path: the frontend gates its update badges on this
   * value matching {@code ContainerDto.image}, which is already a bare repository. Echoing
   * a tagged MC_HERMES_IMAGE verbatim compares unequal and silently retires the upgrade
   * affordance for the whole fleet.
   */
  public String hermesImageRepository() {
    return props.hermesImage() == null ? "" : hermesRepository();
  }

  /** Tags of the configured Hermes image already present in this host's image store. */
  public Set<String> localImageTags(String url) {
    String targetRepo = normalizedHermesRepository();
    if (targetRepo.isBlank()) return Set.of();
    DockerClient client = clients.forUrl(url);
    Set<String> tags = new HashSet<>();
    // no withShowAll: that only adds intermediate and dangling layers, which carry a null or
    // <none> repo tag and are discarded immediately below
    List<Image> images = client.listImagesCmd().exec();
    for (Image image : images) {
      String[] repoTags = image.getRepoTags();
      if (repoTags == null) continue;
      for (String repoTag : repoTags) {
        if (repoTag == null || repoTag.contains("<none>")) continue;
        String[] parts = ImageRef.splitImage(repoTag);
        if (!targetRepo.equals(ImageRef.normalizeRepository(parts[0]))) continue;
        String tag = parts[1];
        if (tag != null && !tag.isBlank()) tags.add(tag);
      }
    }
    return tags;
  }

  /** The local image id for a reference, or null when the host does not hold it. */
  static String imageIdOf(DockerClient client, String image) {
    try {
      return client.inspectImageCmd(image).exec().getId();
    } catch (NotFoundException absent) {
      return null;
    }
  }

  /** Pulls a tag over the streaming client, whose socket carries no read timeout. */
  void pull(String url, String repository, String tag) {
    pull(clients.streamingForUrl(url), repository, tag);
  }

  static void pull(DockerClient client, String repository, String tag) {
    try (var callback = client.pullImageCmd(repository).withTag(tag)
        .exec(new PullImageResultCallback())) {
      if (!callback.awaitCompletion(180, TimeUnit.SECONDS)) {
        throw new UpstreamUnavailableException("image pull timed out: " + repository + ":" + tag);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException("image pull interrupted", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new UpstreamUnavailableException("image pull failed: " + e.getMessage(), e);
    }
  }
}
