package io.hermes.missioncontrol.terminal;

import static io.hermes.missioncontrol.docker.ContainerIds.shortId;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Frame;
import io.hermes.missioncontrol.docker.DockerClients;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Bridges a browser xterm.js session to `docker exec` in a Hermes container.
 *
 * <p>Protocol: binary frames carry raw terminal bytes both ways; text frames
 * carry JSON control messages from the client ({"type":"resize","cols":..,"rows":..}).
 *
 * <p>Each session is a live docker-exec stream that holds two JVM-side daemon
 * threads (a frame reader and a stdin writer). If a session is abandoned — a
 * background tab, a closed browser, a half-open TCP connection — those threads
 * can spin or pile up and peg the CPU. Three mechanisms keep that bounded:
 * <ol>
 *   <li><b>Deterministic teardown</b> ({@link #teardown}) stops both threads and
 *       releases the cap slot. Every close path routes through {@code shells.remove}
 *       so exactly one caller runs it, made idempotent by a per-shell CAS guard.</li>
 *   <li><b>Heartbeat + reaper</b> ({@link #sweep}) pings each session and reaps
 *       idle, over-lifetime, or unresponsive (dead-browser) sessions.</li>
 *   <li><b>Concurrency caps</b> — a global and per-client ceiling on live sessions.</li>
 * </ol>
 * All limits come from {@link TerminalProperties} ({@code mc.terminal.*}).
 */
@Component
public class TerminalSocketHandler extends AbstractWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(TerminalSocketHandler.class);
  private static final String SHELL =
      "command -v bash >/dev/null 2>&1 && exec bash -i || exec sh -i";

  /**
   * One live docker-exec shell. A mutable holder (not a record) so the reaper can
   * read liveness timestamps and teardown can be guarded by {@link #torndown}.
   */
  private static final class Shell {
    final DockerClient client;
    final String execId;
    final String containerId;
    final PipedOutputStream stdin;
    final ResultCallback.Adapter<Frame> output;
    final WebSocketSession sender;   // ConcurrentWebSocketSessionDecorator — serializes sends
    final WebSocketSession raw;      // undecorated session — id + remote address
    final String clientKey;          // remote address, for the per-client cap
    final long createdAtMs;
    volatile long lastActivityMs;    // bumped on any inbound frame (keystroke/resize)
    volatile long lastPongMs;        // bumped on pong — the half-open-TCP detector
    final AtomicBoolean torndown = new AtomicBoolean(false);

    Shell(DockerClient client, String execId, String containerId, PipedOutputStream stdin,
          ResultCallback.Adapter<Frame> output, WebSocketSession sender, WebSocketSession raw,
          String clientKey, long now) {
      this.client = client;
      this.execId = execId;
      this.containerId = containerId;
      this.stdin = stdin;
      this.output = output;
      this.sender = sender;
      this.raw = raw;
      this.clientKey = clientKey;
      this.createdAtMs = now;
      this.lastActivityMs = now;
      this.lastPongMs = now;
    }
  }

  private final DockerClients clients;
  private final HostService hosts;
  private final TerminalProperties props;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, Shell> shells = new ConcurrentHashMap<>();

  /** Both concurrency caps, reserved and released as one — see {@link TerminalSessionLimiter}. */
  private final TerminalSessionLimiter slots;

  // idle/heartbeat reaper — single daemon thread, mirrors ModelProviderService's executor pattern
  private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "terminal-reaper");
    t.setDaemon(true);
    return t;
  });

  public TerminalSocketHandler(DockerClients clients, HostService hosts, TerminalProperties props) {
    this.clients = clients;
    this.hosts = hosts;
    this.props = props;
    this.slots = new TerminalSessionLimiter(props.maxSessions(), props.maxSessionsPerClient());
  }

  @PostConstruct
  void startReaper() {
    long tick = Math.max(1_000, props.heartbeatInterval().toMillis());
    reaper.scheduleAtFixedRate(this::sweep, tick, tick, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stopReaper() {
    reaper.shutdownNow();
    // tear down any still-open shells so their docker-exec threads don't outlive the app
    for (String id : shells.keySet()) {
      teardown(shells.remove(id), CloseStatus.GOING_AWAY.withReason("server shutdown"), "shutdown");
    }
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Map<String, String> query = queryParams(session);
    String hostId = query.get("hostId");
    String containerId = query.get("containerId");
    if (hostId == null || hostId.isBlank() || containerId == null || containerId.isBlank()) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason("hostId and containerId required"));
      return;
    }

    String url;
    try {
      url = hosts.requireConnected(hostId).url();
    } catch (Exception e) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason("unknown host: " + hostId));
      return;
    }

    String clientKey = remoteKey(session);
    switch (slots.tryAcquire(clientKey)) {
      case GLOBAL_CAP -> {
        log.warn("terminal session rejected — global cap {} reached", props.maxSessions());
        session.close(CloseStatus.SERVICE_OVERLOAD.withReason("terminal session limit reached"));
        return;
      }
      case PER_CLIENT_CAP -> {
        log.warn("terminal session rejected — per-client cap {} reached for {}",
            props.maxSessionsPerClient(), clientKey);
        session.close(CloseStatus.SERVICE_OVERLOAD.withReason("per-client terminal limit reached"));
        return;
      }
      case ADMITTED -> { /* reserved — every exit path below releases it */ }
    }

    Shell shell = null;
    try {
      // an attached TTY is silent whenever nobody is typing; a socket timeout on this client
      // would close an idle session long before TerminalProperties' own idle reaper does
      DockerClient client = clients.streamingForUrl(url);
      ExecCreateCmd create = client.execCreateCmd(containerId)
          .withAttachStdin(true)
          .withAttachStdout(true)
          .withAttachStderr(true)
          .withTty(true)
          // plain sh, not a login shell — `sh -l` sources /etc/profile which
          // resets PATH and loses the image's /opt/hermes/bin entry
          .withCmd("sh", "-c", SHELL);
      // the same user every profile-scoped exec runs as (mc.terminal.user, `hermes` by
      // default): a root shell writing into /opt/data leaves files the agent cannot read
      if (props.user() != null && !props.user().isBlank()) create.withUser(props.user());
      ExecCreateCmdResponse exec = create.exec();

      PipedOutputStream stdin = new PipedOutputStream();
      PipedInputStream stdinSource = new PipedInputStream(stdin, 16 * 1024);
      // docker frames arrive on a transport thread; the decorator serializes sends
      WebSocketSession sender = new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);
      long now = System.currentTimeMillis();

      ResultCallback.Adapter<Frame> output = new ResultCallback.Adapter<>() {
        @Override
        public void onNext(Frame frame) {
          byte[] payload = frame.getPayload();
          if (payload == null) return;
          try {
            sender.sendMessage(new BinaryMessage(payload));
          } catch (IOException e) {
            // browser gone — full teardown so the exec threads stop and the slot frees
            teardown(shells.remove(session.getId()),
                CloseStatus.SERVER_ERROR.withReason("send failed"), "send-failed");
          }
        }

        @Override
        public void onComplete() {
          teardown(shells.remove(session.getId()),
              CloseStatus.NORMAL.withReason("shell exited"), "shell-exited");
        }

        @Override
        public void onError(Throwable t) {
          log.warn("terminal stream error for {}: {}", shortId(containerId), brief(t.getMessage(), Integer.MAX_VALUE, "no detail"));
          teardown(shells.remove(session.getId()),
              CloseStatus.SERVER_ERROR.withReason("stream error"), "stream-error");
        }
      };

      shell = new Shell(client, exec.getId(), containerId, stdin, output, sender, session, clientKey, now);
      shells.put(session.getId(), shell);   // register before start so an early frame finds the holder
      client.execStartCmd(exec.getId()).withStdIn(stdinSource).exec(output);
    } catch (Exception e) {
      // A stopped container is the operator clicking a terminal on a card that is not
      // running — the browser is told, and there is nothing for anyone to act on here.
      // It was previously a WARN carrying the 64-character id twice, once in the text and
      // again inside the daemon's raw JSON.
      if (e instanceof ConflictException) {
        log.debug("terminal refused for {}: container is not running", shortId(containerId));
      } else {
        log.warn("terminal setup failed for {}: {}", shortId(containerId), brief(e.getMessage(), Integer.MAX_VALUE, "no detail"));
      }
      if (shell != null) {
        teardown(shells.remove(session.getId()),
            CloseStatus.SERVER_ERROR.withReason("setup failed"), "setup-failure");
      } else {
        slots.release(clientKey);   // shell never built — release the reserved slot directly
      }
      closeQuietly(session, CloseStatus.SERVER_ERROR.withReason("setup failed"));
    }
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
    Shell shell = shells.get(session.getId());
    if (shell == null) return;
    shell.lastActivityMs = System.currentTimeMillis();
    byte[] bytes = new byte[message.getPayload().remaining()];
    message.getPayload().get(bytes);
    shell.stdin.write(bytes);
    shell.stdin.flush();
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    Shell shell = shells.get(session.getId());
    if (shell == null) return;
    shell.lastActivityMs = System.currentTimeMillis();
    try {
      JsonNode node = mapper.readTree(message.getPayload());
      if ("resize".equals(node.path("type").asText())) {
        int cols = node.path("cols").asInt(0);
        int rows = node.path("rows").asInt(0);
        if (cols > 0 && rows > 0) {
          shell.client.resizeExecCmd(shell.execId).withSize(rows, cols).exec();
        }
      }
    } catch (Exception e) {
      log.debug("ignoring malformed terminal control message: {}", e.getMessage());
    }
  }

  @Override
  protected void handlePongMessage(WebSocketSession session, PongMessage message) {
    Shell shell = shells.get(session.getId());
    if (shell != null) shell.lastPongMs = System.currentTimeMillis();
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    teardown(shells.remove(session.getId()), status, "ws-closed");
  }

  /**
   * Reaper tick: ping each live session and reap the stale ones. Package-private so a test can
   * drive one tick directly: on the scheduler this only runs once per heartbeat interval, and
   * the leak it exists to prevent is invisible until it has.
   */
  void sweep() {
    long now = System.currentTimeMillis();
    for (Shell shell : shells.values()) {   // ConcurrentHashMap iterator is weakly consistent — safe
      try {
        String reason = reapReason(now, shell.createdAtMs, shell.lastActivityMs, shell.lastPongMs, props);
        if (reason != null) { reap(shell, reason); continue; }
        shell.sender.sendMessage(new PingMessage());   // serialized by the decorator
      } catch (Exception e) {
        reap(shell, "ping-failed");   // send threw → socket is gone
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("terminal sweep: {} live session(s), {} slot(s) free",
          shells.size(), slots.available());
    }
  }

  /**
   * Why this session should be reaped, or null to keep it. Pulled out of {@link #sweep} so
   * the policy can be checked without a scheduler, a live clock and a websocket: an
   * off-by-one on any of these bounds leaks the exec threads the limits exist to contain.
   *
   * <p>Order matters — the ceiling wins over idleness, and idleness over a missing pong —
   * so the close reason the client sees names the actual cause. {@code "ping-failed"} is
   * not decided here; it is only known once the send throws.
   */
  static String reapReason(
      long now, long createdAtMs, long lastActivityMs, long lastPongMs, TerminalProperties props) {
    if (now - createdAtMs > props.sessionMaxLifetime().toMillis()) return "max-lifetime";
    if (now - lastActivityMs > props.idleTimeout().toMillis()) return "idle";
    if (now - lastPongMs > props.pongTimeout().toMillis()) return "pong-timeout";
    return null;
  }

  private void reap(Shell shell, String reason) {
    teardown(shells.remove(shell.raw.getId()),
        CloseStatus.GOING_AWAY.withReason("terminal " + reason), reason);
  }

  /**
   * Idempotent teardown. Every path first calls {@code shells.remove(id)}, so only one caller gets
   * the non-null Shell; the {@link Shell#torndown} CAS guards the body against a double-pass and so
   * the cap slot is released exactly once. Steps 2–4 are what actually stop the JVM-side exec
   * threads (the CPU-runaway source); step 1 is a best-effort nudge for the in-container shell.
   */
  private void teardown(Shell shell, CloseStatus status, String reason) {
    if (shell == null || !shell.torndown.compareAndSet(false, true)) return;
    // 1. best-effort: ask the in-container shell to exit while stdin is still open. With a TTY,
    //    closing the pipe is not a reliable EOF, so send `exit` + Ctrl-D (EOT). We cannot kill the
    //    exec process directly: docker-java has no stop-exec API and inspectExec's PID is the
    //    daemon-host PID, not the in-container PID, so a `docker exec … kill` would hit the wrong
    //    process namespace. This nudge reaps the common case (shell idle at a prompt).
    try {
      shell.stdin.write("exit\n".getBytes(StandardCharsets.US_ASCII));
      shell.stdin.write(4);   // EOT
      shell.stdin.flush();
    } catch (IOException ignored) { /* pipe may already be gone */ }
    // 2. abort the exec attach — closes the response stream, ending the frame-reader thread
    try { shell.output.close(); } catch (IOException ignored) { }
    // 3. close stdin — EOF to the PipedInputStream, ending the stdin-writer thread
    try { shell.stdin.close(); } catch (IOException ignored) { }
    // 4. close the websocket if still open
    closeQuietly(shell.sender, status == null ? CloseStatus.NORMAL : status);
    // 5. release the concurrency slot (exactly once, inside the CAS guard)
    slots.release(shell.clientKey);
    log.debug("terminal teardown {} ({})", shell.execId, reason);
  }



  private static String remoteKey(WebSocketSession session) {
    InetSocketAddress addr = session.getRemoteAddress();
    if (addr == null || addr.getAddress() == null) return "unknown";
    return addr.getAddress().getHostAddress();
  }

  private static void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      if (session.isOpen()) session.close(status);
    } catch (IOException ignored) { }
  }

  private static Map<String, String> queryParams(WebSocketSession session) {
    Map<String, String> out = new HashMap<>();
    String query = session.getUri() == null ? null : session.getUri().getRawQuery();
    if (query == null) return out;
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      out.put(
          URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }
}
