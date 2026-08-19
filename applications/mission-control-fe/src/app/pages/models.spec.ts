import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { ApiPullState } from '../core/hermes-api';
import { ModelProvider, OllamaModel } from '../core/models';
import { ModelsPage } from './models';
import { el, press, settle, type } from '../testing/dom';

const provider = (id: string, name: string): ModelProvider => ({
  id, name, url: `http://${name}:11434`, kind: 'ollama', status: 'connected',
  version: '0.6.4', detail: null,
});

const model = (name: string): OllamaModel =>
  ({ name, sizeBytes: 4.3e9, family: 'gemma3', parameterSize: '4.3B', modifiedAt: 1 });

const storeStub = (providers: ModelProvider[] = [provider('mp-1', 'workstation')]) => ({
  modelProviders: signal(providers),
  refreshModelProviders: vi.fn().mockResolvedValue(undefined),
  addModelProvider: vi.fn(),
  removeModelProvider: vi.fn(),
  checkModelProvider: vi.fn(),
  providerModels: vi.fn().mockResolvedValue([model('gemma3:4b')]),
  pullModel: vi.fn().mockResolvedValue(undefined),
  deleteProviderModel: vi.fn().mockResolvedValue(undefined),
  pullStatus: vi.fn().mockResolvedValue([] as ApiPullState[]),
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(ModelsPage);
  fixture.detectChanges();
  return { fixture, store };
};

describe('ModelsPage providers', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the provider list when it opens', () => {
    const { store } = render(storeStub());

    expect(store.refreshModelProviders).toHaveBeenCalled();
  });

  it('lists each provider with the endpoint an agent would reach it on', () => {
    const { fixture } = render(storeStub());

    expect(el(fixture).textContent).toContain('workstation');
    expect(el(fixture).textContent).toContain('http://workstation:11434');
    expect(el(fixture).textContent).toContain('ollama 0.6.4');
  });

  it('says so when nothing is registered', () => {
    const { fixture } = render(storeStub([]));

    expect(el(fixture).textContent).toContain('No model providers registered');
  });

  it('refuses a provider without a name or an http endpoint', async () => {
    const { fixture, store } = render(storeStub());
    press(fixture, '+ model provider');
    const add = () => el(fixture).querySelector<HTMLButtonElement>('.provider-add .btn')!;
    expect(add().disabled).toBe(true);

    await type(fixture, '.provider-add .input', 'mac');
    expect(add().disabled).toBe(true);   // still no url

    const url = el(fixture).querySelectorAll<HTMLInputElement>('.provider-add .input')[1];
    url.value = 'host.docker.internal:11434';   // no scheme
    url.dispatchEvent(new Event('input'));
    await settle(fixture);
    expect(add().disabled).toBe(true);

    url.value = 'http://host.docker.internal:11434';
    url.dispatchEvent(new Event('input'));
    await settle(fixture);
    expect(add().disabled).toBe(false);
    add().click();

    expect(store.addModelProvider)
      .toHaveBeenCalledWith('mac', 'http://host.docker.internal:11434');
  });

  it('sends a connectivity check for one provider', () => {
    const { fixture, store } = render(storeStub());

    press(fixture, 'check', '.provider-row');

    expect(store.checkModelProvider).toHaveBeenCalledWith('mp-1');
  });
});

