package io.hermes.missioncontrol.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The edit map in {@code docs/map} still points at code that exists.
 *
 * <p>Cards cite source as {@code `path:line`}, which is the most useful form to read and the
 * most fragile to keep: any edit above a cited line silently moves it, and a card that now
 * points at a blank line or a stray brace still looks authoritative. That is not theoretical —
 * within a day of the map being written, a table rename and a store split had left fourteen
 * citations pointing at the wrong thing, and nothing failed.
 *
 * <p>So this is the map's own version of what {@code RouteContractTest} does for the wire: not
 * a proof that a card is <em>right</em>, which needs a person, but a floor under how wrong it
 * can quietly become. A citation landing on a blank line or a lone brace is the signature of a
 * shifted line number, and it is the one form of rot a machine can spot without understanding
 * the claim.
 *
 * <p>It also checks that every object card is listed in {@code objects/_index.md}. The index is
 * hand-maintained by design — there is no generator — and a card nothing links to is a shelf
 * with no entry in the catalog, which is the failure the catalog exists to prevent.
 */
class MapIntegrityTest {

  /** A `path:line` citation inside backticks. */
  private static final Pattern CITATION =
      Pattern.compile("`([A-Za-z0-9_./\\-]+\\.(?:java|ts|sql|yml|txt|md|scss|html)):(\\d+)`");

  /** A markdown link that is not a URL. */
  private static final Pattern LINK = Pattern.compile("\\[[^\\]]*]\\((?!https?:|mailto:)([^)#]+)\\)");

  /** The signature of a line number that moved: nothing, or scaffolding. */
  private static final Pattern MOVED = Pattern.compile("^\\s*[){}\\[\\]]*\\s*$");

  /** Where a card's citation may be rooted, in the order a reader would try them. */
  private static final List<String> SOURCE_ROOTS = List.of(
      "",
      "applications/mission-control-server/src/main/java/io/hermes/missioncontrol/",
      "applications/mission-control-fe/src/app/",
      "applications/mission-control-server/src/main/resources/",
      "applications/mission-control-server/src/test/java/io/hermes/missioncontrol/");

  @Test
  void everyCitationLandsOnSomething() {
    Path root = RepoDocs.repoRoot();
    assumeTrue(root != null, "docs/map is not in this checkout");

    List<String> broken = new ArrayList<>();
    int checked = 0;

    for (Path card : cards()) {
      Matcher m = CITATION.matcher(read(card));
      while (m.find()) {
        checked++;
        Path file = resolve(root, m.group(1));
        String where = card.getFileName() + " — " + m.group();
        if (file == null) {
          broken.add(where + " — no such file");
          continue;
        }
        List<String> lines = readLines(file);
        int line = Integer.parseInt(m.group(2));
        if (line > lines.size()) {
          broken.add(where + " — file has only " + lines.size() + " lines");
        } else if (MOVED.matcher(lines.get(line - 1)).matches()) {
          broken.add(where + " — lands on `" + lines.get(line - 1).trim() + "`, so the line moved");
        }
      }
    }

    int total = checked;
    assertTrue(broken.isEmpty(), () -> report(
        "these citations in docs/map no longer point at code (" + total + " checked):", broken,
        "Re-read the cited file and correct the line, or drop the citation. A card that points "
            + "at the wrong line is worse than one that points at nothing: it will be believed."));
  }

  @Test
  void everyLinkResolves() {
    assumeTrue(RepoDocs.mapDir() != null, "docs/map is not in this checkout");
    List<String> broken = new ArrayList<>();
    for (Path card : cards()) {
      Matcher m = LINK.matcher(read(card));
      while (m.find()) {
        if (!Files.exists(card.getParent().resolve(m.group(1)).normalize())) {
          broken.add(card.getFileName() + " — " + m.group(1));
        }
      }
    }
    assertTrue(broken.isEmpty(), () -> report(
        "these links in docs/map go nowhere:", broken,
        "The map is a catalog; a dead link in it is a shelf reference to a book that is gone."));
  }

  @Test
  void everyObjectCardIsInTheIndex() {
    Path map = RepoDocs.mapDir();
    assumeTrue(map != null, "docs/map is not in this checkout");
    String index = read(map.resolve("objects/_index.md"));

    List<String> unlisted = cards().stream()
        .filter(card -> card.getParent().getParent().getFileName().toString().equals("objects"))
        .filter(card -> !card.getFileName().toString().startsWith("_"))
        .filter(card -> !card.getFileName().toString().equals("CONTEXT.md"))
        .filter(card -> !index.contains(card.getFileName().toString()))
        .map(card -> card.getParent().getFileName() + "/" + card.getFileName())
        .toList();

    assertTrue(unlisted.isEmpty(), () -> report(
        "these object cards are not listed in objects/_index.md:", unlisted,
        "Every noun gets a line there, card or no card — the index is how a cold agent finds "
            + "one without reading the whole shelf."));
  }

  private static List<Path> cards() {
    try {
      try (Stream<Path> tree = Files.walk(RepoDocs.mapDir())) {
        return tree.filter(p -> p.getFileName().toString().endsWith(".md"))
            .filter(Files::isRegularFile)
            .sorted()
            .toList();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not walk docs/map", e);
    }
  }

  /** A card cites from the reader's point of view, so try each root it might have meant. */
  private static Path resolve(Path root, String cited) {
    for (String prefix : SOURCE_ROOTS) {
      Path candidate = root.resolve(prefix + cited);
      if (Files.isRegularFile(candidate)) return candidate;
    }
    return null;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file, e);
    }
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file, e);
    }
  }

  private static String report(String headline, List<String> findings, String advice) {
    StringBuilder out = new StringBuilder(headline);
    for (String finding : findings) out.append("\n  ").append(finding);
    return out.append("\n\n").append(advice).toString();
  }
}
