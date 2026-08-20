import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { LogEntry, LogLevel } from '../core/models';
import { LogView } from './log-view';
import { el, text } from '../testing/dom';

/** 2026-08-20T14:30:05Z — a fixed instant, so the rendered stamp is assertable. */
const TS = Date.UTC(2026, 7, 20, 14, 30, 5);

const line = (level: LogLevel, msg: string, ts = TS): LogEntry =>
  ({ ts, level, source: 'ContainerInventory', agentId: null, msg });

@Component({
  imports: [LogView],
  template: `<mc-log-view [lines]="lines()" [loading]="loading()" [error]="error()"
                          [emptyText]="emptyText()" [heightKey]="key()" />`,
})
class Host {
  readonly lines = signal<LogEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly emptyText = signal('No log entries.');
  readonly key = signal('mc-log-spec');
}

const render = () => {
  TestBed.resetTestingModule();
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance };
};

/** The filter button whose label starts with this level — each carries a count suffix. */
const filter = (fixture: ReturnType<typeof render>['fixture'], label: string) => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.log-filter button'))
    .find(b => (b.textContent ?? '').trim().startsWith(label));
  if (!match) throw new Error(`no filter button for "${label}"`);
  match.click();
  fixture.detectChanges();
};

const rows = (fixture: ReturnType<typeof render>['fixture']) =>
  Array.from(el(fixture).querySelectorAll('.log-line'));

