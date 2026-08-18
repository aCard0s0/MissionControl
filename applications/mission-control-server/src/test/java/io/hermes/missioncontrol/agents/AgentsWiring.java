package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.docker.DockerExecService;

/**
 * Builds the profile collaborator graph the way Spring does, for tests that exercise a
 * whole flow through {@link HermesProfiles} rather than one collaborator.
 *
 * <p>Tests that only need one concern construct it directly — that is the point of the
 * split, and going through this helper hides which collaborator is under test.
 */
final class AgentsWiring {

  private AgentsWiring() {}

  static HermesContainerFiles files(DockerExecService dockerExec) {
    return new HermesContainerFiles(dockerExec);
  }

  static HermesEnvFile envFile(DockerExecService dockerExec) {
    return new HermesEnvFile(files(dockerExec));
  }

  static HermesProfiles profiles(DockerExecService dockerExec) {
    ObjectMapper json = new ObjectMapper();
    HermesConfigEditor editor = new HermesConfigEditor();
    HermesContainerFiles files = files(dockerExec);
    HermesEnvFile env = new HermesEnvFile(files);
    return new HermesProfiles(
        files,
        env,
        new HermesModelConfig(files, env),
        new HermesSkills(files, editor),
        new HermesProfileMcp(files, editor),
        new HermesSessions(files, json),
        new HermesGatewayLogs(files),
        new HermesIntegrations(files, json));
  }
}
