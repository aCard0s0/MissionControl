import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { CronJob } from '../core/models';
import { CalendarPage } from './calendar';
import { TestFixture, el, field, fill, press, settle } from '../testing/dom';
import { cronJob } from '../testing/models';

/** A Wednesday, so the Monday-first grid has to lead with the previous month. */
const TODAY = new Date('2026-04-15T09:00:00Z');
const at = (iso: string) => new Date(iso).getTime();

const agents = [{ id: 'a-1', name: 'atlas' }, { id: 'a-2', name: 'scribe' }];

const job = (id: string, nextRun: number, patch: Partial<CronJob> = {}): CronJob =>
  cronJob(id, { nextRun, ...patch });

const storeStub = (jobs: CronJob[] = []) => ({
  containerJobs: signal(jobs),
  schedulerRunning: signal(true),
  containerAgents: signal(agents),
  selectedContainer: signal({ id: 'c-1', name: 'hermes-prod' }),
  agentById: (id: string) => agents.find(a => a.id === id) ?? null,
  toggleJob: vi.fn().mockResolvedValue(true),
  updateJob: vi.fn().mockResolvedValue(true),
  createJob: vi.fn().mockResolvedValue(true),
  runJobNow: vi.fn().mockResolvedValue(true),
  removeJob: vi.fn().mockResolvedValue(true),
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(CalendarPage);
  fixture.detectChanges();
  return { fixture, store };
};

/** The grid cell for this day-of-month in the shown month. */
const day = (fixture: TestFixture, num: number): HTMLElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.day'))
    .find(d => (d.querySelector('.num')?.textContent ?? '').trim() === String(num)
      && !d.className.includes('out'));
  if (!match) throw new Error(`no cell for day ${num}`);
  return match;
};

describe('CalendarPage month grid', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(TODAY);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('opens on this month, in whole Monday-first weeks', () => {
    const { fixture } = render(storeStub());

    expect(el(fixture).textContent).toContain('APRIL 2026');
    // April 2026 starts on a Wednesday, so the grid runs Mar 30 – May 3: five weeks
    expect(el(fixture).querySelectorAll('.day').length).toBe(35);
    expect(el(fixture).querySelector('.weekhead')!.textContent).toContain('MON');
  });

  it('walks months without carrying the selected day across', () => {
    const { fixture } = render(storeStub());
    day(fixture, 15).click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('WED 15 APR');

    press(fixture, '→', '.month-nav');
    expect(el(fixture).textContent).toContain('MAY 2026');
    expect(el(fixture).textContent).toContain('ALL SCHEDULED JOBS');

    press(fixture, '←', '.month-nav');
    expect(el(fixture).textContent).toContain('APRIL 2026');
  });

  it('marks the days a job is next due on', () => {
    const { fixture } = render(storeStub([
      job('j-1', at('2026-04-15T09:00:00Z')),
      job('j-2', at('2026-04-15T18:00:00Z')),
      job('j-3', at('2026-04-20T09:00:00Z')),
    ]));

    expect(day(fixture, 15).querySelectorAll('.mark').length).toBe(2);
    expect(day(fixture, 20).querySelectorAll('.mark').length).toBe(1);
    expect(day(fixture, 21).querySelectorAll('.mark').length).toBe(0);
  });

  it('lists every job until a day is picked, then only that day\'s', () => {
    const { fixture } = render(storeStub([
      job('j-1', at('2026-04-15T09:00:00Z')),
      job('j-2', at('2026-04-20T09:00:00Z')),
    ]));
    expect(el(fixture).querySelectorAll('.job').length).toBe(2);

    day(fixture, 15).click();
    fixture.detectChanges();

    expect(el(fixture).querySelectorAll('.job').length).toBe(1);
    expect(el(fixture).textContent).toContain('job j-1');
  });

  it('picking the same day again goes back to every job', () => {
    const { fixture } = render(storeStub([job('j-1', at('2026-04-15T09:00:00Z'))]));

    day(fixture, 15).click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('WED 15 APR');

    day(fixture, 15).click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('ALL SCHEDULED JOBS');
  });

  it('orders the full list by what runs next', () => {
    const { fixture } = render(storeStub([
      job('j-late', at('2026-04-20T09:00:00Z')),
      job('j-soon', at('2026-04-16T09:00:00Z')),
    ]));

    const names = Array.from(el(fixture).querySelectorAll('.job .name'))
      .map(n => (n.textContent ?? '').trim());
    expect(names).toEqual(['job j-soon', 'job j-late']);
  });
});

describe('CalendarPage job form', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(TODAY);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('creates a job on the container, defaulting delivery to the CLI', async () => {
    const { fixture, store } = render(storeStub());

    press(fixture, '+ schedule job');
    await fill(fixture, 'name', 'nightly digest');
    await fill(fixture, 'schedule', '0 9 * * *');

    press(fixture, 'create', '.form-actions');
    await settle(fixture);

    expect(store.createJob).toHaveBeenCalledWith(
      'c-1', 'a-1', 'nightly digest', '0 9 * * *', '', 'local');
  });

  it('refuses a job with no name or an unreadable schedule', async () => {
    const { fixture, store } = render(storeStub());
    press(fixture, '+ schedule job');

    await fill(fixture, 'name', 'nightly digest');
    await fill(fixture, 'schedule', 'whenever i feel like it');
    press(fixture, 'create', '.form-actions');

    expect(store.createJob).not.toHaveBeenCalled();
  });

  it('loads an existing job for editing and updates it in place', async () => {
    const { fixture, store } = render(storeStub([job('j-1', at('2026-04-15T09:00:00Z'))]));

    press(fixture, 'edit', '.job');
    await settle(fixture);
    expect(field(fixture, 'name').querySelector<HTMLInputElement>('.input')!.value)
      .toBe('job j-1');

    await fill(fixture, 'name', 'renamed');
    press(fixture, 'save', '.form-actions');
    await settle(fixture);

    expect(store.updateJob).toHaveBeenCalledWith('j-1', {
      name: 'renamed', schedule: '0 9 * * *', prompt: 'do the thing', deliverTo: 'slack',
    });
    expect(store.createJob).not.toHaveBeenCalled();
  });

  it('sends pause and delete straight to the store', () => {
    const { fixture, store } = render(storeStub([job('j-1', at('2026-04-15T09:00:00Z'))]));

    press(fixture, 'pause', '.job');
    expect(store.toggleJob).toHaveBeenCalledWith('j-1');

    press(fixture, 'delete', '.job');
    expect(store.removeJob).toHaveBeenCalledWith('j-1');
  });

  it('closes the form without writing anything', async () => {
    const { fixture, store } = render(storeStub());
    press(fixture, '+ schedule job');
    await fill(fixture, 'name', 'abandoned');

    press(fixture, 'cancel', '.form-actions');

    expect(el(fixture).querySelector('.form')).toBeNull();
    expect(store.createJob).not.toHaveBeenCalled();
  });
});
