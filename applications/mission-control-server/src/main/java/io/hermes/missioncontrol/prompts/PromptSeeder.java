package io.hermes.missioncontrol.prompts;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The one sample prompt a fresh install ships with, so the library page teaches what an
 * entry looks like instead of opening empty.
 *
 * <p>Guarded on a marker row rather than on "the table is empty": an operator who deletes
 * the sample — or empties the library on purpose — must not have it reappear at the next
 * boot. Writing the marker is therefore the whole point of the meta table.
 */
@Component
class PromptSeeder {

  private static final Logger log = LoggerFactory.getLogger(PromptSeeder.class);

  static final String SEED_META = "library-seed-version";
  static final String SEED_VERSION = "1";
  static final String SEED_ID = "p-seed-triage";

  private static final String SEED_BODY = """
      A Hermes container is unhealthy. Work through it in this order and stop at the first \
      thing that explains it:

      1. `hermes status -p <profile>` — is the gateway up, and which model is configured?
      2. The last 100 log lines — quote the first error, not the last one.
      3. The profile's `config.yaml` — provider, model, and `terminal.cwd`.
      4. Its MCP servers — which are connected, which are failing, and with what error.

      Report what is broken, the evidence you based that on, and the smallest change that \
      would fix it. Do not change anything yet.""";

  private final PromptRepository repository;

  PromptSeeder(PromptRepository repository) {
    this.repository = repository;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    seedOnce();
  }

  /** The seed itself, so a test can drive it without publishing an event. */
  void seedOnce() {
    if (repository.meta(SEED_META).map(SEED_VERSION::equals).orElse(false)) {
      return;
    }
    log.info("seeding the sample prompt library entry ({})", SEED_VERSION);
    long now = System.currentTimeMillis();
    repository.insert(new Prompt(
        SEED_ID,
        "Triage an unhealthy container",
        SEED_BODY,
        "ops",
        "Sample entry. Paste it into a session on the agent that owns the container — it reads "
            + "state and reports, it does not change anything.",
        List.of("ops", "triage", "sample"),
        now,
        now));
    repository.putMeta(SEED_META, SEED_VERSION);
  }
}
