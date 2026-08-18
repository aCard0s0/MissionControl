import { describe, expect, it } from 'vitest';
import { mcpOperationActive } from './mcp-lifecycle';

describe('mcpOperationActive', () => {
  it('treats every non-terminal state the backend can report as active', () => {
    for (const state of ['pulling', 'starting', 'stopping', 'applying', 'deleting']) {
      expect(mcpOperationActive(state)).toBe(true);
    }
  });

  it('treats settled states as idle, however the backend spells them', () => {
    for (const state of ['', 'idle', 'none', 'error', 'failed', 'complete', 'completed', 'IDLE']) {
      expect(mcpOperationActive(state)).toBe(false);
    }
  });
});
