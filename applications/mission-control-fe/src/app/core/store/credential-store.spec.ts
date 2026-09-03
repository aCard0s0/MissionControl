import { describe, expect, it, vi } from 'vitest';
import { ApiCredential } from '../hermes-api';
import { liveError, testSlices } from '../../testing/store';

const apiCredential = (id: string, patch: Partial<ApiCredential> = {}): ApiCredential => ({
  id, name: `cred-${id}`, description: 'the production key',
  entries: [
    { key: 'TELEGRAM_BOT_TOKEN', value: null, secret: true, set: true, recoverable: true },
    { key: 'TELEGRAM_HOME_CHANNEL', value: '#ops', secret: false, set: true, recoverable: true },
  ],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const loaded = async (credentials: ApiCredential[], api: Record<string, unknown> = {}) => {
  const { ctx, credentials: store } = testSlices({
    credentials: { list: vi.fn().mockResolvedValue(credentials), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('CredentialStore', () => {
  it('reads the credentials and fills in the fields the wire leaves null', async () => {
    const { store } = await loaded([apiCredential('cr-1', { description: null, entries: null })]);

    expect(store.credentials()[0]).toMatchObject({ id: 'cr-1', description: '', entries: [] });
  });

  it('never carries a secret value, whatever the wire says', async () => {
    // the backend sends null; a build that started sending something must not reach a form
    const { store } = await loaded([apiCredential('cr-1', {
      entries: [{ key: 'A_KEY', value: 'leaked', secret: true, set: true, recoverable: true }],
    })]);

    expect(store.credentials()[0].entries[0].value).toBe('');
  });

  it('keeps a plain entry value, because the picker has to show it', async () => {
    const { store } = await loaded([apiCredential('cr-1')]);

    expect(store.credentials()[0].entries[1]).toMatchObject({
      key: 'TELEGRAM_HOME_CHANNEL', value: '#ops', secret: false,
    });
  });

  it('treats a missing recoverable flag as the more demanding case', async () => {
    // a picker offering a key the write will refuse is worse than one that warns
    const { store } = await loaded([apiCredential('cr-1', {
      entries: [{
        key: 'A_KEY', value: null, secret: true, set: true,
        recoverable: false as unknown as boolean,
      }],
    })]);

    expect(store.credentials()[0].entries[0].recoverable).toBe(false);
  });

  it('keeps the last credentials when a read fails, rather than emptying the picker', async () => {
    const { store, ctx } = await loaded([apiCredential('cr-1')], {
      list: vi.fn().mockResolvedValueOnce([apiCredential('cr-1')])
        .mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.credentials().map(c => c.id)).toEqual(['cr-1']);
    expect(liveError(ctx)).toBeNull();
  });

  it('answers only the credentials holding the variable a row asks for', async () => {
    const { store } = await loaded([
      apiCredential('cr-1', { name: 'telegram ops' }),
      apiCredential('cr-2', {
        name: 'anthropic',
        entries: [{
          key: 'ANTHROPIC_API_KEY', value: null, secret: true, set: true, recoverable: true,
        }],
      }),
    ]);

    expect(store.providing('ANTHROPIC_API_KEY').map(c => c.name)).toEqual(['anthropic']);
    expect(store.providing('TELEGRAM_HOME_CHANNEL').map(c => c.name)).toEqual(['telegram ops']);
    expect(store.providing('OPENAI_API_KEY')).toEqual([]);
  });

  it('keeps the list by name when a save lands, so the dropdown does not reorder', async () => {
    const { store } = await loaded(
      [apiCredential('cr-1', { name: 'alpha' }), apiCredential('cr-2', { name: 'zebra' })],
      { create: vi.fn().mockResolvedValue(apiCredential('cr-3', { name: 'middle' })) });

    await store.save({ name: 'middle', description: '', entries: [] });

    expect(store.credentials().map(c => c.name)).toEqual(['alpha', 'middle', 'zebra']);
  });

  it('reports a failed save and keeps the list untouched', async () => {
    const { store, ctx } = await loaded([apiCredential('cr-1')], {
      create: vi.fn().mockRejectedValue(new Error('name taken')),
    });

    expect(await store.save({ name: 'anthropic', description: '', entries: [] })).toBe('');
    expect(store.credentials().map(c => c.id)).toEqual(['cr-1']);
    expect(liveError(ctx)).toContain('save credential');
  });

  it('replaces the row in place on an update rather than adding a second', async () => {
    const { store } = await loaded([apiCredential('cr-1', { name: 'anthropic' })], {
      update: vi.fn().mockResolvedValue(apiCredential('cr-1', { name: 'anthropic prod' })),
    });

    await store.save({ name: 'anthropic prod', description: '', entries: [] }, 'cr-1');

    expect(store.credentials().map(c => c.name)).toEqual(['anthropic prod']);
  });

  it('drops the row once a delete lands', async () => {
    const { store } = await loaded([apiCredential('cr-1'), apiCredential('cr-2')], {
      remove: vi.fn().mockResolvedValue(undefined),
    });

    expect(await store.remove('cr-1')).toBe(true);
    expect(store.credentials().map(c => c.id)).toEqual(['cr-2']);
  });

  it('reports a failed delete and keeps the row', async () => {
    const { store, ctx } = await loaded([apiCredential('cr-1')], {
      remove: vi.fn().mockRejectedValue(new Error('offline')),
    });

    expect(await store.remove('cr-1')).toBe(false);
    expect(store.credentials().map(c => c.id)).toEqual(['cr-1']);
    expect(liveError(ctx)).toContain('delete credential');
  });
});
