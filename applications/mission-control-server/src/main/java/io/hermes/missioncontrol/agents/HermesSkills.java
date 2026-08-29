package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.agents.api.SkillFilesDto;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A profile's skills: the SKILL.md files on disk, the enable/disable list in
 * {@code config.yaml}, and the install/uninstall paths.
 *
 * <p>Split out of {@link HermesProfiles} because a skill's identity is not its
 * directory — frontmatter may rename it, and the layout is flat or category-nested —
 * so resolving one takes several reads that no other concern shares.
 */
@Component
class HermesSkills {

  private final HermesContainerFiles files;
  private final HermesConfigEditor config;

  HermesSkills(HermesContainerFiles files, HermesConfigEditor config) {
    this.files = files;
    this.config = config;
  }

  private record SkillMeta(String name, String source, String version, String description) {}

  // ── listing ────────────────────────────────────────────────────────────────

  /**
   * Every skill the profile has, with its frontmatter.
   *
   * <p>Two execs, not {@code 2 + one per skill}. This ran on the agents listing, which runs once
   * per profile, on a twelve-second poll — so a container with the bundled skill set spent most
   * of that poll waiting on {@code cat}, one skill at a time. The manifest rides along in the
   * same batched read as the skill files.
   */
  List<SkillDto> list(DockerHostRef host, String containerId, String profileName, Map<?, ?> configMap) {
    String skillsDir = ProfilePaths.skillsDir(profileName);
    Set<String> disabled = disabledSkills(configMap, ProfilePaths.PLATFORM_CLI);

    String manifestPath = skillsDir + "/.bundled_manifest";
    List<String> skillMdPaths = findSkillMdPaths(host, containerId, skillsDir);
    List<String> toRead = new ArrayList<>(skillMdPaths);
    toRead.add(manifestPath);
    Map<String, String> read = files.readFiles(host, containerId, toRead);

    Set<String> bundled = bundledSkillNames(read.get(manifestPath));
    List<SkillDto> skills = new ArrayList<>();
    for (String skillMdPath : skillMdPaths) {
      String skillMd = read.get(skillMdPath);
      if (skillMd == null || skillMd.isBlank()) continue;
      SkillMeta meta = parseSkillMeta(skillMd, skillDirName(skillMdPath));
      String source = resolveSkillSource(meta, bundled);
      boolean enabled = !disabled.contains(meta.name());
      skills.add(new SkillDto(
          meta.name(), meta.name(), source, meta.version(), meta.description(), enabled));
    }
    return skills;
  }

  /** Reads a skill's SKILL.md body plus its file list, for inspection/editing. */
  SkillContentDto readContent(DockerHostRef host, String containerId, String profileName, String skillName) {
    requireValidSkillName(skillName);
    String skillDir = requireSkillDir(host, containerId, profileName, skillName);
    String body = files.readFile(host, containerId, skillDir + "/SKILL.md");
    return new SkillContentDto(skillName, skillDir, body, listSkillFiles(host, containerId, skillDir));
  }

  // ── mutation ───────────────────────────────────────────────────────────────

  /** Adds or removes the skill from {@code skills.platform_disabled.cli}, leaving every
   *  other key in the config — including ones Mission Control does not model — intact.
   *
   *  <p>Serialized and written atomically for the reason given on
   *  {@code HermesProfileMcp.rewriteConfig}: a toggle that read the file before another edit
   *  landed would otherwise write that edit back out again. */
  void setEnabled(
      DockerHostRef host, String containerId, String profileName, String skillName, boolean enabled) {
    if (skillName == null || skillName.isBlank()) {
      throw new IllegalArgumentException("missing skill name");
    }
    files.serialized(containerId, profileName, () -> {
      String configPath = files.requireProfileDir(host, containerId, profileName) + "/config.yaml";
      String configYaml = files.readFile(host, containerId, configPath);
      Map<Object, Object> root = config.parseForEdit(configYaml, configPath);
      Map<Object, Object> skills = config.asMutableMap(root.get("skills"));
      root.put("skills", skills);
      Map<Object, Object> platformDisabled = config.asMutableMap(skills.get("platform_disabled"));
      skills.put("platform_disabled", platformDisabled);
      List<Object> cliDisabled = YamlValues.asMutableList(platformDisabled.get(ProfilePaths.PLATFORM_CLI));
      platformDisabled.put(ProfilePaths.PLATFORM_CLI, cliDisabled);

      if (enabled) {
        cliDisabled.removeIf(x -> skillName.equals(YamlValues.stringValue(x)));
      } else {
        boolean present = cliDisabled.stream().anyMatch(x -> skillName.equals(YamlValues.stringValue(x)));
        if (!present) cliDisabled.add(skillName);
      }

      files.writeFileAtomically(host, containerId, configPath, YamlValues.dump(root));
    });
  }

