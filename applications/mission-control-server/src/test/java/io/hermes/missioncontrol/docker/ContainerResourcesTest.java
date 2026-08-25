package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ContainerResourcesTest {

  @Test
  void theBaselineIsTheVendorsRecommendationAndNotDockersDefault() {
    // https://hermes-agent.nousresearch.com/docs/user-guide/docker — 2–4 GB recommended,
    // 2 cores recommended. The low end of the band, so 'increase before deploying' has
    // somewhere to go, and above the 2 GB browser-tools floor.
    assertEquals(2048, ContainerResources.BASELINE.memoryMb());
    assertEquals(2.0, ContainerResources.BASELINE.cpus());
  }

  @Test
  void anUnsetFieldMeansTheRecommendationRatherThanNoLimit() {
    // what an older client, or a scripted deploy written before this existed, is asking for
    assertEquals(ContainerResources.BASELINE, ContainerResources.orBaseline(null, null));
  }

  @Test
  void eachFieldFallsBackOnItsOwn() {
    assertEquals(new ContainerResources(8192, ContainerResources.BASELINE.cpus()),
        ContainerResources.orBaseline(8192, null));
    assertEquals(new ContainerResources(ContainerResources.BASELINE.memoryMb(), 4.0),
        ContainerResources.orBaseline(null, 4.0));
  }

  @Test
  void memoryBelowTheVendorsMinimumIsRefusedRatherThanDeployedSmall() {
    // the guide states 1 GB as the minimum; under it the agent is documented not to run,
    // and a deploy that quietly obliged would produce a container that looks fine until used
    assertThrows(IllegalArgumentException.class, () -> new ContainerResources(512, 2.0));
  }

  @Test
  void cpusBelowTheVendorsMinimumIsRefusedToo() {
    assertThrows(IllegalArgumentException.class, () -> new ContainerResources(2048, 0.5));
  }

  @Test
  void absurdValuesAreRefusedAtTheOtherEndAsWell() {
    assertThrows(IllegalArgumentException.class,
        () -> new ContainerResources(ContainerResources.MAX_MEMORY_MB + 1, 2.0));
    assertThrows(IllegalArgumentException.class,
        () -> new ContainerResources(2048, ContainerResources.MAX_CPUS + 1));
  }

  @Test
  void theVendorsOwnMinimumsAreAccepted() {
    ContainerResources smallest =
        new ContainerResources(ContainerResources.MIN_MEMORY_MB, ContainerResources.MIN_CPUS);

    assertEquals(1024, smallest.memoryMb());
    assertEquals(1.0, smallest.cpus());
  }

  @Test
  void memoryIsHandedToDockerInBytes() {
    assertEquals(2048L * 1024 * 1024, ContainerResources.BASELINE.memoryBytes());
  }

  @Test
  void cpusAreHandedToDockerInBillionths() {
    // what `--cpus` is sugar for
    assertEquals(2_000_000_000L, ContainerResources.BASELINE.nanoCpus());
    assertEquals(1_500_000_000L, new ContainerResources(2048, 1.5).nanoCpus());
  }
}
