package io.hermes.missioncontrol.agents.web;

import static io.hermes.missioncontrol.agents.web.AgentWebFixture.BASE;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.CONTAINER;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.HOST;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.agents.AgentLifecycle;
import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentsControllerTest {

  private HermesProfiles profiles;
  private HostService hosts;
  private ProfileTemplateService templates;
  private AgentMcpCatalogService mcpCatalog;
  private AgentLifecycle lifecycle;
  private CredentialService credentials;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    profiles = mock(HermesProfiles.class);
    hosts = mock(HostService.class);
    templates = mock(ProfileTemplateService.class);
    mcpCatalog = mock(AgentMcpCatalogService.class);
    lifecycle = mock(AgentLifecycle.class);
    credentials = mock(CredentialService.class);

    mvc = MockMvcBuilders
        .standaloneSetup(new AgentsController(
            profiles, templates, lifecycle, hosts, mcpCatalog, credentials))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private void hostIsConnected() {
    AgentWebFixture.hostIsConnected(hosts);
  }

  private void hostIsDown() {
    AgentWebFixture.hostIsDown(hosts);
  }

  @Test
  void listingProfilesReachesTheContainerWhenTheHostIsUp() throws Exception {
    hostIsConnected();
    when(profiles.list(HOST, CONTAINER)).thenReturn(List.of());

    mvc.perform(get("/api/agents").param("hostId", HOST.id()).param("containerId", "c1"))
        .andExpect(status().isOk());
  }

  @Test
  void aDisconnectedHostShortCircuitsBeforeTouchingTheContainer() throws Exception {
    hostIsDown();

    mvc.perform(get("/api/agents").param("hostId", HOST.id()).param("containerId", "c1"))
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

  @Test
  void listingEnrichesEveryProfileWithItsCatalogLinks() throws Exception {
    hostIsConnected();
    when(profiles.list(HOST, CONTAINER)).thenReturn(List.of(profile("scout"), profile("scribe")));
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));

    mvc.perform(get("/api/agents").param("hostId", HOST.id()).param("containerId", CONTAINER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("scout"))
        .andExpect(jsonPath("$[1].name").value("scribe"));

    verify(mcpCatalog).enrich(HOST, profile("scout"));
    verify(mcpCatalog).enrich(HOST, profile("scribe"));
  }

  @Test
  void aCreateNamingATemplateGoesThroughTheTemplateServiceRatherThanAPlainCreate() throws Exception {
    // the template path layers soul/memory/skills/mcp/secrets onto the new profile as one
    // rollback-safe operation, so it must not be reachable by accident from the plain path
    hostIsConnected();
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(templates.createFromTemplate(eq("tpl-1"), eq(HOST), any(ProfileSpec.class)))
        .thenReturn(profile("scout"));

    mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-local","containerId":"c1","name":"scout","provider":"anthropic",
             "model":"claude-opus-5","fromTemplateId":"tpl-1"}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("scout"));

    verify(templates).createFromTemplate(eq("tpl-1"), eq(HOST), any(ProfileSpec.class));
    verify(profiles, never()).create(any(), any());
  }

  @Test
  void aBlankTemplateIdIsTreatedAsNoTemplate() throws Exception {
    // the dashboard sends "" for "no template", and that must not look up a template named ""
    hostIsConnected();
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(profiles.create(eq(HOST), any(ProfileSpec.class))).thenReturn(profile("scout"));

    mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-local","containerId":"c1","name":"scout","provider":"anthropic",
             "model":"claude-opus-5","fromTemplateId":"  "}
            """))
        .andExpect(status().isOk());

    verify(profiles).create(eq(HOST), any(ProfileSpec.class));
    verifyNoInteractions(templates);
  }

  @Test
  void aCreateNamingACredentialResolvesItAgainstTheChosenProvidersVariable() throws Exception {
    // the client sends an id, never a key name: a credential id says which values may be read,
    // and letting the caller also name the variable would let it read any of them
    hostIsConnected();
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(credentials.valueFor("cr-1", "ANTHROPIC_API_KEY")).thenReturn("sk-from-the-vault");
    when(profiles.create(eq(HOST), any(ProfileSpec.class))).thenReturn(profile("scout"));

    mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-local","containerId":"c1","name":"scout","provider":"anthropic",
             "model":"claude-opus-5","apiKeyCredentialId":"cr-1"}
            """))
        .andExpect(status().isOk());

    ArgumentCaptor<ProfileSpec> spec = ArgumentCaptor.forClass(ProfileSpec.class);
    verify(profiles).create(eq(HOST), spec.capture());
    assertEquals("sk-from-the-vault", spec.getValue().apiKey());
  }

  @Test
  void aPickedCredentialBeatsAStaleCharacterLeftInTheKeyBox() throws Exception {
    hostIsConnected();
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(credentials.valueFor("cr-1", "ANTHROPIC_API_KEY")).thenReturn("sk-from-the-vault");
    when(profiles.create(eq(HOST), any(ProfileSpec.class))).thenReturn(profile("scout"));

    mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-local","containerId":"c1","name":"scout","provider":"anthropic",
             "model":"claude-opus-5","apiKey":"sk-typed","apiKeyCredentialId":"cr-1"}
            """))
        .andExpect(status().isOk());

    ArgumentCaptor<ProfileSpec> spec = ArgumentCaptor.forClass(ProfileSpec.class);
    verify(profiles).create(eq(HOST), spec.capture());
    assertEquals("sk-from-the-vault", spec.getValue().apiKey());
  }

  @Test
  void aCredentialPickedForAProviderThatTakesNoKeyIsRefusedRatherThanDropped() throws Exception {
    // an OAuth provider has no variable to fill, so silently ignoring the pick would create a
    // keyless profile that reports itself configured
    hostIsConnected();

    mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-local","containerId":"c1","name":"scout","provider":"nous",
             "model":"hermes-4","apiKeyCredentialId":"cr-1"}
            """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(credentials);
    verifyNoInteractions(profiles);
  }

  @Test
  void aCreateWithNoCredentialNeverReachesTheCredentialStore() throws Exception {
    hostIsConnected();
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(profiles.create(eq(HOST), any(ProfileSpec.class))).thenReturn(profile("scout"));

    mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-local","containerId":"c1","name":"scout","provider":"anthropic",
             "model":"claude-opus-5","apiKey":"sk-typed"}
            """))
        .andExpect(status().isOk());

    verifyNoInteractions(credentials);
  }

  @Test
  void aProfileNameThatCouldEscapeAPathIsRejectedByValidation() throws Exception {
    // the name becomes a directory under the container's profile root
    mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-local","containerId":"c1","name":"../../etc","provider":"anthropic",
             "model":"claude-opus-5"}
            """))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(profiles);
    verifyNoInteractions(templates);
  }

  @Test
  void deletingAProfileHandsTheResolvedHostToTheLifecycle() throws Exception {
    // what the delete then does on both sides of the container/SQLite split, and in which
    // order, is AgentLifecycleTest's
    hostIsConnected();

    mvc.perform(delete("/api/agents/" + HOST.id() + "/" + CONTAINER + "/scout"))
        .andExpect(status().isOk());

    verify(lifecycle).delete(HOST, CONTAINER, "scout");
  }

  @Test
  void updatingTheSoulAndTheConfigDelegateTheirBodies() throws Exception {
    hostIsConnected();
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(profiles.updateConfig(HOST, CONTAINER, "scout", "model: opus\n")).thenReturn(profile("scout"));

    mvc.perform(put(BASE + "/soul").contentType(MediaType.APPLICATION_JSON)
            .content("{\"soul\":\"You scout the codebase.\"}"))
        .andExpect(status().isOk());
    mvc.perform(put(BASE + "/config").contentType(MediaType.APPLICATION_JSON)
            .content("{\"configYaml\":\"model: opus\\n\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("scout"));

    verify(profiles).updateSoul(HOST, CONTAINER, "scout", "You scout the codebase.");
    verify(profiles).updateConfig(HOST, CONTAINER, "scout", "model: opus\n");
  }

  @Test
  void integrationsAndLogsDelegateAndTheLogTailDefaultsToOneHundred() throws Exception {
    hostIsConnected();
    when(profiles.integrations(HOST, CONTAINER, "scout"))
        .thenReturn(List.of(new IntegrationDto("slack", "connected", "#ops")));
    when(profiles.logs(eq(HOST), eq(CONTAINER), eq("scout"), anyInt())).thenReturn(List.of());

    mvc.perform(get(BASE + "/integrations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].kind").value("slack"));
    mvc.perform(get(BASE + "/logs")).andExpect(status().isOk());
    mvc.perform(get(BASE + "/logs").param("tail", "5")).andExpect(status().isOk());

    verify(profiles).logs(HOST, CONTAINER, "scout", 100);
    verify(profiles).logs(HOST, CONTAINER, "scout", 5);
  }

  @Test
  void aDisconnectedHostFailsTheProfileWriteEndpointsToo() throws Exception {
    hostIsDown();

    mvc.perform(delete("/api/agents/" + HOST.id() + "/" + CONTAINER + "/scout"))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(put(BASE + "/soul").contentType(MediaType.APPLICATION_JSON).content("{\"soul\":\"x\"}"))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(put(BASE + "/config").contentType(MediaType.APPLICATION_JSON)
            .content("{\"configYaml\":\"x\"}"))
        .andExpect(status().isServiceUnavailable());
    mvc.perform(get(BASE + "/integrations")).andExpect(status().isServiceUnavailable());
    mvc.perform(get(BASE + "/logs")).andExpect(status().isServiceUnavailable());

    verifyNoInteractions(profiles);
  }
}
