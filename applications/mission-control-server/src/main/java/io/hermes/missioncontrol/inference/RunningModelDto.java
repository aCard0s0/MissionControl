package io.hermes.missioncontrol.inference;

/**
 * One model an endpoint is holding in memory right now, from ollama's {@code GET /api/ps}.
 *
 * <p>The answer to "what is actually in use" — a listed model costs disk, a running one costs
 * the VRAM the next one needs. {@code sizeVramBytes} is null for a CPU-only load, which is
 * ollama's own answer rather than a missing field.
 */
public record RunningModelDto(
    String name,
    Long sizeVramBytes) {
}
