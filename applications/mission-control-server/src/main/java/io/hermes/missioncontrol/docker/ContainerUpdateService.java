package io.hermes.missioncontrol.docker;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves a deployed Agent onto another Hermes image tag.
 *
 * <p>Recreating a container mints a new id, so this also repoints the
 * dashboard-owned rows that reference the old one. {@link DockerGateway} stays
 * free of database concerns; the joining lives here.
 */
@Service
public class ContainerUpdateService {

  private static final Logger log = LoggerFactory.getLogger(ContainerUpdateService.class);

  private final DockerGateway docker;
  private final List<ContainerIdListener> listeners;
  private final TransactionTemplate transactions;

  public ContainerUpdateService(
      DockerGateway docker, List<ContainerIdListener> listeners,
      PlatformTransactionManager transactionManager) {
    this.docker = docker;
    this.listeners = listeners;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Takes a {@link DockerHostRef} rather than resolving the host itself: resolving here
   * would make this package depend on the one that owns hosts, and the caller has already
   * done it. Both halves the remap needs — the endpoint to reach the daemon and the id the
   * dashboard rows are keyed by — travel in the one value.
   *
   * @return the id of the replacement container
   */
  public String update(DockerHostRef host, String containerId, String version) {
    UpgradeResult result = docker.upgrade(host, containerId, version);
    remap(host.id(), result.oldContainerId(), result.newContainerId());
    return result.newContainerId();
  }

  /**
   * Repoints board tasks and MCP links at the replacement container.
   *
   * <p>Deliberately does not fail the update: the container is already healthy on
   * the new image, and undoing that to preserve a task link would trade a working
   * Agent for a bookkeeping detail. A retry covers the realistic failure — SQLite
   * is single-writer, so a brief lock is the likely cause.
   */
  private void remap(String hostId, String oldContainerId, String newContainerId) {
    try {
      moveRows(hostId, oldContainerId, newContainerId);
    } catch (RuntimeException first) {
      try {
        Thread.sleep(200);
        moveRows(hostId, oldContainerId, newContainerId);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        log.error("interrupted while remapping {} -> {}", oldContainerId, newContainerId, first);
      } catch (RuntimeException retried) {
        log.error("container {} was updated but dashboard rows still reference the old id {}",
            newContainerId, oldContainerId, retried);
      }
    }
  }

  /** All tables move together, so a partial remap cannot leave rows split across ids. */
  private void moveRows(String hostId, String oldContainerId, String newContainerId) {
    Integer moved = transactions.execute(status -> {
      int rows = 0;
      for (ContainerIdListener listener : listeners) {
        rows += listener.onContainerReplaced(hostId, oldContainerId, newContainerId);
      }
      return rows;
    });
    if (moved != null && moved > 0) {
      log.info("moved {} dashboard row(s) from container {} to {}",
          moved, oldContainerId, newContainerId);
    }
  }
}
