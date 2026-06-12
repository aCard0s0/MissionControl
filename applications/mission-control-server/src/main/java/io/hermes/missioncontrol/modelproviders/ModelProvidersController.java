package io.hermes.missioncontrol.modelproviders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-providers")
public class ModelProvidersController {

  public record CreateModelProviderRequest(@NotBlank String name, @NotBlank String url) {}

  public record ModelNameRequest(@NotBlank String name) {}

  private final ModelProviderService providers;

  public ModelProvidersController(ModelProviderService providers) {
    this.providers = providers;
  }

  @GetMapping
  public List<ModelProviderDto> list() {
    return providers.list();
  }

  @PostMapping
  public ModelProviderDto add(@Valid @RequestBody CreateModelProviderRequest request) {
    return providers.add(request.name().trim(), request.url().trim());
  }

  @PostMapping("/{id}/check")
  public ModelProviderDto check(@PathVariable String id) {
    return providers.check(id);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    providers.delete(id);
  }

  @GetMapping("/{id}/models")
  public List<OllamaModelDto> models(@PathVariable String id) {
    return providers.models(id);
  }

  @PostMapping("/{id}/models/pull")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void pull(@PathVariable String id, @Valid @RequestBody ModelNameRequest request) {
    providers.pull(id, request.name().trim());
  }

  @GetMapping("/{id}/pulls")
  public List<PullStatusDto> pulls(@PathVariable String id) {
    return providers.pulls(id);
  }

  @PostMapping("/{id}/models/delete")
  public void deleteModel(@PathVariable String id, @Valid @RequestBody ModelNameRequest request) {
    providers.deleteModel(id, request.name().trim());
  }
}
