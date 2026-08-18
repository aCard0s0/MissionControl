package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.exception.ConflictException;
import io.hermes.missioncontrol.docker.DockerExecService;
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
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class))).thenThrow(new ConflictException("container is not running"));

    assertEquals(List.of(), AgentsWiring.profiles(dockerExec).list("unix:///sock", "stopped"));
  }
}
