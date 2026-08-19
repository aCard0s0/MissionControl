import { describe, expect, it } from 'vitest';
import { Route } from '@angular/router';
import { routes } from './app.routes';

/** Every lazy route, resolved through its own loadComponent. */
const lazy = routes.filter((r): r is Route & { loadComponent: () => Promise<unknown> } =>
  typeof r.loadComponent === 'function');

describe('app routes', () => {
  it('opens on the fleet, because a container has to be picked before anything else', () => {
    expect(routes[0]).toMatchObject({ path: '', pathMatch: 'full', redirectTo: 'containers' });
  });

  it('sends anything unknown back to the fleet rather than to a blank page', () => {
    expect(routes.at(-1)).toMatchObject({ path: '**', redirectTo: 'containers' });
  });

  it('titles every page for the browser tab', () => {
    for (const route of lazy) {
      expect(route.title, `route "${route.path}" has no title`).toMatch(/ · Mission Control$/);
    }
  });

  it('declares each path exactly once', () => {
    const paths = routes.map(r => r.path);
    expect(new Set(paths).size).toBe(paths.length);
  });

  it('loads a real component for every lazy route', async () => {
    expect(lazy.length).toBe(routes.length - 2);   // minus the two redirects

    for (const route of lazy) {
      const loaded = await route.loadComponent();
      expect(typeof loaded, `route "${route.path}" resolved to ${typeof loaded}`).toBe('function');
    }
  });
});
