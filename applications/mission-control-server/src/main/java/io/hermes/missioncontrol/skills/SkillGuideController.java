package io.hermes.missioncontrol.skills;

import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AgentTargetRequest;
import io.hermes.missioncontrol.common.IdList;
import io.hermes.missioncontrol.common.Text;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * Guides — prose that teaches how to use several skills together, and the one button that
 * puts the whole set on an agent.
 *
 * <p>The deploy is {@link GuideDeploy}'s — several independent writes to someone else's agent,
 * failing one at a time, with an ordering that matters. This class is as thin as
 * {@link SkillController} again: CRUD over the guide rows, and a handler that resolves the
 * guide and the host and hands them over.
 */
@RestController
@RequestMapping("/api/skill-guides")
public class SkillGuideController {

  static final String DEFAULT_CATEGORY = "general";

  private static final int MAX_REFS = 32;

  public record UpsertGuideRequest(
      @NotBlank @Size(max = 80) @Pattern(regexp = ProfileSpec.NAME_PATTERN) String name,
      @Size(max = 2_000) String description,
      @NotBlank @Size(max = 50_000) String body,
      @Size(max = 60) String category,
      @Size(max = MAX_REFS) List<@Size(max = 64) String> skillIds,
      @Size(max = MAX_REFS) List<@Size(max = 64) String> mcpServerIds) {
  }

  private final SkillGuideRepository repository;
  private final HostService hosts;
  private final GuideDeploy deploy;

  public SkillGuideController(
      SkillGuideRepository repository, HostService hosts, GuideDeploy deploy) {
    this.repository = repository;
    this.hosts = hosts;
    this.deploy = deploy;
  }

  @GetMapping
  public List<SkillGuide> list(@RequestParam(required = false) String category) {
    return category == null || category.isBlank()
        ? repository.findAll()
        : repository.findByCategory(category(category));
  }

  @PostMapping
  public SkillGuide create(@Valid @RequestBody UpsertGuideRequest request) {
    long now = System.currentTimeMillis();
    SkillGuide guide = normalize(
        "g-" + UUID.randomUUID().toString().substring(0, 8), request, now, now);
    repository.insert(guide);
    return guide;
  }

  @PutMapping("/{id}")
  public SkillGuide update(@PathVariable String id, @Valid @RequestBody UpsertGuideRequest request) {
    SkillGuide existing = repository.find(id).orElseThrow(() -> unknown(id));
    SkillGuide updated = normalize(
        existing.id(), request, existing.createdAt(), System.currentTimeMillis());
    repository.update(updated);
    return updated;
  }

  /** Idempotent, and reaches no agent: deleting a guide leaves everything it ever deployed
   *  exactly where it is, including its umbrella skill. */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    repository.delete(id);
  }

  /** Resolves the guide and the host, and hands both to {@link GuideDeploy}. */
  @PostMapping("/{id}/deploy")
  public GuideDeploy.Deployed deploy(
      @PathVariable String id, @Valid @RequestBody AgentTargetRequest request) {
    SkillGuide guide = repository.find(id).orElseThrow(() -> unknown(id));
    return deploy.onto(guide, hosts.requireConnected(request.hostId()),
        request.containerId(), request.profile());
  }

  // ── normalization ──────────────────────────────────────────────────────────

  private static SkillGuide normalize(
      String id, UpsertGuideRequest request, long createdAt, long now) {
    return new SkillGuide(
        id, request.name().trim(), Text.blankToNull(request.description()), request.body(),
        category(request.category()), IdList.normalize(request.skillIds()), IdList.normalize(request.mcpServerIds()),
        createdAt, now);
  }


  private static String category(String raw) {
    return raw == null || raw.isBlank() ? DEFAULT_CATEGORY : raw.trim().toLowerCase(Locale.ROOT);
  }


  private static NoSuchElementException unknown(String id) {
    return new NoSuchElementException("unknown guide: " + id);
  }
}
