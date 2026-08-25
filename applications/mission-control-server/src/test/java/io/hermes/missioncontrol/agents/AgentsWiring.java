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
    return profiles(
        dockerExec, new HermesProfileMcp(files(dockerExec), new HermesConfigEditor()));
  }

  /**
   * As {@link #profiles(DockerExecService)}, with the MCP collaborator supplied. It is the
   * one holding a cache, so a test that drives the cache's lifetime builds it with its own
   * clock and keeps the reference to read the cache back through.
   */
  static HermesProfiles profiles(DockerExecService dockerExec, HermesProfileMcp mcp) {
    ObjectMapper json = new ObjectMapper();
    HermesConfigEditor editor = new HermesConfigEditor();
    HermesContainerFiles files = files(dockerExec);
    HermesEnvFile env = new HermesEnvFile(files);
    return new HermesProfiles(
        files,
        env,
        new HermesModelConfig(files, new HermesCli(files), env),
        new HermesSkills(files, editor),
        mcp,
        new HermesSessions(files, json),
        new HermesGatewayLogs(files),
        new HermesGatewayState(files, json),
        new ProfileInventory(files));
  }
}
