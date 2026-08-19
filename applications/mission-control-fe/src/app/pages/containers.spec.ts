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
import { DockerHost, HermesContainer, ImageCatalog, ImageTag } from '../core/models';
import { ContainersPage, containerUpdate, newerImageTags, normalizeSeedProfiles } from './containers';
import {
  TestFixture, button, choose, el, field, fill, press, settle, text, type,
} from '../testing/dom';
import { container, dockerHost } from '../testing/models';

describe('normalizeSeedProfiles', () => {
  it('normalizes, deduplicates, and omits the implicit default profile', () => {
    expect(normalizeSeedProfiles(' Default, Ops, research team, ops '))
      .toEqual(['ops', 'research-team']);
  });
});

const HERMES = 'nousresearch/hermes-agent';

const cat = (tags: (string | ImageTag)[], repository = HERMES): ImageCatalog => ({
  repository,
  tags: tags.map(t => typeof t === 'string' ? { tag: t, pulled: true } : t),
  registryStatus: 'ok',
  fetchedAt: 0,
});

const on = (version: string, image = HERMES) => ({ image, version });

describe('containerUpdate', () => {
  it('offers the newest release and lists every step in between', () => {
    const catalog = cat(['v2026.8.3', 'v2026.7.30', 'v2026.7.20']);
    expect(containerUpdate(on('v2026.7.20'), catalog)?.tag).toBe('v2026.8.3');
    expect(newerImageTags(on('v2026.7.20'), catalog).map(t => t.tag))
      .toEqual(['v2026.8.3', 'v2026.7.30']);
  });

  it('returns null when already on the newest tag', () => {
    expect(containerUpdate(on('v2026.8.3'), cat(['v2026.8.3', 'v2026.7.20']))).toBeNull();
  });

  it('ranks a four-component calendar tag against the release it patches', () => {
    // v2026.7.7.2 is a real published tag; a three-part parser misplaces it
    expect(containerUpdate(on('v2026.7.7'), cat(['v2026.7.7.2']))?.tag).toBe('v2026.7.7.2');
    expect(containerUpdate(on('v2026.7.7.2'), cat(['v2026.7.7']))).toBeNull();
    expect(containerUpdate(on('v2026.7.7.2'), cat(['v2026.7.20']))?.tag).toBe('v2026.7.20');
  });

  it('compares components numerically, not as strings', () => {
    expect(containerUpdate(on('v0.9.0'), cat(['v0.10.0']))?.tag).toBe('v0.10.0');
    expect(containerUpdate(on('v0.10.0'), cat(['v0.9.0']))).toBeNull();
  });

  it('tolerates the v prefix and short forms on either side', () => {
    expect(containerUpdate(on('2026.7.20'), cat(['v2026.7.20']))).toBeNull();
    expect(containerUpdate(on('v1'), cat(['1.0.1']))?.tag).toBe('1.0.1');
  });

  it('never claims a container on a moving or opaque tag is behind', () => {
    for (const version of ['latest', 'main', 'edge', 'sha-9f2c1']) {
      expect(containerUpdate(on(version), cat(['v2026.8.3']))).toBeNull();
    }
  });

  it('never offers latest as a target, because that would un-pin the container', () => {
    expect(containerUpdate(on('v2026.7.20'), cat(['latest']))).toBeNull();
    expect(containerUpdate(on('v2026.7.20'), cat(['latest', 'v2026.8.3']))?.tag).toBe('v2026.8.3');
  });

  it('skips pre-releases as targets but upgrades a container off one', () => {
    expect(containerUpdate(on('v2026.8.3'), cat(['v2026.8.4-rc1']))).toBeNull();
    expect(containerUpdate(on('v2026.8.3-rc1'), cat(['v2026.8.3']))?.tag).toBe('v2026.8.3');
  });

  it('orders two pre-releases of the same version by their marker', () => {
    // only reachable through the ranking itself — neither is ever offered as a
    // target, but a container on rc1 must still see rc2 as the newer build
    expect(newerImageTags(on('v2026.8.3-rc1'), cat(['v2026.8.3-rc2', 'v2026.8.3-rc0']))).toEqual([]);
    expect(newerImageTags(on('v2026.8.3-rc1'), cat(['v2026.8.4', 'v2026.8.3'])).map(t => t.tag))
      .toEqual(['v2026.8.4', 'v2026.8.3']);
  });

  it('surfaces a tag the host has not pulled yet, and says so', () => {
    const target = containerUpdate(on('v2026.7.20'), cat([{ tag: 'v2026.8.3', pulled: false }]));
    expect(target).toEqual({ tag: 'v2026.8.3', pulled: false });
  });

  it('computes the maximum from an unsorted catalog', () => {
    const catalog = cat(['v2026.7.30', 'v2026.8.3', 'v2026.4.3']);
    expect(containerUpdate(on('v2026.7.20'), catalog)?.tag).toBe('v2026.8.3');
    expect(newerImageTags(on('v2026.7.20'), catalog).map(t => t.tag))
      .toEqual(['v2026.8.3', 'v2026.7.30']);
  });

  it('ignores a catalog belonging to another repository', () => {
    expect(containerUpdate(on('v2026.7.20', 'acme/hermes-fork'), cat(['v2026.8.3']))).toBeNull();
    expect(containerUpdate(on('v2026.7.20'), cat(['v2026.8.3'], 'docker.io/nousresearch/hermes-agent'))?.tag)
      .toBe('v2026.8.3');
  });

  it('returns nothing for a missing or empty catalog', () => {
    expect(containerUpdate(on('v2026.7.20'), undefined)).toBeNull();
    expect(containerUpdate(on('v2026.7.20'), cat([]))).toBeNull();
  });
});

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

  it('selects a container and leaves the page for its overview', () => {
    const { fixture, store, router } = render(storeStub([container('hermes-prod')]));

    press(fixture, 'select', '.card');

    expect(store.containers.select).toHaveBeenCalledWith('hermes-prod');
    expect(router.navigate).toHaveBeenCalledWith(['/overview']);
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
  const catalog = (tags: (string | ImageTag)[]): Record<string, ImageCatalog> => ({
    'dh-local': {
      repository: HERMES,
      tags: tags.map(t => typeof t === 'string' ? { tag: t, pulled: true } : t),
      registryStatus: 'ok',
      fetchedAt: 0,
    },
  });

  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('badges a container that has a newer release, and says what the move is', () => {
    const { fixture } = render(storeStub([container('hermes-prod')], catalog(['v2026.8.3'])));

    const badge = el(fixture).querySelector('.chip.warn')!;
    expect(badge.textContent).toContain('update v2026.8.3');
    expect(badge.getAttribute('title')).toBe('v2026.7.20 → v2026.8.3');
  });

  it('warns on the badge when the target is not on the host yet', () => {
    const { fixture } = render(storeStub(
      [container('hermes-prod')], catalog([{ tag: 'v2026.8.3', pulled: false }])));

    expect(el(fixture).querySelector('.chip.warn')!.getAttribute('title'))
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

    press(fixture, 'update', '.card');

    const options = Array.from(field(fixture, 'target version').querySelectorAll('option'));
    expect(options.map(o => o.textContent?.trim())).toEqual(['v2026.8.3', 'v2026.7.30']);
    expect(text(fixture)).toContain('update to v2026.8.3');
  });

  it('warns that an unpulled target is fetched first', () => {
    const { fixture } = render(storeStub([container('hermes-prod')],
      catalog([{ tag: 'v2026.8.3', pulled: false }])));

    press(fixture, 'update', '.card');

    expect(text(fixture)).toContain('the image is pulled first');
  });

  it('says a stopped container stays stopped', () => {
    const { fixture } = render(storeStub([container('hermes-prod', { status: 'stopped' })],
      catalog(['v2026.8.3'])));

    press(fixture, 'update', '.card');

    expect(text(fixture)).toContain('this container is stopped — it stays stopped after the update');
  });

  it('recreates the container on the chosen tag and closes', async () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')],
      catalog(['v2026.8.3', 'v2026.7.30'])));
    press(fixture, 'update', '.card');
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
    press(fixture, 'update', '.card');

    press(fixture, 'update to v2026.8.3');
    await settle(fixture);

    expect(el(fixture).querySelector('.modal')).not.toBeNull();
  });

  it('locks the card and the modal while the recreate is in flight', async () => {
    const store = storeStub([container('hermes-prod')], catalog(['v2026.8.3']));
    let land!: (value: string) => void;
    store.lifecycle.update.mockReturnValue(new Promise<string>(r => { land = r; }));
    const { fixture } = render(store);
    press(fixture, 'update', '.card');

    press(fixture, 'update to v2026.8.3');
    fixture.detectChanges();

    expect(text(fixture)).toContain('recreating…');
    press(fixture, 'cancel');                       // a cancel mid-flight must not close it
    expect(el(fixture).querySelector('.modal')).not.toBeNull();

    land('c-updated');
    await settle(fixture);
  });

  it('cancels back out without touching the container', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod')], catalog(['v2026.8.3'])));
    press(fixture, 'update', '.card');

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
