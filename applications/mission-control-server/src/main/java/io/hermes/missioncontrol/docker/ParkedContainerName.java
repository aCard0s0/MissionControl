package io.hermes.missioncontrol.docker;

/**
 * Naming of a container parked during an upgrade.
 *
 * <p>{@link ContainerUpgrader} mints these names and {@link ContainerInventory} filters them
 * out of the fleet view, so the suffix and the pattern that recognises it must agree — which
 * is why they live here rather than in either of those.
 */
final class ParkedContainerName {

  /** Suffix given to a container parked during an upgrade, until the new one is verified. */
  static final String UPGRADE_SUFFIX = "-mc-upgrade-";

  private ParkedContainerName() {}

  static String of(String originalName, String containerId) {
    return originalName + UPGRADE_SUFFIX + shortToken(containerId);
  }

  /** True for a container parked mid-upgrade — visible only via the unfiltered listing. */
  static boolean isUpgradeLeftover(String name) {
    return name != null && name.matches(".*" + UPGRADE_SUFFIX + "[0-9a-f]{8}$");
  }

  private static String shortToken(String containerId) {
    return containerId == null || containerId.length() < 8
        ? "00000000" : containerId.substring(0, 8);
  }
}
