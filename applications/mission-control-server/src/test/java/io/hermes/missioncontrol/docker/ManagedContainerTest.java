package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The vocabulary that decides whether a container is one of ours.
 *
 * <p>Worth pinning here rather than only through the four callers that used to implement it
 * from string literals: the volume rule has two halves, and a caller that applied one of them
 * would either strand a data volume or refuse a valid upgrade.
 */
class ManagedContainerTest {

  @Test
  void aDeployedContainerCarriesTheLabelsTheOtherReadersLookFor() {
    Map<String, String> labels =
        ManagedContainer.labelsFor(ManagedContainer.dataVolumeName("demo"), List.of("ops", "research"));

    assertTrue(ManagedContainer.isManaged(labels));
    assertEquals("mc-hermes-demo", ManagedContainer.dataVolumeOf(labels));
    assertEquals(List.of("ops", "research"), ManagedContainer.seedProfilesOf(labels));
    assertFalse(ManagedContainer.isBootstrap(labels));
  }

  /**
   * Both halves of the volume rule, which is the check that existed twice. A copied label on an
   * unmanaged container and a managed container naming a foreign volume are equally not ours,
   * and deleting either volume would destroy data this dashboard never created.
   */
  @Test
  void aVolumeIsOnlyOursWhenTheContainerIsManagedAndTheNameIsOne() {
    assertNull(ManagedContainer.dataVolumeOf(Map.of(
        ManagedContainer.DATA_VOLUME_LABEL, "mc-hermes-demo")),
        "an unmanaged container with a copied label");
    assertNull(ManagedContainer.dataVolumeOf(Map.of(
        ManagedContainer.MANAGED_LABEL, "true",
        ManagedContainer.DATA_VOLUME_LABEL, "someone-elses-data")),
        "a managed container naming a volume we did not create");
    assertNull(ManagedContainer.dataVolumeOf(Map.of(ManagedContainer.MANAGED_LABEL, "true")),
        "a managed container with no recorded volume");
    assertNull(ManagedContainer.dataVolumeOf(Map.of()), "no labels at all");
  }

  @Test
  void anAbsentOrBlankProfileLabelIsNoProfilesRatherThanOneBlankName() {
    // List.of("".split(",")) is a single empty string, which would show as a nameless profile
    assertEquals(List.of(), ManagedContainer.seedProfilesOf(Map.of()));
    assertEquals(List.of(), ManagedContainer.seedProfilesOf(Map.of(ManagedContainer.PROFILES_LABEL, "")));
    assertEquals(List.of(), ManagedContainer.seedProfilesOf(Map.of(ManagedContainer.PROFILES_LABEL, "   ")));
    assertEquals(List.of("ops"), ManagedContainer.seedProfilesOf(Map.of(ManagedContainer.PROFILES_LABEL, "ops")));
  }

  @Test
  void aSeedingHelperIsNeverMistakenForAnAgent() {
    Map<String, String> labels = ManagedContainer.bootstrapLabels();

    assertTrue(ManagedContainer.isBootstrap(labels));
    assertFalse(ManagedContainer.isManaged(labels), "a helper must not read as a fleet member");
    assertNull(ManagedContainer.dataVolumeOf(labels),
        "a helper mounts the volume but does not own it — removing one must not drop it");
  }
}
