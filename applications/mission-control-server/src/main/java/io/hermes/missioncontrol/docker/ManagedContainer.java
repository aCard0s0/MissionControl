package io.hermes.missioncontrol.docker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What makes a container one Mission Control deployed: the labels it carries, the data volume
 * they name, and where that volume is mounted.
 *
 * <p>Four classes in this package independently implemented this vocabulary from string
 * literals. {@link HermesDeployer} wrote the labels, {@link ContainerUpgrader} validated them
 * before recreating a container, {@link ContainerLifecycle} read them to decide whether a
 * removal also drops a volume, and {@link ContainerInventory} read them to decide what belongs
 * in the fleet. Renaming a label or the volume prefix meant finding all four, and the
 * "managed, and its volume name looks right" check existed twice with no guarantee the two
 * agreed — a disagreement that would either strand a data volume or refuse an upgrade.
 *
 * <p>Not a package move. The Hermes-specific knowledge in this package is not confined to a
 * class or two that could be lifted out: the image store resolves the configured Hermes
 * repository, the inventory filters the fleet by it, the upgrader checks a container runs it,
 * and readiness runs {@code hermes gateway status}. Extracting a "provisioning" package would
 * leave most of that behind and buy indirection rather than separation. Centralising the one
 * vocabulary they genuinely share is the part with a defect behind it.
 */
final class ManagedContainer {

  /** Set on every container Mission Control deploys. */
  static final String MANAGED_LABEL = "mc.managed";

  /** Names the data volume holding profiles, souls, skills and conversation history. */
  static final String DATA_VOLUME_LABEL = "mc.dataVolume";

  /** The profiles seeded at deploy time, comma-separated. */
  static final String PROFILES_LABEL = "mc.profiles";

  /** Set on the short-lived one-shot containers that seed a data volume. */
  static final String BOOTSTRAP_LABEL = "mc.bootstrap";

  /** Every managed data volume starts with this, which is what distinguishes one from a
   *  volume some other tool created and labelled by copying our container. */
  static final String DATA_VOLUME_PREFIX = "mc-hermes-";

  /** Where the data volume is mounted inside the container. */
  static final String DATA_MOUNT = "/opt/data";

  private ManagedContainer() {}

  static String dataVolumeName(String containerName) {
    return DATA_VOLUME_PREFIX + containerName;
  }

  static boolean isManaged(Map<String, String> labels) {
    return "true".equals(labels.get(MANAGED_LABEL));
  }

  static boolean isBootstrap(Map<String, String> labels) {
    return "true".equals(labels.get(BOOTSTRAP_LABEL));
  }

  /**
   * The data volume this container owns, or null when it has none we may act on.
   *
   * <p>Both halves of the check matter and are the reason this is one method. A container
   * someone else created can carry a copied {@code mc.dataVolume} label without being managed,
   * and a managed container can name a volume that is not ours — neither is a volume this
   * dashboard created, so neither may be deleted or reattached.
   */
  static String dataVolumeOf(Map<String, String> labels) {
    if (!isManaged(labels)) return null;
    String volume = labels.get(DATA_VOLUME_LABEL);
    return volume != null && volume.startsWith(DATA_VOLUME_PREFIX) ? volume : null;
  }

  /** The profiles seeded at deploy time, or empty when the label is absent or blank. */
  static List<String> seedProfilesOf(Map<String, String> labels) {
    String joined = labels.get(PROFILES_LABEL);
    return joined == null || joined.isBlank() ? List.of() : List.of(joined.split(","));
  }

  /** The label set a newly deployed container carries. */
  static Map<String, String> labelsFor(String dataVolume, List<String> seedProfiles) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put(MANAGED_LABEL, "true");
    labels.put(PROFILES_LABEL, String.join(",", seedProfiles));
    labels.put(DATA_VOLUME_LABEL, dataVolume);
    return Map.copyOf(labels);
  }

  /** The label set a one-shot seeding helper carries. */
  static Map<String, String> bootstrapLabels() {
    return Map.of(BOOTSTRAP_LABEL, "true");
  }
}
