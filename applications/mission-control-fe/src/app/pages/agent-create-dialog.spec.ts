import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { ApiModelProvider, ApiSetupAuthProvider } from '../core/hermes-api';
import { HermesContainer, ModelProvider, ProfileTemplate } from '../core/models';
import { AgentCreateDialog } from './agent-create-dialog';

const llm: ApiModelProvider[] = [
  { key: 'nous', label: 'Nous Portal', needsKey: false, oauth: true, hasCatalog: true, envVar: null },
  { key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true,
    envVar: 'ANTHROPIC_API_KEY' },
  { key: 'custom', label: 'Custom endpoint', needsKey: true, oauth: false, hasCatalog: false,
    envVar: 'CUSTOM_API_KEY' },
];

const ollama: ModelProvider[] = [{
  id: 'mp-1', name: 'workstation', url: 'http://10.0.0.5:11434', kind: 'ollama',
  status: 'connected', version: null, detail: null,
}];

const container: HermesContainer = {
  id: 'c-1', name: 'hermes-prod', shortId: 'c1', hostId: 'dh-local', status: 'running',
  image: 'hermes', version: 'v1', startedAt: 1, cpu: 0, ram: 0, ramTotal: 0, disk: 0,
  diskTotal: 0, netIn: 0, netOut: 0, cpuHist: [], ramHist: [], netHist: [],
};

const template = (patch: Partial<ProfileTemplate> = {}): ProfileTemplate => ({
  id: 't-1', name: 'researcher', description: '', provider: 'anthropic', model: 'claude-opus-5',
  baseUrl: '', cwd: '/opt/data', soul: '', memory: '', skills: [], mcpServers: [],
  secrets: [], createdAt: 1, updatedAt: 1, ...patch,
});

/** Only what the dialog reaches for on the store, so nothing here touches a backend. */
const storeStub = (opts: {
  templates?: ProfileTemplate[];
  auth?: ApiSetupAuthProvider[];
  catalog?: string[];
} = {}) => {
  const templates = opts.templates ?? [];
  return {
    llmProviders: signal(llm),
    modelProviders: signal(ollama),
    profileTemplates: signal(templates),
    containerAgents: signal([]),
    templateById: (id: string) => templates.find(t => t.id === id) ?? null,
    authProviders: vi.fn().mockResolvedValue(opts.auth ?? []),
    modelCatalog: vi.fn().mockResolvedValue(opts.catalog ?? ['claude-opus-5', 'claude-sonnet-5']),
    modelCatalogLive: vi.fn().mockResolvedValue(['live-model']),
    providerModels: vi.fn().mockResolvedValue([{ name: 'gemma3:4b' }]),
    createAgent: vi.fn().mockResolvedValue('a-new'),
  };
};

@Component({
  imports: [AgentCreateDialog],
  template: `<mc-agent-create-dialog [container]="container"
                                     (created)="createdId = $event"
                                     (closed)="closes = closes + 1" />`,
})
class Host {
  readonly container = container;
  createdId: string | null = null;
  closes = 0;
}

const render = async (store: ReturnType<typeof storeStub>) => {
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, store, host: fixture.componentInstance };
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

/** The `.field` whose label starts with this text — the form's own labels are the
 *  only stable handle on it, and they read the way an operator sees them. */
const field = (fixture: { nativeElement: unknown }, label: string): HTMLElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.field'))
    .find(f => (f.querySelector('label')?.textContent ?? '').trim().toLowerCase()
      .startsWith(label.toLowerCase()));
  if (!match) throw new Error(`no field labelled "${label}"`);
  return match;
};

type Fixture = { nativeElement: unknown; detectChanges(): void; whenStable(): Promise<unknown> };

const fill = async (fixture: Fixture, label: string, value: string): Promise<void> => {
  const input = field(fixture, label).querySelector<HTMLInputElement>('.input')!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await fixture.whenStable();
  fixture.detectChanges();
};

const choose = async (fixture: Fixture, label: string, value: string): Promise<void> => {
  const select = field(fixture, label).querySelector<HTMLSelectElement>('.select')!;
  select.value = value;
  select.dispatchEvent(new Event('change'));
  await fixture.whenStable();
  fixture.detectChanges();
};

