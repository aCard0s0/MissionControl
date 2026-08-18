package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.docker.DockerExecService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class HermesProfilesRollbackTest {

  @Test
  void baseConfigurationFailureDeletesNewProfile() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    CreateAgentRequest request = new CreateAgentRequest(
        "dh-local", "cid", "ops", "anthropic", "model", null, null, null, null, null);
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""))
        .thenThrow(new RuntimeException("config failed"))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(RuntimeException.class, () -> profiles.createProfileBare("unix:///sock", request));

    verify(dockerExec).runAsUser(
        "unix:///sock", "cid", "hermes", List.of("hermes", "profile", "delete", "ops", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }

  @Test
  void configWriteThatLeavesNoModelDeletesNewProfile() {
    // every exec "succeeds" but config.yaml reads back empty — the state that
    // produced a profile whose auxiliary chain had no provider to resolve to
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    CreateAgentRequest request = new CreateAgentRequest(
        "dh-local", "cid", "ops", "anthropic", "model", null, null, null, null, null);
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(IllegalStateException.class, () -> profiles.createProfileBare("unix:///sock", request));

    verify(dockerExec).runAsUser(
        "unix:///sock", "cid", "hermes", List.of("hermes", "profile", "delete", "ops", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }
}
