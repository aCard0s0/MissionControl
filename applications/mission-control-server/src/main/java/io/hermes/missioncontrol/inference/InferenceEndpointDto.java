package io.hermes.missioncontrol.inference;

/** Matches the frontend InferenceEndpoint model. */
public record InferenceEndpointDto(
    String id,
    String name,
    String url,
    String kind,          // ollama | openai
    String status,        // connected | error
    String version,       // null where the protocol has no version endpoint (openai)
    String detail,
    /** Whether this endpoint can pull and delete — ollama only. The dashboard hides
     *  those controls when false rather than offering a button that 400s. */
    boolean canManageModels) {
}
