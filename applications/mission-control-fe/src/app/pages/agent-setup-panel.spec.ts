import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AgentSetupPanel } from './agent-setup-panel';
import { buttonWith, el } from '../testing/dom';
import { agent } from '../testing/models';
import { provideStores } from '../testing/store';

const setupFor = (name: string, patch: object = {}) => ({
  envPath: `/opt/data/profiles/${name}/.env`,
  envExists: true,
  apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: false, masked: null }],
  authProviders: [{ label: 'Nous Portal', ok: false, status: 'not logged in', hint: 'hermes portal' }],
  apiKeyProviders: [],
  messaging: [{
    label: 'Telegram', ok: false, status: 'not configured',
    tokenVar: 'TELEGRAM_BOT_TOKEN', homeVar: 'TELEGRAM_HOME_CHANNEL', homeChannel: null,
  }],
  ...patch,
});

/** A store stub with the same caching contract as AgentSetupStore. */
const storeStub = () => {
  const cache = signal<Record<string, ReturnType<typeof setupFor>>>({});
  const loading = signal<ReadonlySet<string>>(new Set());
  const setup = {
    reads: [] as Array<{ id: string; force: boolean }>,
    setupOf: (id: string) => cache()[id] ?? null,
    isSetupLoading: (id: string) => loading().has(id),
    setup: vi.fn((id: string, force = false) => {
      setup.reads.push({ id, force });
      if (cache()[id] && !force) return Promise.resolve(cache()[id]);
      cache.update(all => ({ ...all, [id]: setupFor(id) }));
      return Promise.resolve(cache()[id]);
    }),
    setEnv: vi.fn((id: string, entries: Array<{ key: string; value: string | null }>) => {
      cache.update(all => ({
        ...all,
        [id]: setupFor(id, {
          apiKeys: [{
            label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY',
            set: entries[0].value !== null, masked: entries[0].value ? '…here' : null,
          }],
        }),
      }));
      return Promise.resolve(cache()[id]);
    }),
    initEnv: vi.fn((id: string) => {
      cache.update(all => ({ ...all, [id]: setupFor(id, { envExists: true }) }));
      return Promise.resolve(cache()[id]);
    }),
  };
  return { setup, terminal: { open: vi.fn() }, cache, loading };
};

@Component({
  imports: [AgentSetupPanel],
  template: `<mc-agent-setup-panel [agent]="agent()" />`,
})
class Host {
  readonly agent = signal(profile());
}

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return fixture;
};

const profile = (id = 'a-atlas', name = 'atlas') => agent(id, { name });

describe('AgentSetupPanel', () => {
  it('reads the setup when it opens, and renders what came back', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.setup.reads).toEqual([{ id: 'a-atlas', force: false }]);
    expect(el(fixture).textContent).toContain('/opt/data/profiles/a-atlas/.env');
    expect(el(fixture).textContent).toContain('ANTHROPIC_API_KEY');
    expect(el(fixture).textContent).toContain('Nous Portal');
  });

  it('asks for a forced re-read only when refresh is pressed', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();

    buttonWith(fixture, 'refresh').click();
    await fixture.whenStable();

    expect(store.setup.reads).toEqual([
      { id: 'a-atlas', force: false },
      { id: 'a-atlas', force: true },
    ]);
  });

  it('reads a different profile when the tab switches to one', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();

    fixture.componentInstance.agent.set(profile('a-scribe', 'scribe'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.setup.reads.map(r => r.id)).toEqual(['a-atlas', 'a-scribe']);
    expect(el(fixture).textContent).toContain('/opt/data/profiles/a-scribe/.env');
  });

  it('sends a typed key, then clears the field and shows what the store answered', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    const input = el(fixture).querySelector<HTMLInputElement>('.key-in')!;
    input.value = ' sk-ant-typed ';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    buttonWith(fixture, 'set').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.setup.setEnv).toHaveBeenCalledWith(
      'a-atlas', [{ key: 'ANTHROPIC_API_KEY', value: 'sk-ant-typed' }]);
    expect(el(fixture).querySelector<HTMLInputElement>('.key-in')!.value).toBe('');
    expect(el(fixture).textContent).toContain('…here');
  });

  it('refuses to send an empty value as a set', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(buttonWith(fixture, 'set').disabled).toBe(true);
    buttonWith(fixture, 'set').click();
    expect(store.setup.setEnv).not.toHaveBeenCalled();
  });

  it('clears a key that is already set, without typing anything', async () => {
    const store = storeStub();
    store.cache.set({ 'a-atlas': setupFor('a-atlas', {
      apiKeys: [{ label: 'Anthropic', envVar: 'ANTHROPIC_API_KEY', set: true, masked: '…9f2c' }],
    }) });
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    buttonWith(fixture, 'clear').click();

    expect(store.setup.setEnv).toHaveBeenCalledWith(
      'a-atlas', [{ key: 'ANTHROPIC_API_KEY', value: null }]);
  });

  it('offers to create a missing .env, and nothing to configure until it exists', async () => {
    const store = storeStub();
    store.cache.set({ 'a-atlas': setupFor('a-atlas', { envExists: false }) });
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('.env missing');
    buttonWith(fixture, 'create .env template').click();
    await fixture.whenStable();

    expect(store.setup.initEnv).toHaveBeenCalledWith('a-atlas');
  });

  it('expands one messaging platform at a time', async () => {
    const store = storeStub();
    const fixture = render(store);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).querySelector('.msg-config')).toBeNull();
    buttonWith(fixture, 'configure').click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('TELEGRAM_HOME_CHANNEL');

    buttonWith(fixture, 'close').click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('.msg-config')).toBeNull();
  });

  it('says the read is running rather than claiming the setup is empty', () => {
    const store = storeStub();
    store.loading.set(new Set(['a-atlas']));
    store.setup.setup = vi.fn(() => new Promise(() => {}));
    const fixture = render(store);

    expect(el(fixture).textContent).toContain('running hermes status…');
    expect(buttonWith(fixture, 'refresh').disabled).toBe(true);
  });
});
