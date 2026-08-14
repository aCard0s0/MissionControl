package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.hermes.missioncontrol.web.UpstreamUnavailableException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Bounded non-interactive {@code docker exec} with argument-safe failures. */
@Service
public class DockerExecService {

  public record ExecResult(int exitCode, String stdout, String stderr) {}

  /**
   * Stand-in status for an exec the daemon reported no exit code for. Non-zero, so the
   * unchecked callers that use an exit code as a boolean ({@code fileExists},
   * {@code dirExists}) read "no" rather than "yes" when the answer is unknown.
   */
  static final int EXIT_STATUS_UNAVAILABLE = -1;

  private final DockerClients clients;

  public DockerExecService(DockerClients clients) {
    this.clients = clients;
  }

  public ExecResult run(
      String url,
      String containerId,
      List<String> command,
      String operation,
      boolean check,
      boolean sensitive,
      Duration timeout) {
    return runAsUser(url, containerId, null, command, operation, check, sensitive, timeout);
  }

  /** Runs a bounded exec as a specific container user. A null/blank user keeps
   * Docker's default user; Hermes profile mutations pass {@code hermes} so
   * files remain readable by the supervised gateway processes. */
  public ExecResult runAsUser(
      String url,
      String containerId,
      String user,
      List<String> command,
      String operation,
      boolean check,
      boolean sensitive,
      Duration timeout) {
    DockerClient client = clients.forUrl(url);
    ExecCreateCmdResponse exec;
    try {
      var create = client.execCreateCmd(containerId)
          .withAttachStdout(true)
          .withAttachStderr(true)
          .withCmd(command.toArray(new String[0]));
      if (user != null && !user.isBlank()) create.withUser(user);
      exec = create.exec();
    } catch (RuntimeException e) {
      if (sensitive) throw new UpstreamUnavailableException(operation + " failed", e);
      throw e;
    }

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        if (frame.getPayload() == null) return;
        if (frame.getStreamType() == StreamType.STDERR) stderr.writeBytes(frame.getPayload());
        else stdout.writeBytes(frame.getPayload());
      }
    };

    boolean finished;
    try {
      finished = client.execStartCmd(exec.getId()).exec(callback)
          .awaitCompletion(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException(operation + " interrupted", e);
    } catch (RuntimeException e) {
      if (sensitive) throw new UpstreamUnavailableException(operation + " failed", e);
      throw e;
    } finally {
      try {
        callback.close();
      } catch (Exception ignored) { }
    }
    if (!finished) throw new UpstreamUnavailableException(operation + " timed out");

    Integer inspected;
    try {
      inspected = client.inspectExecCmd(exec.getId()).exec().getExitCode();
    } catch (RuntimeException e) {
      if (sensitive) throw new UpstreamUnavailableException(operation + " failed", e);
      throw e;
    }
    String out = stdout.toString(StandardCharsets.UTF_8);
    String err = stderr.toString(StandardCharsets.UTF_8);
    if (inspected == null) {
      // the daemon has no exit status for this exec — it never completed, or the
      // inspection raced it. Treating that as 0 tells every caller the command
      // succeeded, which for a config write or a profile delete means reporting a
      // mutation that may not have happened.
      if (check) throw new UpstreamUnavailableException(operation + " exit status unavailable");
      return new ExecResult(EXIT_STATUS_UNAVAILABLE, out, err);
    }
    int exitCode = inspected;
    if (check && exitCode != 0) {
      throw commandFailure(operation, exitCode, sensitive, out, err);
    }
    return new ExecResult(exitCode, out, err);
  }

  static RuntimeException commandFailure(
      String operation, int exitCode, boolean sensitive, String stdout, String stderr) {
    if (sensitive) return new RuntimeException(operation + " failed with exit code " + exitCode);
    String detail = stderr.trim().isEmpty() ? stdout.trim() : stderr.trim();
    if (detail.isEmpty()) detail = "exit code " + exitCode;
    if (detail.length() > 500) detail = detail.substring(0, 500);
    return new RuntimeException(operation + " failed: " + detail);
  }
}
