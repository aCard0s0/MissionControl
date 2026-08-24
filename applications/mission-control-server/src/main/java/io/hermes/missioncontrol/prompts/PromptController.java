package io.hermes.missioncontrol.prompts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The prompt library — dashboard-owned, like the ops board and unlike everything read
 * through to a container.
 *
 * <p>Thin on purpose: there is no service layer because there is no rule beyond
 * "normalize the input and write it down". The two normalizations that do exist are
 * here rather than in the repository, because they are decisions about what an
 * operator typed: a blank category becomes {@code general} and categories are folded
 * to lower case, so the filter chips on the page never show {@code Ops} beside
 * {@code ops}.
 */
@RestController
@RequestMapping("/api/prompts")
public class PromptController {

  /** Default category for a prompt saved without one. */
  static final String DEFAULT_CATEGORY = "general";

  /** Enough tags to organize a prompt, few enough that the row still reads. */
  private static final int MAX_TAGS = 12;

  public record UpsertPromptRequest(
      @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 20_000) String body,
      @Size(max = 60) String category,
      @Size(max = 2_000) String notes,
      List<@Size(max = 40) String> tags) {
  }

  private final PromptRepository repository;

  public PromptController(PromptRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public List<Prompt> list(@RequestParam(required = false) String category) {
    return category == null || category.isBlank()
        ? repository.findAll()
        : repository.findByCategory(category(category));
  }

  @GetMapping("/{id}")
  public Prompt get(@PathVariable String id) {
    return repository.find(id).orElseThrow(() -> new NoSuchElementException("unknown prompt: " + id));
  }

  @PostMapping
  public Prompt create(@Valid @RequestBody UpsertPromptRequest request) {
    long now = System.currentTimeMillis();
    Prompt prompt = new Prompt(
        "p-" + UUID.randomUUID().toString().substring(0, 8),
        request.title().trim(),
        request.body(),
        category(request.category()),
        notes(request.notes()),
        tags(request.tags()),
        now,
        now);
    repository.insert(prompt);
    return prompt;
  }

  /**
   * Replaces everything an editor owns, keeping {@code createdAt} — the row is read back
   * first for exactly that reason, and because a PUT at an id nobody holds has to be a
   * 404 rather than a silent no-op.
   */
  @PutMapping("/{id}")
  public Prompt update(@PathVariable String id, @Valid @RequestBody UpsertPromptRequest request) {
    Prompt existing = repository.find(id)
        .orElseThrow(() -> new NoSuchElementException("unknown prompt: " + id));
    Prompt updated = new Prompt(
        existing.id(),
        request.title().trim(),
        request.body(),
        category(request.category()),
        notes(request.notes()),
        tags(request.tags()),
        existing.createdAt(),
        System.currentTimeMillis());
    repository.update(updated);
    return updated;
  }

  /** Idempotent: a double-click on delete is not a 404. */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    repository.delete(id);
  }

  private static String category(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_CATEGORY;
    }
    return raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String notes(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }

  /** Trimmed, blanks dropped, duplicates dropped, order kept, capped. */
  private static List<String> tags(List<String> raw) {
    if (raw == null) {
      return List.of();
    }
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    for (String tag : raw) {
      if (tag == null || tag.isBlank()) {
        continue;
      }
      tags.add(tag.trim().toLowerCase(Locale.ROOT));
      if (tags.size() == MAX_TAGS) {
        break;
      }
    }
    return List.copyOf(tags);
  }
}
