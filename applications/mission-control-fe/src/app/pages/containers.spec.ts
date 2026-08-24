import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentStore } from '../core/store/agent-store';
import { ContainerLifecycle } from '../core/store/container-lifecycle';
import { ContainerStore } from '../core/store/container-store';
import { HostStore } from '../core/store/host-store';
import { ImageCatalogStore } from '../core/store/image-catalog-store';
import { StoreContext } from '../core/store/store-context';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { DockerHost, HermesContainer, ImageCatalog, ImageTag } from '../core/models';
import { ContainersPage, normalizeSeedProfiles } from './containers';
import {
  TestFixture, button, buttonWith, choose, el, field, fill, press, settle, text, type,
} from '../testing/dom';
import { container, dockerHost } from '../testing/models';

describe('normalizeSeedProfiles', () => {
  it('normalizes, deduplicates, and omits the implicit default profile', () => {
    expect(normalizeSeedProfiles(' Default, Ops, research team, ops '))
      .toEqual(['ops', 'research-team']);
  });
});

// The tag-comparison rules these tests used to cover live in core/image-policy.spec.ts now.
// What is left below is the page: which modal opens, what it renders, and what it calls.
const HERMES = 'nousresearch/hermes-agent';

type TagSpec = string | (Partial<ImageTag> & { tag: string });

const HOSTS: DockerHost[] = [
  dockerHost('dh-local', {
    name: 'localhost', url: 'unix:///var/run/docker.sock', kind: 'local',
    engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 4,
  }),
  dockerHost('dh-edge', {
    name: 'edge', url: 'tcp://10.0.0.5:2376', status: 'error', note: 'unreachable',
  }),
];

/** Only what the page and its three modals reach for on the store. */
const storeStub = (containers: HermesContainer[], catalogs: Record<string, ImageCatalog> = {}) => {
  const dockerHosts = signal(HOSTS);
  return {
    containers: {
      containers: signal(containers),
      selectedContainerId: signal(containers[0]?.id ?? ''),
      select: vi.fn(),
    },
    hosts: {
      hosts: dockerHosts,
      byId: (id: string) => dockerHosts().find(h => h.id === id) ?? null,
      add: vi.fn(),
      remove: vi.fn(),
      check: vi.fn(),
    },
    agents: { agents: signal([{ id: 'a-1', containerId: 'hermes-prod' }]) },
    ctx: { backendStatus: signal('connected') },
    images: {
      catalog: signal(catalogs),
      refreshAll: vi.fn().mockResolvedValue(undefined),
      tags: vi.fn().mockResolvedValue({ repository: HERMES, tags: ['latest', 'v2026.8.3'] }),
    },
    lifecycle: {
      setStatus: vi.fn(),
      deploy: vi.fn().mockResolvedValue('c-new'),
      update: vi.fn().mockResolvedValue('c-updated'),
      remove: vi.fn().mockResolvedValue(true),
    },
    terminal: { open: vi.fn() },
  };
};

const render = (store: ReturnType<typeof storeStub>) => {
  const router = { navigate: vi.fn() };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: Router, useValue: router },
      { provide: AgentStore, useValue: store.agents },
      { provide: ContainerLifecycle, useValue: store.lifecycle },
      { provide: ContainerStore, useValue: store.containers },
      { provide: HostStore, useValue: store.hosts },
      { provide: ImageCatalogStore, useValue: store.images },
      { provide: StoreContext, useValue: store.ctx },
      { provide: TerminalRequestStore, useValue: store.terminal },
    ],
  });
  const fixture = TestBed.createComponent(ContainersPage);
  fixture.detectChanges();
  return { fixture, store, router };
};

/** Opens the deploy modal and lets the image-tag read land. */
const openDeploy = async (fixture: TestFixture): Promise<void> => {
  press(fixture, '+ deploy container');
  await settle(fixture);
};

