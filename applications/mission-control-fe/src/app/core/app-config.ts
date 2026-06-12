// Runtime configuration, injected via public/config.js (overridable per
// deployment — the Docker entrypoint rewrites it from environment variables
// without rebuilding the app).

export type DataMode = 'mock' | 'live';

export interface McRuntimeConfig {
  dataMode: DataMode;
  /** Base URL of the Mission Control backend API (required in live mode). */
  apiBaseUrl: string;
  /** Default Docker endpoint shown for the local daemon. */
  dockerSocket: string;
}

declare global {
  interface Window { __MC_CONFIG__?: Partial<McRuntimeConfig> }
}

// Fail CLOSED: if config.js is missing, broken, or carries a typo'd dataMode,
// default to live (empty dashboard) — never silently serve demo data that an
// operator could mistake for real state. Dev gets mock explicitly via
// public/config.js.
const DEFAULTS: McRuntimeConfig = {
  dataMode: 'live',
  apiBaseUrl: '',
  dockerSocket: 'unix:///var/run/docker.sock',
};

export function runtimeConfig(): McRuntimeConfig {
  const overrides = typeof window !== 'undefined' ? window.__MC_CONFIG__ ?? {} : {};
  if (typeof window !== 'undefined' && !window.__MC_CONFIG__) {
    console.error('mission-control: config.js missing or failed to parse — falling back to live mode with no backend');
  }
  const merged = { ...DEFAULTS, ...overrides };
  if (merged.dataMode !== 'mock' && merged.dataMode !== 'live') {
    console.error(`mission-control: unrecognized dataMode "${merged.dataMode}" — using live`);
    merged.dataMode = 'live';
  }
  return merged;
}
