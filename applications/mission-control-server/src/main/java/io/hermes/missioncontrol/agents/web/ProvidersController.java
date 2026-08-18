package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.ModelProviderRegistry;
import io.hermes.missioncontrol.agents.api.ProviderOptionDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the model-provider registry so the frontend picker, the "needs an API
 *  key?" rule, and the provider→catalog decision all share one source of truth. */
@RestController
@RequestMapping("/api/providers")
public class ProvidersController {

  @GetMapping
  public List<ProviderOptionDto> list() {
    return ModelProviderRegistry.PROVIDERS.stream()
        .map(p -> new ProviderOptionDto(p.key(), p.label(), p.needsKey(), p.oauth(), p.hasCatalog(), p.envVar()))
        .toList();
  }
}
