package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which failures the readiness gate translates, and which it leaves alone.
 *
 * <p>A non-zero exit from the readiness script is the one case that becomes a 503: an
 * unreadable profile file or a gateway that never reports ready is the same operational
 * outcome the state checks either side of it already report that way, not the 400 a rejected
 * container command means everywhere else.
 *
 * <p>The translation used to be a {@code catch (RuntimeException)}, because the exit arrived as
 * a bare one and would otherwise have been answered as a 500 defect. That also swallowed every
 * genuine defect into a 503, which is what the second and third tests here pin.
 */
class DeploymentReadinessTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final DockerClient client = mock(DockerClient.class);
  private final DeploymentReadiness subject = new DeploymentReadiness(dockerExec);

  @BeforeEach
  void containerIsRunning() {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerState state = mock(ContainerState.class);
    when(client.inspectContainerCmd("main-id")).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getState()).thenReturn(state);
    when(state.getRunning()).thenReturn(true);
  }

  private RuntimeException readinessFailsWith(RuntimeException thrown) {
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class))).thenThrow(thrown);
    return assertThrows(RuntimeException.class,
        () -> subject.validate(HOST, client, "main-id", List.of()));
  }

  @Test
  void aGatewayThatNeverComesUpIsUnavailableRatherThanABadRequest() {
    ContainerCommandFailedException notReady = new ContainerCommandFailedException(
        "Hermes deployment readiness failed: default gateway not ready: still starting");

    RuntimeException failure = readinessFailsWith(notReady);

    assertEquals(UpstreamUnavailableException.class, failure.getClass());
    assertTrue(failure.getMessage().contains("Hermes readiness checks failed"), failure.getMessage());
    // the script's own last line is the only diagnosis an operator gets
    assertTrue(failure.getMessage().contains("default gateway not ready"), failure.getMessage());
    assertSame(notReady, failure.getCause());
  }

  @Test
  void aDefectIsNotLaunderedIntoAnUpstreamFailure() {
    // the blanket catch this replaced reported a NullPointerException in our own code as
    // "Hermes readiness checks failed", at 503, where nothing would ever alert on it
    IllegalStateException defect = new IllegalStateException("boom");

    assertSame(defect, readinessFailsWith(defect));
  }

  @Test
  void anExecThatAlreadyReportsUnavailableIsNotRewrapped() {
    // a timed-out or disconnected exec arrives already carrying the right status, and
    // rewrapping it would bury its message inside a second one
    UpstreamUnavailableException timedOut =
        new UpstreamUnavailableException("Hermes deployment readiness timed out");

    assertSame(timedOut, readinessFailsWith(timedOut));
  }
}
