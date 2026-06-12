package io.hermes.missioncontrol.models;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
public class ModelCatalogController {

  public record LiveModelsRequest(@NotBlank String apiKey) {}

  private final ModelCatalogService catalog;

  public ModelCatalogController(ModelCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/{provider}")
  public ModelCatalogDto configured(@PathVariable String provider) {
    return catalog.configured(provider);
  }

  @PostMapping("/{provider}")
  public ModelCatalogDto live(@PathVariable String provider, @Valid @RequestBody LiveModelsRequest request) {
    return catalog.live(provider, request.apiKey().trim());
  }
}
