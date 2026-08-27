import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentProfile, InferenceEndpoint, EndpointModel, PullState, RunningModel } from '../core/models';
import { ModelsPage } from './models';
import { el, press, settle, text, type, type TestFixture } from '../testing/dom';
import { provideStores } from '../testing/store';

const provider = (id: string, name: string): InferenceEndpoint => ({
  id, name, url: `http://${name}:11434`, kind: 'ollama', status: 'connected',
  version: '0.6.4', detail: null, canManageModels: true,
});

const model = (name: string): EndpointModel =>
  ({ name, sizeBytes: 4.3e9, family: 'gemma3', parameterSize: '4.3B', modifiedAt: 1 });

/** Enough of a profile for the in-use join, which reads only the name and the model. */
const agent = (name: string, model: string) =>
  ({ name, model } as AgentProfile);

const storeStub = (
  providers: InferenceEndpoint[] = [provider('mp-1', 'workstation')],
  agents: AgentProfile[] = [],
) => ({
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
    running: vi.fn().mockResolvedValue([] as RunningModel[]),
    loadModel: vi.fn().mockResolvedValue(undefined),
    unloadModel: vi.fn().mockResolvedValue(undefined),
  },
  agents: { agents: signal(agents) },
});

/** Clicks an endpoint row, which is what opens and closes its model panel. */
const openRow = (fixture: TestFixture, index = 0) => {
  el(fixture).querySelectorAll<HTMLElement>('.provider-row')[index].click();
  fixture.detectChanges();
};

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
  const fixture = TestBed.createComponent(ModelsPage);
  fixture.detectChanges();
  return { fixture, store };
};

// every block here drives the pull-status poller, so they all need the fake clock
beforeEach(() => vi.useFakeTimers());

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe('ModelsPage providers', () => {
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

  it('sends a connectivity check without opening the row it is on', () => {
    const { fixture, store } = render(storeStub());

    press(fixture, 'check', '.provider-row');

    expect(store.providers.check).toHaveBeenCalledWith('mp-1');
    // the row is the control that opens the panel, so its buttons have to stop the click —
    // checking an endpoint must not load a model list as a side effect
    expect(el(fixture).querySelector('.models-panel')).toBeNull();
  });
});

describe('ModelsPage model list', () => {
  it('loads a provider\'s models when its row is clicked', async () => {
    const { fixture, store } = render(storeStub());

    openRow(fixture);
    await settle(fixture);

    expect(store.providers.models).toHaveBeenCalledWith('mp-1');
    expect(el(fixture).textContent).toContain('MODELS — workstation');
    expect(el(fixture).textContent).toContain('gemma3:4b');
    expect(el(fixture).textContent).toContain('4.3 GB');
  });

  it('closes the panel when the open row is clicked again', async () => {
    const { fixture } = render(storeStub());
    openRow(fixture);
    await settle(fixture);
    expect(el(fixture).querySelector('.models-panel')).not.toBeNull();

    openRow(fixture);

    expect(el(fixture).querySelector('.models-panel')).toBeNull();
  });

  it('says the provider is empty rather than looking still busy', async () => {
    const store = storeStub();
    store.providers.models.mockResolvedValue([]);
    const { fixture } = render(store);

    openRow(fixture);
    await settle(fixture);

    expect(el(fixture).textContent).toContain('No models on this endpoint');
  });

  it('surfaces why a read failed', async () => {
    const store = storeStub();
    store.providers.models.mockRejectedValue(new Error('connection refused'));
    const { fixture } = render(store);

    openRow(fixture);
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

    openRow(fixture);        // workstation, pending
    openRow(fixture, 1);     // laptop
    await settle(fixture);

    answerFirst([model('stale:1b')]);
    await settle(fixture);

    expect(el(fixture).textContent).toContain('llama3:8b');
    expect(el(fixture).textContent).not.toContain('stale:1b');
  });

  it('asks twice before deleting a model', async () => {
    const { fixture, store } = render(storeStub());
    openRow(fixture);
    await settle(fixture);

    press(fixture, 'remove', '.model-row:not(.head)');
    expect(store.providers.deleteModel).not.toHaveBeenCalled();

    press(fixture, 'confirm', '.model-row:not(.head)');
    await settle(fixture);

    expect(store.providers.deleteModel).toHaveBeenCalledWith('mp-1', 'gemma3:4b');
  });

  it('asks twice before removing an endpoint, and forgets the selection when it goes', async () => {
    const { fixture, store } = render(storeStub());
    openRow(fixture);
    await settle(fixture);
    expect(el(fixture).querySelector('.models-panel')).not.toBeNull();

    // one click un-registers it and silently breaks the base_url of every agent using it
    press(fixture, 'remove', '.provider-row');
    expect(store.providers.remove).not.toHaveBeenCalled();

    press(fixture, 'confirm', '.provider-row');

    expect(store.providers.remove).toHaveBeenCalledWith('mp-1');
    expect(el(fixture).querySelector('.models-panel')).toBeNull();
  });
});

