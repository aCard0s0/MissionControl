package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatsConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every CPU, RAM and network number the dashboard draws comes out of
 * {@link DockerGateway#toStats} and {@link DockerGateway#usageWithoutCache}.
 */
class ContainerStatsReaderTest {

  private static final long MIB = 1_048_576L;
  private static final long GIB = 1024 * MIB;

  @Test
  void cpuPercentScalesWithOnlineCpus() {
    Statistics stats = sample(
        cpuSample(1_500_000_000L, 11_000_000_000L, 4L),
        cpuSample(1_000_000_000L, 10_000_000_000L, 4L),
        null, null);

    // half of one core's worth of the sampling window, on a 4-core machine, is 200% the
    // way docker stats reports it — the pill is per-core, not capped at 100.
    assertEquals(200.0, ContainerStatsReader.toStats(stats).cpuPercent(), 1e-9);
  }

  @Test
  void aCounterResetAfterRestartReportsZeroRatherThanNegativeCpu() {
    Statistics stats = sample(
        cpuSample(200_000_000L, 11_000_000_000L, 2L),
        cpuSample(5_000_000_000L, 10_000_000_000L, 2L),
        null, null);

    // the container's usage counter restarted under us; a negative delta would render
    // as a nonsense CPU pill instead of the idle container it actually is.
    assertEquals(0.0, ContainerStatsReader.toStats(stats).cpuPercent(), 1e-9);
  }

  @Test
  void missingOnlineCpusDefaultsToOneCore() {
    Statistics stats = sample(
        cpuSample(1_500_000_000L, 11_000_000_000L, null),
        cpuSample(1_000_000_000L, 10_000_000_000L, null),
        null, null);

    assertEquals(50.0, ContainerStatsReader.toStats(stats).cpuPercent(), 1e-9);
  }

  @Test
  void aZeroSystemDeltaCannotDivideByZero() {
    Statistics stats = sample(
        cpuSample(1_500_000_000L, 10_000_000_000L, 2L),
        cpuSample(1_000_000_000L, 10_000_000_000L, 2L),
        null, null);

    // two samples landing inside the same host tick; dividing anyway yields Infinity,
    // which serialises to a JSON number the dashboard cannot plot.
    StatsDto dto = assertDoesNotThrow(() -> ContainerStatsReader.toStats(stats));

    assertTrue(Double.isFinite(dto.cpuPercent()), "cpu must stay a finite number");
    assertEquals(0.0, dto.cpuPercent(), 1e-9);
  }

  @Test
  void absentCpuOrMemoryBlocksReportZeroInsteadOfThrowing() {
    // a container that has just been created answers /stats with the blocks omitted
    Statistics stats = mock(Statistics.class);
    long before = System.currentTimeMillis();

    StatsDto dto = assertDoesNotThrow(() -> ContainerStatsReader.toStats(stats));

    assertEquals(0.0, dto.cpuPercent(), 1e-9);
    assertEquals(0.0, dto.ramMb(), 1e-9);
    assertEquals(0.0, dto.ramTotalMb(), 1e-9);
    assertEquals(0L, dto.rxBytes());
    assertEquals(0L, dto.txBytes());
    // the empty sample is still stamped, so the client can keep computing rates from it
    assertTrue(dto.sampledAt() >= before, "sample must carry a real wall-clock timestamp");
  }

  @Test
  void memoryExcludesTheReclaimablePageCacheTheWayDockerStatsDoes() {
    Statistics stats = sample(null, null,
        memorySample(2 * GIB, 2 * GIB, cacheSample(1536 * MIB, null)), null);

    StatsDto dto = ContainerStatsReader.toStats(stats);

    // without the subtraction an Agent that merely read 1.5 GiB of skills shows as
    // pinned at its 2 GiB limit and about to OOM
    assertEquals(512.0, dto.ramMb(), 0.001);
    assertEquals(2048.0, dto.ramTotalMb(), 0.001);
  }

  @Test
  void cgroupV2ReportsInactiveFileUnderADifferentName() {
    Statistics stats = sample(null, null,
        memorySample(2 * GIB, 2 * GIB, cacheSample(null, 1536 * MIB)), null);

    StatsDto dto = ContainerStatsReader.toStats(stats);

    assertEquals(512.0, dto.ramMb(), 0.001);
    assertEquals(2048.0, dto.ramTotalMb(), 0.001);
  }

  @Test
  void aCacheReadingLargerThanUsageIsIgnoredRatherThanReportedAsNegativeMemory() {
    // a partial sample can pair a stale usage with a fresher cache figure
    MemoryStatsConfig memory = memorySample(100 * MIB, 512 * MIB, cacheSample(300 * MIB, null));

    long usage = ContainerStatsReader.usageWithoutCache(memory);

    assertEquals(100 * MIB, usage);
  }

  @Test
  void memoryWithNoCacheBreakdownFallsBackToRawUsage() {
    MemoryStatsConfig memory = memorySample(700 * MIB, 2 * GIB, null);

    assertEquals(700 * MIB, ContainerStatsReader.usageWithoutCache(memory));
  }

  @Test
  void networkCountersAreSummedAcrossEveryInterface() {
    Statistics stats = sample(null, null, null, Map.of(
        "eth0", networkSample(1_000_000L, 250_000L),
        "eth1", networkSample(30L, 7L)));

    StatsDto dto = ContainerStatsReader.toStats(stats);

    // an Agent on both the bridge and the managed MCP network must report its whole
    // traffic, not whichever interface the daemon happened to list first
    assertEquals(1_000_030L, dto.rxBytes());
    assertEquals(250_007L, dto.txBytes());
  }

  @Test
  void aStatsSampleWithNoNetworksReportsZeroTraffic() {
    Statistics stats = sample(
        cpuSample(1_500_000_000L, 11_000_000_000L, 1L),
        cpuSample(1_000_000_000L, 10_000_000_000L, 1L),
        memorySample(700 * MIB, 2 * GIB, null), null);

    StatsDto dto = assertDoesNotThrow(() -> ContainerStatsReader.toStats(stats));

    assertEquals(0L, dto.rxBytes());
    assertEquals(0L, dto.txBytes());
    // the rest of the sample still has to survive the missing network block
    assertEquals(50.0, dto.cpuPercent(), 1e-9);
    assertEquals(700.0, dto.ramMb(), 0.001);
  }

  private static Statistics sample(CpuStatsConfig cpu, CpuStatsConfig preCpu,
      MemoryStatsConfig memory, Map<String, StatisticNetworksConfig> networks) {
    Statistics stats = mock(Statistics.class);
    when(stats.getCpuStats()).thenReturn(cpu);
    when(stats.getPreCpuStats()).thenReturn(preCpu);
    when(stats.getMemoryStats()).thenReturn(memory);
    when(stats.getNetworks()).thenReturn(networks);
    return stats;
  }

  private static CpuStatsConfig cpuSample(Long totalUsage, Long systemUsage, Long onlineCpus) {
    CpuUsageConfig usage = mock(CpuUsageConfig.class);
    when(usage.getTotalUsage()).thenReturn(totalUsage);
    CpuStatsConfig cpu = mock(CpuStatsConfig.class);
    when(cpu.getCpuUsage()).thenReturn(usage);
    when(cpu.getSystemCpuUsage()).thenReturn(systemUsage);
    when(cpu.getOnlineCpus()).thenReturn(onlineCpus);
    return cpu;
  }

  private static MemoryStatsConfig memorySample(long usage, long limit, StatsConfig detail) {
    MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
    when(memory.getUsage()).thenReturn(usage);
    when(memory.getLimit()).thenReturn(limit);
    when(memory.getStats()).thenReturn(detail);
    return memory;
  }

  private static StatsConfig cacheSample(Long totalInactiveFile, Long inactiveFile) {
    StatsConfig detail = mock(StatsConfig.class);
    when(detail.getTotalInactiveFile()).thenReturn(totalInactiveFile);
    when(detail.getInactiveFile()).thenReturn(inactiveFile);
    return detail;
  }

  private static StatisticNetworksConfig networkSample(long rxBytes, long txBytes) {
    StatisticNetworksConfig net = mock(StatisticNetworksConfig.class);
    when(net.getRxBytes()).thenReturn(rxBytes);
    when(net.getTxBytes()).thenReturn(txBytes);
    return net;
  }
}
