package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import io.hermes.missioncontrol.mcp.McpServerKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Turning the MCP entries in a template save into stored snapshots.
 *
 * <p>Split out of {@link ProfileTemplateService} because two rules here decide whether
 * credentials leak between definitions. A catalog entry is copied into an independent
 * encrypted snapshot, so a later catalog change cannot silently alter what the template
 * deploys. And because the ordinary editor never receives encrypted values, a prior
 * snapshot's secrets are carried forward <em>only</em> while its connection definition is
 * unchanged — replacing an entry with a different server must not inherit the old
 * credentials.
 */
@Component
class TemplateMcpSnapshots {

  private final McpRegistryService registry;
  private final TemplateSecrets secrets;

  TemplateMcpSnapshots(McpRegistryService registry, TemplateSecrets secrets) {
    this.registry = registry;
    this.secrets = secrets;
  }

  /** Resolves input-only catalog ids and persists independent encrypted copies. */
  List<McpServerSpec> materialize(List<McpServerSpec> input, ProfileTemplate existing) {
    Map<String, McpServerSpec> priorByName = new HashMap<>();
    if (existing != null) {
      for (McpServerSpec prior : nz(existing.mcpServers())) {
        if (prior != null && prior.name() != null) priorByName.put(prior.name(), prior);
      }
    }

    List<McpServerSpec> result = new ArrayList<>();
    for (McpServerSpec requested : nz(input)) {
      if (requested == null) continue;
      if (requested.sourceServerId() != null && !requested.sourceServerId().isBlank()) {
        result.add(fromCatalog(requested));
      } else {
        result.add(custom(requested, priorByName.get(requested.name())));
      }
    }
    return List.copyOf(result);
  }

  /** A custom entry, keeping the prior snapshot's secrets only if it is the same connection. */
  private McpServerSpec custom(McpServerSpec requested, McpServerSpec prior) {
    boolean unchanged = prior != null && sameConnection(prior, requested);
    return new McpServerSpec(
        requested.name(), requested.transport(), requested.url(), requested.command(),
        requested.args(), requested.enabled(), null,
        unchanged ? secrets.reencryptValues(prior.environment()) : null,
        unchanged ? secrets.reencryptValues(prior.headers()) : null);
  }

  /** A detached copy of a catalog server, with its environment or headers captured now. */
  private McpServerSpec fromCatalog(McpServerSpec requested) {
    String sourceId = requested.sourceServerId().trim();
    // a snapshot copies the stored definition; whether the server is up right now says
    // nothing about what to capture, so this does not pay for a runtime refresh
    McpServerDto source = registry.definition(sourceId);
    String alias = requested.name() == null || requested.name().isBlank()
        ? source.name() : requested.name().trim();
    boolean enabled = requested.enabled() == null || requested.enabled();

    if (McpServerKind.STDIO.is(source.kind())) {
      if (source.stdioCommand() == null || source.stdioCommand().isBlank()) {
        throw new IllegalArgumentException("catalog stdio server has no command: " + source.name());
      }
      return new McpServerSpec(
          alias, "stdio", null, source.stdioCommand(), joinArgs(source.args()), enabled,
          null, secrets.encryptValues(registry.materializedEnvironment(sourceId)), List.of());
    }

    String url = firstNonBlank(source.crossHostUrl(), source.connectionUrl(), source.url());
    if (url == null) {
      throw new IllegalArgumentException("catalog server has no usable connection URL: " + source.name());
    }
    return new McpServerSpec(
        alias, source.transport(), url, null, null, enabled, null, List.of(),
        secrets.encryptValues(registry.materializedHeaders(sourceId)));
  }

  private static boolean sameConnection(McpServerSpec left, McpServerSpec right) {
    return Objects.equals(left.transport(), right.transport())
        && Objects.equals(left.url(), right.url())
        && Objects.equals(left.command(), right.command())
        && Objects.equals(left.args(), right.args());
  }

  /** A catalog server's argv rendered as the single string the profile config stores. */
  private static String joinArgs(List<String> args) {
    if (args == null || args.isEmpty()) return null;
    return args.stream().map(TemplateMcpSnapshots::quoteArg).reduce((a, b) -> a + " " + b).orElse(null);
  }

  private static String quoteArg(String value) {
    if (value == null) return "''";
    if (!value.isEmpty() && value.chars().noneMatch(ch -> Character.isWhitespace(ch) || ch == '\'' || ch == '"')) {
      return value;
    }
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  private static <T> List<T> nz(List<T> value) {
    return value == null ? List.of() : value;
  }
}
