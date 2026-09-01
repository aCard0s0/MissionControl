package io.hermes.missioncontrol.mcp;

import java.util.List;

/**
 * A named set of catalog entries, deployable onto an agent in one action.
 *
 * <p>The third group in this application and the only one that <em>does</em> something. A
 * skill group and a prompt group file a library; this one also has a deploy, because the set
 * an agent needs is usually several servers at once and connecting them one at a time is the
 * thing this exists to stop.
 *
 * <p><b>It records no agents.</b> Deploying a group connects each of its servers to one agent,
 * and every one of those connections is already a row in {@code mcp_agent_links} — the
 * dashboard's existing record of "this profile is connected to this catalog entry". Which
 * agents a group reaches is therefore <em>derived</em> from those links rather than stored
 * here.
 *
 * <p>That is the difference that matters. A stored group-to-agent association would be a
 * second source of truth: disconnect one server on the agent's own MCP tab and the
 * association would still claim the group was connected. Deriving it means the count can only
 * ever say what the links say. A group deployed and then unlinked reads as not connected,
 * which is the honest answer.
 *
 * <p>It follows that a group and an agent are many-to-many in both directions without any
 * table saying so: the same group deploys onto as many agents as you like, and an agent's
 * links may come from several groups and from servers connected individually. A server in two
 * groups counts toward both.
 *
 * <p>{@link #serverIds} are ids, not foreign keys — see the note on {@code mcp_groups} in
 * {@code schema.sql}. An entry deleted from the catalog is reported as skipped by a deploy
 * rather than silently dropped from the group.
 */
public record McpGroup(
    String id,
    String name,
    String description,
    List<String> serverIds,
    long createdAt,
    long updatedAt) {
}
