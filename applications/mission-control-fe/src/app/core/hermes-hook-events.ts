/**
 * The lifecycle events an outbound webhook target can subscribe to — hermes'
 * `VALID_HOOKS`, grouped so a picker is readable.
 *
 * Captured against Hermes Agent v0.20.5. Hermes is the authority: it ignores an event it
 * does not know, with a warning in its own log and nothing on this screen, which is why the
 * UI offers this list rather than a free-text field. The backend does **not** validate
 * against it — a hermes release that adds an event should not need a dashboard release
 * before an operator can use it, so a target created by hand keeps working and simply shows
 * its event as an unknown chip here.
 */
export interface HermesHookEventGroup {
  readonly title: string;
  readonly events: readonly string[];
}

export const HERMES_HOOK_EVENTS: readonly HermesHookEventGroup[] = [
  {
    title: 'Session',
    events: ['on_session_start', 'on_session_end', 'on_session_finalize', 'on_session_reset'],
  },
  {
    title: 'Tools and commands',
    events: ['pre_tool_call', 'post_tool_call', 'pre_command', 'transform_tool_result',
      'transform_terminal_output'],
  },
  {
    title: 'Model calls',
    events: ['pre_llm_call', 'post_llm_call', 'pre_api_request', 'post_api_request',
      'api_request_error', 'transform_llm_output', 'transform_api_error_classification'],
  },
  {
    title: 'Streaming',
    events: ['on_stream_start', 'on_stream_delta', 'on_stream_end', 'on_interim_message'],
  },
  {
    title: 'Subagents and approvals',
    events: ['subagent_start', 'subagent_stop', 'pre_approval_request', 'post_approval_response'],
  },
  {
    title: 'Kanban',
    events: ['kanban_task_claimed', 'kanban_task_completed', 'kanban_task_blocked',
      'on_kanban_task_updated', 'on_kanban_dispatch_tick', 'on_kanban_worker_spawned',
      'on_kanban_worker_exited', 'on_kanban_worker_stale_claim'],
  },
  {
    title: 'Gateway and other',
    events: ['gateway_platform_event', 'pre_gateway_dispatch', 'on_skill_lifecycle',
      'pre_transcription', 'pre_verify'],
  },
];

/** Every event, flattened — for the "is this one hermes knows about?" check. */
export const HERMES_HOOK_EVENT_NAMES: readonly string[] =
  HERMES_HOOK_EVENTS.flatMap(g => g.events);

/** Events where a `matcher` regex does anything at all; hermes ignores it elsewhere. */
export const MATCHER_EVENTS: readonly string[] = ['pre_tool_call', 'post_tool_call'];

export const isKnownHookEvent = (event: string): boolean =>
  HERMES_HOOK_EVENT_NAMES.includes(event);

/** True when a matcher on this selection would be honoured by at least one of its events. */
export const matcherApplies = (events: readonly string[]): boolean =>
  events.some(e => MATCHER_EVENTS.includes(e));
