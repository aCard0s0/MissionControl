package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerClients} is the only place a Docker connection pool is created, so its caching
 * is what keeps the fleet poll from opening a new one per host per tick. Building a client does
 * not talk to a daemon, so these exercise the real thing rather than a mock.
 */
class DockerClientsTest {

  private final DockerClients clients = new DockerClients();

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

  @Test
  void aTcpDaemonUrlIsAccepted() {
    // remote hosts are a first-class feature: most of the fleet is not the local socket
    DockerClient remote = assertDoesNotThrow(() -> clients.forUrl("tcp://192.0.2.10:2375"));

    assertNotNull(remote);
    // and the client has to be wired far enough to build commands, not merely constructed
    assertNotNull(remote.listContainersCmd());
  }
}
