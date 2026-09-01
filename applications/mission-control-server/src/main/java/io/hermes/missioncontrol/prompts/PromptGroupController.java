package io.hermes.missioncontrol.prompts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prompt groups — how the prompt library is filed, and nothing else.
 *
 * <p>Four routes, no behaviour. There is nothing to deploy: unlike a skill, a prompt is text
 * for a person to paste, so neither it nor a set of them ever reaches a container.
 *
 * <p>{@code promptIds} is stored as given and never checked against the {@code prompts} table.
 * The rows behind those ids can be deleted at any moment, so the honest answer is to resolve
 * them on read and drop what is gone; validating here would only move the lie earlier.
 */
@RestController
@RequestMapping("/api/prompt-groups")
public class PromptGroupController {

  private static final int MAX_PROMPTS = 200;

  public record UpsertPromptGroupRequest(
      @NotBlank @Size(max = 80) String name,
      @Size(max = 2_000) String description,
      @Size(max = MAX_PROMPTS) List<@Size(max = 64) String> promptIds) {
  }

  private final PromptGroupRepository repository;

  public PromptGroupController(PromptGroupRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public List<PromptGroup> list() {
    return repository.findAll();
  }

  @PostMapping
  public PromptGroup create(@Valid @RequestBody UpsertPromptGroupRequest request) {
    long now = System.currentTimeMillis();
    PromptGroup group = normalize(
        "pg-" + UUID.randomUUID().toString().substring(0, 8), request, now, now);
    repository.insert(group);
    return group;
  }

  @PutMapping("/{id}")
  public PromptGroup update(
      @PathVariable String id, @Valid @RequestBody UpsertPromptGroupRequest request) {
    PromptGroup existing = repository.find(id).orElseThrow(() -> unknown(id));
    PromptGroup updated = normalize(
        existing.id(), request, existing.createdAt(), System.currentTimeMillis());
    repository.update(updated);
    return updated;
  }

  /** Idempotent, and reaches nothing: deleting a group leaves every prompt it named in the
   *  library. Only the filing goes. */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    repository.delete(id);
  }

  // ── normalization ──────────────────────────────────────────────────────────

  private static PromptGroup normalize(
      String id, UpsertPromptGroupRequest request, long createdAt, long now) {
    return new PromptGroup(
        id, request.name().trim(), blankToNull(request.description()),
        ids(request.promptIds()), createdAt, now);
  }

  /** Blanks dropped, duplicates dropped, order kept — the order is what the group's prompts
   *  are listed in, so it is the operator's and not a set's. */
  private static List<String> ids(List<String> raw) {
    if (raw == null) {
      return List.of();
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (String id : raw) {
      if (id != null && !id.isBlank()) {
        ids.add(id.trim());
      }
    }
    return List.copyOf(ids);
  }

  private static String blankToNull(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }

  private static NoSuchElementException unknown(String id) {
    return new NoSuchElementException("unknown prompt group: " + id);
  }
}
