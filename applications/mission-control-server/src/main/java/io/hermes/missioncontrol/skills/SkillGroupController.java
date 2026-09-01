package io.hermes.missioncontrol.skills;

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
 * Skill groups — how the library is filed, and optionally which guide explains a set.
 *
 * <p>The thinnest controller in this package, and that is the point: a group is four fields
 * and no side effects. There is no deploy here. A group names skills so an operator can read
 * the library; pushing a set at an agent is what {@link SkillGuideController} is for, which is
 * also why the association points that way — a group that wants deploying links the guide
 * that does it rather than growing its own.
 *
 * <p>{@code skillIds} and {@code guideId} are stored as given and never checked against the
 * other tables. Same rule as a guide's id lists: production runs with foreign keys off, the
 * referenced row can be deleted at any time, and the honest answer is to resolve on read and
 * mark what is gone. Validating here would only move the lie earlier.
 */
@RestController
@RequestMapping("/api/skill-groups")
public class SkillGroupController {

  private static final int MAX_SKILLS = 200;

  public record UpsertGroupRequest(
      @NotBlank @Size(max = 80) String name,
      @Size(max = 2_000) String description,
      @Size(max = MAX_SKILLS) List<@Size(max = 64) String> skillIds,
      @Size(max = 64) String guideId) {
  }

  private final SkillGroupRepository repository;

  public SkillGroupController(SkillGroupRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public List<SkillGroup> list() {
    return repository.findAll();
  }

  @PostMapping
  public SkillGroup create(@Valid @RequestBody UpsertGroupRequest request) {
    long now = System.currentTimeMillis();
    SkillGroup group = normalize(
        "sg-" + UUID.randomUUID().toString().substring(0, 8), request, now, now);
    repository.insert(group);
    return group;
  }

  @PutMapping("/{id}")
  public SkillGroup update(
      @PathVariable String id, @Valid @RequestBody UpsertGroupRequest request) {
    SkillGroup existing = repository.find(id).orElseThrow(() -> unknown(id));
    SkillGroup updated = normalize(
        existing.id(), request, existing.createdAt(), System.currentTimeMillis());
    repository.update(updated);
    return updated;
  }

  /** Idempotent, and reaches nothing: deleting a group leaves every skill it named in the
   *  library, and the guide it pointed at where it is. Only the filing goes. */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    repository.delete(id);
  }

  // ── normalization ──────────────────────────────────────────────────────────

  private static SkillGroup normalize(
      String id, UpsertGroupRequest request, long createdAt, long now) {
    return new SkillGroup(
        id, request.name().trim(), blankToNull(request.description()),
        ids(request.skillIds()), blankToNull(request.guideId()), createdAt, now);
  }

  /** Blanks dropped, duplicates dropped, order kept — the order is what the group's skills
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
    return new NoSuchElementException("unknown skill group: " + id);
  }
}
