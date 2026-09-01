package io.hermes.missioncontrol.agents.api;

/**
 * What one part of a multi-part deploy did.
 *
 * <p>The report shape for anything that is several independent writes to an agent someone else
 * owns: a guide's skills and MCP servers, an MCP group's servers. Those fail one at a time — a
 * row deleted from the library since it was named, an alias already taken on that agent, a
 * managed server that is not running — and none of them should cost the caller the parts that
 * would have worked.
 *
 * <p>So the rule those deploys inherit from {@code agents.templates.TemplateApplier} is
 * <em>surface the error, do not roll back</em>, and this is how it is surfaced: one row per
 * part rather than one status for the lot. Undoing half of it would mean removing things that
 * may have been on that agent before the deploy ever ran.
 *
 * <p>{@link #detail} is null unless something went wrong. {@link #SKIPPED} is "gone from the
 * library or the catalog"; {@link #FAILED} is "attempted and refused".
 *
 * <p>Here rather than in one of the packages that answers it: two now do, and a second copy of
 * a wire type is what drifts.
 */
public record DeployedPart(String kind, String name, String status, String detail) {

  public static final String DEPLOYED = "deployed";
  public static final String SKIPPED = "skipped";
  public static final String FAILED = "failed";

  public static DeployedPart ok(String kind, String name) {
    return new DeployedPart(kind, name, DEPLOYED, null);
  }
}
