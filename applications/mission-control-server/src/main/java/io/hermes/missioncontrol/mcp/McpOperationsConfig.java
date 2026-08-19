package io.hermes.missioncontrol.mcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The executor every managed-MCP compose operation runs on.
 *
 * <p>A bean rather than something {@link McpComposeLifecycle} creates for itself, because it is
 * the one dependency in this package a test needs to substitute: a same-thread executor makes
 * the lifecycle's effects — desired state, {@code operation_state}, {@code applied_revision},
 * a recorded failure — observable, which an async task offers no way to await. That
 * substitution used to be why the whole collaborator graph was hand-built inside
 * {@link McpRegistryService}.
 *
 * <p>Virtual threads because a compose operation is almost entirely waiting on the daemon.
 *
 * <p>{@code destroyMethod = ""} on purpose: {@link McpRegistryService} already shuts this down
 * through the lifecycle on {@code @PreDestroy}, and it uses {@code shutdownNow} rather than the
 * graceful {@code shutdown} Spring would infer, because a dashboard going down should not wait
 * on an image pull.
 */
@Configuration
class McpOperationsConfig {

  @Bean(destroyMethod = "")
  ExecutorService mcpOperations() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
