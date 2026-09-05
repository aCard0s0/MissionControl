import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HermesContainer, LlmProvider, ProfileTemplate } from '../core/models';
import { ProfileDeployDialog } from './profile-deploy-dialog';
import { TestFixture, el, settle, text, stubConfirm } from '../testing/dom';
import { container as buildContainer, template as buildTemplate } from '../testing/models';
import { provideStores } from '../testing/store';

const llm: LlmProvider[] = [
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
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
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

  it('proposes the name hermes will actually create, and says so when a typed one differs', async () => {
    // hermes folds a profile name to lower case on create; a blueprint called Coach used to be
    // sent as Coach, created as coach, and then every later write missed it
    const { fixture } = render(storeStub('c-1'), template({ name: 'Coach' }));
    await settle(fixture);
    const name = el(fixture).querySelector<HTMLInputElement>('#deploy-agent-name')!;
    expect(name.value).toBe('coach');
    expect(text(fixture)).not.toContain('keeps profile names lowercase');

    name.value = 'Fitness-Coach';
    name.dispatchEvent(new Event('input'));
    await settle(fixture);

    expect(text(fixture)).toContain('keeps profile names lowercase');
    expect(text(fixture)).toContain('fitness-coach');
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

    expect(el(fixture).textContent).toContain('no running containers available');
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

  it('deploys under the folded name, whatever case was typed', async () => {
    const { fixture, store } = render(storeStub('c-1'));
    await settle(fixture);
    const name = el(fixture).querySelector<HTMLInputElement>('#deploy-agent-name')!;
    name.value = ' Coach ';
    name.dispatchEvent(new Event('input'));
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(store.templates.deploy).toHaveBeenCalledWith('t-1', 'c-1', 'coach');
  });

  it('stays open when the deploy is refused, and says the form is still there', async () => {
    const store = storeStub('c-1');
    store.templates.deploy.mockResolvedValue('');
    const { fixture, host } = render(store);
    await settle(fixture);

    submit(fixture).click();
    await settle(fixture);

    expect(host.deployedId).toBeNull();
    expect(host.closes).toBe(0);
    expect(text(fixture)).toContain('nothing was deployed');
    expect(submit(fixture).disabled).toBe(false);
  });

  it('says the deploy runs on without it, and can be closed while it does', async () => {
    const store = storeStub('c-1');
    store.templates.deploy.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture, host } = render(store);
    await settle(fixture);

    submit(fixture).click();
    fixture.detectChanges();

    expect(text(fixture)).toContain('close it and keep working');
    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();
    expect(host.closes).toBe(1);
  });

  it('routes nobody anywhere when the dialog is gone before the deploy lands', async () => {
    const store = storeStub('c-1');
    let land = (_: string): void => { /* replaced below */ };
    store.templates.deploy.mockReturnValue(new Promise(resolve => { land = resolve; }));
    const { fixture, host } = render(store);
    await settle(fixture);

    submit(fixture).click();
    fixture.detectChanges();

    fixture.destroy();
    land('a-new');
    await Promise.resolve();

    expect(host.deployedId).toBeNull();
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
    const { fixture, store } = render(storeStub('c-1'), template({
      provider: 'anthropic', secrets: [{ key: 'ANTHROPIC_API_KEY', set: false, recoverable: false }],
    }));
    await settle(fixture);
    const confirmed = stubConfirm(false);

    submit(fixture).click();
    await settle(fixture);

    expect(confirmed.mock.calls[0][0].message).toContain('ANTHROPIC_API_KEY');
    expect(confirmed.mock.calls[0][0].warn).toBe(true);
    expect(store.templates.deploy).not.toHaveBeenCalled();
  });

  it('deploys anyway once the operator accepts the missing key', async () => {
    const { fixture, store } = render(storeStub('c-1'), template({ provider: 'anthropic' }));
    await settle(fixture);
    stubConfirm(true);

    submit(fixture).click();
    await settle(fixture);

    expect(store.templates.deploy).toHaveBeenCalled();
  });

  it('asks nothing when the blueprint already carries the key', async () => {
    const { fixture, store } = render(storeStub('c-1'), template({
      provider: 'anthropic', secrets: [{ key: 'ANTHROPIC_API_KEY', set: true, recoverable: true }],
    }));
    await settle(fixture);
    const confirmed = stubConfirm(true);

    submit(fixture).click();
    await settle(fixture);

    expect(confirmed).not.toHaveBeenCalled();
    expect(store.templates.deploy).toHaveBeenCalled();
  });

  it('asks nothing for a provider that needs no key at all', async () => {
    const { fixture } = render(storeStub('c-1'));
    await settle(fixture);
    const confirmed = stubConfirm(true);

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

describe('ProfileDeployDialog targets', () => {
  it('offers only running containers — a profile is created by exec-ing into one', async () => {
    const store = storeStub('c-2');
    store.containers.containers.set([
      container('c-1', 'hermes-prod'),
      { ...container('c-2', 'hermes-lab'), status: 'stopped' },
    ]);
    const { fixture } = render(store);
    await settle(fixture);

    const select = el(fixture).querySelector<HTMLSelectElement>('#deploy-target-container')!;
    expect(Array.from(select.options).map(o => o.textContent?.trim())).toEqual(['hermes-prod']);
    expect(select.value).toBe('c-1');
  });

  it('says when nothing is running to deploy onto', async () => {
    const store = storeStub('c-1');
    store.containers.containers.set([{ ...container('c-1', 'hermes-prod'), status: 'stopped' }]);
    const { fixture } = render(store);
    await settle(fixture);

    expect(text(fixture)).toContain('no running containers available');
  });
});
