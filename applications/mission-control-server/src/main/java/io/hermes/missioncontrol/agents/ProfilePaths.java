package io.hermes.missioncontrol.agents;

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

  /** Also applied to skill names and ids, which reach us from user-authored templates. */
  static final Pattern NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

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
   * The argv prefix that scopes a hermes command to one profile. Hermes takes {@code -p}
   * only for named profiles — {@code default} lives at the hermes home and is invoked bare.
   *
   * <p>Here because this class already owns that special case for paths. The older call
   * sites in {@link HermesSetup} and {@link HermesProfileMcp} still inline the same ternary
   * and could adopt this.
   */
  static java.util.List<String> hermesCli(String profileName) {
    profileDir(profileName);   // validates a URL-sourced name before it reaches an argv
    return "default".equals(profileName)
        ? java.util.List.of("hermes")
        : java.util.List.of("hermes", "-p", profileName);
  }

  static String gatewayLogDir(String profileName) {
    profileDir(profileName); // validates the URL-sourced profile name
    return HERMES_HOME + "/logs/gateways/" + profileName;
  }

  static boolean isValidName(String name) {
    return name != null && NAME.matcher(name).matches();
  }
}
