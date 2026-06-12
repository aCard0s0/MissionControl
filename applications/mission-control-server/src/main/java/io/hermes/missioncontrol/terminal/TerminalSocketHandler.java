package io.hermes.missioncontrol.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.hermes.missioncontrol.docker.DockerClients;
import io.hermes.missioncontrol.hosts.HostService;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * Bridges a browser xterm.js session to `docker exec` in a Hermes container.
 *
 * Protocol: binary frames carry raw terminal bytes both ways; text frames
 * carry JSON control messages from the client ({"type":"resize","cols":..,"rows":..}).
 */
@Component
public class TerminalSocketHandler extends AbstractWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(TerminalSocketHandler.class);
  private static final String SHELL =
      "command -v bash >/dev/null 2>&1 && exec bash -i || exec sh -i";

  private record Shell(DockerClient client, String execId, PipedOutputStream stdin,
                       ResultCallback.Adapter<Frame> output, WebSocketSession sender) {}

  private final DockerClients clients;
  private final HostService hosts;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, Shell> shells = new ConcurrentHashMap<>();

  public TerminalSocketHandler(DockerClients clients, HostService hosts) {
    this.clients = clients;
    this.hosts = hosts;
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
      url = hosts.urlOf(hostId);
    } catch (Exception e) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason("unknown host: " + hostId));
      return;
    }

    DockerClient client = clients.forUrl(url);
    ExecCreateCmdResponse exec = client.execCreateCmd(containerId)
        .withAttachStdin(true)
        .withAttachStdout(true)
        .withAttachStderr(true)
        .withTty(true)
        // plain sh, not a login shell — `sh -l` sources /etc/profile which
        // resets PATH and loses the image's /opt/hermes/bin entry
        .withCmd("sh", "-c", SHELL)
        .exec();

    PipedOutputStream stdin = new PipedOutputStream();
    PipedInputStream stdinSource = new PipedInputStream(stdin, 16 * 1024);
    // docker frames arrive on a transport thread; the decorator serializes sends
    WebSocketSession sender = new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);

    ResultCallback.Adapter<Frame> output = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        byte[] payload = frame.getPayload();
        if (payload == null) return;
        try {
          sender.sendMessage(new BinaryMessage(payload));
        } catch (IOException e) {
          try { sender.close(); } catch (IOException ignored) { }
        }
      }

      @Override
      public void onComplete() {
        closeQuietly(sender, CloseStatus.NORMAL.withReason("shell exited"));
      }

      @Override
      public void onError(Throwable t) {
        log.warn("terminal stream error for {}: {}", containerId, t.getMessage());
        closeQuietly(sender, CloseStatus.SERVER_ERROR.withReason("stream error"));
      }
    };

    shells.put(session.getId(), new Shell(client, exec.getId(), stdin, output, sender));
    client.execStartCmd(exec.getId()).withStdIn(stdinSource).exec(output);
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
    Shell shell = shells.get(session.getId());
    if (shell == null) return;
    byte[] bytes = new byte[message.getPayload().remaining()];
    message.getPayload().get(bytes);
    shell.stdin().write(bytes);
    shell.stdin().flush();
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    Shell shell = shells.get(session.getId());
    if (shell == null) return;
    try {
      JsonNode node = mapper.readTree(message.getPayload());
      if ("resize".equals(node.path("type").asText())) {
        int cols = node.path("cols").asInt(0);
        int rows = node.path("rows").asInt(0);
        if (cols > 0 && rows > 0) {
          shell.client().resizeExecCmd(shell.execId()).withSize(rows, cols).exec();
        }
      }
    } catch (Exception e) {
      log.debug("ignoring malformed terminal control message: {}", e.getMessage());
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Shell shell = shells.remove(session.getId());
    if (shell == null) return;
    // EOF on stdin makes the shell exit, which ends the exec on the daemon side
    try { shell.stdin().close(); } catch (IOException ignored) { }
    try { shell.output().close(); } catch (IOException ignored) { }
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