describe('ContainersPage fleet', () => {

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the image catalogs on arrival, so update badges are not a poll behind', () => {
    const { store } = render(storeStub([container('hermes-prod')]));

    expect(store.images.refreshAll).toHaveBeenCalled();
  });

  it('names the host a container runs on, and falls back when that host is gone', () => {
    const { fixture } = render(storeStub([
      container('hermes-prod'), container('hermes-orphan', { hostId: 'dh-deleted' }),
    ]));

    expect(text(fixture)).toContain('on localhost');
    expect(text(fixture)).toContain('on ?');
  });

  it('counts the profiles inside each container', () => {
    const { fixture } = render(storeStub([container('hermes-prod'), container('hermes-lab')]));

    const counts = Array.from(el(fixture).querySelectorAll('.stats > div'))
      .filter(d => (d.textContent ?? '').includes('profiles'))
      .map(d => (d.textContent ?? '').replace('profiles', '').trim());
    expect(counts).toEqual(['1', '0']);
  });

  it('blames the backend for an empty fleet while it is unreachable', () => {
    const waiting = storeStub([]);
    waiting.ctx.backendStatus.set('unreachable');

    const { fixture } = render(waiting);

    expect(text(fixture)).toContain('waiting for the Mission Control backend');
  });

  it('says the connected hosts simply have none, once the backend answers', () => {
    const { fixture } = render(storeStub([]));

    expect(text(fixture)).toContain('No Hermes containers detected');
  });

  it('opens a container from the keyboard, on Enter and on Space', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));
    const card = el(fixture).querySelector<HTMLElement>('.card')!;

    card.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(store.containers.select).toHaveBeenCalledWith('hermes-prod');

    store.containers.select.mockClear();
    const space = new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true });
    card.dispatchEvent(space);

    // a real button fires on Space too, and does not scroll the page doing it
    expect(store.containers.select).toHaveBeenCalledWith('hermes-prod');
    expect(space.defaultPrevented).toBe(true);
  });

  it('selects a container from a click on the card and leaves the page for its overview', () => {
    const { fixture, store, router } = render(storeStub([container('hermes-prod')]));
    const card = el(fixture).querySelector<HTMLElement>('.card')!;

    card.click();
    fixture.detectChanges();

    expect(store.containers.select).toHaveBeenCalledWith('hermes-prod');
    expect(router.navigate).toHaveBeenCalledWith(['/overview']);
    // the card is the control; a separate select button would only duplicate it
    expect(Array.from(card.querySelectorAll('button'))
      .map(b => (b.textContent ?? '').trim())).not.toContain('select');
  });

  it('offers start for a stopped container, and shows no telemetry for it', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod', { status: 'stopped' })]));

    expect(text(fixture)).toContain('no telemetry — stopped');
    press(fixture, 'start', '.card');

    expect(store.lifecycle.setStatus).toHaveBeenCalledWith('hermes-prod', 'running');
  });

  it('offers stop for a running one', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));

    press(fixture, 'stop', '.card');

    expect(store.lifecycle.setStatus).toHaveBeenCalledWith('hermes-prod', 'stopped');
  });

  it('opens a terminal on the container without running anything in it', () => {
    const { fixture, store, router } = render(storeStub([container('hermes-prod')]));
    const term = el(fixture).querySelector<HTMLButtonElement>('.card .term')!;

    // an icon-only control still has to say what it does
    expect(term.getAttribute('aria-label')).toBe('open a terminal in hermes-prod');
    term.click();
    fixture.detectChanges();

    expect(store.terminal.open).toHaveBeenCalledWith({
      hostId: 'dh-local', containerId: 'hermes-prod', label: 'hermes-prod',
    });
    // the operator asked for a prompt, not for something to be run at it
    expect(store.terminal.open.mock.calls[0][0]).not.toHaveProperty('command');
    // the shell is not a reason to leave the page
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('says why a stopped container has no shell to open', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod', { status: 'stopped' })]));
    const term = el(fixture).querySelector<HTMLButtonElement>('.card .term')!;

    expect(term.disabled).toBe(true);
    expect(term.title).toContain('start it to open a shell');

    term.click();
    fixture.detectChanges();

    expect(store.terminal.open).not.toHaveBeenCalled();
  });
});

