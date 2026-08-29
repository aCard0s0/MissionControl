package io.hermes.missioncontrol.skills;

import static java.util.stream.Collectors.toMap;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/**
 * Putting one library skill onto one agent — the branch on {@code kind}, in one place.
 *
 * <p>This was inline in {@link SkillController} while there was a single caller. A guide
 * deploys several skills in a row and has to report on each one, so the branch now has two
 * callers and the second would have had to duplicate it.
 *
 * <p>A {@code hub} row defers to hermes, which resolves the name against the Skills Hub and
 * owns the files it writes. A {@code local} row has no id to resolve — hermes has no
 * {@code skills create} — so its files are written out directly. Picking the wrong one is
 * silent: an install of a name the Hub never heard of fails on the agent, and writing files
 * for a hub skill would plant a stale copy beside the real one.
 */
@Component
class SkillDeployer {

  private final HermesProfiles profiles;

  SkillDeployer(HermesProfiles profiles) {
    this.profiles = profiles;
  }

  AgentProfileDto deploy(
      DockerHostRef host, String containerId, String profile, Skill skill) {
    return Skill.LOCAL.equals(skill.kind())
        ? profiles.installSkillFiles(host, containerId, profile, skill.name(),
            // order kept, and the controller already dropped duplicate paths
            skill.files().stream().collect(toMap(
                SkillFile::path, SkillFile::body, (first, ignored) -> first, LinkedHashMap::new)))
        : profiles.installSkill(host, containerId, profile, skill.name());
  }
}
