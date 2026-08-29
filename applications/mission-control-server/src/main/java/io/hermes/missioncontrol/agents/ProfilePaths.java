package io.hermes.missioncontrol.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Container-side paths of a Hermes profile, and the single rule that stops a
 * URL-sourced profile name from escaping the profiles directory.
 *
 * <p>Split out of {@link HermesProfiles} so the traversal guard has one home:
 * every path this package builds is concatenated here, which is the only place
 * a name can be validated once and trusted afterwards.
 */
final class ProfilePaths {

  static final String HERMES_HOME = "/opt/data";
  static final String PROFILES_DIR = "/opt/data/profiles";
  static final String PLATFORM_CLI = "cli";

  /** Also applied to skill names and ids, which reach us from user-authored templates.
   *  Compiled from {@link ProfileSpec#NAME_PATTERN}, which is where the expression is
   *  written once for the request records that have to declare it as an annotation. */
  static final Pattern NAME = Pattern.compile(ProfileSpec.NAME_PATTERN);

  private ProfilePaths() {}

  static String profileDir(String name) {
    if ("default".equals(name)) return HERMES_HOME;
    // names reach us from URL path segments — reject anything that could
    // escape the profiles dir before it is concatenated into a container path
    if (!isValidName(name)) {
      throw new IllegalArgumentException("invalid profile name");
    }
    return PROFILES_DIR + "/" + name;
  }

  static String profileId(String containerId, String name) {
    return containerId + "--" + name;
  }

  static String skillsDir(String profileName) {
    return profileDir(profileName) + "/skills";
  }

  /** How deep a skill's own files may nest. Bounded by the reader, not by taste:
   *  {@code HermesSkills.listSkillFiles} runs {@code find -maxdepth 3}, so a file
   *  written deeper than this is invisible to the very call that lists it back. */
  static final int MAX_SKILL_FILE_DEPTH = 3;

  /**
   * One file inside a skill's directory, from a relative path a library row supplied.
   *
   * <p>Every {@code /}-separated segment must pass {@link #isValidName} on its own. That
   * turns the same whitelist {@link #profileDir} relies on into a per-segment rule, which
   * is what a multi-segment path needs: {@code NAME} already rejects {@code ..}, an empty
   * segment, a leading dot, a backslash and a leading {@code -}, so a path that survives
   * every segment cannot climb out of the directory it is joined to.
   *
   * <p>Rejecting rather than sanitizing is deliberate. A silently-rewritten path would
   * write an operator's file somewhere they did not ask for.
   */
  static String skillFile(String profileName, String skillName, String relativePath) {
    if (!isValidName(skillName)) {
      throw new IllegalArgumentException("invalid skill name");
    }
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("missing skill file path");
    }
    // split(-1) keeps trailing empties, so "a/" and "a//b" are rejected rather than
    // silently collapsing to "a" and "a/b"
    String[] segments = relativePath.split("/", -1);
    if (segments.length > MAX_SKILL_FILE_DEPTH) {
      throw new IllegalArgumentException(
          "skill file path is deeper than " + MAX_SKILL_FILE_DEPTH + " segments: " + relativePath);
    }
    for (String segment : segments) {
      if (!isValidName(segment)) {
        throw new IllegalArgumentException("invalid skill file path: " + relativePath);
      }
    }
    return skillsDir(profileName) + "/" + skillName + "/" + relativePath;
  }

  static String configFile(String profileName) {
    return profileDir(profileName) + "/config.yaml";
  }

  static String stateDb(String profileName) {
    return profileDir(profileName) + "/state.db";
  }

  /** Hermes keeps a profile's schedule here, as a JSON document it owns. */
  static String cronJobsFile(String profileName) {
    return profileDir(profileName) + "/cron/jobs.json";
  }

  /** And its webhook routes here, keyed by route name — including their HMAC secrets,
   *  in plaintext, which is why nothing reads this straight out to the browser. */
  static String webhookSubscriptionsFile(String profileName) {
    return profileDir(profileName) + "/webhook_subscriptions.json";
  }

  /**
   * The emergency-stop sentinel {@code hermes pause} writes. Its presence is the pause —
   * hermes honours a bare {@code touch} — and its body, when there is one, is
   * {@code {"engaged_at": …, "reason": …}}.
   */
  static String estopFile(String profileName) {
    return profileDir(profileName) + "/ESTOP";
  }

  /**
   * The argv prefix that scopes a hermes command to one profile. {@code default} lives at
   * the hermes home rather than under the profiles directory, and is invoked bare.
   *
   * <p>Bare is the canonical form, not the only one that works: hermes v2026.8.19 accepts
   * {@code -p default} and resolves it to the same home, checked against a real container on
   * {@code skills list} and {@code skills install}. Worth knowing before treating a call site
   * that passes it as a live defect — the reason to route every one of them through here is
   * that this method validates the name, not that the other spelling fails.
   *
   * <p>Here because this class already owns that special case for paths, and because every
   * caller in this package builds an argv the same way: this prefix, then the subcommand.
   */
  static List<String> hermesCli(String profileName) {
    profileDir(profileName);   // validates a URL-sourced name before it reaches an argv
    return "default".equals(profileName)
        ? List.of("hermes")
        : List.of("hermes", "-p", profileName);
  }

  /** {@link #hermesCli(String)} with the subcommand appended, for the call sites that
   *  have their whole argv up front. */
  static List<String> hermesCli(String profileName, String... args) {
    List<String> command = new ArrayList<>(hermesCli(profileName));
    command.addAll(List.of(args));
    return List.copyOf(command);
  }

  static String gatewayLogDir(String profileName) {
    profileDir(profileName); // validates the URL-sourced profile name
    return HERMES_HOME + "/logs/gateways/" + profileName;
  }

  static boolean isValidName(String name) {
    return name != null && NAME.matcher(name).matches();
  }
}
