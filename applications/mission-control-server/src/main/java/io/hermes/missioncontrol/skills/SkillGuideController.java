package io.hermes.missioncontrol.skills;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.AgentTargetRequest;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.agents.api.DeployedPart;
import io.hermes.missioncontrol.common.IdList;
import io.hermes.missioncontrol.common.Text;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>The deploy is the reason this class is not as thin as {@link SkillController}. It is
 * several independent writes to someone else's agent, and they can fail one at a time: a
 * skill deleted from the library since the guide named it, an MCP alias already taken, a
 * managed server that is not running. So it follows the rule
 * {@code agents.templates.TemplateApplier} states for layering onto a profile the caller
 * does not own — <em>surface the error, do not roll back</em> — and reports what each part
 * did rather than collapsing the lot into one status.
 *
 * <p>Undoing half a guide would mean removing skills and MCP entries that may have been on
 * that agent before the guide ever ran. Reporting beats guessing.
 */
@RestController
@RequestMapping("/api/skill-guides")
public class SkillGuideController {

  private static final Logger log = LoggerFactory.getLogger(SkillGuideController.class);

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

  /** The refreshed profile, plus one row per part so a half-applied guide is legible. */
  public record DeployedGuide(AgentProfileDto profile, List<DeployedPart> parts) {
  }

  private final SkillGuideRepository repository;
  private final SkillRepository skills;
  private final SkillDeployer deployer;
  private final McpRegistryService registry;
  private final AgentMcpCatalogService mcpCatalog;
  private final HermesProfiles profiles;
  private final HostService hosts;

  public SkillGuideController(
      SkillGuideRepository repository, SkillRepository skills, SkillDeployer deployer,
      McpRegistryService registry, AgentMcpCatalogService mcpCatalog, HermesProfiles profiles,
      HostService hosts) {
    this.repository = repository;
    this.skills = skills;
    this.deployer = deployer;
    this.registry = registry;
    this.mcpCatalog = mcpCatalog;
    this.profiles = profiles;
    this.hosts = hosts;
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

  /**
   * Puts a whole guide on one agent: every skill it names, every MCP server it needs, and
   * the guide itself as an umbrella skill the agent can read.
   *
   * <p>The umbrella skill is written <em>last</em> and names only the parts that actually
   * landed. Writing it first would tell the agent to reach for a skill that then failed to
   * deploy, which is worse than not mentioning it.
   */
  @PostMapping("/{id}/deploy")
  public DeployedGuide deploy(
      @PathVariable String id, @Valid @RequestBody AgentTargetRequest request) {
    SkillGuide guide = repository.find(id).orElseThrow(() -> unknown(id));
    DockerHostRef host = hosts.requireConnected(request.hostId());
    String containerId = request.containerId();
    String profile = request.profile();

    List<DeployedPart> parts = new ArrayList<>();
    List<String> deployedSkills = new ArrayList<>();
    List<String> linkedServers = new ArrayList<>();

    for (String skillId : guide.skillIds()) {
      Skill skill = skills.find(skillId).orElse(null);
      if (skill == null) {
        // the guide outlived the skill; say which one rather than failing the whole deploy
        parts.add(new DeployedPart("skill", skillId, DeployedPart.SKIPPED,
            "no longer in the library"));
        continue;
      }
      try {
        deployer.deploy(host, containerId, profile, skill);
        deployedSkills.add(skill.name());
        parts.add(DeployedPart.ok("skill", skill.name()));
      } catch (RuntimeException failure) {
        parts.add(failed("skill", skill.name(), failure));
      }
    }

    for (String serverId : guide.mcpServerIds()) {
      String name = serverId;
      try {
        name = registry.definition(serverId).name();
        mcpCatalog.connect(host, containerId, profile,
            new ConnectCatalogMcpRequest(serverId, name));
        linkedServers.add(name);
        parts.add(DeployedPart.ok("mcp", name));
      } catch (NoSuchElementException gone) {
        parts.add(new DeployedPart("mcp", name, DeployedPart.SKIPPED, "no longer in the catalog"));
      } catch (RuntimeException failure) {
        // an alias already on the agent is the common one, and it is not a problem: the
        // server the guide wanted is already there
        parts.add(failed("mcp", name, failure));
      }
    }

    // A guide and a library skill of the same name both resolve to skills/<name>/, so
    // writing the umbrella there would replace that skill's own SKILL.md with this
    // document — silently, and on the agent rather than here. Refuse that part instead.
    String clash = skills.findByName(guide.name()).map(Skill::name).orElse(null);
    if (clash != null) {
      parts.add(new DeployedPart("guide", guide.name(), DeployedPart.FAILED,
          "a library skill is also called '" + clash + "', and both deploy to skills/"
              + clash + " — rename one"));
    } else {
      try {
        profiles.installSkillFiles(host, containerId, profile, guide.name(),
            Map.of(Skill.SKILL_MD, GuideDocument.render(guide, deployedSkills, linkedServers)));
        parts.add(DeployedPart.ok("guide", guide.name()));
      } catch (RuntimeException failure) {
        parts.add(failed("guide", guide.name(), failure));
      }
    }

    return new DeployedGuide(refreshedProfile(host, containerId, profile, parts),
        List.copyOf(parts));
  }

  /**
   * The profile as it now stands, or null if reading it back fails.
   *
   * <p>Null rather than a throw: by this point the parts have already landed on the agent,
   * and letting a failed re-read propagate would answer 500 and discard the report of what
   * was written — the one thing a half-applied deploy needs to hand back.
   */
  private AgentProfileDto refreshedProfile(
      DockerHostRef host, String containerId, String profile, List<DeployedPart> parts) {
    try {
      return mcpCatalog.enrich(host, profiles.get(host, containerId, profile));
    } catch (RuntimeException failure) {
      log.warn("guide deploy: could not re-read profile '{}' afterwards: {}",
          profile, failure.getMessage());
      parts.add(new DeployedPart("guide", profile, DeployedPart.FAILED,
          "deployed, but the agent could not be read back: " + failure.getMessage()));
      return null;
    }
  }

  private static DeployedPart failed(String kind, String name, RuntimeException failure) {
    log.warn("guide deploy: {} '{}' failed: {}", kind, name, failure.getMessage());
    return new DeployedPart(kind, name, DeployedPart.FAILED, failure.getMessage());
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
