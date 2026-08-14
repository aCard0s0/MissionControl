package io.hermes.missioncontrol.docker;

import java.util.Locale;
import java.util.Set;

/**
 * Parsing and ordering rules for image references and tags. Pure string work —
 * nothing here talks to a daemon or a registry.
 */
final class ImageRef {

  private static final Set<String> DOCKER_HUB_PREFIXES =
      Set.of("docker.io/", "registry-1.docker.io/", "index.docker.io/");

  /** Tags that move: they name a stream, not a release, so they are never "newest". */
  private static final Set<String> FLOATING = Set.of("latest", "main", "edge", "nightly", "dev");

  private ImageRef() {
  }

  /** Splits a reference into {repository, tag}, defaulting the tag to 'latest'. */
  static String[] splitImage(String image) {
    if (image == null) return new String[]{"?", "?"};
    int idx = image.lastIndexOf(':');
    // a ':' inside a registry host:port segment is not a tag separator
    if (idx > 0 && image.indexOf('/', idx) == -1) {
      return new String[]{image.substring(0, idx), image.substring(idx + 1)};
    }
    return new String[]{image, "latest"};
  }

  /** Drops any tag and the Docker Hub registry prefixes so short and long forms compare equal. */
  static String normalizeRepository(String repository) {
    if (repository == null) return "";
    String repo = repository;
    int idx = repo.lastIndexOf(':');
    if (idx > 0 && repo.indexOf('/', idx) == -1) {
      repo = repo.substring(0, idx);
    }
    String normalized = repo.toLowerCase(Locale.ROOT);
    for (String prefix : DOCKER_HUB_PREFIXES) {
      if (normalized.startsWith(prefix)) {
        return normalized.substring(prefix.length());
      }
    }
    return normalized;
  }

  /**
   * The 'namespace/name' path Docker Hub's API expects, or null when the
   * repository lives on another registry (which this codebase cannot enumerate).
   */
  static String dockerHubPath(String repository) {
    String normalized = normalizeRepository(repository);
    if (normalized.isBlank()) return null;
    String[] segments = normalized.split("/");
    if (segments.length > 2) return null;
    String first = segments[0];
    // a registry host is the only segment that carries a dot, a port, or is localhost
    if (segments.length == 2
        && (first.contains(".") || first.contains(":") || "localhost".equals(first))) {
      return null;
    }
    return segments.length == 1 ? "library/" + first : normalized;
  }

  /** True for tags that track a stream rather than pinning a release. */
  static boolean isFloating(String tag) {
    return tag != null && FLOATING.contains(tag.toLowerCase(Locale.ROOT));
  }

  /** Orders tags newest-first: 'latest', then descending version, then unparseable tags. */
  static int compareTags(String left, String right) {
    if ("latest".equals(left)) return "latest".equals(right) ? 0 : -1;
    if ("latest".equals(right)) return 1;
    int[] leftVer = parseVersion(left);
    int[] rightVer = parseVersion(right);
    if (leftVer != null && rightVer != null) {
      int compared = compareVersions(leftVer, rightVer);
      return -compared;   // descending
    }
    if (leftVer != null) return -1;
    if (rightVer != null) return 1;
    return right.compareTo(left);
  }

  /**
   * Numeric components of a version tag, or null when the tag is not a version.
   * The component count is not capped — Hermes publishes calendar tags such as
   * v2026.7.7.2, and truncating those would rank them against unrelated releases.
   */
  static int[] parseVersion(String tag) {
    if (tag == null) return null;
    String trimmed = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    if (!trimmed.matches("\\d+(\\.\\d+)*")) return null;
    String[] parts = trimmed.split("\\.");
    int[] result = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        result[i] = Integer.parseInt(parts[i]);
      } catch (NumberFormatException overflow) {
        return null;   // absurdly long numeric run — not a version we can rank
      }
    }
    return result;
  }

  /** Element-wise comparison over the longer operand; a missing component counts as 0. */
  static int compareVersions(int[] left, int[] right) {
    int length = Math.max(left.length, right.length);
    for (int i = 0; i < length; i++) {
      int a = i < left.length ? left[i] : 0;
      int b = i < right.length ? right[i] : 0;
      if (a != b) return Integer.compare(a, b);
    }
    return 0;
  }
}