describe('ContainersPage docker hosts', () => {

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('lists every daemon with its engine and why it is unreachable', () => {
    const { fixture } = render(storeStub([]));

    expect(text(fixture)).toContain('localhost');
    expect(text(fixture)).toContain('Docker 27.3 · api 1.47');
    expect(text(fixture)).toContain('— unreachable');
  });

  it('re-checks a daemon on demand, and removes only a remote one', () => {
    const { fixture, store } = render(storeStub([]));

    press(fixture, 'check', '.host-row');
    expect(store.hosts.check).toHaveBeenCalledWith('dh-local');

    const rows = el(fixture).querySelectorAll('.host-row');
    expect(rows[0].textContent).not.toContain('remove');   // the local socket is not removable
    press(fixture, 'remove', rows[1]);
    expect(store.hosts.remove).toHaveBeenCalledWith('dh-edge');
  });

  it('only accepts a tcp URL carrying an explicit port', async () => {
    const { fixture, store } = render(storeStub([]));
    press(fixture, '+ remote host');

    const [name, url] = Array.from(el(fixture).querySelectorAll<HTMLInputElement>('.host-add .input'));
    const fillInput = async (input: HTMLInputElement, value: string) => {
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await settle(fixture);
    };

    await fillInput(name, 'edge-vm');
    await fillInput(url, 'http://10.0.0.5:2376');
    expect(button(fixture, 'connect').disabled).toBe(true);

    await fillInput(url, 'tcp://10.0.0.5');
    expect(button(fixture, 'connect').disabled).toBe(true);

    await fillInput(url, 'tcp://10.0.0.5:2376');
    press(fixture, 'connect');
    expect(store.hosts.add).toHaveBeenCalledWith('edge-vm', 'tcp://10.0.0.5:2376');
  });

  it('closes the form and resets it once the host is added', async () => {
    const { fixture } = render(storeStub([]));
    press(fixture, '+ remote host');
    await type(fixture, '.host-add .input', 'edge-vm');
    const url = el(fixture).querySelectorAll<HTMLInputElement>('.host-add .input')[1];
    url.value = 'tcp://10.0.0.5:2376';
    url.dispatchEvent(new Event('input'));
    await settle(fixture);

    press(fixture, 'connect');

    expect(el(fixture).querySelector('.host-add')).toBeNull();
  });
});

