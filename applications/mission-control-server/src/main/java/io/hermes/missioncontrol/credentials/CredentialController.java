package io.hermes.missioncontrol.credentials;

import io.hermes.missioncontrol.credentials.api.CredentialDto;
import io.hermes.missioncontrol.credentials.api.UpsertCredentialRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credential library: CRUD and nothing else.
 *
 * <p>No route here resolves a secret, so none can return one. The three writes that take a
 * credential id resolve it in the controller that owns the write — an agent's {@code .env}, a
 * new profile, a blueprint's secrets list — and a picker never handles key material itself.
 *
 * <p>There is deliberately no apply-to-an-agent route. Picking a credential on the Setup tab
 * fills every row that credential covers, so the bundle case is already one choice; a route
 * that wrote them in one request would save a button press and duplicate the resolution.
 */
@RestController
@RequestMapping("/api/credentials")
class CredentialController {

  private final CredentialService credentials;

  CredentialController(CredentialService credentials) {
    this.credentials = credentials;
  }

  @GetMapping
  public List<CredentialDto> list() {
    return credentials.list();
  }

  @PostMapping
  public CredentialDto create(@Valid @RequestBody UpsertCredentialRequest request) {
    return credentials.create(request);
  }

  @PutMapping("/{id}")
  public CredentialDto update(
      @PathVariable String id, @Valid @RequestBody UpsertCredentialRequest request) {
    return credentials.update(id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    credentials.delete(id);
  }
}
