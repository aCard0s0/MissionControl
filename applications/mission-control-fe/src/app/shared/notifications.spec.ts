import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MC_CONFIG } from '../core/app-config';
import { ActivityStore } from '../core/store/activity-store';
import { StoreContext } from '../core/store/store-context';
import { Notifications } from './notifications';
import { el, settle, text } from '../testing/dom';
import { specConfig } from '../testing/store';

/** Mirrors EXIT_MS in the component: how long a dropped card is held to animate out. */
const EXIT_MS = 200;

const render = () => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: MC_CONFIG, useValue: specConfig() }] });
  const fixture = TestBed.createComponent(Notifications);
  fixture.detectChanges();
  return { fixture, activity: TestBed.inject(ActivityStore), ctx: TestBed.inject(StoreContext) };
};

const cards = (fixture: { nativeElement: unknown }): HTMLElement[] =>
  Array.from(el(fixture).querySelectorAll<HTMLElement>('.note'));

describe('Notifications', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('renders nothing at all when there is nothing to report', () => {
    const { fixture } = render();

    expect(el(fixture).querySelector('.stack')).toBeNull();
  });

  it('shows work in flight with a spinner and an indeterminate bar', () => {
    const { fixture, activity } = render();

    activity.begin('deploying ops-bot');
    fixture.detectChanges();

    const [card] = cards(fixture);
    expect(card.classList.contains('running')).toBe(true);
    expect(card.querySelector('.spin')).not.toBeNull();
    // indeterminate: the backend reports no percentage, so the bar must not imply one
    expect(card.querySelector('.bar')).not.toBeNull();
    expect(text(fixture)).toContain('deploying ops-bot');
  });

  it('ticks the elapsed readout, so a slow deploy does not read as a stalled one', async () => {
    const { fixture, activity } = render();
    activity.begin('deploying ops-bot');
    fixture.detectChanges();
    expect(text(fixture)).toContain('0s');

    await settle(fixture, 5_000);

    expect(text(fixture)).toContain('5s');
  });

  it('shows a completed operation as a confirmation, and a failed one as an error', () => {
    const { fixture, ctx } = render();

    ctx.notify('container hermes-lab deployed');
    ctx.toast('start failed: port bound');
    fixture.detectChanges();

    const kinds = cards(fixture).map(c => c.className.split(' ').find(k => k === 'ok' || k === 'error'));
    expect(kinds).toEqual(['ok', 'error']);
    expect(text(fixture)).toContain('container hermes-lab deployed');
    expect(text(fixture)).toContain('start failed: port bound');
  });

  it('carries the running card and its outcome together, in the order they were raised', () => {
    const { fixture, activity, ctx } = render();

    activity.begin('deploying ops-bot');
    ctx.notify('container ops-bot deployed');
    fixture.detectChanges();

    expect(cards(fixture).map(c => c.querySelector('.msg')!.textContent!.trim()))
      .toEqual(['deploying ops-bot', 'container ops-bot deployed']);
  });

  it('holds a dropped card on screen long enough to animate out, then removes it', async () => {
    const { fixture, activity } = render();
    const running = activity.begin('deploying ops-bot');
    fixture.detectChanges();

    activity.end(running);
    fixture.detectChanges();

    // still mounted, now marked for the exit animation
    expect(cards(fixture)).toHaveLength(1);
    expect(cards(fixture)[0].classList.contains('leaving')).toBe(true);

    await settle(fixture, EXIT_MS);

    expect(el(fixture).querySelector('.stack')).toBeNull();
  });

  it('keeps a leaving card in its own slot rather than letting it jump the queue', async () => {
    const { fixture, activity, ctx } = render();
    const first = activity.begin('deploying ops-bot');
    fixture.detectChanges();
    ctx.notify('container ops-bot deployed');
    fixture.detectChanges();

    activity.end(first);
    fixture.detectChanges();

    // the card on its way out was raised first, so it stays first
    expect(cards(fixture)[0].classList.contains('leaving')).toBe(true);
    expect(cards(fixture)[0].querySelector('.msg')!.textContent!.trim()).toBe('deploying ops-bot');
  });

  it('withdraws a toast on its own timer, without touching the work still running', async () => {
    const { fixture, activity, ctx } = render();
    activity.begin('deploying ops-bot');
    ctx.notify('container hermes-lab deployed');
    fixture.detectChanges();
    expect(cards(fixture)).toHaveLength(2);

    // the store drops it at 6s; the stack then holds it for the exit animation
    await settle(fixture, 6_000);
    await settle(fixture, EXIT_MS);

    expect(cards(fixture).map(c => c.className.includes('running'))).toEqual([true]);
  });

  it('lets an operator dismiss a toast early, but offers no such control on running work', () => {
    const { fixture, activity, ctx } = render();
    activity.begin('deploying ops-bot');
    ctx.toast('start failed: port bound');
    fixture.detectChanges();

    const [running, failed] = cards(fixture);
    expect(running.querySelector('.x')).toBeNull();

    failed.querySelector<HTMLButtonElement>('.x')!.click();
    fixture.detectChanges();

    expect(ctx.toasts()).toHaveLength(0);
  });

  it('stacks every running operation instead of collapsing them into one', () => {
    const { fixture, activity } = render();

    activity.begin('deploying ops-bot');
    activity.begin('starting hermes-lab');
    fixture.detectChanges();

    expect(cards(fixture)).toHaveLength(2);
  });
});
