import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { runtimeConfig } from './app-config';

// public/config.js is rewritten by the Docker entrypoint at container start, so
// every failure mode here is a deployment mistake an operator will hit, not a
// programming error: a missing file, a stale file, a typo'd env var.
describe('runtimeConfig', () => {
  let errors: string[];

  beforeEach(() => {
    errors = [];
    vi.spyOn(console, 'error').mockImplementation((m: string) => { errors.push(m); });
  });

  afterEach(() => {
    delete window.__MC_CONFIG__;
    vi.restoreAllMocks();
  });

  it('falls back to live with no backend when config.js never loaded', () => {
    // failing OPEN here would paint seeded demo containers over an empty
    // dashboard, and an operator cannot tell mock inventory from real
    delete window.__MC_CONFIG__;
    expect(runtimeConfig()).toEqual({
      dataMode: 'live', apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    });
  });

  it('says so on the console when config.js is missing, so the cause is findable', () => {
    delete window.__MC_CONFIG__;
    runtimeConfig();
    expect(errors[0]).toContain('config.js missing or failed to parse');
  });

  it('serves mock data only when the deployment asks for it by exact spelling', () => {
    window.__MC_CONFIG__ = { dataMode: 'mock' };
    expect(runtimeConfig().dataMode).toBe('mock');
  });

  it('rejects a misspelled dataMode down to live, naming the value it refused', () => {
    for (const bad of ['Mock', 'MOCK', 'demo', '', 'true']) {
      window.__MC_CONFIG__ = { dataMode: bad as never };
      expect(runtimeConfig().dataMode, bad).toBe('live');
    }
    expect(errors.some(e => e.includes('unrecognized dataMode "demo"'))).toBe(true);
  });

  it('keeps the defaults for keys the deployment did not override', () => {
    window.__MC_CONFIG__ = { apiBaseUrl: 'https://mc.example' };
    expect(runtimeConfig()).toEqual({
      dataMode: 'live',
      apiBaseUrl: 'https://mc.example',
      dockerSocket: 'unix:///var/run/docker.sock',
    });
    expect(errors).toEqual([]);   // a present-but-partial config is not an error
  });

  it('lets a deployment point the local daemon at a non-standard socket', () => {
    window.__MC_CONFIG__ = { dataMode: 'live', dockerSocket: 'tcp://10.0.0.2:2375' };
    expect(runtimeConfig().dockerSocket).toBe('tcp://10.0.0.2:2375');
  });
});
