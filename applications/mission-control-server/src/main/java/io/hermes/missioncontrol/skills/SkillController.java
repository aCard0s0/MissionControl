package io.hermes.missioncontrol.skills;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.SkillFilesDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * The skill library — dashboard-owned, like the prompt library, and unlike the per-agent
 * skills read through to a container by {@code AgentSkillsController}.
 *
 * <p>Thin for the same reason {@link io.hermes.missioncontrol.prompts.PromptController} is:
 * normalize what an operator typed, then write it down. The one rule beyond that is the
 * branch in {@link #deploy}, which is the whole point of the split between a {@code hub}
 * row and a {@code local} one — see {@link Skill}. It is a dozen lines, so it lives here;
 * it earns a class of its own the day a guide has to compose several deploys.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

  /** Default category for a skill saved without one. */
  static final String DEFAULT_CATEGORY = "general";

  private static final int MAX_FILES = 64;

  public record UpsertSkillRequest(
      @NotBlank @Size(max = 10) String kind,
      @NotBlank @Size(max = 80) @Pattern(regexp = ProfileSpec.NAME_PATTERN) String name,
      @Size(max = 2_000) String description,
      @Size(max = 60) String category,
      @Size(max = 500) String repoUrl,
      @Size(max = 40) String version,
      @Size(max = MAX_FILES) List<@Valid SkillFileRequest> files) {
  }

  /**
   * A relative path, one to three segments, each segment a legal directory name.
   *
   * <p>Built from {@link ProfileSpec#NAME_PATTERN} so the charset has one home, and capped at
   * three segments because {@code HermesSkills.listSkillFiles} runs {@code find -maxdepth 3} —
   * a file written deeper is invisible to the call that lists it back.
   *
   * <p>Defence in depth, <em>not</em> the guard: {@code ProfilePaths.skillFile} owns that, and
   * has to hold for every caller of the exec seam rather than only for rows that came through
   * this controller. What this adds is keeping a row that could never deploy out of the library
   * in the first place, so an operator learns at save time rather than at deploy time.
   */
  static final String FILE_PATH_PATTERN =
      ProfileSpec.NAME_PATTERN + "(/" + ProfileSpec.NAME_PATTERN + "){0,2}";

  public record SkillFileRequest(
      @NotBlank @Size(max = 200) @Pattern(regexp = FILE_PATH_PATTERN) String path,
      @Size(max = 200_000) String body) {
  }

  /** Which agent to act on. The three parts are the profile's identity — a profile name is
   *  only unique inside a container. {@code profile} reaches a shell, so it carries the
   *  pattern here as well as being re-guarded by {@code ProfilePaths}. */
  public record AgentTargetRequest(
      @NotBlank String hostId,
      @NotBlank String containerId,
      @NotBlank @Pattern(regexp = ProfileSpec.NAME_PATTERN) String profile) {
  }

  public record ImportSkillRequest(
      @NotBlank String hostId,
      @NotBlank String containerId,
      @NotBlank @Pattern(regexp = ProfileSpec.NAME_PATTERN) String profile,
      @NotBlank @Pattern(regexp = ProfileSpec.NAME_PATTERN) String skillName,
      @Size(max = 60) String category) {
  }

  /** What an import found, so the page can say which files were left behind. */
  public record ImportedSkill(Skill skill, List<String> skipped) {
  }

  private final SkillRepository repository;
  private final SkillDeployer deployer;
  private final HermesProfiles profiles;
  private final HostService hosts;
  private final AgentMcpCatalogService mcpCatalog;

  public SkillController(
      SkillRepository repository, SkillDeployer deployer, HermesProfiles profiles,
      HostService hosts, AgentMcpCatalogService mcpCatalog) {
    this.repository = repository;
    this.deployer = deployer;
    this.profiles = profiles;
    this.hosts = hosts;
    this.mcpCatalog = mcpCatalog;
  }

  @GetMapping
  public List<Skill> list(@RequestParam(required = false) String category) {
    return category == null || category.isBlank()
        ? repository.findAll()
        : repository.findByCategory(category(category));
  }

  @PostMapping
  public Skill create(@Valid @RequestBody UpsertSkillRequest request) {
    long now = System.currentTimeMillis();
    Skill skill = normalize(
        "s-" + UUID.randomUUID().toString().substring(0, 8), request, now, now);
    repository.insert(skill);
    return skill;
  }

  /** Replaces everything an editor owns, keeping {@code createdAt}: the row is read back
   *  first for that reason, and because a PUT at an id nobody holds has to be a 404 rather
   *  than a silent insert. */
  @PutMapping("/{id}")
  public Skill update(@PathVariable String id, @Valid @RequestBody UpsertSkillRequest request) {
    Skill existing = repository.find(id).orElseThrow(() -> unknown(id));
    Skill updated = normalize(
        existing.id(), request, existing.createdAt(), System.currentTimeMillis());
    repository.update(updated);
    return updated;
  }

  /** Idempotent: a double-click on delete is not a 404.
   *
   *  <p>This removes the library row only. Any copy already deployed onto an agent stays
   *  exactly where it is — a library row is a stamp, not a live link, deliberately unlike
   *  an MCP agent link. Removing a deployed skill is the agent's own Skills tab. */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    repository.delete(id);
  }

  /**
   * Puts one library skill onto one agent.
   *
   * <p>The branch is the feature. A {@code hub} row defers to hermes, which resolves the id
   * against the Skills Hub and owns the files it writes. A {@code local} row has no id to
   * resolve — hermes has no {@code skills create} — so its files are written out directly.
   *
   * <p>A local deploy is an overlay, not a sync: it writes the files the row holds and does
   * not remove anything already in the directory, so a file renamed in the library leaves
   * its old copy on the agent.
   */
  @PostMapping("/{id}/deploy")
  public AgentProfileDto deploy(
      @PathVariable String id, @Valid @RequestBody AgentTargetRequest request) {
    Skill skill = repository.find(id).orElseThrow(() -> unknown(id));
    DockerHostRef host = hosts.requireConnected(request.hostId());
    return mcpCatalog.enrich(host, deployer.deploy(
        host, request.containerId(), request.profile(), skill));
  }

  /**
   * Copies a skill off an agent into the library, so one an agent's own curator authored
   * can be kept and pushed to another agent.
   *
   * <p>Always lands as {@code local}: a skill read off a disk has no hub id to install by,
   * even when it originally came from the Hub. Re-importing the same name updates that row
   * rather than colliding with the unique index.
   */
  @PostMapping("/import")
  public ImportedSkill importFromAgent(@Valid @RequestBody ImportSkillRequest request) {
    DockerHostRef host = hosts.requireConnected(request.hostId());
    SkillFilesDto read = profiles.readSkillFiles(
        host, request.containerId(), request.profile(), request.skillName());

    List<SkillFile> files = read.files().entrySet().stream()
        .map(file -> new SkillFile(file.getKey(), file.getValue()))
        .toList();
    requireSkillMd(files);

    long now = System.currentTimeMillis();
    Skill existing = repository.findByName(request.skillName()).orElse(null);
    Skill skill = new Skill(
        existing != null ? existing.id() : "s-" + UUID.randomUUID().toString().substring(0, 8),
        Skill.LOCAL,
        request.skillName(),
        existing != null ? existing.description() : null,
        category(request.category() != null ? request.category()
            : existing != null ? existing.category() : null),
        existing != null ? existing.repoUrl() : null,
        existing != null ? existing.version() : null,
        files,
        existing != null ? existing.createdAt() : now,
        now);
    if (existing != null) {
      repository.update(skill);
    } else {
      repository.insert(skill);
    }
    return new ImportedSkill(skill, read.skipped());
  }

  // ── normalization ──────────────────────────────────────────────────────────

  private static Skill normalize(String id, UpsertSkillRequest request, long createdAt, long now) {
    String kind = request.kind().trim().toLowerCase(Locale.ROOT);
    if (!Skill.HUB.equals(kind) && !Skill.LOCAL.equals(kind)) {
      throw new IllegalArgumentException("skill kind must be 'hub' or 'local', not: " + kind);
    }
    List<SkillFile> files = files(request.files());
    if (Skill.HUB.equals(kind)) {
      // a hub row carrying content would be a second copy of what the Hub owns, going
      // stale the moment the Hub moves — the split exists to prevent exactly that
      if (!files.isEmpty()) {
        throw new IllegalArgumentException(
            "a hub skill carries no files — the Skills Hub owns its content");
      }
    } else {
      requireSkillMd(files);
    }
    return new Skill(
        id, kind, request.name().trim(), blankToNull(request.description()),
        category(request.category()), blankToNull(request.repoUrl()),
        blankToNull(request.version()), files, createdAt, now);
  }

  /** Hermes finds a skill by its SKILL.md. A local row without one could be saved and
   *  deployed and still leave the agent with nothing it can load, so it is rejected at
   *  the door rather than discovered on the agent. */
  private static void requireSkillMd(List<SkillFile> files) {
    boolean present = files.stream().anyMatch(file -> Skill.SKILL_MD.equals(file.path()));
    if (!present) {
      throw new IllegalArgumentException("a local skill needs a " + Skill.SKILL_MD + " file");
    }
  }

  /**
   * Trimmed paths, later duplicates dropped, order kept.
   *
   * <p>Only duplicates are settled here. A blank or malformed path is already a 400 from
   * {@link #FILE_PATH_PATTERN} before this runs, and the path is guarded again by
   * {@code ProfilePaths.skillFile} at the seam — so the one thing left to decide is what an
   * operator meant by naming the same file twice, and the answer is the first one.
   *
   * <p>A {@code null} element is a JSON array hole rather than an invalid object, so bean
   * validation lets it through and it is skipped here.
   */
  private static List<SkillFile> files(List<SkillFileRequest> raw) {
    if (raw == null) {
      return List.of();
    }
    Map<String, SkillFile> files = new LinkedHashMap<>();
    for (SkillFileRequest file : raw) {
      if (file == null) {
        continue;
      }
      String path = file.path().trim();
      files.putIfAbsent(path, new SkillFile(path, file.body() == null ? "" : file.body()));
    }
    return List.copyOf(files.values());
  }

  private static String category(String raw) {
    return raw == null || raw.isBlank() ? DEFAULT_CATEGORY : raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }

  private static NoSuchElementException unknown(String id) {
    return new NoSuchElementException("unknown skill: " + id);
  }
}
