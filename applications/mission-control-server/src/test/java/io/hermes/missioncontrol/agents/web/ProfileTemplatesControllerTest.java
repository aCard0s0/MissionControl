package io.hermes.missioncontrol.agents.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.web.ApiExceptionHandler;
import io.hermes.missioncontrol.web.ResourceConflictException;
import io.hermes.missioncontrol.web.UpstreamUnavailableException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The template endpoints. Templates are dashboard-owned, so the interesting rule is which
 * of these seven endpoints needs a live Docker host and which must work with every daemon
 * in the fleet down.
 */
class ProfileTemplatesControllerTest {

  private static final String HOST = "dh-local";
  private static final String URL = "unix:///var/run/docker.sock";

  private ProfileTemplateService service;
  private HostService hosts;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(ProfileTemplateService.class);
    hosts = mock(HostService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new ProfileTemplatesController(service, hosts))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private void hostIsConnected() {
    when(hosts.requireConnected(HOST)).thenReturn(
        new DockerHostDto(HOST, "localhost", URL, "local", "connected", "docker", "1.47", 3L, null));
  }

  private static ProfileTemplateDto template() {
    return new ProfileTemplateDto("pt-1", "researcher", "digs", "anthropic", "claude-opus-5",
        null, "/work", "soul", "memory", List.of(), List.of(), List.of(), 1L, 2L);
  }

  private static AgentProfileDto profile() {
    return new AgentProfileDto("scout", "c1", "scout", null, "idle", "anthropic",
        "claude-opus-5", "...abcd", "/work", "soul", "memory", "model: x",
        List.of(), List.of(), List.of(), 9L);
  }

  @Test
  void listingAndGettingATemplateNeverTouchesADockerHost() throws Exception {
    when(service.list()).thenReturn(List.of(template()));
    when(service.get("pt-1")).thenReturn(template());

    mvc.perform(get("/api/profile-templates"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("researcher"));
    mvc.perform(get("/api/profile-templates/pt-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("claude-opus-5"));

    // templates live in the dashboard's own database — browsing them with every daemon
    // down has to keep working
    verifyNoInteractions(hosts);
  }

  @Test
  void creatingUpdatingAndDeletingATemplateNeverTouchesADockerHost() throws Exception {
    when(service.create(org.mockito.ArgumentMatchers.any())).thenReturn(template());
    when(service.update(eq("pt-1"), org.mockito.ArgumentMatchers.any())).thenReturn(template());

    String body = "{\"name\":\"researcher\",\"provider\":\"anthropic\",\"model\":\"claude-opus-5\"}";
    mvc.perform(post("/api/profile-templates").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/profile-templates/pt-1").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/profile-templates/pt-1")).andExpect(status().isOk());

    verifyNoInteractions(hosts);
    verify(service).delete("pt-1");
  }

  @Test
  void captureAndDeployBothRequireAConnectedHostAndStopBeforeTheService() throws Exception {
    when(hosts.requireConnected(HOST))
        .thenThrow(new UpstreamUnavailableException("docker host not connected"));

    mvc.perform(post("/api/profile-templates/capture")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"containerId\":\"c1\",\"name\":\"scout\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("docker host not connected"));

    mvc.perform(post("/api/profile-templates/pt-1/deploy")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"containerId\":\"c1\",\"name\":\"scout\"}"))
        .andExpect(status().isServiceUnavailable());

    // both endpoints exec into a container, so a dead daemon must be a 503 here rather
    // than an obscure exec failure deeper down
    verifyNoInteractions(service);
  }

  @Test
  void captureAndDeployPassTheResolvedHostUrlRatherThanTheHostId() throws Exception {
    hostIsConnected();
    when(service.captureFromAgent(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(template());
    when(service.deploy(anyString(), anyString(), anyString(), anyString())).thenReturn(profile());

    mvc.perform(post("/api/profile-templates/capture")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"hostId":"dh-local","containerId":"c1","name":"scout","templateName":"researcher"}
                """))
        .andExpect(status().isOk());
    mvc.perform(post("/api/profile-templates/pt-1/deploy")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"containerId\":\"c1\",\"name\":\"scout\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("scout"));

    // the service talks to a daemon, so it needs the url; handing it a host id would
    // fail at the transport layer
    verify(service).captureFromAgent(URL, "c1", "scout", "researcher");
    verify(service).deploy("pt-1", URL, "c1", "scout");
  }

  @Test
  void anInvalidUpsertOrDeployBodyIsRejectedBeforeAnythingElseRuns() throws Exception {
    mvc.perform(post("/api/profile-templates")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"../escape\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    // the deploy name becomes a profile directory, so its pattern is a path guard
    mvc.perform(post("/api/profile-templates/pt-1/deploy")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"containerId\":\"c1\",\"name\":\"../../etc\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(service);
    verifyNoInteractions(hosts);
  }

  @Test
  void anUnknownTemplateIsANotFoundAndADuplicateNameIsAConflict() throws Exception {
    when(service.get("pt-ghost")).thenThrow(new NoSuchElementException("unknown template: pt-ghost"));
    when(service.create(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new ResourceConflictException("a template named 'researcher' already exists"));

    mvc.perform(get("/api/profile-templates/pt-ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown template: pt-ghost"));

    mvc.perform(post("/api/profile-templates")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"researcher\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("a template named 'researcher' already exists"));
  }
}
