package io.hermes.missioncontrol.contract;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locating the two published files these contract tests read, from either the module directory
 * or the repo root — Maven runs from one and a developer often runs from the other.
 *
 * <p>Shared rather than written out per test: the walk-up depth and the candidate paths have to
 * agree between callers, and a copy that drifted would not fail, it would silently skip
 * ({@code assumeTrue}) and report a green check that read nothing.
 */
final class RepoDocs {

  private RepoDocs() {}

  /** What the frontend publishes: one `METHOD /path` per line. */
  static Path contractFile() {
    return findUpwards("applications/api-contract.txt", "api-contract.txt");
  }

  /** The hand-written API reference the routes above are supposed to appear in. */
  static Path apiDoc() {
    return findUpwards("docs/api.md", "../docs/api.md");
  }

  private static Path findUpwards(String... candidates) {
    Path dir = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
      for (String candidate : candidates) {
        Path file = dir.resolve(candidate);
        if (Files.isRegularFile(file)) return file;
      }
    }
    return null;
  }
}
