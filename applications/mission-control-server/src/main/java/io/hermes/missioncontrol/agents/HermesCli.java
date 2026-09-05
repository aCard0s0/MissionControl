package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The {@code hermes -p <profile> …} protocol: invoking a subcommand, and reading what it
 * answers with.
 *
 * <p>{@link HermesCron} and {@link HermesWebhooks} are both thin wrappers over one hermes
 * subcommand that answers in JSON, and both had grown their own copy of the same five
 * helpers — {@code run}, {@code addOption}, {@code notBlank}, {@code text} and
 * {@code epochMillis}, the last three byte-for-byte identical. The timestamp reader in
 * particular carries a rule worth having once: hermes writes ISO-8601 with an offset but
 * not always, so a bare instant has to be tried second, and a value neither parser
 * understands must not fail the whole listing.
 *
 * <p>Deliberately not the exec seam itself — {@link HermesContainerFiles} still owns that,
 * and this only knows how a profile-scoped hermes command is spelled.
 */
@Component
class HermesCli {

  private final HermesContainerFiles files;

  HermesCli(HermesContainerFiles files) {
    this.files = files;
  }

  /** Runs a subcommand, failing on a non-zero exit. */
  ExecResult run(
      DockerHostRef host, String containerId, String profileName, List<String> args) {
    return run(host, containerId, profileName, args, true);
  }

  /** As {@link #run}, with the exit code left for the caller to interpret. */
  ExecResult run(
      DockerHostRef host, String containerId, String profileName, List<String> args,
      boolean check) {
    List<String> command = new ArrayList<>(ProfilePaths.hermesCli(profileName));
    command.addAll(args);
    return files.exec(host, containerId, command, check);
  }

  /** A subcommand's stdout, for the callers that only read what it printed. */
  String stdout(
      DockerHostRef host, String containerId, String profileName, List<String> args) {
    return run(host, containerId, profileName, args).stdout();
  }

  /** {@code hermes -p <profile> config set <key> <value>}, through hermes' own writer so its
   *  validation and migration stay in the loop. */
  void setConfig(
      DockerHostRef host, String containerId, String profileName, String key, String value) {
    run(host, containerId, profileName, List.of("config", "set", key, value));
  }

  /** {@code hermes -p <profile> config unset <key>}, unchecked: hermes answers 1 when the key
   *  is already absent, and builds before v0.21.0 have no {@code unset} at all — in both cases
   *  there is nothing left to remove. */
  void unsetConfig(DockerHostRef host, String containerId, String profileName, String key) {
    run(host, containerId, profileName, List.of("config", "unset", key), false);
  }

  /**
   * Appends {@code flag=value} only when the value is worth sending.
   *
   * <p>One argv word rather than two: argparse reads {@code --name -x} as the option missing
   * its argument and exits with usage, so a name or prompt that starts with a hyphen could not
   * be sent at all. {@code --name=-x} is the value it looks like — checked against hermes
   * v0.20.5 (2026.8.19).
   */
  static void addOption(List<String> command, String flag, String value) {
    if (notBlank(value)) command.add(flag + "=" + value.trim());
  }

  static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  /** A JSON field as a string, with hermes' explicit nulls and absent fields both read as null. */
  static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || value.isMissingNode() ? null : value.asText();
  }

  /**
   * Hermes writes ISO-8601 with an offset; the dashboard works in epoch millis.
   *
   * <p>A value neither parser understands yields null rather than throwing, because one
   * unreadable timestamp must not cost the caller the whole listing.
   */
  static Long epochMillis(String isoTimestamp) {
    if (!notBlank(isoTimestamp)) return null;
    try {
      return OffsetDateTime.parse(isoTimestamp).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(isoTimestamp).toEpochMilli();
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }
  }
}