describe('ContainersPage deploy', () => {

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('cannot be opened without a connected daemon to deploy onto', () => {
    const offline = storeStub([]);
    offline.hosts.hosts.set([{ ...HOSTS[1] }]);
    const { fixture } = render(offline);

    expect(button(fixture, '+ deploy container').disabled).toBe(true);
  });

  it('offers only connected hosts and loads that host\'s tags', async () => {
    const { fixture, store } = render(storeStub([]));

    await openDeploy(fixture);

    expect(store.images.tags).toHaveBeenCalledWith('dh-local');
    const hosts = field(fixture, 'docker host').querySelectorAll('option');
    expect(Array.from(hosts).map(o => o.textContent?.trim()))
      .toEqual(['localhost — unix:///var/run/docker.sock']);
  });

  it('prefers latest when the host has it, so a deploy is not pinned by accident', async () => {
    const { fixture } = render(storeStub([]));

    await openDeploy(fixture);

    expect(field(fixture, 'image version')
      .querySelector<HTMLSelectElement>('.select')!.value).toBe('latest');
  });

  it('falls back to the newest tag on a host with no latest', async () => {
    const store = storeStub([]);
    store.images.tags.mockResolvedValue({ repository: HERMES, tags: ['v2026.8.3', 'v2026.7.20'] });
    const { fixture } = render(store);

    await openDeploy(fixture);

    expect(field(fixture, 'image version')
      .querySelector<HTMLSelectElement>('.select')!.value).toBe('v2026.8.3');
  });

  it('says why the version list is empty rather than showing a bare select', async () => {
    const store = storeStub([]);
    store.images.tags.mockRejectedValue(new Error('registry unreachable'));
    const { fixture } = render(store);

    await openDeploy(fixture);

    expect(text(fixture)).toContain('image tags unavailable — registry unreachable');
    expect(button(fixture, 'deploy').disabled).toBe(true);
  });

  it('deploys with normalized seed profiles and moves to the new container', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);
    await fill(fixture, 'container name', ' hermes-staging ');
    await fill(fixture, 'seed profiles', 'Ops, research team, ops');

    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy)
      .toHaveBeenCalledWith('hermes-staging', 'latest', ['ops', 'research-team'], 'dh-local');
    expect(store.containers.select).toHaveBeenCalledWith('c-new');
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('keeps the modal open, with the name intact, when the deploy fails', async () => {
    const store = storeStub([]);
    store.lifecycle.deploy.mockResolvedValue('');
    const { fixture } = render(store);
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    press(fixture, 'deploy');
    await settle(fixture);

    expect(el(fixture).querySelector('.modal')).not.toBeNull();
    expect(field(fixture, 'container name')
      .querySelector<HTMLInputElement>('.input')!.value).toBe('hermes-staging');
  });

  it('will not deploy without a name', async () => {
    const { fixture, store } = render(storeStub([]));
    await openDeploy(fixture);

    expect(button(fixture, 'deploy').disabled).toBe(true);
    await fill(fixture, 'container name', '   ');
    expect(button(fixture, 'deploy').disabled).toBe(true);
    expect(store.lifecycle.deploy).not.toHaveBeenCalled();
  });

  it('ignores the tags of a host the operator has already switched away from', async () => {
    const store = storeStub([]);
    let landEdge!: (value: unknown) => void;
    store.images.tags.mockImplementation((hostId: string) => hostId === 'dh-edge'
      ? new Promise(resolve => { landEdge = resolve; })
      : Promise.resolve({ repository: HERMES, tags: ['latest'] }));
    store.hosts.hosts.set([HOSTS[0], { ...HOSTS[1], status: 'connected' }]);
    const { fixture } = render(store);
    await openDeploy(fixture);

    await choose(fixture, 'docker host', 'dh-edge');
    await choose(fixture, 'docker host', 'dh-local');
    landEdge({ repository: HERMES, tags: ['edge-only'] });
    await settle(fixture);

    const options = Array.from(field(fixture, 'image version').querySelectorAll('option'));
    expect(options.map(o => o.textContent?.trim())).toEqual(['latest']);
  });

  it('leaves the version unset on a host with no images at all', async () => {
    const store = storeStub([]);
    store.images.tags.mockResolvedValue({ repository: HERMES, tags: [] });
    const { fixture } = render(store);

    await openDeploy(fixture);

    expect(text(fixture)).toContain('No local Hermes images found on this host.');
    expect(button(fixture, 'deploy').disabled).toBe(true);
  });

  it('will not deploy onto a host that is no longer connected', async () => {
    const store = storeStub([]);
    const { fixture } = render(store);
    await openDeploy(fixture);
    await fill(fixture, 'container name', 'hermes-staging');

    store.hosts.hosts.set([{ ...HOSTS[0], status: 'error' }]);
    press(fixture, 'deploy');
    await settle(fixture);

    expect(store.lifecycle.deploy).not.toHaveBeenCalled();
  });

  it('empties the version list when the modal has no host to read tags from', async () => {
    const store = storeStub([]);
    store.hosts.hosts.set([]);
    const { fixture } = render(store);
    const page = fixture.componentInstance as unknown as { openDeploy(): void };

    page.openDeploy();
    await settle(fixture);

    expect(store.images.tags).not.toHaveBeenCalled();
    expect(text(fixture)).toContain('No local Hermes images found on this host.');
  });
});

