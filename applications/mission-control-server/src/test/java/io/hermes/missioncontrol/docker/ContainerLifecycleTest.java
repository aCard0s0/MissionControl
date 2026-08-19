package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.InOrder;

/**
 * DELETE /api/containers is the one irreversible operation Mission Control offers: the
 * data volume it deletes holds every profile, soul and credential an agent ever had, and
 * nothing restores it. These tests pin the two guards that decide whether a volume is
 * Mission Control's to destroy.
 */
class ContainerLifecycleTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");
  private final DockerClients clients = mock(DockerClients.class);
  private final DockerClient client = mock(DockerClient.class);
  private final DockerExecService dockerExec = mock(DockerExecService.class);
  private final ContainerLifecycle subject = new ContainerLifecycle(clients);

  @BeforeEach
  void setUp() {
    when(clients.forUrl("unix:///sock")).thenReturn(client);
  }

  @Test
  void removingAnUnmanagedContainerNeverTouchesAVolume() {
    // a container someone else created can still carry an mc.dataVolume label — copied
    // from a template, or left behind by an earlier tool — but without mc.managed
    // Mission Control never took ownership of that volume
    stubLabels("cid", Map.of("mc.dataVolume", "mc-hermes-demo"));
    RemoveContainerCmd removeContainer = stubRemoveContainer("cid");

    subject.remove(HOST, "cid");

    verify(client, never()).removeVolumeCmd(anyString());
    // the container itself is still removed, and forcibly, so a running agent does not
    // turn a delete into a daemon-level conflict
    verify(removeContainer).withForce(true);
    verify(removeContainer).exec();
  }

  @Test
  void removingAManagedContainerIgnoresAVolumeNameOutsideTheManagedPrefix() {
    // without the mc-hermes- prefix check a stray or hand-edited label points the delete
    // at somebody else's database volume, and the daemon would happily obey
    stubLabels("cid", Map.of("mc.managed", "true", "mc.dataVolume", "postgres-data"));
    RemoveContainerCmd removeContainer = stubRemoveContainer("cid");

    subject.remove(HOST, "cid");

    verify(client, never()).removeVolumeCmd(anyString());
    verify(removeContainer).exec();
  }

  @Test
  void aContainerWithNoLabelsAtAllIsRemovedWithoutAVolumeLookup() {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerConfig config = mock(ContainerConfig.class);
    when(client.inspectContainerCmd("cid")).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getConfig()).thenReturn(config);
    when(config.getLabels()).thenReturn(null);
    RemoveContainerCmd removeContainer = stubRemoveContainer("cid");

    subject.remove(HOST, "cid");

    verify(removeContainer).exec();
    verify(client, never()).inspectVolumeCmd(anyString());
    verify(client, never()).removeVolumeCmd(anyString());
  }

  @Test
  void anAlreadyGoneVolumeIsNotAnError() {
    stubLabels("cid", managedLabels());
    RemoveContainerCmd removeContainer = stubRemoveContainer("cid");
    RemoveVolumeCmd removeVolume = mock(RemoveVolumeCmd.class);
    when(client.removeVolumeCmd("mc-hermes-demo")).thenReturn(removeVolume);
    // a retried delete, or a volume already pruned by hand: the caller's desired state
    // is the actual state, so the request must succeed
    when(removeVolume.exec()).thenThrow(new NotFoundException("no such volume"));

    assertDoesNotThrow(() -> subject.remove(HOST, "cid"));

    // the removal must still have been attempted — silently skipping it would leak the
    // volume on every real delete and pass this test anyway
    verify(removeVolume).exec();
    verify(removeContainer).exec();
  }

  @Test
  void aVolumeThatCannotBeRemovedIsReportedAsUpstreamUnavailable() {
    stubLabels("cid", managedLabels());
    RemoveContainerCmd removeContainer = stubRemoveContainer("cid");
    RemoveVolumeCmd removeVolume = mock(RemoveVolumeCmd.class);
    when(client.removeVolumeCmd("mc-hermes-demo")).thenReturn(removeVolume);
    RuntimeException daemonFailure = new RuntimeException("volume is in use");
    when(removeVolume.exec()).thenThrow(daemonFailure);

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> subject.remove(HOST, "cid"));

    // the operator is left with an orphaned volume and has to clean it up by hand, so
    // the message has to say which one
    assertTrue(failure.getMessage().contains("mc-hermes-demo"), failure.getMessage());
    assertSame(daemonFailure, failure.getCause());
    // the container is gone by this point; the failure must not imply otherwise
    verify(removeContainer).exec();
  }

  @Test
  void theManagedVolumeIsRemovedOnlyAfterTheContainerIsGone() {
    stubLabels("cid", managedLabels());
    RemoveContainerCmd removeContainer = stubRemoveContainer("cid");
    RemoveVolumeCmd removeVolume = mock(RemoveVolumeCmd.class);
    when(client.removeVolumeCmd("mc-hermes-demo")).thenReturn(removeVolume);

    subject.remove(HOST, "cid");

    // the daemon refuses to remove a volume that is still attached, so the reverse order
    // leaves the volume behind on every delete of a live agent
    InOrder ordered = inOrder(removeContainer, removeVolume);
    ordered.verify(removeContainer).exec();
    ordered.verify(removeVolume).exec();
  }

  private static Map<String, String> managedLabels() {
    return Map.of("mc.managed", "true", "mc.dataVolume", "mc-hermes-demo");
  }

  private void stubLabels(String id, Map<String, String> labels) {
    InspectContainerCmd inspect = mock(InspectContainerCmd.class);
    InspectContainerResponse inspected = mock(InspectContainerResponse.class);
    ContainerConfig config = mock(ContainerConfig.class);
    when(client.inspectContainerCmd(id)).thenReturn(inspect);
    when(inspect.exec()).thenReturn(inspected);
    when(inspected.getConfig()).thenReturn(config);
    when(config.getLabels()).thenReturn(labels);
  }

  private RemoveContainerCmd stubRemoveContainer(String id) {
    RemoveContainerCmd remove = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
    when(client.removeContainerCmd(id)).thenReturn(remove);
    return remove;
  }
}