describe('ModelsPage model list', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('loads a provider\'s models when it is selected', async () => {
    const { fixture, store } = render(storeStub());

    press(fixture, 'models', '.provider-row');
    await settle(fixture);

    expect(store.providerModels).toHaveBeenCalledWith('mp-1');
    expect(el(fixture).textContent).toContain('MODELS — workstation');
    expect(el(fixture).textContent).toContain('gemma3:4b');
    expect(el(fixture).textContent).toContain('4.3 GB');
  });

  it('says the provider is empty rather than looking still busy', async () => {
    const store = storeStub();
    store.providerModels.mockResolvedValue([]);
    const { fixture } = render(store);

    press(fixture, 'models', '.provider-row');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('No models on this provider');
  });

  it('surfaces why a read failed', async () => {
    const store = storeStub();
    store.providerModels.mockRejectedValue(new Error('connection refused'));
    const { fixture } = render(store);

    press(fixture, 'models', '.provider-row');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('models unavailable — connection refused');
  });

  it('drops a read that lands after another provider was selected', async () => {
    const store = storeStub([provider('mp-1', 'workstation'), provider('mp-2', 'laptop')]);
    let answerFirst: (models: OllamaModel[]) => void = () => { /* replaced below */ };
    store.providerModels.mockImplementationOnce(
      () => new Promise<OllamaModel[]>(resolve => { answerFirst = resolve; }));
    store.providerModels.mockResolvedValue([model('llama3:8b')]);
    const { fixture } = render(store);

    press(fixture, 'models', '.provider-row');                      // workstation, pending
    const rows = el(fixture).querySelectorAll('.provider-row');
    Array.from(rows[1].querySelectorAll('button'))
      .find(b => (b.textContent ?? '').trim() === 'models')!.click();   // laptop
    fixture.detectChanges();
    await settle(fixture);

    answerFirst([model('stale:1b')]);
    await settle(fixture);

    expect(el(fixture).textContent).toContain('llama3:8b');
    expect(el(fixture).textContent).not.toContain('stale:1b');
  });

  it('asks twice before deleting a model', async () => {
    const { fixture, store } = render(storeStub());
    press(fixture, 'models', '.provider-row');
    await settle(fixture);

    press(fixture, 'remove', '.model-row:not(.head)');
    expect(store.deleteProviderModel).not.toHaveBeenCalled();

    press(fixture, 'confirm', '.model-row:not(.head)');
    await settle(fixture);

    expect(store.deleteProviderModel).toHaveBeenCalledWith('mp-1', 'gemma3:4b');
  });

  it('forgets the selection when the selected provider is removed', async () => {
    const { fixture, store } = render(storeStub());
    press(fixture, 'models', '.provider-row');
    await settle(fixture);
    expect(el(fixture).querySelector('.models-panel')).not.toBeNull();

    press(fixture, 'remove', '.provider-row');

    expect(store.removeModelProvider).toHaveBeenCalledWith('mp-1');
    expect(el(fixture).querySelector('.models-panel')).toBeNull();
  });
});

describe('ModelsPage pulls', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  const open = async (store: ReturnType<typeof storeStub>) => {
    const rendered = render(store);
    press(rendered.fixture, 'models', '.provider-row');
    await settle(rendered.fixture);
    return rendered;
  };

  it('pulls the model that was typed, and clears the field', async () => {
    const { fixture, store } = await open(storeStub());

    await type(fixture, '.pull-bar .input', ' gemma3:4b ');
    press(fixture, 'pull model', '.pull-bar');
    await settle(fixture);

    expect(store.pullModel).toHaveBeenCalledWith('mp-1', 'gemma3:4b');
    expect(el(fixture).querySelector<HTMLInputElement>('.pull-bar .input')!.value).toBe('');
  });

  it('shows what each pull is doing', async () => {
    const store = storeStub();
    store.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: '40%' }]);
    const { fixture } = await open(store);

    expect(el(fixture).textContent).toContain('gemma3:4b · pulling');
  });

  it('keeps polling while a pull is running, and stops once it is not', async () => {
    const store = storeStub();
    store.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);
    const afterOpen = store.pullStatus.mock.calls.length;

    await settle(fixture, 3_000);
    expect(store.pullStatus.mock.calls.length).toBeGreaterThan(afterOpen);

    store.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'done', detail: null }]);
    await settle(fixture, 3_000);
    const afterDone = store.pullStatus.mock.calls.length;

    await settle(fixture, 30_000);
    expect(store.pullStatus.mock.calls.length).toBe(afterDone);
  });

  it('re-reads the model list once a pull finishes', async () => {
    const store = storeStub();
    store.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);
    const afterOpen = store.providerModels.mock.calls.length;

    store.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'done', detail: null }]);
    await settle(fixture, 3_000);

    expect(store.providerModels.mock.calls.length).toBeGreaterThan(afterOpen);
  });

  it('gives up polling when the pull status cannot be read', async () => {
    const store = storeStub();
    store.pullStatus.mockResolvedValueOnce([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);
    store.pullStatus.mockRejectedValue(new Error('provider gone'));

    await settle(fixture, 3_000);
    const afterFailure = store.pullStatus.mock.calls.length;
    await settle(fixture, 9_000);

    expect(store.pullStatus.mock.calls.length).toBe(afterFailure);
  });

  it('stops polling once the page is gone', async () => {
    const store = storeStub();
    store.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);

    fixture.destroy();
    const afterDestroy = store.pullStatus.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);

    expect(store.pullStatus.mock.calls.length).toBe(afterDestroy);
  });
});