const toggleAux = async (fixture: Fixture): Promise<void> => {
  const box = el(fixture).querySelector<HTMLInputElement>('.check input[type=checkbox]')!;
  box.click();
  await fixture.whenStable();
  fixture.detectChanges();
};

const submit = async (fixture: Fixture): Promise<void> => {
  el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.primary')!.click();
  await fixture.whenStable();
  fixture.detectChanges();
};

const submitButton = (fixture: Fixture): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.primary')!;

describe('AgentCreateDialog opening', () => {
  it('loads the default provider\'s catalog and this container\'s auth status', async () => {
    const { fixture, store } = await render(storeStub());

    expect(store.modelCatalog).toHaveBeenCalledWith('nous');
    expect(store.authProviders).toHaveBeenCalledWith('c-1');
    expect(el(fixture).textContent).toContain('NEW AGENT PROFILE — hermes-prod');
    expect(field(fixture, 'model').querySelector<HTMLInputElement>('.input')!.value)
      .toBe('claude-opus-5');
  });

  it('warns when Nous Portal has not been logged in on this container', async () => {
    const { fixture } = await render(storeStub({
      auth: [{ label: 'Nous Portal', ok: false, status: 'not logged in' }] as ApiSetupAuthProvider[],
    }));

    expect(el(fixture).textContent).toContain('Nous Portal not logged in');
  });

  it('confirms the login rather than asking for a key it does not need', async () => {
    const { fixture } = await render(storeStub({
      auth: [{ label: 'Nous Portal', ok: true, status: 'ok' }] as ApiSetupAuthProvider[],
    }));

    expect(el(fixture).textContent).toContain('Nous Portal connected on this container');
    expect(() => field(fixture, 'API key')).toThrow();
  });
});

describe('AgentCreateDialog provider choice', () => {
  it('asks for a key only for a provider that needs one', async () => {
    const { fixture } = await render(storeStub());
    expect(() => field(fixture, 'API key')).toThrow();

    await choose(fixture, 'provider', 'anthropic');
    expect(field(fixture, 'API key')).toBeTruthy();

    await choose(fixture, 'provider', 'ollama: workstation');
    expect(() => field(fixture, 'API key')).toThrow();
  });

  it('re-selects the model from the new provider\'s catalog on a switch', async () => {
    const { fixture, store } = await render(storeStub());

    await choose(fixture, 'provider', 'ollama: workstation');

    expect(store.providerModels).toHaveBeenCalledWith('mp-1');
    expect(field(fixture, 'model').querySelector<HTMLInputElement>('.input')!.value)
      .toBe('gemma3:4b');
  });

  it('offers no suggestions for a provider with no catalog, keeping the field free text', async () => {
    const { fixture, store } = await render(storeStub());

    await choose(fixture, 'provider', 'custom');

    expect(store.modelCatalog).not.toHaveBeenCalledWith('custom');
    expect(el(fixture).querySelectorAll('#agent-model-list option').length).toBe(0);
  });

  it('fetches the live catalog once a key is typed for a catalog-backed provider', async () => {
    const { fixture, store } = await render(storeStub());
    await choose(fixture, 'provider', 'anthropic');

    await fill(fixture, 'API key', 'sk-test');
    field(fixture, 'API key').querySelector<HTMLButtonElement>('.key-refresh')!.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.modelCatalogLive).toHaveBeenCalledWith('anthropic', 'sk-test');
  });
});

