package io.hermes.missioncontrol.terminal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.ResizeExecCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.hermes.missioncontrol.docker.DockerClients;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.hosts.HostService;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The admission, teardown and reaper wiring of the web terminal.
 *
 * <p>Every session holds a live {@code docker exec} attach plus two JVM-side threads, so the
 * cap slot has to come back on every exit path — a refused handshake, a failed setup, a shell
 * that exits, a socket that dies half-open, a reap, a shutdown. A slot leaked here is not an
 * exception anywhere: the terminal simply stops being available until the process restarts.
 * {@link TerminalSessionLimiter} and {@link TerminalSocketHandler#reapReason} are tested on
 * their own; this covers the handler that has to use them correctly.
 */
class TerminalSocketHandlerTest {

  private static final String HOST = "unix:///sock";
  private static final String EXEC_ID = "exec-1";

  private DockerClients clients;
  private DockerClient client;
  private HostService hosts;
  private ExecCreateCmd create;
  private ExecStartCmd start;
  private ResizeExecCmd resize;

  /** The callback docker-java would drive from its transport thread. */
  private ResultCallback.Adapter<Frame> attached;
  /** The pipe the handler hands to the exec as stdin. */
  private InputStream execStdin;

  @BeforeEach
  void setUp() {
    clients = mock(DockerClients.class);
    client = mock(DockerClient.class);
    hosts = mock(HostService.class);
    create = mock(ExecCreateCmd.class, Answers.RETURNS_SELF);
    start = mock(ExecStartCmd.class);
    resize = mock(ResizeExecCmd.class, Answers.RETURNS_SELF);
    ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);

    when(hosts.requireConnected("dh-local")).thenReturn(new DockerHostRef("dh-local", HOST));
    when(clients.streamingForUrl(HOST)).thenReturn(client);
    when(client.execCreateCmd(anyString())).thenReturn(create);
    when(create.exec()).thenReturn(created);
    when(created.getId()).thenReturn(EXEC_ID);
    when(client.execStartCmd(EXEC_ID)).thenReturn(start);
    when(client.resizeExecCmd(EXEC_ID)).thenReturn(resize);
    when(start.withStdIn(any())).thenAnswer(invocation -> {
      execStdin = invocation.getArgument(0);
      return start;
    });
    when(start.exec(any())).thenAnswer(invocation -> {
      attached = invocation.getArgument(0);
      return attached;
    });
  }

  // ── handshake validation ────────────────────────────────────────────────

  @Test
  void aHandshakeMissingEitherParameterIsRefusedWithoutTouchingDocker() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));

    WebSocketSession noContainer = session("a", "10.0.0.1", "hostId=dh-local");
    WebSocketSession blankHost = session("b", "10.0.0.1", "hostId=%20&containerId=cid");
    WebSocketSession noQuery = session("c", "10.0.0.1", null);

    handler.afterConnectionEstablished(noContainer);
    handler.afterConnectionEstablished(blankHost);
    handler.afterConnectionEstablished(noQuery);

    for (WebSocketSession refused : List.of(noContainer, blankHost, noQuery)) {
      verify(refused).close(CloseStatus.POLICY_VIOLATION.withReason("hostId and containerId required"));
    }
    verify(client, never()).execCreateCmd(anyString());
  }

  @Test
  void anUnknownHostIsRefusedRatherThanPropagatingTheLookupFailure() throws Exception {
    when(hosts.requireConnected("dh-gone")).thenThrow(new NoSuchElementException("no such docker host"));
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession session = session("a", "10.0.0.1", "hostId=dh-gone&containerId=cid");

    handler.afterConnectionEstablished(session);

    verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("unknown host: dh-gone"));
    verify(client, never()).execCreateCmd(anyString());
  }

  @Test
  void theQueryStringIsUrlDecodedAndMalformedPairsAreIgnored() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));

    handler.afterConnectionEstablished(session("a", "10.0.0.1",
        "flagOnly&=novalue&hostId=dh%2Dlocal&containerId=my%20container&empty="));

    // 'flagOnly' (no '=') and '=novalue' (empty name) are dropped rather than mistaken for input
    verify(client).execCreateCmd("my container");
  }

  // ── admission caps ──────────────────────────────────────────────────────

  @Test
  void theGlobalCapRefusesTheNextSession() throws Exception {
    TerminalSocketHandler handler = handler(props(1, 5));

    handler.afterConnectionEstablished(session("a", "10.0.0.1", QUERY));
    WebSocketSession refused = session("b", "10.0.0.2", QUERY);
    handler.afterConnectionEstablished(refused);

    verify(refused).close(CloseStatus.SERVICE_OVERLOAD.withReason("terminal session limit reached"));
    assertEquals(1, execStarts());
  }

  @Test
  void thePerClientCapRefusesTheNextSessionAndHandsTheGlobalSlotBack() throws Exception {
    // the interesting part is the back-out: the global slot is reserved before the per-client
    // check, so failing that check has to release it or the cap bleeds away one slot per refusal
    TerminalSocketHandler handler = handler(props(2, 1));

    handler.afterConnectionEstablished(session("a", "10.0.0.1", QUERY));
    WebSocketSession refused = session("b", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(refused);
    handler.afterConnectionEstablished(session("c", "10.0.0.2", QUERY));
    handler.afterConnectionEstablished(session("d", "10.0.0.3", QUERY));

    verify(refused).close(CloseStatus.SERVICE_OVERLOAD.withReason("per-client terminal limit reached"));
    // a and c hold the two global slots; d is over the global cap, not the per-client one
    assertEquals(2, execStarts());
  }

  @Test
  void sessionsWithNoRemoteAddressShareTheUnknownClientBucket() throws Exception {
    // getRemoteAddress() is null behind some proxies; those sessions must still be capped
    // together rather than each getting its own unbounded bucket
    TerminalSocketHandler handler = handler(props(5, 1));

    handler.afterConnectionEstablished(session("a", null, QUERY));
    WebSocketSession refused = session("b", null, QUERY);
    handler.afterConnectionEstablished(refused);

    verify(refused).close(CloseStatus.SERVICE_OVERLOAD.withReason("per-client terminal limit reached"));
  }

  @Test
  void closingASessionReturnsItsSlotAndTeardownIsIdempotent() throws Exception {
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession first = session("a", "10.0.0.1", QUERY);

    handler.afterConnectionEstablished(first);
    handler.afterConnectionClosed(first, CloseStatus.NORMAL);
    // a second close for the same session must not credit a second slot
    handler.afterConnectionClosed(first, CloseStatus.NORMAL);

    handler.afterConnectionEstablished(session("b", "10.0.0.2", QUERY));
    WebSocketSession refused = session("c", "10.0.0.3", QUERY);
    handler.afterConnectionEstablished(refused);

    verify(refused).close(CloseStatus.SERVICE_OVERLOAD.withReason("terminal session limit reached"));
    assertEquals(2, execStarts());
  }

  // ── setup failures ──────────────────────────────────────────────────────

  @Test
  void aFailureBeforeTheShellExistsReleasesTheSlotDirectly() throws Exception {
    // exec create is the first daemon call: no Shell has been registered, so teardown cannot
    // be the thing that frees the slot
    ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
    when(created.getId()).thenReturn(EXEC_ID);
    when(create.exec()).thenThrow(new RuntimeException("container is not running")).thenReturn(created);
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession failed = session("a", "10.0.0.1", QUERY);

    handler.afterConnectionEstablished(failed);

    verify(failed).close(CloseStatus.SERVER_ERROR.withReason("setup failed"));
    // the slot came back, so the next handshake is admitted
    handler.afterConnectionEstablished(session("b", "10.0.0.2", QUERY));
    assertEquals(1, execStarts());
  }

  @Test
  void aFailureAfterTheShellIsRegisteredTearsItDownAndReleasesTheSlot() throws Exception {
    when(start.exec(any())).thenThrow(new RuntimeException("attach refused"));
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession failed = session("a", "10.0.0.1", QUERY);

    handler.afterConnectionEstablished(failed);

    // teardown closes the decorated session and the catch block closes the raw one again; the
    // second close is a no-op on a real socket, so only 'at least once' is meaningful here
    verify(failed, atLeastOnce()).close(CloseStatus.SERVER_ERROR.withReason("setup failed"));
    // the registered shell was removed, so a later frame for it is a no-op rather than an NPE
    assertDoesNotThrow(() -> handler.handleBinaryMessage(failed, new BinaryMessage(new byte[] {1})));
    // and the slot is free again: the next handshake is admitted as far as the attach (which
    // this test keeps failing) rather than being refused by the cap
    WebSocketSession next = session("b", "10.0.0.2", QUERY);
    handler.afterConnectionEstablished(next);
    verify(next, never()).close(CloseStatus.SERVICE_OVERLOAD.withReason("terminal session limit reached"));
    assertEquals(2, execStarts());
  }

  // ── client → shell ──────────────────────────────────────────────────────

  @Test
  void keystrokesAreWrittenToTheExecStdin() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);
    assertNotNull(execStdin, "the exec must be started with the handler's stdin pipe");

    handler.handleBinaryMessage(session, new BinaryMessage(ByteBuffer.wrap("ls -la\n".getBytes(StandardCharsets.UTF_8))));

    byte[] read = new byte[7];
    assertEquals(7, execStdin.read(read));
    assertArrayEquals("ls -la\n".getBytes(StandardCharsets.UTF_8), read);
  }

  @Test
  void framesForAnUnknownSessionAreIgnored() {
    // a frame can arrive after teardown, or for a handshake that was refused
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession stranger = session("z", "10.0.0.9", QUERY);

    assertDoesNotThrow(() -> {
      handler.handleBinaryMessage(stranger, new BinaryMessage(new byte[] {1}));
      handler.handleTextMessage(stranger, new TextMessage("{\"type\":\"resize\",\"cols\":80,\"rows\":24}"));
      handler.handlePongMessage(stranger, new PongMessage());
      handler.afterConnectionClosed(stranger, CloseStatus.NORMAL);
    });
    verify(client, never()).resizeExecCmd(anyString());
  }

  @Test
  void aResizeControlMessageResizesTheExec() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));

    // docker takes rows first, xterm.js reports cols first — the order is the whole point
    verify(resize).withSize(40, 120);
    verify(resize).exec();
  }

  @Test
  void malformedZeroSizedAndUnknownControlMessagesAreIgnored() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    handler.handleTextMessage(session, new TextMessage("not json at all"));
    handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
    handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":0,\"rows\":24}"));
    handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":80,\"rows\":0}"));

    verify(resize, never()).exec();
  }

  // ── shell → client ──────────────────────────────────────────────────────

  @Test
  void outputFramesAreForwardedAsBinaryAndEmptyFramesAreDropped() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    attached.onNext(new Frame(StreamType.RAW, "hello".getBytes(StandardCharsets.UTF_8)));
    attached.onNext(new Frame(StreamType.RAW, null));

    verify(session).sendMessage(new BinaryMessage("hello".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aFailedSendTearsTheSessionDownSoTheExecThreadsStop() throws Exception {
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);
    doThrow(new IOException("broken pipe")).when(session).sendMessage(any(BinaryMessage.class));

    attached.onNext(new Frame(StreamType.RAW, "hello".getBytes(StandardCharsets.UTF_8)));

    verify(session).close(CloseStatus.SERVER_ERROR.withReason("send failed"));
    assertSlotWasReturned(handler);
  }

  @Test
  void theShellExitingClosesTheBrowserSessionNormally() throws Exception {
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    attached.onComplete();

    verify(session).close(CloseStatus.NORMAL.withReason("shell exited"));
    assertSlotWasReturned(handler);
  }

  @Test
  void aStreamErrorClosesTheBrowserSessionAsAServerError() throws Exception {
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    attached.onError(new IllegalStateException("attach dropped"));

    verify(session).close(CloseStatus.SERVER_ERROR.withReason("stream error"));
    assertSlotWasReturned(handler);
  }

  // ── the reaper ──────────────────────────────────────────────────────────

  @Test
  void aSweepPingsEveryLiveSession() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    handler.sweep();

    verify(session).sendMessage(any(PingMessage.class));
    verify(session, never()).close(any());
  }

  @Test
  void aSweepReapsAStaleSessionAndReturnsItsSlot() throws Exception {
    // a negative lifetime ceiling makes every session instantly over-lifetime, so the reap is
    // deterministic without sleeping through a real timeout
    TerminalSocketHandler handler = handler(new TerminalProperties(
        1, 5, Duration.ofMinutes(30), Duration.ofMillis(-1), Duration.ofSeconds(30),
        Duration.ofSeconds(90), "hermes"));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    handler.sweep();

    verify(session).close(CloseStatus.GOING_AWAY.withReason("terminal max-lifetime"));
    verify(session, never()).sendMessage(any(PingMessage.class));
    assertSlotWasReturned(handler);
  }

  @Test
  void aSessionWhosePingThrowsIsReapedBecauseTheSocketIsGone() throws Exception {
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);
    doThrow(new IOException("connection reset")).when(session).sendMessage(any(PingMessage.class));

    handler.sweep();

    verify(session).close(CloseStatus.GOING_AWAY.withReason("terminal ping-failed"));
    assertSlotWasReturned(handler);
  }

  @Test
  void shutdownTearsDownEveryLiveSession() throws Exception {
    TerminalSocketHandler handler = handler(props(5, 5));
    WebSocketSession first = session("a", "10.0.0.1", QUERY);
    WebSocketSession second = session("b", "10.0.0.2", QUERY);
    handler.afterConnectionEstablished(first);
    handler.afterConnectionEstablished(second);

    handler.startReaper();
    handler.stopReaper();

    verify(first).close(CloseStatus.GOING_AWAY.withReason("server shutdown"));
    verify(second).close(CloseStatus.GOING_AWAY.withReason("server shutdown"));
  }

  @Test
  void theShellRunsAsTheConfiguredUserSoItCannotLeaveRootOwnedFilesBehind() throws Exception {
    // every profile-scoped exec goes through DockerExecService as `hermes`; a web shell running
    // as the image default would write /opt/data files the agent itself can no longer read
    TerminalSocketHandler handler = handler(props(5, 5));

    handler.afterConnectionEstablished(session("a", "10.0.0.1", QUERY));

    verify(create).withUser("hermes");
  }

  @Test
  void aBlankUserKeepsTheImageDefaultForAnImageWithoutThatAccount() throws Exception {
    TerminalSocketHandler handler = handler(new TerminalProperties(
        5, 5, Duration.ofMinutes(30), Duration.ofHours(8), Duration.ofSeconds(30),
        Duration.ofSeconds(90), "   "));

    handler.afterConnectionEstablished(session("a", "10.0.0.1", QUERY));

    verify(create, never()).withUser(anyString());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static final String QUERY = "hostId=dh-local&containerId=cid";

  private TerminalSocketHandler handler(TerminalProperties props) {
    return new TerminalSocketHandler(clients, hosts, props);
  }

  private static TerminalProperties props(int maxSessions, int maxPerClient) {
    return new TerminalProperties(maxSessions, maxPerClient,
        Duration.ofMinutes(30), Duration.ofHours(8), Duration.ofSeconds(30), Duration.ofSeconds(90),
        "hermes");
  }

  private WebSocketSession session(String id, String remoteAddress, String query) {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    when(session.isOpen()).thenReturn(true);
    when(session.getUri()).thenReturn(URI.create("ws://mc.test/api/terminal" + (query == null ? "" : "?" + query)));
    when(session.getRemoteAddress()).thenReturn(
        remoteAddress == null ? null : new InetSocketAddress(remoteAddress, 54_321));
    return session;
  }

  /** How many sessions actually reached the exec attach — i.e. were admitted. */
  private int execStarts() {
    return (int) mockingDetails(start).getInvocations().stream()
        .filter(invocation -> "exec".equals(invocation.getMethod().getName()))
        .count();
  }

  /**
   * Proves the freed slot is usable again rather than merely decremented: with a global cap of
   * one, a fresh handshake can only be admitted if the previous session gave its slot back.
   */
  private void assertSlotWasReturned(TerminalSocketHandler handler) throws Exception {
    WebSocketSession next = session("next", "10.0.0.250", QUERY);
    handler.afterConnectionEstablished(next);
    verify(next, never()).close(any());
  }

  @Test
  void aCloseWithNoStatusStillTearsTheSessionDownNormally() throws Exception {
    // Spring passes null when the transport dropped without a close frame
    TerminalSocketHandler handler = handler(props(1, 5));
    WebSocketSession session = session("a", "10.0.0.1", QUERY);
    handler.afterConnectionEstablished(session);

    handler.afterConnectionClosed(session, null);

    verify(session).close(CloseStatus.NORMAL);
    assertSlotWasReturned(handler);
  }
}
