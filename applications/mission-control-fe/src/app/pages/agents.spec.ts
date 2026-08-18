import '@angular/compiler';
import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentsPage } from './agents';
import { agentSessionCommand } from './agents';

describe('Agent shell shortcut command', () => {
  it('scopes a named profile with -p', () => {
    expect(agentSessionCommand('ops-bot')).toBe('hermes -p ops-bot');
  });

  it('invokes the default profile bare — hermes takes -p only for named ones', () => {
    expect(agentSessionCommand('default')).toBe('hermes');
  });

  it('accepts the punctuation hermes allows in a profile directory name', () => {
    expect(agentSessionCommand('ops.bot_2-v1')).toBe('hermes -p ops.bot_2-v1');
  });

  it('refuses a name carrying shell metacharacters so nothing is typed blind', () => {
    for (const name of ['ops; rm -rf /', 'ops bot', 'ops$(id)', 'ops`id`', 'ops&&id', '../escape', '']) {
      expect(agentSessionCommand(name)).toBeUndefined();
    }
  });
});

// A real HermesStore in mock mode, so the roster is covered against the store's
// actual seeded profiles.
const render = () => {
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(AgentsPage);
  fixture.detectChanges();
  return fixture;
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

describe('AgentsPage roster', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      dataMode: 'mock', apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('shows one card per profile in the selected container, with the day\'s totals', () => {
    const fixture = render();

    const cards = el(fixture).querySelectorAll('.card-wrap');
    expect(cards.length).toBeGreaterThan(0);
    expect(el(fixture).textContent).toContain('atlas');
    expect(el(fixture).textContent).toContain('msgs today');
    expect(el(fixture).textContent).toContain('tokens today');
  });

  it('disables the shell shortcut, which needs the live backend', () => {
    const fixture = render();

    const shell = el(fixture).querySelector<HTMLButtonElement>('.shell-btn')!;
    expect(shell.disabled).toBe(true);
    expect(shell.title).toContain('needs the live backend');
  });

  it('opens the create dialog on the selected container', async () => {
    const fixture = render();

    Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === '+ new agent')!.click();
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    expect(el(fixture).querySelector('mc-agent-create-dialog')).not.toBeNull();
    expect(el(fixture).textContent).toContain('NEW AGENT PROFILE —');
    // the dialog is where the Nous login warning belongs, and mock mode is not logged in
    expect(el(fixture).textContent).toContain('Nous Portal');
  });

  it('takes the dialog away again on cancel', async () => {
    const fixture = render();
    Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === '+ new agent')!.click();
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();
    fixture.detectChanges();

    expect(el(fixture).querySelector('mc-agent-create-dialog')).toBeNull();
  });
});
