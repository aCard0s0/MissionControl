/**
 * True while a managed MCP server is mid-operation (pulling, starting,
 * applying, …) and the UI should keep its controls busy and keep polling. The
 * backend names the states, so anything it doesn't recognise as terminal counts
 * as active. Shared by the catalog store and the MCP Servers page so the two
 * can never disagree about what "busy" means.
 */
export function mcpOperationActive(state: string): boolean {
  return !['', 'idle', 'none', 'error', 'failed', 'complete', 'completed'].includes(state.toLowerCase());
}
