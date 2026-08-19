import { defineConfig } from 'vitest/config';

/**
 * Base config the Angular unit-test builder merges its own settings into
 * (angular.json → test.runnerConfig).
 *
 * The only thing set here is the timeout. A handful of specs are legitimately
 * slow — the json tree renders its 8000-node cap, the terminal panel opens its
 * twelve-tab limit with a real xterm behind each one — and on CI hardware those
 * pass the 5s default while still being correct. Raising the ceiling stops a
 * slow machine reporting them as failures; it weakens no assertion, and a test
 * that genuinely hangs still fails, just later.
 */
export default defineConfig({
  test: {
    testTimeout: 30_000,
    hookTimeout: 30_000,
  },
});
