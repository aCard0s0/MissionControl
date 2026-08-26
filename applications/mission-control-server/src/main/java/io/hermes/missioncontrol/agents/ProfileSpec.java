package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AuxiliaryModelSpec;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;

/**
 * What it takes to create a profile inside a container: already validated.
 *
 * <p>Exists because {@link CreateAgentRequest} was doing this job, and one caller that never
 * serves an HTTP request had to lie to do it — the template deploy flow built one passing
 * {@code null} for a {@code @NotBlank hostId}, because by then the host was already resolved
 * and the field had nothing to say. Bean validation does not run on a hand-built record, so
 * the annotation neither held nor mattered on that path, and the name rule it declared was
 * duplicated as an explicit check further in.
 *
 * <p>The two fields that drop out are the ones that were never about creating a profile:
 * {@code hostId}, which the edge resolves to a {@code DockerHostRef} before anything here is
 * reached, and {@code fromTemplateId}, which decides <em>which</em> creation flow runs and is
 * the controller's business.
 *
 * <p>The name rule lives in the canonical constructor, so it holds for the template flow as
 * well as the request one, and {@code HermesProfiles} no longer repeats it.
 *
 * @param containerId the Hermes container the profile is created in
 * @param name        a valid profile name — see {@link ProfilePaths#isValidName}
 * @param provider    the model provider key, or blank/auto to leave it to hermes
 * @param model       the model id, passed verbatim so an OpenRouter namespace survives
 * @param apiKey      written to the profile's .env when the provider takes one; null to skip
 * @param cloneFrom   an existing profile to clone; null for a fresh one
 * @param baseUrl     a custom/local endpoint that owns its own routing; null for a standard
 *                    provider
 * @param auxiliary   an override for the side-task model; null follows the main model
 */
public record ProfileSpec(
    String containerId,
    String name,
    String provider,
    String model,
    String apiKey,
    String cloneFrom,
    String baseUrl,
    AuxiliaryModelSpec auxiliary) {

  /**
   * What a profile name may look like, for the places that state it as text rather than run
   * {@link ProfilePaths#isValidName}: this is the expression {@link ProfilePaths#NAME} is
   * compiled from, and the one the {@code @Pattern} annotations on {@code CreateAgentRequest}
   * and {@code DeployFromTemplateRequest} declare.
   *
   * <p>Here rather than on {@code ProfilePaths} only because bean validation needs a
   * compile-time constant that {@code agents.templates} can see, and that class is deliberately
   * package-private — it owns the traversal guard, and the guard is worth exactly one home.
   *
   * <p>Two other records carry the same expression and are deliberately <em>not</em> wired to
   * this one: a template name and a Docker container name are different rules that happen to
   * coincide today, and joining them would stop either from moving.
   */
  public static final String NAME_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9_.-]*";

  public ProfileSpec {
    if (containerId == null || containerId.isBlank()) {
      throw new IllegalArgumentException("missing container id");
    }
    if (!ProfilePaths.isValidName(name)) {
      throw new IllegalArgumentException("invalid profile name");
    }
    cloneFrom = blankToNull(cloneFrom);
    baseUrl = blankToNull(baseUrl);
    apiKey = blankToNull(apiKey);
  }

  /** Interprets a request body: the one place the wire shape is read. */
  public static ProfileSpec from(CreateAgentRequest request) {
    if (request == null) throw new IllegalArgumentException("request body is required");
    return new ProfileSpec(
        request.containerId(),
        request.name(),
        request.provider(),
        request.model(),
        request.apiKey(),
        request.cloneFrom(),
        request.baseUrl(),
        request.auxiliary());
  }

  /**
   * A blank string is an absent value, not an empty one.
   *
   * <p>Here rather than once per file: this package normalized the same three inputs — a
   * webhook field left empty in a form, an MCP endpoint hermes wrote as {@code ""}, a cloned
   * profile's unset {@code baseUrl} — with four private copies, one of which also trimmed. It
   * trims: every caller is reading a value a human typed or a config file carried, and a
   * trailing space is not part of any of them.
   */
  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
