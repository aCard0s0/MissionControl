package io.hermes.missioncontrol.agents.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.agents.ModelProviderRegistry;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The provider list the frontend picker is built from. */
class ProvidersControllerTest {

  private final MockMvc mvc = MockMvcBuilders
      .standaloneSetup(new ProvidersController())
      .setControllerAdvice(new ApiExceptionHandler())
      .build();

  @Test
  void theProviderListMirrorsTheRegistryRowForRowIncludingNeedsKey() throws Exception {
    mvc.perform(get("/api/providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(ModelProviderRegistry.PROVIDERS.size()))
        // Nous is OAuth: the picker must not ask for a key, and there is no env slot
        .andExpect(jsonPath("$[0].key").value("nous"))
        .andExpect(jsonPath("$[0].oauth").value(true))
        .andExpect(jsonPath("$[0].needsKey").value(false))
        .andExpect(jsonPath("$[0].envVar").doesNotExist())
        // a key-based provider carries both the flag and the env var the agent writes to
        .andExpect(jsonPath("$[?(@.key == 'anthropic')].needsKey").value(true))
        .andExpect(jsonPath("$[?(@.key == 'anthropic')].envVar").value("ANTHROPIC_API_KEY"))
        .andExpect(jsonPath("$[?(@.key == 'anthropic')].hasCatalog").value(true))
        // and one without a curated catalog takes a free-text model id instead
        .andExpect(jsonPath("$[?(@.key == 'gemini')].hasCatalog").value(false));
  }
}
