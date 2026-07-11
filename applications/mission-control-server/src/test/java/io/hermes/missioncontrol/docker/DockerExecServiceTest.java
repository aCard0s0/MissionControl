package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    DockerClients clients = mock(DockerClients.class);
    DockerClient client = mock(DockerClient.class);
    ExecCreateCmd create = mock(ExecCreateCmd.class, Answers.RETURNS_SELF);
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
    when(inspected.getExitCode()).thenReturn(0);

    new DockerExecService(clients).runAsUser(
        "unix:///sock", "cid", "hermes", List.of("true"), "test",
        true, false, Duration.ofSeconds(1));

    verify(create).withUser("hermes");
  }
}
