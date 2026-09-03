package io.hermes.missioncontrol.agents;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import java.util.function.Supplier;

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

  /**
   * A mocked file seam whose {@code serialized} still runs the work handed to it.
   *
   * <p>Here rather than in each test that mocks this class, because the failure mode is silent:
   * every profile edit now runs inside {@code serialized}, and a bare mock returns without
   * calling it — so the write under test simply never happens and the assertion fails somewhere
   * else entirely. Anything that mocks {@link HermesContainerFiles} wants this.
   */
  static HermesContainerFiles mockFiles() {
    HermesContainerFiles files = mock(HermesContainerFiles.class);
    when(files.serialized(anyString(), anyString(), any(Supplier.class)))
        .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
    doAnswer(call -> {
      call.<Runnable>getArgument(2).run();
      return null;
    }).when(files).serialized(anyString(), anyString(), any(Runnable.class));
    return files;
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
        new ProfileInventory(files),
        catalogLinks());
  }

  /**
   * The catalog overlay over mocked stores, so a profile read carries no links.
   *
   * <p>The real class over empty repositories rather than a mock of it: every profile read goes
   * through it now, and a bare mock would answer null instead of the profile — a failure a long
   * way from its cause. What it does with links of its own is {@link CatalogLinkOverlayTest}'s.
   */
  static CatalogLinkOverlay catalogLinks() {
    return new CatalogLinkOverlay(
        mock(AgentMcpLinkRepository.class), mock(McpRegistryService.class));
  }
}
