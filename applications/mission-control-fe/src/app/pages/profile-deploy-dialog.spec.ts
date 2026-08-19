import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ContainerStore } from '../core/store/container-store';
import { ProviderStore } from '../core/store/provider-store';
import { TemplateStore } from '../core/store/template-store';
import { ApiModelProvider } from '../core/hermes-api';
import { HermesContainer, ProfileTemplate } from '../core/models';
import { ProfileDeployDialog } from './profile-deploy-dialog';
import { TestFixture, el, settle } from '../testing/dom';
import { container as buildContainer, template as buildTemplate } from '../testing/models';

const llm: ApiModelProvider[] = [
  { key: 'nous', label: 'Nous Portal', needsKey: false, oauth: true, hasCatalog: true, envVar: null },
  { key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true,
    envVar: 'ANTHROPIC_API_KEY' },
];

/** Only what the dialog reaches for on the store, so nothing here touches a backend. */
const storeStub = (selected = '') => ({
  containers: {
    containers: signal([container('c-1', 'hermes-prod'), container('c-2', 'hermes-lab')]),
    selectedContainerId: signal(selected),
  },
  providers: {
    llmProviders: signal(llm),
  },
  templates: {
    deploy: vi.fn().mockResolvedValue('a-new'),
  },
});

@Component({
  imports: [ProfileDeployDialog],
  template: `<mc-profile-deploy-dialog [template]="template()"
                                       (deployed)="deployedId = $event"
                                       (closed)="closes = closes + 1" />`,
})
class Host {
  readonly template = signal(template());
  deployedId: string | null = null;
  closes = 0;
}

const render = (store: ReturnType<typeof storeStub>, t = template()) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: ContainerStore, useValue: store.containers }, { provide: ProviderStore, useValue: store.providers }, { provide: TemplateStore, useValue: store.templates }] });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.template.set(t);
  fixture.detectChanges();
  return { fixture, store, host: fixture.componentInstance };
};

const submit = (fixture: TestFixture): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.primary')!;

const container = (id: string, name: string): HermesContainer =>
  buildContainer(id, { name, shortId: id });

const template = (patch: Partial<ProfileTemplate> = {}): ProfileTemplate =>
  buildTemplate('t-1', { name: 'ops-sre', provider: 'nous', model: 'Hermes-4-405B', ...patch });

describe('ProfileDeployDialog', () => {
  afterEach(() => vi.restoreAllMocks());

  it('opens on the selected container, named after the blueprint', async () => {
    const { fixture } = render(storeStub('c-2'));
    await settle(fixture);

    expect(el(fixture).textContent).toContain('deploy — ops-sre');
    expect(el(fixture).querySelector<HTMLSelectElement>('.select')!.value).toBe('c-2');
    expect(el(fixture).querySelector<HTMLInputElement>('.input')!.value).toBe('ops-sre');
  });

  it('falls back to the first container when none is selected', async () => {
    const { fixture } = render(storeStub());
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLSelectElement>('.select')!.value).toBe('c-1');
  });

  it('says so when there is nowhere to deploy to, and refuses the deploy', async () => {
    const store = storeStub();
    store.containers.containers.set([]);
    const { fixture } = render(store);
    await settle(fixture);

    expect(el(fixture).textContent).toContain('no containers available');
    expect(submit(fixture).disabled).toBe(true);
  });

  it('deploys into the chosen container and reports the new agent', async () => {
    const { fixture, store, host } = render(storeStub('c-1'));
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(store.templates.deploy).toHaveBeenCalledWith('t-1', 'c-1', 'ops-sre');
    expect(host.deployedId).toBe('a-new');
  });

  it('stays open when the deploy is refused', async () => {
    const store = storeStub('c-1');
    store.templates.deploy.mockResolvedValue('');
    const { fixture, host } = render(store);
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(host.deployedId).toBeNull();
    expect(host.closes).toBe(0);
  });

  it('will not send a second deploy while the first is in flight', async () => {
    const store = storeStub('c-1');
    store.templates.deploy.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture } = render(store);
    await settle(fixture);

    submit(fixture).click();
    fixture.detectChanges();
    expect(submit(fixture).textContent?.trim()).toBe('deploying…');
    submit(fixture).click();

    expect(store.templates.deploy).toHaveBeenCalledTimes(1);
  });

  it('warns before deploying a blueprint that carries no usable key', async () => {
    const confirmed = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const { fixture, store } = render(storeStub('c-1'), template({
      provider: 'anthropic', secrets: [{ key: 'ANTHROPIC_API_KEY', set: false, recoverable: false }],
    }));
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(confirmed.mock.calls[0][0]).toContain('ANTHROPIC_API_KEY');
    expect(store.templates.deploy).not.toHaveBeenCalled();
  });

  it('deploys anyway once the operator accepts the missing key', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture, store } = render(storeStub('c-1'), template({ provider: 'anthropic' }));
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(store.templates.deploy).toHaveBeenCalled();
  });

  it('asks nothing when the blueprint already carries the key', async () => {
    const confirmed = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture, store } = render(storeStub('c-1'), template({
      provider: 'anthropic', secrets: [{ key: 'ANTHROPIC_API_KEY', set: true, recoverable: true }],
    }));
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(confirmed).not.toHaveBeenCalled();
    expect(store.templates.deploy).toHaveBeenCalled();
  });

  it('asks nothing for a provider that needs no key at all', async () => {
    const confirmed = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture } = render(storeStub('c-1'));
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(confirmed).not.toHaveBeenCalled();
  });

  it('reports a cancel to the page rather than closing itself', async () => {
    const { fixture, store, host } = render(storeStub('c-1'));
    await settle(fixture);

    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();

    expect(host.closes).toBe(1);
    expect(store.templates.deploy).not.toHaveBeenCalled();
  });
});
