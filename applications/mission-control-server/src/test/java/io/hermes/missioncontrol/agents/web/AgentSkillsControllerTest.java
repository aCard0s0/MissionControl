package io.hermes.missioncontrol.agents.web;

import static io.hermes.missioncontrol.agents.web.AgentWebFixture.BASE;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.CONTAINER;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.HOST;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.PROFILE;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.hostIsConnected;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.hostIsDown;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.profile;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.support.HostPathBinding;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The skill write endpoints: what they delegate, and what they refuse. */
class AgentSkillsControllerTest {

  private static final String SKILL = BASE + "/skills/refactor";

  private HermesProfiles profiles;
  private HostService hosts;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    profiles = mock(HermesProfiles.class);
    hosts = mock(HostService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new AgentSkillsController(profiles))
        .setConversionService(HostPathBinding.conversionService(hosts))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void enablingASkillWritesTheFlagFromTheBodyAndAnswersWithTheWholeProfile() throws Exception {
    hostIsConnected(hosts);
    when(profiles.setSkillEnabled(HOST, CONTAINER, PROFILE, "refactor", false))
        .thenReturn(profile(PROFILE));

    mvc.perform(put(SKILL).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(PROFILE));

    verify(profiles).setSkillEnabled(HOST, CONTAINER, PROFILE, "refactor", false);
  }

  @Test
  void installingASkillRequiresAName() throws Exception {
    // The host is resolved first now, because {hostId} binds to a probed DockerHostRef during
    // argument binding and @Valid on the body runs after that. This used to assert
    // verifyNoInteractions(hosts) — a malformed request cost nothing at all — and cannot: a
    // bad body sent to a route whose daemon is down answers 503 rather than 400.
    hostIsConnected(hosts);

    mvc.perform(post(BASE + "/skills")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verifyNoInteractions(profiles);
  }

  @Test
  void installingAndUninstallingASkillDelegate() throws Exception {
    hostIsConnected(hosts);
    when(profiles.installSkill(HOST, CONTAINER, PROFILE, "refactor")).thenReturn(profile(PROFILE));
    when(profiles.uninstallSkill(HOST, CONTAINER, PROFILE, "refactor")).thenReturn(profile(PROFILE));

    mvc.perform(post(BASE + "/skills")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"refactor\"}"))
        .andExpect(status().isOk());
    mvc.perform(delete(SKILL)).andExpect(status().isOk());

    verify(profiles).installSkill(HOST, CONTAINER, PROFILE, "refactor");
    verify(profiles).uninstallSkill(HOST, CONTAINER, PROFILE, "refactor");
  }

  @Test
  void readingAndWritingSkillContentDelegate() throws Exception {
    hostIsConnected(hosts);
    when(profiles.readSkillContent(HOST, CONTAINER, PROFILE, "refactor"))
        .thenReturn(new SkillContentDto("refactor", "/skills/refactor/SKILL.md", "# refactor", List.of()));
    when(profiles.updateSkillContent(HOST, CONTAINER, PROFILE, "refactor", "# rewritten"))
        .thenReturn(profile(PROFILE));

    mvc.perform(get(SKILL + "/content"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.body").value("# refactor"));
    mvc.perform(put(SKILL + "/content")
            .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"# rewritten\"}"))
        .andExpect(status().isOk());

    verify(profiles).updateSkillContent(HOST, CONTAINER, PROFILE, "refactor", "# rewritten");
  }

  @Test
  void aDisconnectedHostFailsEverySkillEndpointBeforeTheContainerIsTouched() throws Exception {
    hostIsDown(hosts);

    for (RequestBuilder request : List.of(
        put(SKILL).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"),
        post(BASE + "/skills").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"refactor\"}"),
        delete(SKILL),
        get(SKILL + "/content"),
        put(SKILL + "/content").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"x\"}"))) {
      mvc.perform(request)
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error").value("docker host not connected"));
    }

    verifyNoInteractions(profiles);
  }
}
