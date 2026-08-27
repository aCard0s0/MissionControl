import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InferenceEndpoint, EndpointModel, PullState } from '../core/models';
import { ModelsPage } from './models';
import { el, press, settle, type } from '../testing/dom';
import { provideStores } from '../testing/store';

const provider = (id: string, name: string): InferenceEndpoint => ({
  id, name, url: `http://${name}:11434`, kind: 'ollama', status: 'connected',
  version: '0.6.4', detail: null, canManageModels: true,
});

const model = (name: string): EndpointModel =>
  ({ name, sizeBytes: 4.3e9, family: 'gemma3', parameterSize: '4.3B', modifiedAt: 1 });

const storeStub = (providers: InferenceEndpoint[] = [provider('mp-1', 'workstation')]) => ({
  providers: {
    endpoints: signal(providers),
    refresh: vi.fn().mockResolvedValue(undefined),
    add: vi.fn(),
    remove: vi.fn(),
    check: vi.fn(),
    models: vi.fn().mockResolvedValue([model('gemma3:4b')]),
    pullModel: vi.fn().mockResolvedValue(undefined),
    deleteModel: vi.fn().mockResolvedValue(undefined),
    pullStatus: vi.fn().mockResolvedValue([] as PullState[]),
  },
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
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

    expect(store.providers.refresh).toHaveBeenCalled();
  });

  it('lists each provider with the endpoint an agent would reach it on', () => {
    const { fixture } = render(storeStub());

    expect(el(fixture).textContent).toContain('workstation');
    expect(el(fixture).textContent).toContain('http://workstation:11434');
    expect(el(fixture).textContent).toContain('ollama 0.6.4');
  });

  it('says so when nothing is registered', () => {
    const { fixture } = render(storeStub([]));

    expect(el(fixture).textContent).toContain('No endpoints registered');
  });

  it('refuses a provider without a name or an http endpoint', async () => {
    const { fixture, store } = render(storeStub());
    press(fixture, '+ endpoint');
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

    expect(store.providers.add)
      .toHaveBeenCalledWith('mac', 'http://host.docker.internal:11434');
  });

  it('sends a connectivity check for one provider', () => {
    const { fixture, store } = render(storeStub());

    press(fixture, 'check', '.provider-row');

    expect(store.providers.check).toHaveBeenCalledWith('mp-1');
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

    expect(store.providers.models).toHaveBeenCalledWith('mp-1');
    expect(el(fixture).textContent).toContain('MODELS — workstation');
    expect(el(fixture).textContent).toContain('gemma3:4b');
    expect(el(fixture).textContent).toContain('4.3 GB');
  });

  it('says the provider is empty rather than looking still busy', async () => {
    const store = storeStub();
    store.providers.models.mockResolvedValue([]);
    const { fixture } = render(store);

    press(fixture, 'models', '.provider-row');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('No models on this endpoint');
  });

  it('surfaces why a read failed', async () => {
    const store = storeStub();
    store.providers.models.mockRejectedValue(new Error('connection refused'));
    const { fixture } = render(store);

    press(fixture, 'models', '.provider-row');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('models unavailable — connection refused');
  });

  it('drops a read that lands after another provider was selected', async () => {
    const store = storeStub([provider('mp-1', 'workstation'), provider('mp-2', 'laptop')]);
    let answerFirst: (models: EndpointModel[]) => void = () => { /* replaced below */ };
    store.providers.models.mockImplementationOnce(
      () => new Promise<EndpointModel[]>(resolve => { answerFirst = resolve; }));
    store.providers.models.mockResolvedValue([model('llama3:8b')]);
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
    expect(store.providers.deleteModel).not.toHaveBeenCalled();

    press(fixture, 'confirm', '.model-row:not(.head)');
    await settle(fixture);

    expect(store.providers.deleteModel).toHaveBeenCalledWith('mp-1', 'gemma3:4b');
  });

  it('forgets the selection when the selected provider is removed', async () => {
    const { fixture, store } = render(storeStub());
    press(fixture, 'models', '.provider-row');
    await settle(fixture);
    expect(el(fixture).querySelector('.models-panel')).not.toBeNull();

    press(fixture, 'remove', '.provider-row');

    expect(store.providers.remove).toHaveBeenCalledWith('mp-1');
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

    expect(store.providers.pullModel).toHaveBeenCalledWith('mp-1', 'gemma3:4b');
    expect(el(fixture).querySelector<HTMLInputElement>('.pull-bar .input')!.value).toBe('');
  });

  it('shows what each pull is doing', async () => {
    const store = storeStub();
    store.providers.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: '40%' }]);
    const { fixture } = await open(store);

    expect(el(fixture).textContent).toContain('gemma3:4b · pulling');
  });

  it('keeps polling while a pull is running, and stops once it is not', async () => {
    const store = storeStub();
    store.providers.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);
    const afterOpen = store.providers.pullStatus.mock.calls.length;

    await settle(fixture, 3_000);
    expect(store.providers.pullStatus.mock.calls.length).toBeGreaterThan(afterOpen);

    store.providers.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'done', detail: null }]);
    await settle(fixture, 3_000);
    const afterDone = store.providers.pullStatus.mock.calls.length;

    await settle(fixture, 30_000);
    expect(store.providers.pullStatus.mock.calls.length).toBe(afterDone);
  });

  it('re-reads the model list once a pull finishes', async () => {
    const store = storeStub();
    store.providers.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);
    const afterOpen = store.providers.models.mock.calls.length;

    store.providers.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'done', detail: null }]);
    await settle(fixture, 3_000);

    expect(store.providers.models.mock.calls.length).toBeGreaterThan(afterOpen);
  });

  it('gives up polling when the pull status cannot be read', async () => {
    const store = storeStub();
    store.providers.pullStatus.mockResolvedValueOnce([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);
    store.providers.pullStatus.mockRejectedValue(new Error('provider gone'));

    await settle(fixture, 3_000);
    const afterFailure = store.providers.pullStatus.mock.calls.length;
    await settle(fixture, 9_000);

    expect(store.providers.pullStatus.mock.calls.length).toBe(afterFailure);
  });

  it('stops polling once the page is gone', async () => {
    const store = storeStub();
    store.providers.pullStatus.mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: null }]);
    const { fixture } = await open(store);

    fixture.destroy();
    const afterDestroy = store.providers.pullStatus.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);

    expect(store.providers.pullStatus.mock.calls.length).toBe(afterDestroy);
  });
});

