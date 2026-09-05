package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HermesProfilesRollbackTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  @Test
  void baseConfigurationFailureDeletesNewProfile() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "ops", "anthropic", "model", null, null, null, null);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""))
        .thenThrow(new RuntimeException("config failed"))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(RuntimeException.class, () -> profiles.createProfileBare(HOST, spec));

    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "delete", "ops", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }

  @Test
  void aMixedCaseNameIsCreatedAndRolledBackUnderTheNameHermesFoldsItTo() {
    // hermes lower-cases the name on create, so `Coach` lives at profiles/coach: a delete that
    // said Coach found no directory and left the half-built profile behind
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "Coach", "anthropic", "model", null, null, null, null);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""))
        .thenThrow(new RuntimeException("config failed"))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(RuntimeException.class, () -> profiles.createProfileBare(HOST, spec));

    assertEquals("coach", spec.name());
    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "create", "coach"),
        "Hermes command", true, false, Duration.ofSeconds(30));
    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "delete", "coach", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }

  @Test
  void aProfileCountsAsCreatingFromTheFirstExecUntilTheCreateIsOver() {
    // the directory exists after the first exec, so this is the window the inventory must
    // not report the profile in, and a stop would roll it back
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "ops", "anthropic", "model", null, null, null, null);
    List<List<String>> seenDuring = new ArrayList<>();
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenAnswer(call -> {
          seenDuring.add(profiles.creating("cid"));
          return new DockerExecService.ExecResult(0, "", "");
        });

    assertThrows(IllegalStateException.class, () -> profiles.createProfileBare(HOST, spec));

    assertEquals(false, seenDuring.isEmpty());
    seenDuring.forEach(seen -> assertEquals(List.of("ops"), seen));
    assertEquals(List.of(), profiles.creating("cid"));
    assertEquals(List.of(), profiles.creating("other"));
  }

  @Test
  void configWriteThatLeavesNoModelDeletesNewProfile() {
    // every exec "succeeds" but config.yaml reads back empty — the state that
    // produced a profile whose auxiliary chain had no provider to resolve to
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec);
    ProfileSpec spec = new ProfileSpec(
        "cid", "ops", "anthropic", "model", null, null, null, null);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyBoolean(),
        any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    assertThrows(IllegalStateException.class, () -> profiles.createProfileBare(HOST, spec));

    verify(dockerExec).runAsUser(
        HOST, "cid", "hermes", List.of("hermes", "profile", "delete", "ops", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }
}
