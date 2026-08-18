package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerClients} is the only place a Docker connection pool is created, so its caching
 * is what keeps the fleet poll from opening a new one per host per tick. Building a client does
 * not talk to a daemon, so these exercise the real thing rather than a mock.
 *
 * <p>Releasing is the exception. A cached client outlives the host that needed it unless
 * something closes it, and a real client's {@code close()} is silent — so those tests drive a
 * stub factory and assert on the close itself.
 */
class DockerClientsTest {

  private final DockerClients clients = new DockerClients();

  /** Hands out mock clients and remembers them in call order. */
  private static final class StubFactory implements DockerClients.ClientFactory {

    private final List<DockerClient> created = new ArrayList<>();

    @Override
    public DockerClient create(String url, Duration responseTimeout) {
      DockerClient client = mock(DockerClient.class);
      created.add(client);
      return client;
    }
  }

  @Test
  void theSameDaemonUrlAlwaysYieldsTheSameCachedClient() {
    DockerClient first = clients.forUrl("unix:///var/run/docker.sock");
    DockerClient second = clients.forUrl("unix:///var/run/docker.sock");

    // every container card, log tail and stats sample resolves its client through here;
    // a fresh client per call leaks a connection pool on each one
    assertSame(first, second);
  }

  @Test
  void differentDaemonUrlsGetTheirOwnClients() {
    DockerClient local = clients.forUrl("unix:///var/run/docker.sock");
    DockerClient remote = clients.forUrl("tcp://192.0.2.10:2375");

    // sharing one client across hosts would send every remote host's commands to
    // whichever daemon was asked for first
    assertNotSame(local, remote);
  }

  /**
   * Removing a host has to close what it opened. Both flavours are cached per url, so a
   * deleted host otherwise leaves two clients — with their pooled sockets and the threads
   * Apache HttpClient runs them on — held for the life of the process under a url nothing
   * references any more.
   */
  @Test
  void releasingADaemonUrlClosesBothOfItsClientsAndDropsThem() throws Exception {
    StubFactory factory = new StubFactory();
    DockerClients clients = new DockerClients(factory);
    clients.forUrl("tcp://10.0.0.7:2375");
    clients.streamingForUrl("tcp://10.0.0.7:2375");
    assertEquals(2, clients.cachedClientCount());

    clients.release("tcp://10.0.0.7:2375");

    assertEquals(0, clients.cachedClientCount());
    for (DockerClient created : factory.created) {
      // dropping the reference is not enough: the pool is only freed by close()
      verify(created).close();
    }
  }

  @Test
  void releasingOneHostLeavesEveryOtherHostConnected() throws Exception {
    StubFactory factory = new StubFactory();
    DockerClients clients = new DockerClients(factory);
    DockerClient doomed = clients.forUrl("tcp://10.0.0.7:2375");
    DockerClient survivor = clients.forUrl("tcp://10.0.0.8:2375");

    clients.release("tcp://10.0.0.7:2375");

    assertEquals(1, clients.cachedClientCount());
    assertSame(survivor, clients.forUrl("tcp://10.0.0.8:2375"),
        "deleting one host must not make every other host rebuild its pool");
    verify(doomed).close();
    verify(survivor, never()).close();
  }

  /**
   * The host row is already deleted by the time this runs, so there is nothing useful to
   * abort — and a daemon that has gone away is exactly when a close is most likely to throw.
   */
  @Test
  void aClientThatFailsToCloseIsStillDroppedAndDoesNotStrandTheOther() throws Exception {
    StubFactory factory = new StubFactory();
    DockerClients clients = new DockerClients(factory);
    DockerClient unary = clients.forUrl("tcp://10.0.0.7:2375");
    DockerClient streaming = clients.streamingForUrl("tcp://10.0.0.7:2375");
    doThrow(new IOException("connection reset")).when(unary).close();

    assertDoesNotThrow(() -> clients.release("tcp://10.0.0.7:2375"));

    assertEquals(0, clients.cachedClientCount());
    verify(streaming).close();
  }

  @Test
  void releasingAUrlThatWasNeverUsedIsANoOp() {
    // host deletes are not ordered against first use: a host can be added and removed
    // without a single command ever running against it
    assertDoesNotThrow(() -> clients.release("tcp://198.51.100.4:2375"));
    assertEquals(0, clients.cachedClientCount());
  }

  @Test
  void aTcpDaemonUrlIsAccepted() {
    // remote hosts are a first-class feature: most of the fleet is not the local socket
    DockerClient remote = assertDoesNotThrow(() -> clients.forUrl("tcp://192.0.2.10:2375"));

    assertNotNull(remote);
    // and the client has to be wired far enough to build commands, not merely constructed
    assertNotNull(remote.listContainersCmd());
  }
}
