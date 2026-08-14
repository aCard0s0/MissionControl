package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import io.hermes.missioncontrol.web.UpstreamUnavailableException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class DockerExecServiceTest {

  @Test
  void sensitiveFailureNeverContainsCommandOutput() {
    RuntimeException failure = DockerExecService.commandFailure(
        "write profile environment", 1, true, "", "failed for sk-ant-secret-value");

    assertTrue(failure.getMessage().contains("write profile environment"));
    assertFalse(failure.getMessage().contains("sk-ant-secret-value"));
  }

  @Test
  void explicitHermesUserIsAppliedToDockerExec() {
    DockerClients clients = stubbedClients(0);

    new DockerExecService(clients).runAsUser(
        "unix:///sock", "cid", "hermes", List.of("true"), "test",
        true, false, Duration.ofSeconds(1));

    verify(create).withUser("hermes");
  }

  @Test
  void anUnknownExitCodeIsNotReportedAsSuccess() {
    // the daemon has no exit status: the exec never completed, or the inspection raced it.
    // Reporting 0 tells HermesProfiles that a config write or a profile delete succeeded
    // when nothing confirms it did.
    DockerClients clients = stubbedClients(null);

    assertThrows(UpstreamUnavailableException.class, () -> new DockerExecService(clients).runAsUser(
        "unix:///sock", "cid", "hermes", List.of("hermes", "profile", "delete"), "delete profile",
        true, false, Duration.ofSeconds(1)));
  }

  @Test
  void anUncheckedExecWithNoExitStatusReportsFailureRatherThanZero() {
    // fileExists/dirExists read the exit code as a boolean and pass check=false; an
    // unknown status must read as "no", not as "yes"
    DockerClients clients = stubbedClients(null);

    DockerExecService.ExecResult result = new DockerExecService(clients).runAsUser(
        "unix:///sock", "cid", "hermes", List.of("test", "-f", "/x"), "check file",
        false, false, Duration.ofSeconds(1));

    assertNotEquals(0, result.exitCode());
  }

  @Test
  void aNonZeroExitCodeIsReturnedRatherThanThrownWhenCheckIsFalse() {
    DockerClients clients = stubbedClients(1);

    DockerExecService.ExecResult result = new DockerExecService(clients).runAsUser(
        "unix:///sock", "cid", "hermes", List.of("test", "-f", "/absent"), "check file",
        false, false, Duration.ofSeconds(1));

    assertEquals(1, result.exitCode());
  }

  private ExecCreateCmd create;

  /** The whole docker-java exec chain, answering {@code exitCode} from the inspection. */
  private DockerClients stubbedClients(Integer exitCode) {
    DockerClients clients = mock(DockerClients.class);
    DockerClient client = mock(DockerClient.class);
    create = mock(ExecCreateCmd.class, Answers.RETURNS_SELF);
    ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
    ExecStartCmd start = mock(ExecStartCmd.class);
    InspectExecCmd inspect = mock(InspectExecCmd.class);
    InspectExecResponse inspected = mock(InspectExecResponse.class);
    when(clients.forUrl("unix:///sock")).thenReturn(client);
    when(client.execCreateCmd("cid")).thenReturn(create);
    when(create.exec()).thenReturn(created);
    when(created.getId()).thenReturn("exec-id");
    when(client.execStartCmd("exec-id")).thenReturn(start);
    when(start.exec(any())).thenAnswer(invocation -> {
      ResultCallback<?> callback = invocation.getArgument(0);
      callback.onComplete();
      return callback;
    });
    when(client.inspectExecCmd("exec-id")).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getExitCode()).thenReturn(exitCode);
    return clients;
  }
}
