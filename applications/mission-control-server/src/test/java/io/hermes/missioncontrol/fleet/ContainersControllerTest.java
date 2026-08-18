package io.hermes.missioncontrol.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.docker.ContainerUpdateService;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.docker.StatsDto;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The container endpoints. The gateway is a mock — what is being pinned here is the layer
 * above it: which hosts get skipped, whether a host id or a daemon url reaches the gateway,
 * and that a body the validator rejects never touches Docker at all.
 */
class ContainersControllerTest {

  private static final String URL = "unix:///var/run/docker.sock";

  private DockerGateway docker;
  private HostService hosts;
  private ContainerUpdateService updates;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    docker = mock(DockerGateway.class);
    hosts = mock(HostService.class);
    updates = mock(ContainerUpdateService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new ContainersController(docker, hosts, updates))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static DockerHostDto host(String id, String url, String status) {
    return new DockerHostDto(id, id, url, "local", status, "docker", "1.47", 3L, null);
  }

  private static ContainerDto container(String id, String hostId) {
    return new ContainerDto(id, id.substring(0, 3), "hermes-" + id, hostId, "running",
        "hermes/agent:v1", "v1", 1L, null, List.of());
  }

  @Test
  void listSkipsHostsThatAreNotConnected() throws Exception {
    when(hosts.list()).thenReturn(List.of(
        host("dh-up", URL, "connected"),
        host("dh-down", "tcp://10.0.0.7:2375", "error")));
    when(docker.listContainers(URL, "dh-up", false)).thenReturn(List.of(container("abc123", "dh-up")));

    mvc.perform(get("/api/containers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    // the down host's status is already visible on /api/hosts; probing it here would
    // just make the inventory call as slow as the slowest dead daemon
    verify(docker).listContainers(URL, "dh-up", false);
    verify(docker, org.mockito.Mockito.never()).listContainers(eq("tcp://10.0.0.7:2375"), anyString(), anyBoolean());
  }

  @Test
  void listSurvivesOneHostThrowingAndStillReturnsTheOthers() throws Exception {
    when(hosts.list()).thenReturn(List.of(
        host("dh-broken", "tcp://10.0.0.7:2375", "connected"),
        host("dh-ok", URL, "connected")));
    when(docker.listContainers("tcp://10.0.0.7:2375", "dh-broken", false))
        .thenThrow(new RuntimeException("daemon went away mid-list"));
    when(docker.listContainers(URL, "dh-ok", false)).thenReturn(List.of(container("abc123", "dh-ok")));

    // a host that dies between the probe and the listing must not take the whole fleet
    // view down with it
    mvc.perform(get("/api/containers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].hostId").value("dh-ok"));
  }

  @Test
  void listFiltersToOneHostWhenHostIdIsGivenAndForwardsTheAllFlag() throws Exception {
    when(hosts.list()).thenReturn(List.of(host("dh-a", URL, "connected"), host("dh-b", "tcp://b:2375", "connected")));
    when(docker.listContainers(anyString(), anyString(), anyBoolean())).thenReturn(List.of());

    mvc.perform(get("/api/containers").param("hostId", "dh-a").param("all", "true"))
        .andExpect(status().isOk());

    ArgumentCaptor<Boolean> all = ArgumentCaptor.forClass(Boolean.class);
    verify(docker).listContainers(eq(URL), eq("dh-a"), all.capture());
    // all=true switches off the Hermes name/image filter — a silently dropped flag makes
    // the "show everything" toggle in the UI do nothing
    assertEquals(true, all.getValue());
    verify(docker, org.mockito.Mockito.never()).listContainers(eq("tcp://b:2375"), anyString(), anyBoolean());
  }

  @Test
  void statsAndLogsResolveTheHostUrlBeforeReachingTheDaemon() throws Exception {
    when(hosts.urlOf("dh-local")).thenReturn(URL);
    when(docker.stats(URL, "abc123")).thenReturn(new StatsDto(12.5, 256, 2048, 1, 2, 99L));
    when(docker.logs(URL, "abc123", 100)).thenReturn(List.of(new LogLineDto(1L, "info", "stdout", "up")));

    mvc.perform(get("/api/containers/dh-local/abc123/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cpuPercent").value(12.5));

    // the default tail is the contract the frontend relies on when it omits the param
    mvc.perform(get("/api/containers/dh-local/abc123/logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    verify(docker).stats(URL, "abc123");
    verify(docker).logs(URL, "abc123", 100);
  }

  @Test
  void anExplicitTailIsForwardedToTheGateway() throws Exception {
    when(hosts.urlOf("dh-local")).thenReturn(URL);
    when(docker.logs(anyString(), anyString(), anyInt())).thenReturn(List.of());

    mvc.perform(get("/api/containers/dh-local/abc123/logs").param("tail", "500"))
        .andExpect(status().isOk());

    verify(docker).logs(URL, "abc123", 500);
  }

  @Test
  void deployRejectsAnInvalidContainerNameBeforeTouchingDocker() throws Exception {
    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"../evil\",\"version\":\"v1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verifyNoInteractions(docker);
    verifyNoInteractions(hosts);
  }

  @Test
  void deployRejectsAnInvalidProfileNameAndReturnsTheNewContainerId() throws Exception {
    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"scout\",\"profiles\":[\"Bad Name\"]}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(docker);

    when(hosts.urlOf("dh-local")).thenReturn(URL);
    when(docker.deploy(URL, "dh-local", "scout", "v1", List.of("default"))).thenReturn("newid123");

    mvc.perform(post("/api/containers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"name\":\"scout\",\"version\":\"v1\",\"profiles\":[\"default\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("newid123"));
  }

  @Test
  void updateRejectsAnInvalidImageTagAndOtherwiseReturnsTheReplacementId() throws Exception {
    mvc.perform(post("/api/containers/dh-local/abc123/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"../evil\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(updates);

    when(hosts.urlOf("dh-local")).thenReturn(URL);
    when(updates.update(URL, "dh-local", "abc123", "v2")).thenReturn("replacement456");

    // the container id changes on an update, so the caller has to be told the new one
    mvc.perform(post("/api/containers/dh-local/abc123/update")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":\"v2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("replacement456"));
  }

  @Test
  void anUnknownHostIsANotFound() throws Exception {
    when(hosts.urlOf("dh-ghost")).thenThrow(new NoSuchElementException("unknown docker host: dh-ghost"));

    mvc.perform(get("/api/containers/dh-ghost/abc123/stats"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown docker host: dh-ghost"));
  }

  @Test
  void startStopAndRemoveAllResolveTheHostUrl() throws Exception {
    when(hosts.urlOf("dh-local")).thenReturn(URL);

    mvc.perform(post("/api/containers/dh-local/abc123/start")).andExpect(status().isOk());
    mvc.perform(post("/api/containers/dh-local/abc123/stop")).andExpect(status().isOk());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/api/containers/dh-local/abc123"))
        .andExpect(status().isOk());

    verify(docker).start(URL, "abc123");
    verify(docker).stop(URL, "abc123");
    verify(docker).remove(URL, "abc123");
  }
}