describe('ModelsPage pulls', () => {
  const open = async (store: ReturnType<typeof storeStub>) => {
    const rendered = render(store);
    openRow(rendered.fixture);
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

  it('shows how far each pull has got, not just that it is pulling', async () => {
    const store = storeStub();
    store.providers.pullStatus
      .mockResolvedValue([{ model: 'gemma3:4b', status: 'pulling', detail: '47% · downloading' }]);
    const { fixture } = await open(store);

    expect(text(fixture)).toContain('gemma3:4b · pulling · 47% · downloading');
  });

  it('keeps polling for as long as the panel is open, pull or no pull', async () => {
    // both halves move without the operator: a pull reports progress, and an agent's own
    // call loads a model and lets it expire again
    const store = storeStub();
    const { fixture } = await open(store);
    const afterOpen = store.providers.pullStatus.mock.calls.length;

    await settle(fixture, 3_000);

    expect(store.providers.pullStatus.mock.calls.length).toBeGreaterThan(afterOpen);
    expect(store.providers.running.mock.calls.length).toBeGreaterThan(1);
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

  it('rides out a failed poll instead of going quiet', async () => {
    // the next tick is the retry — a panel that stopped polling on one bad read would sit
    // there showing a stale loaded set with no sign that it had given up
    const store = storeStub();
    const { fixture } = await open(store);
    store.providers.pullStatus.mockRejectedValue(new Error('provider gone'));

    await settle(fixture, 3_000);
    const afterFailure = store.providers.pullStatus.mock.calls.length;
    await settle(fixture, 9_000);

    expect(store.providers.pullStatus.mock.calls.length).toBeGreaterThan(afterFailure);
  });

  it('stops polling once the page is gone', async () => {
    const store = storeStub();
    const { fixture } = await open(store);

    fixture.destroy();
    const afterDestroy = store.providers.pullStatus.mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);

    expect(store.providers.pullStatus.mock.calls.length).toBe(afterDestroy);
  });
});

describe('ModelsPage what is in use', () => {
  const resident = (name: string, sizeVramBytes = 5.2e9): RunningModel =>
    ({ name, sizeVramBytes });

  const open = async (store: ReturnType<typeof storeStub>) => {
    const rendered = render(store);
    openRow(rendered.fixture);
    await settle(rendered.fixture);
    return rendered;
  };

  it('marks a resident model with the memory it holds, and totals both costs', async () => {
    const store = storeStub();
    store.providers.running.mockResolvedValue([resident('gemma3:4b')]);
    const { fixture } = await open(store);

    expect(text(fixture)).toContain('loaded · 5.2 GB');
    // disk is what an idle model costs, memory is what it costs the next one to load
    expect(text(fixture)).toContain('4.3 GB on disk');
    expect(text(fixture)).toContain('1 loaded · 5.2 GB in memory');
  });

  it('starts a model and re-reads what is resident rather than assuming it worked', async () => {
    const store = storeStub();
    const { fixture } = await open(store);
    const afterOpen = store.providers.running.mock.calls.length;

    press(fixture, 'start', '.model-row:not(.head)');
    await settle(fixture);

    expect(store.providers.loadModel).toHaveBeenCalledWith('mp-1', 'gemma3:4b');
    expect(store.providers.running.mock.calls.length).toBeGreaterThan(afterOpen);
  });

  it('offers stop, not start, for a model already in memory', async () => {
    const store = storeStub();
    store.providers.running.mockResolvedValue([resident('gemma3:4b')]);
    const { fixture } = await open(store);

    press(fixture, 'stop', '.model-row:not(.head)');
    await settle(fixture);

    expect(store.providers.unloadModel).toHaveBeenCalledWith('mp-1', 'gemma3:4b');
    expect(store.providers.loadModel).not.toHaveBeenCalled();
  });

  it('names the agent a model is configured on, so remove is not a surprise', async () => {
    const store = storeStub([provider('mp-1', 'workstation')], [agent('atlas', 'gemma3:4b')]);
    const { fixture } = await open(store);

    expect(text(fixture)).toContain('atlas');
  });

  it('counts the agents instead of listing them once there is more than one', async () => {
    const store = storeStub([provider('mp-1', 'workstation')],
      [agent('atlas', 'gemma3:4b'), agent('nova', 'gemma3:4b'), agent('vega', 'other:1b')]);
    const { fixture } = await open(store);

    expect(text(fixture)).toContain('2 agents');
  });
});

describe('ModelsPage openai-compatible endpoints', () => {
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
    openRow(fixture);
    await settle(fixture);
    return { fixture, store };
  };

  it('shows the kind when the protocol reports no version', () => {
    const { fixture } = render(storeStub([openai()]));

    expect(el(fixture).textContent).toContain('openai-compatible');
  });

  it('shows no protocol chip for an endpoint that is not answering', () => {
    // kind is probed, so a switched-off endpoint simply has none — better than claiming one
    const off: InferenceEndpoint = {
      ...openai(), kind: null, status: 'error', detail: 'no model server answered',
    };
    const { fixture } = render(storeStub([off]));

    expect(el(fixture).querySelector('.chip')).toBeNull();
    expect(el(fixture).textContent).toContain('no model server answered');
  });

  it('lists the models it can see', async () => {
    const { fixture } = await openStub();

    expect(el(fixture).textContent).toContain('qwen3-8b');
  });

  it('offers no pull bar, because the protocol has no pull', async () => {
    const { fixture } = await openStub();

    expect(el(fixture).querySelector('.pull-bar')).toBeNull();
    expect(el(fixture).textContent).toContain('cannot add, remove or load them');
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
    // name + in use + modified: no actions cell either, since none of them can render
    expect(head!.querySelectorAll('span').length).toBe(3);
  });

  it('does not poll an endpoint that cannot say what is loaded or pulling', async () => {
    const { store } = await openStub();

    expect(store.providers.running).not.toHaveBeenCalled();
    expect(store.providers.pullStatus).not.toHaveBeenCalled();
  });

  it('says models are loaded on the server when there are none', async () => {
    const store = storeStub([openai()]);
    store.providers.models.mockResolvedValue([]);
    const { fixture } = render(store);
    openRow(fixture);
    await settle(fixture);

    expect(el(fixture).textContent).toContain('load one on the server itself');
  });
});
