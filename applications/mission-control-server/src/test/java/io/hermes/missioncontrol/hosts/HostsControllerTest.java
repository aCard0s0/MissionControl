package io.hermes.missioncontrol.hosts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.AppProperties;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import io.hermes.missioncontrol.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The host endpoints over a real {@link HostService} and a real database — the only place
 * in the suite where {@code HostService} is instantiated rather than mocked. Everything the
 * other tests take on faith about it (url validation, the probe cache, the local-host
 * guard) is decided here.
 *
 * <p>Only the daemon itself is a mock: {@link DockerGateway#ping} is the boundary.
 */
class HostsControllerTest {

  private static final String SOCKET = "unix:///var/run/docker.sock";

  private SqliteTestDatabase database;
  private DockerGateway docker;
  private HostRepository repository;
  private HostService hosts;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    docker = mock(DockerGateway.class);
    repository = new HostRepository(database.jdbc());
    hosts = new HostService(
        repository, docker,
        new AppProperties("live", "", SOCKET, "hermes/agent:latest", "hermes", "test"));
    mvc = MockMvcBuilders
        .standaloneSetup(new HostsController(hosts))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private void daemonAnswers() {
    when(docker.ping(anyString())).thenReturn(new DockerGateway.DaemonInfo("docker", "1.47", 3L));
  }

  private void daemonIsDown() {
    when(docker.ping(anyString())).thenThrow(new RuntimeException("connection refused"));
  }

  @Test
  void addingARemoteHostPersistsItAndReportsTheProbeResult() throws Exception {
    daemonAnswers();

    mvc.perform(post("/api/hosts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"build-box\",\"url\":\"tcp://10.0.0.7:2375\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("remote"))
        .andExpect(jsonPath("$.status").value("connected"))
        .andExpect(jsonPath("$.engine").value("docker"))
        .andExpect(jsonPath("$.apiVersion").value("1.47"));

    assertEquals(1, repository.findAll().size());
    assertEquals("tcp://10.0.0.7:2375", repository.findAll().getFirst().url());
  }

  @Test
  void theNameAndUrlAreTrimmedBeforeReachingTheService() throws Exception {
    daemonAnswers();

    // untrimmed input would fail the tcp://host:port match and store a name with padding
    mvc.perform(post("/api/hosts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  build-box  \",\"url\":\"  tcp://10.0.0.7:2375  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("build-box"));
  }

  @Test
  void aUrlThatIsNotTcpHostPortIsRejected() throws Exception {
    mvc.perform(post("/api/hosts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"local\",\"url\":\"unix:///var/run/docker.sock\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("remote host url must look like tcp://host:port"));

    mvc.perform(post("/api/hosts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"noport\",\"url\":\"tcp://10.0.0.7\"}"))
        .andExpect(status().isBadRequest());

    assertEquals(0, repository.findAll().size());
  }

  @Test
  void aDuplicateUrlIsRejectedAndLeavesOneRow() throws Exception {
    daemonAnswers();
    String body = "{\"name\":\"build-box\",\"url\":\"tcp://10.0.0.7:2375\"}";

    mvc.perform(post("/api/hosts").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    mvc.perform(post("/api/hosts").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("a host with this url already exists"));

    assertEquals(1, repository.findAll().size());
  }

  @Test
  void aBlankNameOrUrlIsRejectedByValidationBeforeTheService() throws Exception {
    mvc.perform(post("/api/hosts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\",\"url\":\"tcp://10.0.0.7:2375\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/api/hosts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"url\":\"   \"}"))
        .andExpect(status().isBadRequest());

    // @Valid runs before the method body, so no probe was attempted
    verify(docker, times(0)).ping(anyString());
  }

  @Test
  void aFailingProbeIsReportedAsErrorWithAHintThatMatchesTheHostKind() throws Exception {
    daemonIsDown();
    hosts.seedLocalHost();

    // the local host is reachable only through a mounted socket, so that is the hint
    mvc.perform(post("/api/hosts/" + HostService.LOCAL_HOST_ID + "/check"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("docker.sock")));

    // a remote host cannot have a socket problem — it gets the address/firewall hint
    repository.insert(new HostRepository.HostRow("dh-remote", "box", "tcp://10.0.0.7:2375", "remote"));
    mvc.perform(post("/api/hosts/dh-remote/check"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("firewall")));
  }

  @Test
  void listServesTheCachedProbeWhileCheckForcesAFreshOne() throws Exception {
    daemonAnswers();
    hosts.seedLocalHost();

    mvc.perform(get("/api/hosts")).andExpect(status().isOk());
    mvc.perform(get("/api/hosts")).andExpect(status().isOk());
    // two listings inside the 10s TTL share one probe, so the dashboard polling the host
    // list does not hammer the daemon
    verify(docker, times(1)).ping(SOCKET);

    mvc.perform(post("/api/hosts/" + HostService.LOCAL_HOST_ID + "/check")).andExpect(status().isOk());
    // an explicit check is the user asking "is it up *now*" — it must bypass the cache
    verify(docker, times(2)).ping(SOCKET);
  }

  @Test
  void requireConnectedRejectsAHostWhoseDaemonIsDown() {
    daemonIsDown();
    hosts.seedLocalHost();

    // every endpoint that is about to talk to a container goes through this, so that a
    // dead daemon is a 503 rather than an obscure exec failure later
    org.junit.jupiter.api.Assertions.assertThrows(
        io.hermes.missioncontrol.web.UpstreamUnavailableException.class,
        () -> hosts.requireConnected(HostService.LOCAL_HOST_ID));
  }

  @Test
  void deletingTheLocalSocketHostIsRefusedAndAnUnknownHostIsANotFound() throws Exception {
    hosts.seedLocalHost();

    mvc.perform(delete("/api/hosts/" + HostService.LOCAL_HOST_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("the local socket host cannot be removed"));
    assertEquals(1, repository.findAll().size());

    mvc.perform(delete("/api/hosts/dh-ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown docker host: dh-ghost"));
  }

  @Test
  void deletingARemoteHostRemovesIt() throws Exception {
    repository.insert(new HostRepository.HostRow("dh-remote", "box", "tcp://10.0.0.7:2375", "remote"));

    mvc.perform(delete("/api/hosts/dh-remote")).andExpect(status().isOk());

    assertEquals(0, repository.findAll().size());
  }

  @Test
  void seedLocalHostIsIdempotentAndUsesTheConfiguredSocket() {
    hosts.seedLocalHost();
    hosts.seedLocalHost();

    assertEquals(1, repository.findAll().size());
    assertEquals(SOCKET, repository.findAll().getFirst().url());
  }

  @Test
  void isLocalDaemonConnectedIsFalseWhenTheRowIsMissingAndTracksTheProbeOtherwise() {
    // /health calls this before seeding has happened on a fresh database
    org.junit.jupiter.api.Assertions.assertFalse(hosts.isLocalDaemonConnected());

    daemonAnswers();
    hosts.seedLocalHost();
    org.junit.jupiter.api.Assertions.assertTrue(hosts.isLocalDaemonConnected());
  }
}
