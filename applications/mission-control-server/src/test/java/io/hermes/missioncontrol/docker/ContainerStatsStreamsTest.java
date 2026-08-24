package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.Statistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

/**
 * The stats streams, driven by hand.
 *
 * <p>Every test here turns on the difference between a stream that is open and one that is
 * not, so each drives the callback the way the daemon would rather than asserting on a
 * returned value alone.
 */
class ContainerStatsStreamsTest {

  private static final DockerHostRef HOST = new DockerHostRef("local", "unix:///sock");

  /**
   * The test's "now".
   *
   * <p>Taken from the wall clock rather than made up, because a sample is stamped by
   * {@link ContainerStatsReader#toStats} at the moment it arrives — so a fabricated T0 would
   * make every delivered sample look decades stale and be withheld.
   */
  private final long t0 = System.currentTimeMillis();

  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  private final List<ResultCallback<Statistics>> opened = new ArrayList<>();

  private ContainerStatsStreams subject;

  @BeforeEach
  void setUp() {
    when(clients.streamingForUrl("unix:///sock")).thenReturn(client);
    StatsCmd cmd = mock(StatsCmd.class, Answers.RETURNS_SELF);
    when(client.statsCmd(anyString())).thenReturn(cmd);
    when(cmd.exec(any())).thenAnswer(invocation -> {
      ResultCallback<Statistics> callback = invocation.getArgument(0);
      opened.add(callback);
      return callback;
    });
    subject = new ContainerStatsStreams(clients);
  }

  @Test
  void aContainerWithNoSampleYetIsAbsentRatherThanZero() {
    // the first read lands before the daemon has sent anything; a card already showing a
    // figure must keep it rather than blink to 0%
    Map<String, StatsDto> samples = subject.samples(HOST, List.of("c-1"), t0);

    assertTrue(samples.isEmpty());
    assertEquals(1, subject.openStreamCount());
  }

  @Test
  void theNewestSampleTheStreamDeliveredIsWhatIsReported() {
    subject.samples(HOST, List.of("c-1"), t0);

    deliver(0, ram(256));
    deliver(0, ram(512));

    StatsDto sample = subject.samples(HOST, List.of("c-1"), t0).get("c-1");
    assertEquals(512, sample.ramMb(), 1e-9);
  }

  @Test
  void aSecondRequestReusesTheStreamRatherThanOpeningAnother() {
    subject.samples(HOST, List.of("c-1"), t0);
    subject.samples(HOST, List.of("c-1"), t0 + 3_000);
    subject.samples(HOST, List.of("c-1"), t0 + 6_000);

    // the whole point: one connection per container, not one per tick
    verify(client, times(1)).statsCmd("c-1");
    assertEquals(1, subject.openStreamCount());
  }

  @Test
  void oneRequestCoversEveryContainerItNames() {
    subject.samples(HOST, List.of("c-1", "c-2", "c-3"), t0);
    deliver(0, ram(1));
    deliver(2, ram(3));

    Map<String, StatsDto> samples = subject.samples(HOST, List.of("c-1", "c-2", "c-3"), t0);

    // c-2's stream is open but has not spoken yet, so it is simply missing
    assertEquals(List.of("c-1", "c-3"), List.copyOf(samples.keySet()));
    assertEquals(3, subject.openStreamCount());
  }

  @Test
  void aSampleTooOldToBelieveIsWithheld() {
    subject.samples(HOST, List.of("c-1"), t0);
    deliver(0, ram(256));

    // a stream delivers about one a second, so a sample this old means it wedged — reporting
    // it would draw the sparkline out of a number from another minute
    assertTrue(subject.samples(HOST, List.of("c-1"), t0 + 60_000).isEmpty());
  }

  @Test
  void aStreamTheDaemonEndedIsReopened() {
    subject.samples(HOST, List.of("c-1"), t0);
    opened.get(0).onComplete();

    subject.samples(HOST, List.of("c-1"), t0 + 3_000);

    // a container that stopped and came back keeps its id, and the ended stream is the only
    // sign of it — leaving it closed would strand that container without telemetry for good
    verify(client, times(2)).statsCmd("c-1");
    assertEquals(1, subject.openStreamCount());
  }

  @Test
  void aStreamThatFailedIsReopenedOnTheSameTerms() {
    subject.samples(HOST, List.of("c-1"), t0);
    opened.get(0).onError(new IllegalStateException("connection reset"));

    subject.samples(HOST, List.of("c-1"), t0 + 3_000);

    verify(client, times(2)).statsCmd("c-1");
  }

  @Test
  void aContainerThatCannotBeStreamedIsAbsentRatherThanAFailedRequest() {
    when(client.statsCmd("gone")).thenThrow(new NotFoundException("no such container"));

    Map<String, StatsDto> samples = subject.samples(HOST, List.of("gone", "c-1"), t0);

    // it stopped between the listing and this call — one container going away must not fail
    // the sample every other card on the page is waiting for
    assertFalse(samples.containsKey("gone"));
    assertEquals(1, subject.openStreamCount());
  }

  @Test
  void aStreamNobodyHasAskedAboutIsClosed() {
    subject.samples(HOST, List.of("c-1"), t0);

    subject.sweep(t0 + 31_000);

    // a dashboard nobody has open must cost nothing — the same rule the browser's own
    // visibility gating follows
    assertEquals(0, subject.openStreamCount());
  }

  @Test
  void aStreamStillBeingReadIsLeftAlone() {
    subject.samples(HOST, List.of("c-1"), t0);

    subject.sweep(t0 + 5_000);

    assertEquals(1, subject.openStreamCount());
  }

  @Test
  void aFinishedStreamIsSweptEvenWhileItIsStillBeingAskedAbout() {
    subject.samples(HOST, List.of("c-1"), t0);
    opened.get(0).onComplete();

    subject.sweep(t0);

    assertEquals(0, subject.openStreamCount());
  }

  @Test
  void shutdownClosesEveryStreamItStillHolds() {
    subject.samples(HOST, List.of("c-1", "c-2"), t0);

    subject.stopReaper();

    // the reader threads belong to the transport, and closing the callbacks is what ends them
    assertEquals(0, subject.openStreamCount());
  }

  @Test
  void theNumberOfHeldStreamsIsBounded() {
    List<String> many = new ArrayList<>();
    for (int i = 0; i < 130; i++) many.add("c-" + i);

    subject.samples(HOST, many, t0);

    // the request names the containers, so without this one caller asking about a large
    // daemon would decide how many connections and reader threads this process holds
    assertEquals(100, subject.openStreamCount());
  }

  /** Hands the stream opened Nth the sample the daemon would have pushed. */
  private void deliver(int streamIndex, Statistics stats) {
    opened.get(streamIndex).onNext(stats);
  }

  /** A sample carrying only memory — enough to tell two of them apart. */
  private static Statistics ram(long mb) {
    MemoryStatsConfig memory = mock(MemoryStatsConfig.class);
    when(memory.getUsage()).thenReturn(mb * 1_048_576L);
    Statistics stats = mock(Statistics.class);
    when(stats.getMemoryStats()).thenReturn(memory);
    return stats;
  }
}
