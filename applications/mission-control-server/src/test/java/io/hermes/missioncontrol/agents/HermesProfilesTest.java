package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.docker.ContainerNotRunningException;
import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The profile facade itself: inventory and the degradations that belong to it rather
 * than to any one collaborator.
 *
 * <p>Each concern a profile is made of has its own suite — {@link HermesModelConfigTest},
 * {@link HermesProfileMcpTest}, {@link HermesGatewayLogsTest},
 * {@link HermesProfileFileAccessTest} and {@link HermesProfilesRollbackTest}.
 */
class HermesProfilesTest {

  @Test
  void stoppedContainerHasNoReadableProfileInventory() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class))).thenThrow(new ContainerNotRunningException("Hermes command needs a running container: stopped", null));

    assertEquals(List.of(), AgentsWiring.profiles(dockerExec).list(new DockerHostRef("dh-local", "unix:///sock"), "stopped"));
  }

  @Test
  void deletingAProfileThatIsAlreadyGoneIsNotAskedOfHermes() {
    // `hermes profile delete` exits non-zero on a name it does not know. Without this guard a
    // delete whose dashboard-side link cleanup failed could never be retried: the retry died
    // here, before reaching the cleanup that was the only thing left to do.
    DockerExecService dockerExec = mock(DockerExecService.class);
    // test -d returns non-zero: no such profile directory
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(1, "", ""));

    AgentsWiring.profiles(dockerExec).delete(HOST, "cid", "ops");

    verify(dockerExec, never()).runAsUser(any(), anyString(), anyString(),
        eq(List.of("hermes", "profile", "delete", "ops", "--yes")),
        anyString(), anyBoolean(), anyBoolean(), any(Duration.class));
  }

  @Test
  void deletingAProfileThatExistsStillGoesThroughHermes() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));

    AgentsWiring.profiles(dockerExec).delete(HOST, "cid", "ops");

    verify(dockerExec).runAsUser(HOST, "cid", "hermes",
        List.of("hermes", "profile", "delete", "ops", "--yes"),
        "Hermes command", true, false, Duration.ofSeconds(30));
  }

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");
}
