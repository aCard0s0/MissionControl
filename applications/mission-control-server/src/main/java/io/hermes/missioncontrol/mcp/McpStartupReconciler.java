package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What the managed catalog does once, at boot: seed the defaults, repair a default an earlier
 * version seeded wrong, and bring every managed record back to the desired state it was left in.
 *
 * <p>Split out of {@link McpRegistryService}, which owns the catalog's rules and had no other
 * reason to know about boot ordering. It also had to reach into {@code HostService} and call
 * {@code seedLocalHost()} itself, because seeding needs the local host row and two
 * {@code ApplicationReadyEvent} listeners have no defined order between them. That call is gone:
 * host seeding is now explicitly ordered ahead of every other listener, so this one can assume
 * the row exists rather than re-running someone else's initialization to be sure.
 *
 * <p>This listener is deliberately left unordered, which puts it at {@code LOWEST_PRECEDENCE} —
 * after anything that declares an order, host seeding included.
 */
@Component
class McpStartupReconciler {

  private final McpServerRepository repository;
  private final McpCatalogSeeder seeder;
  private final McpComposeLifecycle lifecycle;
  private final boolean enabled;

  McpStartupReconciler(
      McpServerRepository repository,
      McpCatalogSeeder seeder,
      McpComposeLifecycle lifecycle,
      AppProperties props) {
    this.repository = repository;
    this.seeder = seeder;
    this.lifecycle = lifecycle;
    this.enabled = props.startupReconcile();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    // A context test boots the whole application without a daemon, so seeding and
    // reconciliation — which pull images and create containers — must be skippable.
    if (!enabled) return;
    run();
  }

  /** The sequence itself, so it can be driven against a real catalog without an event. */
  void run() {
    seedOnce();
    repairOnce();
    reconcileEveryManagedRecord();
  }

  void seedOnce() {
    if (repository.meta(McpCatalogSeeder.SEED_META)
        .map(McpCatalogSeeder.SEED_VERSION::equals).orElse(false)) {
      return;
    }
    seeder.seedDefaults();
    repository.putMeta(McpCatalogSeeder.SEED_META, McpCatalogSeeder.SEED_VERSION);
  }

  /**
   * Seeding only ever inserts, so a corrected default never reaches a catalog that was seeded by
   * an earlier version. Repair runs before the reconcile pass, which then applies the rewritten
   * definition as part of its normal startup work.
   */
  void repairOnce() {
    if (repository.meta(McpCatalogSeeder.SEED_REPAIR_META)
        .map(McpCatalogSeeder.SEED_REPAIR_VERSION::equals).orElse(false)) {
      return;
    }
    seeder.repairSeeds();
    repository.putMeta(McpCatalogSeeder.SEED_REPAIR_META, McpCatalogSeeder.SEED_REPAIR_VERSION);
  }

  /**
   * Reconciles persisted desired state after a dashboard restart. Per-host locks serialize this
   * with any seed provisioning already queued above.
   */
  private void reconcileEveryManagedRecord() {
    for (ServerRow row : repository.findAll()) {
      if (!"managed".equals(row.kind())) continue;
      if ("deleting".equals(row.operationState())) {
        lifecycle.submit(row.id(), () -> lifecycle.runDelete(row.id()));
      } else {
        repository.beginOperation(row.id(), row.desiredState(), "reconciling");
        lifecycle.submit(row.id(), () -> lifecycle.reconcile(row.id()));
      }
    }
  }
}
