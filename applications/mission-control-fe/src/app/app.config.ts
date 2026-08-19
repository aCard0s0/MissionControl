import {
  ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';

import { routes } from './app.routes';
import { LiveSync } from './core/store/live-sync';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    // The store starts empty and LiveSync fills it. Deliberately not awaited:
    // the probe retries a backend that is down, and the first load fan-out takes
    // as long as the slowest daemon — the shell renders its "connecting…" banner
    // meanwhile rather than holding the page blank.
    provideAppInitializer(() => { void inject(LiveSync).probeBackend(); }),
  ]
};
