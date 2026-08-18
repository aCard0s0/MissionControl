package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.InvocationBuilder;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * One resource sample for a container.
 *
 * <p>Split out of {@link DockerGateway} because the mapping is arithmetic worth pinning on
 * its own: CPU is a delta between two cumulative counters, and memory has to have the
 * reclaimable page cache subtracted or an Agent that merely read a few GB of files looks
 * pinned at its limit.
 */
@Component
public class ContainerStatsReader {

  private final DockerClients clients;

  public ContainerStatsReader(DockerClients clients) {
    this.clients = clients;
  }

  public StatsDto stats(String url, String containerId) {
    DockerClient client = clients.forUrl(url);
    try (InvocationBuilder.AsyncResultCallback<Statistics> callback =
        new InvocationBuilder.AsyncResultCallback<>()) {
      client.statsCmd(containerId).withNoStream(true).exec(callback);
      Statistics stats = callback.awaitResult();
      if (stats == null) {
        // the stream completed without ever delivering a sample — a truncated response, or
        // a daemon restarting mid-request. awaitResult reports that as null rather than an
        // error, and mapping null would surface a defect-shaped 500 for a dependency outage.
        throw new UpstreamUnavailableException("stats returned no sample for " + containerId);
      }
      return toStats(stats);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new UpstreamUnavailableException("stats failed: " + e.getMessage(), e);
    }
  }

  /** Package-private for the same reason as {@link ContainerLogReader#parseLogFrame}: it is
   *  pure mapping worth pinning. */
  static StatsDto toStats(Statistics stats) {
    double cpu = 0;
    var cpuStats = stats.getCpuStats();
    var preCpu = stats.getPreCpuStats();
    if (cpuStats != null && preCpu != null
        && cpuStats.getCpuUsage() != null && preCpu.getCpuUsage() != null
        && cpuStats.getSystemCpuUsage() != null && preCpu.getSystemCpuUsage() != null) {
      long cpuDelta = orZero(cpuStats.getCpuUsage().getTotalUsage()) - orZero(preCpu.getCpuUsage().getTotalUsage());
      long sysDelta = cpuStats.getSystemCpuUsage() - preCpu.getSystemCpuUsage();
      long cpus = cpuStats.getOnlineCpus() != null ? cpuStats.getOnlineCpus() : 1;
      if (sysDelta > 0 && cpuDelta >= 0) cpu = (double) cpuDelta / sysDelta * cpus * 100.0;
    }

    double ramMb = 0;
    double ramTotalMb = 0;
    if (stats.getMemoryStats() != null) {
      ramMb = usageWithoutCache(stats.getMemoryStats()) / 1_048_576.0;
      ramTotalMb = orZero(stats.getMemoryStats().getLimit()) / 1_048_576.0;
    }

    long rx = 0;
    long tx = 0;
    Map<String, StatisticNetworksConfig> networks = stats.getNetworks();
    if (networks != null) {
      for (StatisticNetworksConfig net : networks.values()) {
        rx += orZero(net.getRxBytes());
        tx += orZero(net.getTxBytes());
      }
    }

    return new StatsDto(cpu, ramMb, ramTotalMb, rx, tx, System.currentTimeMillis());
  }

  private static long orZero(Long value) {
    return value == null ? 0 : value;
  }

  /**
   * Memory usage with the reclaimable page cache subtracted, the way {@code docker stats}
   * itself reports it.
   *
   * <p>Raw {@code memory_stats.usage} counts every page the kernel cached on the
   * container's behalf and will drop under pressure. An Agent that has read a few GB of
   * skills or model files otherwise shows as pinned at its limit and about to OOM while
   * the daemon reports it comfortably idle.
   *
   * <p>cgroup v1 reports {@code total_inactive_file}, cgroup v2 {@code inactive_file};
   * a value larger than usage is nonsense from a partial sample, so it is ignored.
   */
  static long usageWithoutCache(MemoryStatsConfig memory) {
    long usage = orZero(memory.getUsage());
    var detail = memory.getStats();
    if (detail == null) return usage;
    Long cache = detail.getTotalInactiveFile() != null
        ? detail.getTotalInactiveFile() : detail.getInactiveFile();
    if (cache == null || cache < 0 || cache > usage) return usage;
    return usage - cache;
  }
}