  void install(DockerHostRef host, String containerId, String profileName, String skillId) {
    if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("missing skill name");
    // skill ids flow in from reusable templates (user-authored) — validate the
    // same way uninstall does so a stray value can't be parsed as a CLI flag
    if (!ProfilePaths.isValidName(skillId)) {
      throw new IllegalArgumentException("invalid skill id: " + skillId);
    }
    files.exec(host, containerId,
        ProfilePaths.hermesCli(profileName, "skills", "install", skillId, "--force"));
  }

  void uninstall(DockerHostRef host, String containerId, String profileName, String skillName) {
    if (skillName == null || skillName.isBlank()) throw new IllegalArgumentException("missing skill name");
    if (!ProfilePaths.isValidName(skillName)) throw new IllegalArgumentException("invalid skill name");
    // `hermes skills uninstall` prompts "Confirm [y/N]" (no --yes flag) and
    // reports failures on stdout with exit code 0, so it cannot be driven
    // reliably through a non-tty exec — remove the skill directory instead.
    files.removeTree(host, containerId, requireSkillDir(host, containerId, profileName, skillName));
  }

  /** A skill deployed from the library may not bring more than this. Both caps refuse
   *  rather than truncate: a silently half-imported skill is worse than a failed import. */
  private static final int MAX_SKILL_FILES = 64;
  private static final int MAX_SKILL_BYTES = 512 * 1024;

  /**
   * Writes a whole skill directory from the library onto a profile.
   *
   * <p>Guarded on the <em>profile</em> rather than the skill dir — the difference from
   * {@link #updateContent}, which edits a skill that already exists. A deploy's whole point
   * is that the directory does not exist yet, and {@code writeFile} runs {@code mkdir -p},
   * so without {@code requireProfileDir} a mistyped profile name would mint a phantom one.
   *
   * <p>Every path is validated before the first write. A rejection discovered halfway
   * through would leave a half-deployed skill, which hermes would still try to load.
   *
   * <p>Serialized not for a read-modify-write — there is none — but so two deploys of the
   * same skill cannot interleave and mix files from two versions into one directory.
   *
   * <p>ponytail: the set is not atomic. A failure partway leaves a partial directory; the
   * upgrade path is writing to a temp dir and renaming it into place.
   */
  void writeSkillFiles(
      DockerHostRef host, String containerId, String profileName, String skillName,
      Map<String, String> skillFiles) {
    requireValidSkillName(skillName);
    if (skillFiles == null || skillFiles.isEmpty()) {
      throw new IllegalArgumentException("missing skill files");
    }
    if (skillFiles.size() > MAX_SKILL_FILES) {
      throw new IllegalArgumentException("too many skill files: " + skillFiles.size());
    }

    // resolve every path up front, so a bad one rejects before anything is written
    Map<String, String> resolved = new LinkedHashMap<>();
    long bytes = 0;
    for (Map.Entry<String, String> file : skillFiles.entrySet()) {
      String body = file.getValue() == null ? "" : file.getValue();
      bytes += body.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_SKILL_BYTES) {
        throw new IllegalArgumentException("skill exceeds " + MAX_SKILL_BYTES + " bytes");
      }
      resolved.put(ProfilePaths.skillFile(profileName, skillName, file.getKey()), body);
    }

