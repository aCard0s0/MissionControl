import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { TerminalPanel } from './terminal-panel';

/** Only what the panel reaches for on the store. No container is selected, so
 *  nothing here opens a shell — the sessions themselves are covered by
 *  terminal-session.spec.ts, and the height by panel-height.spec.ts. */
const storeStub = () => ({
  config: { apiBaseUrl: '', dockerSocket: '' },
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

describe('TerminalPanel', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('starts collapsed, with nothing attached yet', () => {
    const { fixture } = render();

    expect(el(fixture).textContent).toContain('TERMINAL');
    expect(el(fixture).textContent).toContain('no shell');
    expect(el(fixture).querySelector('.body')).toBeNull();
    expect(el(fixture).querySelector('.tabs')).toBeNull();
  });

  it('offers no shell controls while it is closed', () => {
    const { fixture } = render();

    expect(el(fixture).querySelector('button[title="restart session"]')).toBeNull();
    expect(el(fixture).querySelector('button[title="taller"]')).toBeNull();
  });

  it('restores nothing when there are no saved tabs', () => {
    const { fixture } = render();

    expect(el(fixture).querySelectorAll('.tab').length).toBe(0);
  });
});