describe('AgentCreateDialog template prefill', () => {
  it('prefills the provider and model the template names', async () => {
    const { fixture } = await render(storeStub({ templates: [template()] }));

    await choose(fixture, 'from profile', 't-1');

    expect(field(fixture, 'provider').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('anthropic');
    expect(field(fixture, 'model').querySelector<HTMLInputElement>('.input')!.value)
      .toBe('claude-opus-5');
  });

  it('still asks for a key when the template only records the name of one', async () => {
    const { fixture } = await render(storeStub({
      templates: [template({ secrets: [{ key: 'ANTHROPIC_API_KEY', set: false, recoverable: false }] })],
    }));

    await choose(fixture, 'from profile', 't-1');

    expect(field(fixture, 'API key')).toBeTruthy();
  });

  it('skips the key prompt when the template carries a usable one', async () => {
    const { fixture } = await render(storeStub({
      templates: [template({ secrets: [{ key: 'ANTHROPIC_API_KEY', set: true, recoverable: true }] })],
    }));

    await choose(fixture, 'from profile', 't-1');

    expect(() => field(fixture, 'API key')).toThrow();
  });
});

describe('AgentCreateDialog auxiliary override', () => {
  it('starts the override on the main provider, and asks for no second key', async () => {
    const { fixture } = await render(storeStub());
    await choose(fixture, 'provider', 'anthropic');
    await fill(fixture, 'API key', 'sk-test');

    await toggleAux(fixture);

    expect(field(fixture, 'auxiliary provider').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('anthropic');
    expect(() => field(fixture, 'auxiliary API key')).toThrow();
  });

  it('sends a same-provider override as a model on its own', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await fill(fixture, 'auxiliary model', 'claude-sonnet-5');

    await submit(fixture);

    expect(store.createAgent.mock.calls[0][8]).toEqual({ model: 'claude-sonnet-5' });
  });

  it('sends its own provider, endpoint and key when the override switches provider', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await choose(fixture, 'auxiliary provider', 'anthropic');
    await fill(fixture, 'auxiliary model', 'claude-sonnet-5');
    await fill(fixture, 'auxiliary API key', 'sk-aux');

    await submit(fixture);

    expect(store.createAgent.mock.calls[0][8]).toEqual({
      provider: 'anthropic', model: 'claude-sonnet-5', baseUrl: undefined, apiKey: 'sk-aux',
    });
  });

  it('refuses an override that names no model', async () => {
    const { fixture } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await fill(fixture, 'auxiliary model', '');

    expect(submitButton(fixture).disabled).toBe(true);
  });

  it('forgets the override\'s fields when it is switched back off', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await toggleAux(fixture);
    await fill(fixture, 'auxiliary model', 'claude-sonnet-5');
    await toggleAux(fixture);

    await submit(fixture);

    expect(store.createAgent.mock.calls[0][8]).toBeUndefined();
  });
});

describe('AgentCreateDialog create', () => {
  it('sends the slug the CLI would accept, and reports the new profile', async () => {
    const { fixture, store, host } = await render(storeStub());

    await fill(fixture, 'profile name', '  Ops Bot  ');
    await submit(fixture);

    expect(store.createAgent).toHaveBeenCalledWith(
      'c-1', 'ops-bot', 'nous', 'claude-opus-5', '', undefined, undefined, undefined, undefined);
    expect(host.createdId).toBe('a-new');
    expect(host.closes).toBe(0);
  });

  it('sends the bare provider and endpoint an ollama option resolves to', async () => {
    const { fixture, store } = await render(storeStub());
    await fill(fixture, 'profile name', 'ops-bot');
    await choose(fixture, 'provider', 'ollama: workstation');

    await submit(fixture);

    expect(store.createAgent.mock.calls[0].slice(2, 5))
      .toEqual(['ollama', 'gemma3:4b', '']);
    expect(store.createAgent.mock.calls[0][6]).toBe('http://10.0.0.5:11434/v1');
  });

  it('refuses to create with no name, and refuses a needed key that is blank', async () => {
    const { fixture } = await render(storeStub());
    expect(submitButton(fixture).disabled).toBe(true);

    await fill(fixture, 'profile name', 'ops-bot');
    expect(submitButton(fixture).disabled).toBe(false);

    await choose(fixture, 'provider', 'anthropic');
    expect(submitButton(fixture).disabled).toBe(true);
  });

  it('closes without a profile when the backend refuses the create', async () => {
    const store = storeStub();
    store.createAgent.mockResolvedValue('');
    const { fixture, host } = await render(store);

    await fill(fixture, 'profile name', 'ops-bot');
    await submit(fixture);

    expect(host.createdId).toBeNull();
    expect(host.closes).toBe(1);
  });

  it('reports a cancel to the page rather than closing itself', async () => {
    const { fixture, store, host } = await render(storeStub());

    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();

    expect(host.closes).toBe(1);
    expect(store.createAgent).not.toHaveBeenCalled();
  });
});
