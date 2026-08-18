package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.command.VersionCmd;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Version;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

/**
 * The read-through paths the live dashboard calls directly: the daemon probe, the
 * stats poll, the log tail, the start/stop buttons, and the image the update badges
 * compare against.
 */
class DockerGatewayReadThroughTest {

  private static final long MIB = 1_048_576L;
  private static final long GIB = 1024 * MIB;

  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final DockerGateway gateway = new DockerGateway(
      clients, new AppProperties("live", "", "unix:///sock", "hermes/image", "hermes", "test"), dockerExec);

  /** The last callback the gateway handed to the log stream, so a test can drive it late. */
  private final AtomicReference<ResultCallback<Frame>> lastLogCallback = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    when(clients.forUrl("unix:///sock")).thenReturn(client);
  }

  // ── daemon probing ───────────────────────────────────────────────────────

  @Test
  void pingReportsTheEngineVersionApiVersionAndALatency() {
    PingCmd ping = mock(PingCmd.class);
    when(client.pingCmd()).thenReturn(ping);
    // the round trip is what the host card's latency badge measures, so the probe has
    // to be timed rather than reported as a constant
    when(ping.exec()).thenAnswer(invocation -> {
      Thread.sleep(20);
      return null;
    });
    VersionCmd versionCmd = mock(VersionCmd.class);
    Version version = mock(Version.class);
    when(client.versionCmd()).thenReturn(versionCmd);
    when(versionCmd.exec()).thenReturn(version);
    when(version.getVersion()).thenReturn("27.1.2");
    when(version.getApiVersion()).thenReturn("1.46");

    DockerGateway.DaemonInfo info = gateway.ping("unix:///sock");

    assertEquals("Docker 27.1.2", info.engine());
    assertEquals("1.46", info.apiVersion());
    assertTrue(info.latencyMs() >= 10,
        "latency must measure the ping round trip, was " + info.latencyMs() + "ms");
  }

  // ── stats ────────────────────────────────────────────────────────────────

  @Test
  void aStatsSampleIsMappedIntoTheDashboardReading() {
    stubStatsStream("cid", sample(
        cpuSample(1_500_000_000L, 11_000_000_000L, 1L),
        cpuSample(1_000_000_000L, 10_000_000_000L, 1L),
        memorySample(700 * MIB, 2 * GIB)));
    long before = System.currentTimeMillis();

    StatsDto dto = gateway.stats("unix:///sock", "cid");

    assertEquals(50.0, dto.cpuPercent(), 1e-9);
    assertEquals(700.0, dto.ramMb(), 0.001);
    assertEquals(2048.0, dto.ramTotalMb(), 0.001);
    assertTrue(dto.sampledAt() >= before, "the sample must carry a real wall-clock timestamp");
  }

  @Test
  void aStatsStreamThatEndsWithoutASampleIsReportedAsAnUpstreamOutage() {
    // no onNext at all: a truncated response, or a daemon restarting mid-request. Mapping
    // the null instead would surface a defect-shaped 500 every 3s, because the frontend
    // polls stats continuously.
    stubStatsStream("cid");

    UpstreamUnavailableException outage = assertThrows(UpstreamUnavailableException.class,
        () -> gateway.stats("unix:///sock", "cid"));

    assertTrue(outage.getMessage().contains("cid"),
        "the outage must name the container it polled, was: " + outage.getMessage());
  }

  // ── logs ─────────────────────────────────────────────────────────────────

  @Test
  void logLinesAreReturnedNewestRequestFirstAndParsedFromTheFrames() {
    // both frames arrive on stdout, whose default level is "info", so the "error" below
    // is genuinely read off the line rather than inferred from the stream
    stubLogStream("cid",
        frame(StreamType.STDOUT, "2026-08-14T10:00:00.000000000Z INFO gateway ready\n"),
        frame(StreamType.STDOUT, "2026-08-14T10:00:01.000000000Z ERROR registry unreachable\n"));

    List<LogLineDto> lines = gateway.logs("unix:///sock", "cid", 100);

    assertEquals(2, lines.size());
    assertEquals(List.of("INFO gateway ready", "ERROR registry unreachable"),
        lines.stream().map(LogLineDto::msg).toList());
    assertEquals(List.of("info", "error"), lines.stream().map(LogLineDto::level).toList());
    assertEquals(1786701600000L, lines.get(0).ts());
    assertEquals(1786701601000L, lines.get(1).ts());
  }

  @Test
  void theRequestedTailIsClampedToTheDocumentedRange() {
    LogContainerCmd logs = stubLogStream("cid");

    gateway.logs("unix:///sock", "cid", 0);
    gateway.logs("unix:///sock", "cid", 100);
    gateway.logs("unix:///sock", "cid", 9999);

    ArgumentCaptor<Integer> tail = ArgumentCaptor.forClass(Integer.class);
    verify(logs, times(3)).withTail(tail.capture());
    // the 500 ceiling is the documented API contract; asking the daemon for 9999 lines
    // blocks the request for as long as it takes to stream them
    assertEquals(List.of(1, 100, 500), tail.getAllValues());
  }

  @Test
  void theReturnedLogListIsImmutableSoALateFrameCannotMutateIt() {
    stubLogStream("cid", frame(StreamType.STDOUT, "2026-08-14T10:00:00.000000000Z INFO gateway ready\n"));

    List<LogLineDto> lines = gateway.logs("unix:///sock", "cid", 100);

    // close() does not join the reader thread, so it can still append after the 8s wait
    // expires. Handing back the live ArrayList would let it mutate mid-serialization.
    lastLogCallback.get().onNext(
        frame(StreamType.STDOUT, "2026-08-14T10:00:09.000000000Z INFO arrived too late\n"));
    assertEquals(1, lines.size());
    assertThrows(UnsupportedOperationException.class,
        () -> lines.add(new LogLineDto(0L, "info", "container", "injected")));
  }

  // ── lifecycle buttons ────────────────────────────────────────────────────

  @Test
  void startAndStopReachTheDaemonForTheRequestedContainer() {
    StartContainerCmd start = mock(StartContainerCmd.class);
    StopContainerCmd stop = mock(StopContainerCmd.class, Answers.RETURNS_SELF);
    when(client.startContainerCmd("cid")).thenReturn(start);
    when(client.stopContainerCmd("cid")).thenReturn(stop);

    gateway.start("unix:///sock", "cid");
    gateway.stop("unix:///sock", "cid");

    verify(start).exec();
    // an Agent gets 10s to flush its work and shut down cleanly before SIGKILL
    verify(stop).withTimeout(10);
    verify(stop).exec();
  }

  // ── configured image ─────────────────────────────────────────────────────

  @Test
  void theReportedRepositoryDropsATagOnTheConfiguredImage() {
    DockerGateway tagged = new DockerGateway(clients, new AppProperties(
        "live", "", "unix:///sock", "nousresearch/hermes-agent:v2026.7.7", "hermes", "test"), dockerExec);

    // the frontend compares this against ContainerDto.image, which is always bare; a
    // tagged value compares unequal and silently retires the update badge fleet-wide
    assertEquals("nousresearch/hermes-agent", tagged.hermesImageRepository());
  }

  @Test
  void anUnconfiguredHermesImageReportsAnEmptyRepository() {
    DockerGateway unset = new DockerGateway(clients,
        new AppProperties("live", "", "unix:///sock", null, "hermes", "test"), dockerExec);
    DockerGateway blank = new DockerGateway(clients,
        new AppProperties("live", "", "unix:///sock", "", "hermes", "test"), dockerExec);

    // '?' or 'null' would read as a repository name and match nothing the frontend holds
    assertEquals("", unset.hermesImageRepository());
    assertEquals("", blank.hermesImageRepository());
  }

  // ── stubs ────────────────────────────────────────────────────────────────

  /** Drives the gateway's own AsyncResultCallback: each sample, then completion. */
  private void stubStatsStream(String containerId, Statistics... samples) {
    StatsCmd stats = mock(StatsCmd.class, Answers.RETURNS_SELF);
    when(client.statsCmd(containerId)).thenReturn(stats);
    when(stats.exec(any())).thenAnswer(invocation -> {
      ResultCallback<Statistics> callback = invocation.getArgument(0);
      for (Statistics sample : samples) {
        callback.onNext(sample);
      }
      callback.onComplete();
      return callback;
    });
  }

  /** Drives the gateway's own Frame callback and keeps it reachable after logs() returns. */
  private LogContainerCmd stubLogStream(String containerId, Frame... frames) {
    LogContainerCmd logs = mock(LogContainerCmd.class, Answers.RETURNS_SELF);
    when(client.logContainerCmd(containerId)).thenReturn(logs);
    when(logs.exec(any())).thenAnswer(invocation -> {
      ResultCallback<Frame> callback = invocation.getArgument(0);
      lastLogCallback.set(callback);
      for (Frame frame : frames) {
        callback.onNext(frame);
      }
      callback.onComplete();
      return callback;
    });
    return logs;
  }

  private static Frame frame(StreamType stream, String payload) {
    return new Frame(stream, payload.getBytes(StandardCharsets.UTF_8));
  }

  private static Statistics sample(CpuStatsConfig cpu, CpuStatsConfig preCpu, MemoryStatsConfig memory) {
    Statistics stats = mock(Statistics.class);
    when(stats.getCpuStats()).thenReturn(cpu);
    when(stats.getPreCpuStats()).thenReturn(preCpu);
    when(stats.getMemoryStats()).thenReturn(memory);
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

  private static MemoryStatsConfig memorySample(long usage, long limit) {
    MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
    when(memory.getUsage()).thenReturn(usage);
    when(memory.getLimit()).thenReturn(limit);
    return memory;
  }
}