describe('ModelsPage openai-compatible endpoints', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  /** LM Studio, MLX, vLLM, llama.cpp: /v1/models lists, and there is nothing else to call. */
  const openai = (): InferenceEndpoint => ({
    id: 'mp-2', name: 'lm-studio', url: 'http://mac:1234', kind: 'openai',
    status: 'connected', version: null, detail: null, canManageModels: false,
  });

  /** /v1/models reports an id and maybe a timestamp — nothing else. */
  const thinModel = (name: string): EndpointModel =>
    ({ name, sizeBytes: 0, family: '', parameterSize: '', modifiedAt: 0 });

  const openStub = async () => {
    const store = storeStub([openai()]);
    store.providers.models.mockResolvedValue([thinModel('qwen3-8b')]);
    const { fixture } = render(store);
    press(fixture, 'models');
    await settle(fixture);
    return { fixture, store };
  };

  it('shows the kind when the protocol reports no version', () => {
    const { fixture } = render(storeStub([openai()]));

    expect(el(fixture).textContent).toContain('openai-compatible');
  });

  it('lists the models it can see', async () => {
    const { fixture } = await openStub();

    expect(el(fixture).textContent).toContain('qwen3-8b');
  });

  it('offers no pull bar, because the protocol has no pull', async () => {
    const { fixture } = await openStub();

    expect(el(fixture).querySelector('.pull-bar')).toBeNull();
    expect(el(fixture).textContent).toContain('cannot add or remove them');
  });

  it('offers no remove button, because the protocol has no delete', async () => {
    const { fixture } = await openStub();

    const labels = [...el(fixture).querySelectorAll('.model-row button')]
      .map(b => b.textContent?.trim());
    expect(labels).not.toContain('remove');
  });

  it('drops the columns /v1/models cannot fill rather than showing them empty', async () => {
    const { fixture } = await openStub();

    const head = el(fixture).querySelector('.model-row.head');
    expect(head).not.toBeNull();
    expect(head!.textContent).not.toContain('params');
    expect(head!.textContent).not.toContain('family');
    // name + modified + actions, where ollama's row has three more
    expect(head!.querySelectorAll('span').length).toBe(3);
  });

  it('says models are loaded on the server when there are none', async () => {
    const store = storeStub([openai()]);
    store.providers.models.mockResolvedValue([]);
    const { fixture } = render(store);
    press(fixture, 'models');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('load one on the server itself');
  });
});
