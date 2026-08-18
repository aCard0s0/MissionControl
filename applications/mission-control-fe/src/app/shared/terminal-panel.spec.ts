import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { TerminalPanel } from './terminal-panel';

/** Only what the panel reaches for on the store. Mock mode is the dev default,
 *  and the one mode where no shell is ever opened — so no socket is involved. */
const storeStub = () => ({
  config: { dataMode: 'mock' as const, apiBaseUrl: '', dockerSocket: '' },
  terminalRequest: signal(null),
  containers: signal([]),
  selectedContainer: signal(null),
  hostById: () => null,
  toast: vi.fn(),
});

const render = () => {
  const store = storeStub();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(TerminalPanel);
  fixture.detectChanges();
  return { fixture, store };
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

type Fixture = { nativeElement: unknown; detectChanges(): void };

const press = (fixture: Fixture, title: string): void => {
  const match = el(fixture).querySelector<HTMLButtonElement>(`button[title="${title}"]`);
  if (!match) throw new Error(`no button titled "${title}"`);
  match.click();
  fixture.detectChanges();
};

const bodyHeight = (fixture: Fixture): number =>
  Number(el(fixture).querySelector<HTMLElement>('.body')!.style.height.replace('px', ''));

describe('TerminalPanel in mock mode', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('starts collapsed, with the bar saying there is no live backend', () => {
    const { fixture } = render();

    expect(el(fixture).textContent).toContain('TERMINAL');
    expect(el(fixture).textContent).toContain('live mode only');
    expect(el(fixture).querySelector('.body')).toBeNull();
  });

  it('opens on the bar, and explains what the terminal needs', () => {
    const { fixture } = render();

    el(fixture).querySelector<HTMLElement>('.bar')!.click();
    fixture.detectChanges();

    expect(el(fixture).querySelector('.body')).not.toBeNull();
    expect(el(fixture).textContent).toContain('switch dataMode to \'live\'');
    // no shell to restart or clear, and no tab strip, without a live backend
    expect(el(fixture).querySelector('button[title="restart session"]')).toBeNull();
    expect(el(fixture).querySelector('.tabs')).toBeNull();
  });

  it('steps the height, and remembers it for the next load', () => {
    const { fixture } = render();
    el(fixture).querySelector<HTMLElement>('.bar')!.click();
    fixture.detectChanges();
    const start = bodyHeight(fixture);

    press(fixture, 'taller');
    expect(bodyHeight(fixture)).toBe(start + 80);

    press(fixture, 'shorter');
    expect(bodyHeight(fixture)).toBe(start);
    expect(localStorage.getItem('mc-terminal-height')).toBe(String(start));
  });

  it('resizes by dragging its top edge', () => {
    const { fixture } = render();
    el(fixture).querySelector<HTMLElement>('.bar')!.click();
    fixture.detectChanges();
    const start = bodyHeight(fixture);

    el(fixture).querySelector('.drag')!
      .dispatchEvent(new PointerEvent('pointerdown', { clientY: 400, bubbles: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { clientY: 360 }));
    window.dispatchEvent(new PointerEvent('pointerup'));
    fixture.detectChanges();

    expect(bodyHeight(fixture)).toBe(start + 40);
  });

  it('opens at the height it was last left at', () => {
    localStorage.setItem('mc-terminal-height', '360');
    const { fixture } = render();

    el(fixture).querySelector<HTMLElement>('.bar')!.click();
    fixture.detectChanges();

    expect(bodyHeight(fixture)).toBe(360);
  });
});
