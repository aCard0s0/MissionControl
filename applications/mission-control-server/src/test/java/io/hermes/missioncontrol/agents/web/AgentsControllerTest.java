package io.hermes.missioncontrol.agents.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentsControllerTest {

  private static final String HOST = "dh-local";

  private HermesProfiles profiles;
  private HostService hosts;
  private ProfileTemplateService templates;
  private AgentMcpCatalogService mcpCatalog;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    profiles = mock(HermesProfiles.class);
    hosts = mock(HostService.class);
    templates = mock(ProfileTemplateService.class);
    mcpCatalog = mock(AgentMcpCatalogService.class);

    mvc = MockMvcBuilders
        .standaloneSetup(new AgentsController(
            profiles, mock(HermesSetup.class), hosts, templates, mcpCatalog))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private void hostIsConnected() {
    when(hosts.requireConnected(HOST)).thenReturn(new DockerHostDto(
        HOST, "localhost", "unix:///var/run/docker.sock", "local",
        "connected", "docker", "1.47", 3L, null));
  }

  private void hostIsDown() {
    when(hosts.requireConnected(HOST))
        .thenThrow(new UpstreamUnavailableException("docker host not connected"));
  }

  @Test
  void listingProfilesReachesTheContainerWhenTheHostIsUp() throws Exception {
    hostIsConnected();
    when(profiles.list("unix:///var/run/docker.sock", "c1")).thenReturn(List.of());

    mvc.perform(get("/api/agents").param("hostId", HOST).param("containerId", "c1"))
        .andExpect(status().isOk());
  }

  @Test
  void aDisconnectedHostShortCircuitsBeforeTouchingTheContainer() throws Exception {
    hostIsDown();

    mvc.perform(get("/api/agents").param("hostId", HOST).param("containerId", "c1"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("docker host not connected"));

    // the guard exists so a dead daemon never becomes an obscure exec failure
    verifyNoInteractions(profiles);
  }

  @Test
  void creatingAnAgentOnADisconnectedHostNeverReachesTheTemplateService() throws Exception {
    hostIsDown();

    mvc.perform(post("/api/agents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"hostId":"dh-local","containerId":"c1","name":"scout",
                 "provider":"anthropic","model":"claude-opus-5"}
                """))
        .andExpect(status().isServiceUnavailable());

    verifyNoInteractions(templates);
    verifyNoInteractions(profiles);
  }

  @Test
  void anInvalidCreateBodyIsRejectedBeforeAnyHostLookup() throws Exception {
    // @Valid runs before the method body, so a bad request must not probe the daemon
    mvc.perform(post("/api/agents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"containerId\":\"c1\",\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verifyNoInteractions(profiles);
    verifyNoInteractions(templates);
  }
}
