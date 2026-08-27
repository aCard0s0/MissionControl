package io.hermes.missioncontrol.inference;

/**
 * One model an endpoint is holding in memory right now, from ollama's {@code GET /api/ps}.
 *
 * <p>The answer to "what is actually in use" — a listed model costs disk, a running one costs
 * the VRAM the next one needs. Both optional fields are ollama's own: a model kept resident
 * reports no expiry, and a CPU-only load reports no VRAM.
 */
public record RunningModelDto(
    String name,
    Long sizeVramBytes,
    Long expiresAt) {
}