describe('ContainersPage image update', () => {
  const catalog = (tags: TagSpec[]): Record<string, ImageCatalog> => ({
    'dh-local': {
      repository: HERMES,
      tags: tags.map(t => typeof t === 'string'
        ? { tag: t, pulled: true, digest: null }
        : { pulled: true, digest: null, ...t }),
      registryStatus: 'ok',
      fetchedAt: 0,
    },
  });

  /** The card's update button — its label now carries the target tag, so match by prefix. */
  const pressUpdate = (fixture: TestFixture): void => {
    buttonWith(fixture, 'update', '.card').click();
    fixture.detectChanges();
  };

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('offers the update on a floating tag once the registry has moved it', () => {
    // the case the tag rules alone can never see: `latest` is always the newest tag,
    // so only the digests say the running image is two months old
    const stale = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([stale],
      catalog([{ tag: 'latest', digest: 'sha256:bbb' }])));

    // no release tag shares either digest here, so `latest` stays the only honest name
    const button = el(fixture).querySelector('.btn.upd')!;
    expect(button.textContent).toContain('update latest');
    expect(button.getAttribute('title'))
      .toContain('the registry published a new image on latest');
  });

  it('names both ends as releases, so a move on latest is not "latest → latest"', () => {
    // what the operator was actually asking about: the card said `:latest` and the
    // button said `update latest`, which reads as a no-op even though it is not
    const stale = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([stale], catalog([
      { tag: 'latest', digest: 'sha256:bbb' },
      { tag: 'v2026.8.3', digest: 'sha256:bbb' },
      { tag: 'v2026.7.20', digest: 'sha256:aaa' },
    ])));

    // the version it runs, not the pointer it followed there
    expect(text(fixture)).toContain('v2026.7.20');
    const button = el(fixture).querySelector('.btn.upd')!;
    expect(button.textContent).toContain('update v2026.8.3');
    expect(button.getAttribute('title')).toContain('v2026.7.20 → v2026.8.3');
    // and it still says which moving tag carries it there
    expect(button.getAttribute('title')).toContain('on latest');
  });

  it('still says which tag a resolved container follows', () => {
    const c = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([c],
      catalog([{ tag: 'v2026.7.20', digest: 'sha256:aaa' }])));

    // a container pinned to v2026.7.20 and one on latest that resolves to it behave
    // differently the next time they are recreated, so the pointer stays visible
    const meta = el(fixture).querySelector('.panel-b .meta')!;
    expect(meta.textContent).toContain('v2026.7.20');
    expect(meta.querySelector('.tracks')!.textContent!.trim()).toBe('latest');
  });

  it('offers nothing on a floating tag it already matches', () => {
    const current = container('hermes-prod', { version: 'latest', imageDigest: 'sha256:aaa' });
    const { fixture } = render(storeStub([current],
      catalog([{ tag: 'latest', digest: 'sha256:aaa' }])));

    expect(el(fixture).querySelector('.btn.upd')).toBeNull();
  });

  it('names the move on the update button, which is the only badge now', () => {
    const { fixture } = render(storeStub([container('hermes-prod')], catalog(['v2026.8.3'])));

    const button = el(fixture).querySelector('.btn.upd')!;
    expect(button.textContent).toContain('update v2026.8.3');
    expect(button.getAttribute('title')).toBe('v2026.7.20 → v2026.8.3');
  });

  it('warns on the button when the target is not on the host yet', () => {
    const { fixture } = render(storeStub(
      [container('hermes-prod')], catalog([{ tag: 'v2026.8.3', pulled: false }])));

    expect(el(fixture).querySelector('.btn.upd')!.getAttribute('title'))
      .toBe('v2026.7.20 → v2026.8.3 · not pulled on this host yet');
  });

  it('offers no update button for a container already on the newest tag', () => {
    const { fixture } = render(storeStub([container('hermes-prod', { version: 'v2026.8.3' })],
      catalog(['v2026.8.3'])));

    expect(el(fixture).querySelector('.btn.upd')).toBeNull();
  });

  it('opens on the newest release and lists every step in between', () => {
    const { fixture } = render(storeStub([container('hermes-prod')],
      catalog(['v2026.8.3', 'v2026.7.30'])));

    pressUpdate(fixture);

    const options = Array.from(field(fixture, 'target version').querySelectorAll('option'));
    expect(options.map(o => o.textContent?.trim())).toEqual(['v2026.8.3', 'v2026.7.30']);
    expect(text(fixture)).toContain('update to v2026.8.3');
  });

  it('warns that an unpulled target is fetched first', () => {
    const { fixture } = render(storeStub([container('hermes-prod')],
      catalog([{ tag: 'v2026.8.3', pulled: false }])));

    pressUpdate(fixture);

    expect(text(fixture)).toContain('the image is pulled first');
  });

  it('says a stopped container stays stopped', () => {
    const { fixture } = render(storeStub([container('hermes-prod', { status: 'stopped' })],
      catalog(['v2026.8.3'])));

    pressUpdate(fixture);

    expect(text(fixture)).toContain('this container is stopped — it stays stopped after the update');
  });

  it('recreates the container on the chosen tag and closes', async () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')],
      catalog(['v2026.8.3', 'v2026.7.30'])));
    pressUpdate(fixture);
    await choose(fixture, 'target version', 'v2026.7.30');

    press(fixture, 'update to v2026.7.30');
    await settle(fixture);

    expect(store.lifecycle.update).toHaveBeenCalledWith('hermes-prod', 'v2026.7.30');
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('keeps the modal open when the recreate failed, so the reason stays on screen', async () => {
    const store = storeStub([container('hermes-prod')], catalog(['v2026.8.3']));
    store.lifecycle.update.mockResolvedValue('');
    const { fixture } = render(store);
    pressUpdate(fixture);

    press(fixture, 'update to v2026.8.3');
    await settle(fixture);

    expect(el(fixture).querySelector('.modal')).not.toBeNull();
  });

  it('locks the card and the modal while the recreate is in flight', async () => {
    const store = storeStub([container('hermes-prod')], catalog(['v2026.8.3']));
    let land!: (value: string) => void;
    store.lifecycle.update.mockReturnValue(new Promise<string>(r => { land = r; }));
    const { fixture } = render(store);
    pressUpdate(fixture);

    press(fixture, 'update to v2026.8.3');
    fixture.detectChanges();

    // a recreate takes long enough to look stalled, so it says what it is doing and spins
    expect(text(fixture)).toContain('recreating the container');
    expect(el(fixture).querySelectorAll('.modal .spin').length).toBeGreaterThan(0);
    press(fixture, 'cancel');                       // a cancel mid-flight must not close it
    expect(el(fixture).querySelector('.modal')).not.toBeNull();

    land('c-updated');
    await settle(fixture);
  });

  it('cancels back out without touching the container', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')], catalog(['v2026.8.3'])));
    pressUpdate(fixture);

    press(fixture, 'cancel');

    expect(el(fixture).querySelector('.modal')).toBeNull();
    expect(store.lifecycle.update).not.toHaveBeenCalled();
  });
});

