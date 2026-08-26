import { describe, expect, it } from 'vitest';
import {
  CPU_PRESETS, HERMES_BASELINE, MEMORY_PRESETS_MB, formatMemory, memoryNote,
} from './container-resources';

/** The vendor floor the backend enforces. Written here and not exported from the module:
 *  nothing renders it, and only this invariant reads it. */
const VENDOR_MINIMUM = { memoryMb: 1024, cpus: 1 };

describe('Hermes container resources', () => {
  it('starts at the vendor recommendation, not at the absence of one Docker defaults to', () => {
    // https://hermes-agent.nousresearch.com/docs/user-guide/docker — 2–4 GB, 2 cores
    expect(HERMES_BASELINE).toEqual({ memoryMb: 2048, cpus: 2 });
  });

  it('offers the baseline as one of the presets, so it can be returned to', () => {
    expect(MEMORY_PRESETS_MB).toContain(HERMES_BASELINE.memoryMb);
    expect(CPU_PRESETS).toContain(HERMES_BASELINE.cpus);
  });

  it('offers nothing below the vendor minimum, which the backend would refuse', () => {
    expect(Math.min(...MEMORY_PRESETS_MB)).toBeGreaterThanOrEqual(VENDOR_MINIMUM.memoryMb);
    expect(Math.min(...CPU_PRESETS)).toBeGreaterThanOrEqual(VENDOR_MINIMUM.cpus);
  });

  it('reads a size the way an operator would say it', () => {
    expect(formatMemory(1024)).toBe('1 GB');
    expect(formatMemory(2048)).toBe('2 GB');
    expect(formatMemory(512)).toBe('512 MB');
    expect(formatMemory(1536)).toBe('1536 MB');
  });

  it('warns at the floor that browser automation will not fit', () => {
    expect(memoryNote(1024)).toContain('browser automation');
  });

  it('says nothing across the recommended band, so the note means something when it appears', () => {
    expect(memoryNote(2048)).toBeNull();
    expect(memoryNote(4096)).toBeNull();
  });

  it('notes that memory above the recommendation is reserved whether used or not', () => {
    expect(memoryNote(8192)).toContain('reserved on the host');
  });
});
