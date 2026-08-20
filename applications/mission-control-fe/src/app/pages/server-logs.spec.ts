import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StoreContext } from '../core/store/store-context';
import { ApiLogLine } from '../core/hermes-api';
import { ServerLogsPage } from './server-logs';
import { el, press, settle, text } from '../testing/dom';

const TS = Date.UTC(2026, 7, 20, 14, 30, 5);

const line = (level: ApiLogLine['level'], msg: string): ApiLogLine =>
  ({ ts: TS, level, source: 'ContainerInventory', msg });

const ctxStub = (over: {
  logs?: ApiLogLine[];
  info?: { version: string; retained: number; startedAt: number };
  logsFails?: boolean;
  infoFails?: boolean;
} = {}) => ({
  api: {
    server: {
      logs: over.logsFails
        ? vi.fn().mockRejectedValue(new Error('backend unreachable'))
        : vi.fn().mockResolvedValue(over.logs ?? []),
      info: over.infoFails
        ? vi.fn().mockRejectedValue(new Error('no info'))
        : vi.fn().mockResolvedValue(
          over.info ?? { version: '0.1.0', retained: 1000, startedAt: TS - 3_600_000 }),
    },
  },
});

const render = (ctx: ReturnType<typeof ctxStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: StoreContext, useValue: ctx }],
  });
  const fixture = TestBed.createComponent(ServerLogsPage);
  fixture.detectChanges();
  return { fixture };
};

const rows = (fixture: { nativeElement: unknown }) =>
  Array.from(el(fixture).querySelectorAll('.log-line'));

describe('ServerLogsPage', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the dashboard process tail on open and renders one row per line', async () => {
    const ctx = ctxStub({ logs: [line('info', 'Mission Control 0.1.0 listening on port 8080')] });
    const { fixture } = render(ctx);

    await settle(fixture);

    expect(ctx.api.server.logs).toHaveBeenCalled();
    expect(rows(fixture).length).toBe(1);
    expect(text(fixture)).toContain('listening on port 8080');
  });

  it('reports the process it is showing logs for', async () => {
    const { fixture } = render(ctxStub());

    await settle(fixture);

    expect(text(fixture)).toContain('v0.1.0');
    expect(text(fixture)).toContain('1000 lines retained');
  });

  it('degrades to the tail alone when the process details cannot be read', async () => {
    // the header is a convenience; losing it must not cost the page its reason to exist
    const ctx = ctxStub({ infoFails: true, logs: [line('warn', 'hiding demo')] });
    const { fixture } = render(ctx);

    await settle(fixture);

    expect(text(fixture)).toContain('process details unavailable');
    expect(rows(fixture).length).toBe(1);
  });

  it('counts the warnings and errors in the tail', async () => {
    const { fixture } = render(ctxStub({ logs: [
      line('error', 'boom'), line('error', 'again'), line('warn', 'slow'),
    ] }));

    await settle(fixture);

    expect(text(fixture)).toContain('2 errors');
    expect(text(fixture)).toContain('1 warning');
  });

  it('keeps polling, and stops while paused so a reader does not lose their place', async () => {
    const ctx = ctxStub({ logs: [line('info', 'a')] });
    const { fixture } = render(ctx);
    await settle(fixture);

    const afterFirst = ctx.api.server.logs.mock.calls.length;
    await settle(fixture, 5_000);
    expect(ctx.api.server.logs.mock.calls.length).toBeGreaterThan(afterFirst);

    // counted before the press, so a fetch on the way into the pause cannot hide inside it
    const afterPause = ctx.api.server.logs.mock.calls.length;
    press(fixture, 'pause');
    expect(ctx.api.server.logs.mock.calls.length).toBe(afterPause);
    await settle(fixture, 20_000);
    expect(ctx.api.server.logs.mock.calls.length).toBe(afterPause);

    // and resuming re-arms it
    press(fixture, 'resume');
    await settle(fixture, 5_000);
    expect(ctx.api.server.logs.mock.calls.length).toBeGreaterThan(afterPause);
  });

  it('surfaces why the tail could not be read', async () => {
    const { fixture } = render(ctxStub({ logsFails: true }));

    await settle(fixture);

    expect(text(fixture)).toContain('backend unreachable');
  });

  it('re-reads on demand', async () => {
    const ctx = ctxStub({ logs: [line('info', 'a')] });
    const { fixture } = render(ctx);
    await settle(fixture);

    const before = ctx.api.server.logs.mock.calls.length;
    press(fixture, 'refresh');
    await settle(fixture);

    expect(ctx.api.server.logs.mock.calls.length).toBeGreaterThan(before);
  });
});
