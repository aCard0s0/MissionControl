package io.hermes.missioncontrol.fleet;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.ImageCatalogService;
import io.hermes.missioncontrol.docker.ImageTagsDto;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The image-tag endpoint that feeds the version picker. */
class ImagesControllerTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///var/run/docker.sock");

  private ImageCatalogService catalog;
  private HostService hosts;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    catalog = mock(ImageCatalogService.class);
    hosts = mock(HostService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new ImagesController(catalog, hosts))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private void hostIsConnected() {
    when(hosts.requireConnected("dh-local")).thenReturn(HOST);
  }

  private static ImageTagsDto tags() {
    return new ImageTagsDto("hermes/agent", List.of("v2", "v1"), List.of(), "v2", "ok", null, 99L);
  }

  @Test
  void theRemoteFlagDefaultsToTrueAndIsForwarded() throws Exception {
    hostIsConnected();
    when(catalog.tags(HOST, true)).thenReturn(tags());
    when(catalog.tags(HOST, false)).thenReturn(tags());

    mvc.perform(get("/api/images/tags").param("hostId", "dh-local"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repository").value("hermes/agent"))
        .andExpect(jsonPath("$.newest").value("v2"));
    verify(catalog).tags(HOST, true);

    // remote=false is the "don't call the registry" escape hatch; dropping it would make
    // the endpoint always pay for a Docker Hub round trip
    mvc.perform(get("/api/images/tags").param("hostId", "dh-local").param("remote", "false"))
        .andExpect(status().isOk());
    verify(catalog).tags(HOST, false);
  }

  @Test
  void aDisconnectedHostIsRejectedBeforeTheCatalogIsAsked() throws Exception {
    when(hosts.requireConnected("dh-local"))
        .thenThrow(new UpstreamUnavailableException("docker host not connected"));

    mvc.perform(get("/api/images/tags").param("hostId", "dh-local"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("docker host not connected"));

    verifyNoInteractions(catalog);
  }

  @Test
  void anUnknownHostIsANotFound() throws Exception {
    when(hosts.requireConnected("dh-ghost"))
        .thenThrow(new NoSuchElementException("unknown docker host: dh-ghost"));

    mvc.perform(get("/api/images/tags").param("hostId", "dh-ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown docker host: dh-ghost"));
  }

  @Test
  void aMissingHostIdKeepsTheErrorBodyShapeEveryOtherFailureHonours() throws Exception {
    // hostId is required, and the frontend treats a non-{"error": ...} body as a parse
    // failure rather than a message it can show
    mvc.perform(get("/api/images/tags"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verifyNoInteractions(catalog);
  }
}
