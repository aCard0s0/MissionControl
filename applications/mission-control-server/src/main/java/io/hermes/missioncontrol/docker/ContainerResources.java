package io.hermes.missioncontrol.docker;

/**
 * The CPU and memory ceiling a deployed Hermes container runs under.
 *
 * <p>Values come from the vendor's own Docker guide, which states a 1 GB minimum
 * and 2–4 GB recommended for memory, and 1 core minimum with 2 recommended:
 * <a href="https://hermes-agent.nousresearch.com/docs/user-guide/docker">Hermes
 * Docker setup</a>.
 *
 * <p>{@link #BASELINE} takes the low end of the recommended band rather than the
 * top. Browser automation (Playwright/Chromium) is the memory-hungry feature and
 * wants at least 2 GB, so this clears that floor while leaving the operator
 * somewhere to go — the deploy form offers the increase, and a baseline already
 * at the ceiling would make that offer meaningless.
 *
 * <p>The guide also asks for 2+ GB of <em>disk</em> on the data volume. Nothing
 * here enforces that: a local Docker volume has no size of its own to set, it
 * grows into the host filesystem. The deploy form says so rather than pretending
 * to a control it does not have.
 */
public record ContainerResources(int memoryMb, double cpus) {

  /** The vendor's stated minimums; below these an agent is documented not to work. */
  public static final int MIN_MEMORY_MB = 1024;
  public static final double MIN_CPUS = 1.0;

  /** Sanity ceilings, not recommendations — they exist so a typo cannot ask for a petabyte. */
  public static final int MAX_MEMORY_MB = 262_144;
  public static final double MAX_CPUS = 64.0;

  /** What a deploy uses when the operator does not raise it. */
  public static final ContainerResources BASELINE = new ContainerResources(2048, 2.0);

  public ContainerResources {
    if (memoryMb < MIN_MEMORY_MB || memoryMb > MAX_MEMORY_MB) {
      throw new IllegalArgumentException(
          "memory must be between " + MIN_MEMORY_MB + " and " + MAX_MEMORY_MB + " MB");
    }
    if (cpus < MIN_CPUS || cpus > MAX_CPUS) {
      throw new IllegalArgumentException(
          "cpus must be between " + MIN_CPUS + " and " + MAX_CPUS);
    }
  }

  /**
   * The request's values, or the baseline for whichever it left out.
   *
   * <p>Absent means "use the recommendation", which is what an older client — or
   * a scripted deploy written before this existed — is asking for by saying
   * nothing. It is not the same as asking for no limit at all, and there is
   * deliberately no way to ask for that: an unbounded agent is what this exists
   * to stop, since one runaway container can take the whole host down with it.
   */
  public static ContainerResources orBaseline(Integer memoryMb, Double cpus) {
    return new ContainerResources(
        memoryMb == null ? BASELINE.memoryMb() : memoryMb,
        cpus == null ? BASELINE.cpus() : cpus);
  }

  public long memoryBytes() {
    return memoryMb * 1024L * 1024L;
  }

  /** Docker expresses a CPU share in billionths, which is what `--cpus` is sugar for. */
  public long nanoCpus() {
    return Math.round(cpus * 1_000_000_000L);
  }
}
