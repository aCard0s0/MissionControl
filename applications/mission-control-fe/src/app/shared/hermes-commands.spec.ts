import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HERMES_COMMANDS, HERMES_DOCS } from '../core/hermes-commands';
import { HermesCommands } from './hermes-commands';
import { el, settle, text, type as typeInto } from '../testing/dom';

/** Drives the list the way both hosts do — a profile to scope by, and a place for the line. */
@Component({
  selector: 'mc-hermes-commands-host',
  imports: [HermesCommands],
  template: `<mc-hermes-commands [profile]="profile()" [canInsert]="canInsert()" [dark]="dark()"
                                 (insert)="inserted.set($event)" />`,
})
class Host {
  readonly profile = signal<string | undefined>(undefined);
  readonly canInsert = signal(false);
  readonly dark = signal(false);
  readonly inserted = signal<string | null>(null);
}

const render = (setup: (host: Host) => void = () => { /* defaults */ }) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(Host);
  setup(fixture.componentInstance);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance };
};

type Fixture = ReturnType<typeof render>['fixture'];

/** Every rendered command line, as an operator reads them. */
const lines = (fixture: Fixture): string[] =>
  Array.from(el(fixture).querySelectorAll('.cmd .line')).map(c => (c.textContent ?? '').trim());

/** The row whose command line is exactly this. */
const row = (fixture: Fixture, line: string): HTMLElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.cmd'))
    .find(r => (r.querySelector('.line')?.textContent ?? '').trim() === line);
  if (!match) throw new Error(`no row for "${line}"`);
  return match;
};

const act = (fixture: Fixture, line: string, label: string): HTMLElement => {
  const match = Array.from(row(fixture, line).querySelectorAll<HTMLElement>('.act'))
    .find(a => (a.textContent ?? '').trim().startsWith(label));
  if (!match) throw new Error(`no "${label}" action on "${line}"`);
  return match;
};

describe('the hermes command list', () => {
  it('shows every command in the catalog', () => {
    const { fixture } = render();

    expect(lines(fixture)).toHaveLength(HERMES_COMMANDS.length);
    expect(lines(fixture)).toContain('hermes cron');
  });

  it('scopes every line to the profile it was given', () => {
    const { fixture } = render(h => h.profile.set('ops-bot'));

    expect(lines(fixture)).toContain('hermes -p ops-bot cron');
    expect(text(fixture)).toContain('-p ops-bot');
  });

  it('leaves lines bare with no profile, rather than guessing at one', () => {
    const { fixture } = render();

    expect(lines(fixture).every(l => !l.includes('-p '))).toBe(true);
  });

  it('links each row to its own section of the upstream reference', () => {
    const { fixture } = render();

    expect(act(fixture, 'hermes cron', 'docs').getAttribute('href'))
      .toBe(`${HERMES_DOCS}#hermes-cron`);
  });

  it('marks the commands that would rewrite the install of a container deployment', () => {
    const { fixture } = render();

    expect(row(fixture, 'hermes uninstall').textContent).toContain('rewrites the install');
    expect(row(fixture, 'hermes cron').textContent).not.toContain('rewrites the install');
  });
});

describe('searching the list', () => {
  it('filters to what matches, and says how much is left', async () => {
    const { fixture } = render();

    await typeInto(fixture, '.find .input', 'webhook');

    expect(lines(fixture)).toContain('hermes webhook');
    expect(lines(fixture)).not.toContain('hermes cron');
    expect(text(fixture)).toContain(`${lines(fixture).length}/${HERMES_COMMANDS.length}`);
  });

  it('says so when nothing matches, instead of showing an empty page', async () => {
    const { fixture } = render();

    await typeInto(fixture, '.find .input', 'kubernetes');

    expect(lines(fixture)).toEqual([]);
    expect(text(fixture)).toContain('no command matches');
  });
});

describe('putting a line somewhere', () => {
  beforeEach(() => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockResolvedValue(undefined) },
      configurable: true,
    });
  });

  afterEach(() => {
    Reflect.deleteProperty(navigator as unknown as Record<string, unknown>, 'clipboard');
    vi.useRealTimers();
  });

  it('copies the scoped line, and confirms on the row that was clicked', async () => {
    const { fixture } = render(h => h.profile.set('ops-bot'));

    act(fixture, 'hermes -p ops-bot doctor', 'copy').click();
    await settle(fixture);

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('hermes -p ops-bot doctor');
    expect(row(fixture, 'hermes -p ops-bot doctor').textContent).toContain('copied');
    expect(row(fixture, 'hermes -p ops-bot cron').textContent).not.toContain('copied');
  });

  it('offers no insert where there is no shell to insert into', () => {
    const { fixture } = render();

    expect(el(fixture).textContent).not.toContain('insert');
  });

  it('emits the scoped line for the host to place, and runs nothing itself', () => {
    const { fixture, host } = render(h => {
      h.canInsert.set(true);
      h.profile.set('ops-bot');
    });

    act(fixture, 'hermes -p ops-bot status', 'insert').click();

    expect(host.inserted()).toBe('hermes -p ops-bot status');
  });

  it('takes the terminal palette when asked, so it can sit inside the panel', () => {
    const { fixture } = render(h => h.dark.set(true));

    expect(el(fixture).querySelector('mc-hermes-commands')!.classList).toContain('dark');
  });
});
