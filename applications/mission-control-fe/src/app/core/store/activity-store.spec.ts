import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ActivityStore } from './activity-store';

const store = (): ActivityStore => {
  TestBed.resetTestingModule();
  return TestBed.inject(ActivityStore);
};

describe('ActivityStore', () => {
  let activity: ActivityStore;

  beforeEach(() => { activity = store(); });

  it('holds nothing until something is started', () => {
    expect(activity.active()).toEqual([]);
    expect(activity.busy()).toBe(false);
  });

  it('carries the label the shell reads out, and drops it when it ends', () => {
    const id = activity.begin('deploying ops-bot');

    expect(activity.active().map(a => a.label)).toEqual(['deploying ops-bot']);
    expect(activity.busy()).toBe(true);

    activity.end(id);

    expect(activity.active()).toEqual([]);
    expect(activity.busy()).toBe(false);
  });

  it('keeps concurrent operations apart, in the order they were started', () => {
    const first = activity.begin('deploying ops-bot');
    activity.begin('starting hermes-lab');

    activity.end(first);

    expect(activity.active().map(a => a.label)).toEqual(['starting hermes-lab']);
  });

  it('ignores an id it does not hold, so a caller may end one twice', () => {
    const id = activity.begin('deploying ops-bot');
    activity.end(id);

    expect(() => activity.end(id)).not.toThrow();
    expect(activity.active()).toEqual([]);
  });

  it('clears the entry when the work resolves, and answers what it answered', async () => {
    const result = await activity.run('deploying ops-bot', async () => 'a-new');

    expect(result).toBe('a-new');
    expect(activity.active()).toEqual([]);
  });

  it('clears the entry when the work throws, rather than advertising it forever', async () => {
    await expect(activity.run('deploying ops-bot', async () => {
      throw new Error('name already in use');
    })).rejects.toThrow('name already in use');

    expect(activity.active()).toEqual([]);
  });

  it('is running for as long as the work is', async () => {
    let release = (): void => { /* replaced below */ };
    const work = activity.run('deploying ops-bot', () => new Promise<string>(resolve => {
      release = () => resolve('a-new');
    }));

    expect(activity.busy()).toBe(true);

    release();
    await work;

    expect(activity.busy()).toBe(false);
  });
});