describe('LogView', () => {
  beforeEach(() => localStorage.clear());

  it('stamps each line with a date, not a bare clock', () => {
    // a tail spans midnight, and a stopped container's is often days old — the time
    // alone read as "today" for entries that were not
    const { fixture, host } = render();
    host.lines.set([line('info', 'started')]);
    fixture.detectChanges();

    const stamp = (rows(fixture)[0].querySelector('.ts')?.textContent ?? '').trim();
    expect(stamp).toContain('20 Aug');
    expect(stamp).toMatch(/\d\d:\d\d:\d\d/);
  });

  it('offers a filter for every level the dashboard renders, info included', () => {
    const { fixture } = render();

    const labels = Array.from(el(fixture).querySelectorAll('.log-filter button'))
      .map(b => (b.textContent ?? '').trim().replace(/\d+$/, ''));
    expect(labels).toEqual(['all', 'error', 'warn', 'info', 'debug']);
  });

  it('narrows to one level and says so when that level is empty', () => {
    const { fixture, host } = render();
    host.lines.set([line('info', 'started'), line('warn', 'slow'), line('error', 'boom')]);
    fixture.detectChanges();

    filter(fixture, 'info');
    expect(rows(fixture).length).toBe(1);
    expect(text(fixture)).toContain('started');

    filter(fixture, 'debug');
    expect(rows(fixture).length).toBe(0);
    expect(text(fixture)).toContain('No lines at this level');

    filter(fixture, 'all');
    expect(rows(fixture).length).toBe(3);
  });

  it('counts each level on its own button', () => {
    const { fixture, host } = render();
    host.lines.set([line('warn', 'a'), line('warn', 'b'), line('error', 'c')]);
    fixture.detectChanges();

    const labelFor = (level: string) =>
      Array.from(el(fixture).querySelectorAll('.log-filter button'))
        .map(b => (b.textContent ?? '').trim())
        .find(l => l.startsWith(level));
    expect(labelFor('warn')).toBe('warn2');
    expect(labelFor('error')).toBe('error1');
    expect(labelFor('debug')).toBe('debug0');
  });

  it('reports how many of the tail the current filter shows', () => {
    const { fixture, host } = render();
    host.lines.set([line('info', 'a'), line('error', 'b')]);
    fixture.detectChanges();

    expect(text(fixture)).toContain('2 / 2');
    filter(fixture, 'error');
    expect(text(fixture)).toContain('1 / 2');
  });

  it('resizes by dragging its bottom edge, and remembers where it was let go', () => {
    const { fixture } = render();
    const heightOf = () =>
      Number(el(fixture).querySelector<HTMLElement>('.logs')!.style.height.replace('px', ''));

    const start = heightOf();
    el(fixture).querySelector<HTMLElement>('.log-grip')!
      .dispatchEvent(new PointerEvent('pointerdown', { clientY: 100, bubbles: true }));
    // the grip is under the rows, so dragging down is what makes the panel taller
    window.dispatchEvent(new PointerEvent('pointermove', { clientY: 160 }));
    window.dispatchEvent(new PointerEvent('pointerup'));
    fixture.detectChanges();

    expect(heightOf()).toBe(start + 60);
    // the size survives a reload, and is keyed so two placements do not fight over one value
    expect(Number(localStorage.getItem('mc-log-spec'))).toBe(start + 60);
  });

  it('keeps the rows on screen when a refresh fails', () => {
    // a failed poll does not discard the tail already read — the banner joins it
    const { fixture, host } = render();
    host.lines.set([line('info', 'last known')]);
    host.error.set('log refresh failed');
    fixture.detectChanges();

    expect(rows(fixture).length).toBe(1);
    expect(text(fixture)).toContain('log refresh failed');
  });

  it('distinguishes loading from genuinely empty', () => {
    const { fixture, host } = render();
    host.loading.set(true);
    fixture.detectChanges();
    expect(text(fixture)).toContain('Loading logs…');

    host.loading.set(false);
    host.emptyText.set('No container log entries.');
    fixture.detectChanges();
    expect(text(fixture)).toContain('No container log entries.');
  });

  // ── reading order ──────────────────────────────────────────────────────────

  const messages = (fixture: ReturnType<typeof render>['fixture']) =>
    rows(fixture).map(r => (r.querySelector('.msg')?.textContent ?? '').trim());

  it('reads like a log: oldest at the top, newest on the last line', () => {
    const { fixture, host } = render();
    host.lines.set([
      line('info', 'newest', TS + 2000),
      line('info', 'middle', TS + 1000),
      line('info', 'oldest', TS),
    ]);
    fixture.detectChanges();

    expect(messages(fixture)).toEqual(['oldest', 'middle', 'newest']);
  });

  it('orders by time whichever way the caller handed them over', () => {
    // the container and server tails arrive newest-first so a caller can take the newest N;
    // the MCP reader already sorts ascending. Both have to render the same way round.
    const ascending = [line('info', 'a', TS), line('info', 'b', TS + 1000)];
    const { fixture, host } = render();

    host.lines.set(ascending);
    fixture.detectChanges();
    expect(messages(fixture)).toEqual(['a', 'b']);

    host.lines.set([...ascending].reverse());
    fixture.detectChanges();
    expect(messages(fixture)).toEqual(['a', 'b']);
  });

  it('keeps the newest line in view as lines arrive', async () => {
    const { fixture, host } = render();
    const body = el(fixture).querySelector<HTMLElement>('.logs')!;
    Object.defineProperty(body, 'scrollHeight', { value: 500, configurable: true });
    Object.defineProperty(body, 'clientHeight', { value: 100, configurable: true });

    host.lines.set([line('info', 'first', TS)]);
    fixture.detectChanges();
    await Promise.resolve();

    expect(body.scrollTop).toBe(500);
  });

  it('stops following once the operator scrolls up, and resumes at the bottom', async () => {
    // pulling someone back down while they are reading is what made older lines
    // feel unreachable in the first place
    const { fixture, host } = render();
    const body = el(fixture).querySelector<HTMLElement>('.logs')!;
    Object.defineProperty(body, 'scrollHeight', { value: 500, configurable: true });
    Object.defineProperty(body, 'clientHeight', { value: 100, configurable: true });
    await Promise.resolve();                   // let the initial follow settle first

    body.scrollTop = 20;                       // scrolled up into the history
    body.dispatchEvent(new Event('scroll'));
    host.lines.set([line('info', 'arrived while reading', TS + 5000)]);
    fixture.detectChanges();
    await Promise.resolve();
    expect(body.scrollTop).toBe(20);

    body.scrollTop = 400;                      // back at the bottom (500 - 100)
    body.dispatchEvent(new Event('scroll'));
    host.lines.set([line('info', 'arrived after catching up', TS + 6000)]);
    fixture.detectChanges();
    await Promise.resolve();
    expect(body.scrollTop).toBe(500);
  });
});
