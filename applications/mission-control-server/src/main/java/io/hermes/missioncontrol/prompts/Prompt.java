package io.hermes.missioncontrol.prompts;

import java.util.List;

/**
 * One entry in the prompt library — dashboard-owned text an operator keeps for later.
 *
 * <p>Nothing inside a Hermes container reads this: it is a dictionary the dashboard
 * holds so a prompt worth reusing can be found again, copied, and pasted wherever it
 * is needed (a session, a cron job, a webhook).
 */
public record Prompt(
    String id,
    String title,
    String body,
    String category,
    String notes,
    List<String> tags,
    long createdAt,
    long updatedAt) {
}
