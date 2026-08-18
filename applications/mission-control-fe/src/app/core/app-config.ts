// Runtime configuration, injected via public/config.js (overridable per
// deployment — the Docker entrypoint rewrites it from environment variables
// without rebuilding the app).

export interface McRuntimeConfig {
  /** Base URL of the Mission Control backend API. */
  apiBaseUrl: string;
  /** Default Docker endpoint shown for the local daemon. */
  dockerSocket: string;
}

declare global {
  interface Window { __MC_CONFIG__?: Partial<McRuntimeConfig> }
}

// A missing or broken config.js leaves an empty dashboard pointed at the same
// origin, and says so on the console — never a silent half-configured app.
const DEFAULTS: McRuntimeConfig = {
  apiBaseUrl: '',
  dockerSocket: 'unix:///var/run/docker.sock',
};

export function runtimeConfig(): McRuntimeConfig {
  const overrides = typeof window !== 'undefined' ? window.__MC_CONFIG__ ?? {} : {};
  if (typeof window !== 'undefined' && !window.__MC_CONFIG__) {
    console.error('mission-control: config.js missing or failed to parse — falling back to same-origin defaults');
  }
  return { ...DEFAULTS, ...overrides };
}
