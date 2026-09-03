package io.hermes.missioncontrol.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Every value {@code mcp_servers.kind} may hold: what a catalog record <em>is</em>, which
 * decides whether it has a container at all.
 *
 * <p>Declared for the reason {@link McpOperationState} and {@link McpRuntimeState} were, and
 * with more spread to show for it than either: the three names were written out as string
 * literals **32 times across 13 files in two packages** — including the one place that listed
 * all three, {@code Set.of("managed", "external", "stdio")} in {@link McpRequestValidator}.
 * Adding a kind meant finding every one of them first, and {@code "stdio"} is also a transport
 * value, so a plain search for it does not separate the two vocabularies.
 *
 * <p>Public, unlike its two siblings, because {@code kind} is the one part of this vocabulary
 * that leaves the package: an Agent's MCP entry is materialized differently for each kind, so
 * {@code agents/AgentMcpCatalogService} and {@code agents/templates/TemplateMcpSnapshots} both
 * ask what a catalog record is.
 *
 * <p>The column and the API keep the lowercase spelling they already had, and neither
 * {@code ServerRow} nor {@code McpServerDto} changes type — the same division these two siblings
 * keep, where the vocabulary is declared here and carried as a {@code String} on the wire. That
 * is what makes {@link #is} the shape of a comparison: {@code MANAGED.is(row.kind())} says what
 * {@code "managed".equals(row.kind())} said, in a way that a rename cannot leave behind.
 */
public enum McpServerKind {

  /** Mission Control runs the container: it renders and drives a Compose service for it. */
  MANAGED,
  /** An endpoint someone else runs, reached by the URL it was registered with. */
  EXTERNAL,
  /** A command an Agent runs for itself. No container here, and nothing to reach over HTTP. */
  STDIO;

  /** As the column stores it and the API sends it. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Whether a stored value is this kind. */
  public boolean is(String stored) {
    return this == of(stored);
  }

  /**
   * The kind a stored value names, or null when this build does not know it.
   *
   * <p>Case-insensitive, like {@link McpOperationState#of} and
   * {@link McpRuntimeState#fromContainerStatus}. Every writer stores it lower-cased — the
   * validator lower-cases what a request sent, and the seeder writes {@link #wire()} — so this
   * is for the column, which is {@code TEXT} an operator can edit by hand.
   * {@code TemplateMcpSnapshots} relied on that tolerance before this enum existed, and its
   * test pins it.
   */
  public static McpServerKind of(String stored) {
    if (stored == null) return null;
    String wanted = stored.toLowerCase(Locale.ROOT);
    for (McpServerKind kind : values()) {
      if (kind.wire().equals(wanted)) return kind;
    }
    return null;
  }

  /**
   * The kind a request asked for, or a refusal naming the three that exist.
   *
   * <p>Here rather than in the validator so that the list and the vocabulary cannot disagree —
   * a fourth kind added to this enum and not to that literal would have been accepted by every
   * check downstream and refused only at the edge.
   */
  public static McpServerKind require(String requested) {
    McpServerKind kind = of(requested);
    if (kind == null) throw new IllegalArgumentException("kind must be " + spelledOut());
    return kind;
  }

  /** The three names as the refusal above lists them: {@code managed, external, or stdio}. */
  private static String spelledOut() {
    List<String> names = Arrays.stream(values()).map(McpServerKind::wire).toList();
    return String.join(", ", names.subList(0, names.size() - 1))
        + ", or " + names.getLast();
  }
}