    files.serialized(containerId, profileName, () -> {
      files.requireProfileDir(host, containerId, profileName);
      // one exec per file: an operator-initiated one-shot, not a poll
      resolved.forEach((path, body) -> files.writeFile(host, containerId, path, body));
    });
  }

  /**
   * A skill's whole file set, with contents — what {@link #readContent} cannot give,
   * since {@code listSkillFiles} returns names only.
   *
   * <p>Two execs: the existing listing, then one batched read for every body.
   *
   * <p>A file holding a NUL byte is reported rather than returned. The exec pipe is UTF-8,
   * so a binary asset would arrive corrupted, and silently storing that corruption in the
   * library is the one failure here that loses data rather than raising.
   */
  SkillFilesDto readSkillFiles(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    requireValidSkillName(skillName);
    String skillDir = requireSkillDir(host, containerId, profileName, skillName);
    List<String> names = listSkillFiles(host, containerId, skillDir);
    if (names.size() > MAX_SKILL_FILES) {
      throw new IllegalArgumentException(
          "skill has more than " + MAX_SKILL_FILES + " files: " + names.size());
    }

    List<String> paths = names.stream().map(name -> skillDir + "/" + name).toList();
    Map<String, String> read = files.readFiles(host, containerId, paths);

    Map<String, String> contents = new LinkedHashMap<>();
    List<String> skipped = new ArrayList<>();
    long bytes = 0;
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      String body = read.getOrDefault(paths.get(i), "");
      if (body.indexOf('\0') >= 0) {
        skipped.add(name);
        continue;
      }
      bytes += body.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_SKILL_BYTES) {
        throw new IllegalArgumentException("skill exceeds " + MAX_SKILL_BYTES + " bytes");
      }
      contents.put(name, body);
    }
    return new SkillFilesDto(skillName, skillDir, contents, List.copyOf(skipped));
  }

  /** Overwrites a skill's SKILL.md. The caller re-reads the profile so the refreshed
   *  name/version/description/source flow back. */
  void updateContent(
      DockerHostRef host, String containerId, String profileName, String skillName, String body) {
    requireValidSkillName(skillName);
    if (body == null) throw new IllegalArgumentException("missing skill body");
    String skillDir = requireSkillDir(host, containerId, profileName, skillName);
    files.writeFile(host, containerId, skillDir + "/SKILL.md", body);
  }

  // ── resolution ─────────────────────────────────────────────────────────────

  private static void requireValidSkillName(String skillName) {
    if (!ProfilePaths.isValidName(skillName)) {
      throw new IllegalArgumentException("invalid skill name");
    }
  }

  private String requireSkillDir(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    String skillDir = findSkillDir(host, containerId, profileName, skillName);
    if (skillDir == null) throw new IllegalArgumentException("skill not found: " + skillName);
    return skillDir;
  }

  /** Resolves the directory backing a skill: the dir name usually matches the
   *  skill name, but SKILL.md frontmatter may override the display name. Searches
   *  flat and category-nested layouts alike. */
  private String findSkillDir(
      DockerHostRef host, String containerId, String profileName, String skillName) {
    String skillsDir = ProfilePaths.skillsDir(profileName);
    String direct = skillsDir + "/" + skillName;
    if (files.dirExists(host, containerId, direct)) return direct;
    for (String skillMdPath : findSkillMdPaths(host, containerId, skillsDir)) {
      String dir = parentDir(skillMdPath);
      String dirName = skillDirName(skillMdPath);
      if (skillName.equals(dirName)) return dir;
      String skillMd = files.readFile(host, containerId, skillMdPath);
      if (!skillMd.isBlank() && skillName.equals(parseSkillMeta(skillMd, dirName).name())) {
        return dir;
      }
    }
    return null;
  }

  /** All SKILL.md paths under a profile's skills dir — flat (skills/&lt;x&gt;/SKILL.md)
   *  AND category-nested (skills/&lt;category&gt;/&lt;x&gt;/SKILL.md), skipping curator
   *  backups and other dot-dirs. The old flat-only `ls` missed nested skills. */
  private List<String> findSkillMdPaths(DockerHostRef host, String containerId, String skillsDir) {
    ExecResult find = files.exec(host, containerId, List.of("sh", "-lc",
        "find \"$1\" -mindepth 1 -maxdepth 3 -name SKILL.md -not -path '*/.*' 2>/dev/null || true",
        "_", skillsDir));
    return HermesContainerFiles.lines(find.stdout());
  }

  /** Relative file paths inside a skill dir (skipping dot-files), for the UI. */
  private List<String> listSkillFiles(DockerHostRef host, String containerId, String skillDir) {
    String script = "d=\"$1\"; cd \"$d\" 2>/dev/null || exit 0; "
        + "find . -maxdepth 3 -type f -not -path '*/.*' 2>/dev/null | sed 's|^\\./||' | sort";
    ExecResult ls = files.exec(host, containerId, List.of("sh", "-lc", script, "_", skillDir));
    return new ArrayList<>(HermesContainerFiles.lines(ls.stdout()));
  }

  private static String parentDir(String skillMdPath) {
    int fileSlash = skillMdPath.lastIndexOf('/');
    return fileSlash >= 0 ? skillMdPath.substring(0, fileSlash) : skillMdPath;
  }

  /** Skill directory name from a `.../&lt;dir&gt;/SKILL.md` path. */
  private static String skillDirName(String skillMdPath) {
    String dir = parentDir(skillMdPath);
    int dirSlash = dir.lastIndexOf('/');
    return dirSlash >= 0 ? dir.substring(dirSlash + 1) : dir;
  }

  /** Names listed in skills/.bundled_manifest ("name:hash" per line) ship with
   *  Hermes. Anything present on disk but absent here was created locally — by
   *  the agent itself or the curator (which authors umbrella skills). */
  private static Set<String> bundledSkillNames(String manifest) {
    Set<String> names = new HashSet<>();
    if (manifest == null) return names;
    for (String line : HermesContainerFiles.lines(manifest)) {
      int colon = line.indexOf(':');
      String name = colon >= 0 ? line.substring(0, colon).trim() : line;
      if (!name.isEmpty()) names.add(name);
    }
    return names;
  }

  /** Frontmatter `source` wins when an author declares it; otherwise a skill in
   *  the bundled manifest is "bundled" and everything else is agent-authored "user". */
  private static String resolveSkillSource(SkillMeta meta, Set<String> bundled) {
    if (meta.source() != null && !meta.source().isBlank()) return meta.source();
    return bundled.contains(meta.name()) ? "bundled" : "user";
  }

  private static Set<String> disabledSkills(Map<?, ?> configMap, String platform) {
    Set<String> disabled = new HashSet<>();
    if (configMap == null) return disabled;
    Object skills = configMap.get("skills");
    if (!(skills instanceof Map<?, ?> skillsMap)) return disabled;
    addStringList(disabled, skillsMap.get("disabled"));
    Object platformDisabled = skillsMap.get("platform_disabled");
    if (platformDisabled instanceof Map<?, ?> platformMap) {
      addStringList(disabled, platformMap.get(platform));
    }
    return disabled;
  }

  private static void addStringList(Set<String> out, Object node) {
    if (node instanceof List<?> list) {
      for (Object v : list) {
        String s = YamlValues.stringValue(v);
        if (!s.isBlank()) out.add(s);
      }
    }
  }

  private static SkillMeta parseSkillMeta(String skillMd, String fallbackName) {
    String text = skillMd == null ? "" : skillMd;
    if (text.startsWith("---")) {
      int end = text.indexOf("\n---", 3);
      if (end > 0) {
        Map<?, ?> meta = YamlValues.parseMap(text.substring(3, end));
        if (!meta.isEmpty()) {
          String name = YamlValues.stringValue(meta.get("name"));
          String description = YamlValues.stringValue(meta.get("description"));
          String version = YamlValues.stringValue(meta.get("version"));
          // frontmatter may declare its origin; blank means "infer from manifest"
          String source = YamlValues.stringValue(meta.get("source"));
          return new SkillMeta(name.isBlank() ? fallbackName : name, source, version, description);
        }
      }
    }
    return new SkillMeta(fallbackName, "", "", "");
  }
}
