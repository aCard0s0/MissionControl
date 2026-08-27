package io.hermes.missioncontrol.inference;

/** Matches the frontend InferenceEndpoint model. */
public record InferenceEndpointDto(
    String id,
    String name,
    String url,
    /** Protocol that answered the last probe: ollama | openai, or null if none did.
     *  Derived, never stored — the server decides what it is, not the row. */
    String kind,
    String status,        // connected | error
    String version,       // null where the protocol has no version endpoint (openai)
    String detail,
    /** Whether this endpoint can pull and delete — ollama only. The dashboard hides
     *  those controls when false rather than offering a button that 400s. */
    boolean canManageModels) {
}
