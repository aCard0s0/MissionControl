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

  it('falls back to the same origin when config.js never loaded', () => {
    delete window.__MC_CONFIG__;
    expect(runtimeConfig()).toEqual({
      apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    });
  });

  it('says so on the console when config.js is missing, so the cause is findable', () => {
    delete window.__MC_CONFIG__;
    runtimeConfig();
    expect(errors[0]).toContain('config.js missing or failed to parse');
  });

  it('keeps the defaults for keys the deployment did not override', () => {
    window.__MC_CONFIG__ = { apiBaseUrl: 'https://mc.example' };
    expect(runtimeConfig()).toEqual({
      apiBaseUrl: 'https://mc.example',
      dockerSocket: 'unix:///var/run/docker.sock',
    });
    expect(errors).toEqual([]);   // a present-but-partial config is not an error
  });

  it('lets a deployment point the local daemon at a non-standard socket', () => {
    window.__MC_CONFIG__ = { dockerSocket: 'tcp://10.0.0.2:2375' };
    expect(runtimeConfig().dockerSocket).toBe('tcp://10.0.0.2:2375');
  });
});