describe('ContainersPage removal', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('names what the delete takes with it, and holds until the name is typed', async () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));

    press(fixture, 'remove', '.card');
    expect(text(fixture)).toContain('1 profile(s) inside it');
    expect(button(fixture, 'remove permanently').disabled).toBe(true);

    await fill(fixture, 'type', 'hermes-pro');
    expect(button(fixture, 'remove permanently').disabled).toBe(true);

    await fill(fixture, 'type', 'hermes-prod');
    press(fixture, 'remove permanently');
    await settle(fixture);

    expect(store.lifecycle.remove).toHaveBeenCalledWith('hermes-prod');
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('keeps the modal open when the delete failed', async () => {
    const store = storeStub([container('hermes-prod')]);
    store.lifecycle.remove.mockResolvedValue(false);
    const { fixture } = render(store);
    press(fixture, 'remove', '.card');
    await fill(fixture, 'type', 'hermes-prod');

    press(fixture, 'remove permanently');
    await settle(fixture);

    expect(el(fixture).querySelector('.modal')).not.toBeNull();
  });

  it('cancels back out without deleting anything', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')]));
    press(fixture, 'remove', '.card');

    press(fixture, 'cancel');

    expect(el(fixture).querySelector('.modal')).toBeNull();
    expect(store.lifecycle.remove).not.toHaveBeenCalled();
  });
});
