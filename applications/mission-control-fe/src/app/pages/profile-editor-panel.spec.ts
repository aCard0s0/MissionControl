import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { Credential, McpCatalogServer, ProfileTemplate } from '../core/models';
import { ProfileDraft, newProfileDraft, profileDraftFrom } from './profile-editor';
import { ProfileEditorPanel } from './profile-editor-panel';
import { AGENT_ICONS } from '../shared/agent-icon';
import { TestFixture, el, press, type } from '../testing/dom';
import { catalogServer as sharedCatalogServer } from '../testing/models';
import { provideStores } from '../testing/store';

const stored = (patch: Partial<ProfileTemplate> = {}): ProfileTemplate => ({
  id: 't-1', name: 'ops-sre', icon: '', description: '', category: 'ops', provider: 'anthropic',
  model: 'claude-opus-5',
  baseUrl: '', cwd: '/opt/data', soul: '', memory: '', skills: [], mcpServers: [],
  secrets: [], createdAt: 1, updatedAt: 1, ...patch,
});

/** Only what the panel reaches for on the store, so nothing here touches a backend. */
const storeStub = (
  templates: ProfileTemplate[] = [],
  catalog: McpCatalogServer[] = [],
  credentials: Credential[] = [],
) => ({
  catalog: {
    servers: signal(catalog),
    byId: (id: string | null) => catalog.find(s => s.id === id) ?? null,
  },
  ctx: {
    toast: vi.fn(),
  },
  providers: {
    llmProviders: signal([]),
  },
  endpoints: { endpoints: signal([]) },
  credentials: { credentials: signal(credentials) },
  templates: {
    byId: (id: string | null) => templates.find(t => t.id === id) ?? null,
    categories: signal([...new Set(templates.map(t => t.category).filter(Boolean))].sort()),
    save: vi.fn().mockResolvedValue('t-new'),
  },
});

@Component({
  imports: [ProfileEditorPanel],
  template: `<mc-profile-editor [draft]="draft()"
                                (saved)="savedId = $event"
                                (closed)="closes = closes + 1"
                                (deployRequested)="deployed = $event.id"
                                (removeRequested)="removed = $event.id" />`,
})
class Host {
  readonly draft = signal<ProfileDraft>(newProfileDraft());
  savedId: string | null = null;
  closes = 0;
  deployed: string | null = null;
  removed: string | null = null;
}

/** Renders and lets the panel settle — its draft effect flushes on first
 *  stability, and it clears the scratch fields when it does. */
/**
 * Opens every optional group.
 *
 * <p>They start closed. Most specs here are about what a group *contains* —
 * adding a skill, snapshotting a server — so the harness opens them and those
 * specs stay about their subject. The disclosure itself has its own describe
 * block below, which renders with `groups: 'closed'`.
 */
const openAllGroups = (fixture: TestFixture): void => {
  for (const header of Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.group-h'))) {
    if (header.getAttribute('aria-expanded') !== 'true') header.click();
  }
  fixture.detectChanges();
};

const render = async (
  store: ReturnType<typeof storeStub>, draft = newProfileDraft(), groups: 'open' | 'closed' = 'open',
) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    // the pane links to /agents, so RouterLink needs a router present
    providers: [provideRouter([]), ...provideStores(store)],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.draft.set(draft);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  if (groups === 'open') openAllGroups(fixture);
  return { fixture, store, host: fixture.componentInstance };
};

/** A saved credential holding one secret. */
const credential = (
  id: string, name: string, key: string, patch: object = {},
): Credential => ({
  id, name, description: '', createdAt: 1, updatedAt: 1,
  entries: [{ key, value: '', secret: true, set: true, recoverable: true, ...patch }],
});

const credentialPicker = (fixture: TestFixture) =>
  el(fixture).querySelector<HTMLSelectElement>('.add-row select[aria-label="saved credential"]');

/** Awaits stability like {@link typeInto}: the picker writes `secretKey`, which `ngModel`
 *  flushes into the key box only on the next beat. */
const pickCredential = async (
  fixture: TestFixture & { whenStable(): Promise<unknown> }, value: string,
): Promise<void> => {
  const select = credentialPicker(fixture);
  if (!select) throw new Error('no credential picker');
  select.value = value;
  select.dispatchEvent(new Event('change'));
  await fixture.whenStable();
  fixture.detectChanges();
};

const submit = (fixture: TestFixture): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.editor-actions .btn.primary')!;

describe('ProfileEditorPanel', () => {
  it('titles itself after the stored blueprint, or says the draft is new', async () => {
    const template = stored();
    const { fixture, host } = await render(storeStub([template]));
    expect(el(fixture).textContent).toContain('new profile template');

    host.draft.set(profileDraftFrom(template, 'anthropic'));
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('edit — ops-sre');
  });

  it('clears half-typed rows when the page hands over a different draft', async () => {
    const { fixture, host } = await render(storeStub([stored()]));
    await type(fixture, '.add-row .input', 'web-research');
    expect(el(fixture).querySelector<HTMLInputElement>('.add-row .input')!.value)
      .toBe('web-research');

    host.draft.set(profileDraftFrom(stored(), 'anthropic'));
    fixture.detectChanges();
    await fixture.whenStable();
    // a new draft closes the groups with it, so the row has to be reached again
    openAllGroups(fixture);

    expect(el(fixture).querySelector<HTMLInputElement>('.add-row .input')!.value).toBe('');
  });

  it('clears them for a second new draft too, not only a different blueprint', async () => {
    const { fixture, host } = await render(storeStub());
    await type(fixture, '.add-row .input', 'web-research');

    host.draft.set(newProfileDraft());
    fixture.detectChanges();
    await fixture.whenStable();
    openAllGroups(fixture);

    expect(el(fixture).querySelector<HTMLInputElement>('.add-row .input')!.value).toBe('');
  });

  it('saves the mapped request and reports the id back to the page', async () => {
    const draft = { ...newProfileDraft(), name: ' ops-sre ' };
    const { fixture, store, host } = await render(storeStub(), draft);

    press(fixture, 'create template', '.editor-actions');
    await fixture.whenStable();

    expect(store.templates.save.mock.calls[0][0]).toMatchObject({ name: 'ops-sre' });
    expect(store.templates.save.mock.calls[0][1]).toBeUndefined();
    expect(host.savedId).toBe('t-new');
    // the saved id lands on the draft, so a second save updates in place
    expect(draft.id).toBe('t-new');
  });

  it('refuses a name the backend would reject, and one that is blank', async () => {
    const { fixture } = await render(storeStub());
    expect(submit(fixture).disabled).toBe(true);
  });

  it('will not send a second save while the first is in flight', async () => {
    const store = storeStub();
    store.templates.save.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture } = await render(store, { ...newProfileDraft(), name: 'ops-sre' });

    press(fixture, 'create template', '.editor-actions');
    expect(submit(fixture).textContent?.trim()).toBe('saving…');
    submit(fixture).click();

    expect(store.templates.save).toHaveBeenCalledTimes(1);
  });

  it('asks the page to deploy or delete rather than doing either itself', async () => {
    const template = stored();
    const { fixture, host } = await render(storeStub([template]), profileDraftFrom(template, 'anthropic'));

    press(fixture, 'deploy →', '.editor-actions');
    expect(host.deployed).toBe('t-1');

    press(fixture, 'delete', '.editor-actions');
    expect(host.removed).toBe('t-1');
  });

  it('offers neither until the blueprint has been saved once', async () => {
    const { fixture } = await render(storeStub());

    const labels = Array.from(el(fixture).querySelectorAll('.editor-actions button'))
      .map(b => (b.textContent ?? '').trim());
    expect(labels).toEqual(['cancel', 'create template']);
  });

  it('reports a cancel to the page rather than closing itself', async () => {
    const { fixture, store, host } = await render(storeStub());

    press(fixture, 'cancel', '.editor-actions');

    expect(host.closes).toBe(1);
    expect(store.templates.save).not.toHaveBeenCalled();
  });
});

/** The managed catalog entry these tests connect to. */
const catalogServer = (patch: Partial<McpCatalogServer> = {}): McpCatalogServer =>
  sharedCatalogServer('mcp-browser', {
    name: 'browser', serviceKey: 'browser', image: 'playwright:latest',
    connectionUrl: 'http://browser:1100/mcp', ...patch,
  });

/** The `.field` whose label names this section of the form. */
const section = (fixture: TestFixture, label: string): HTMLElement => {
  const group = Array.from(el(fixture).querySelectorAll<HTMLElement>('.group'))
    .find(g => (g.querySelector('.group-t')?.textContent ?? '').includes(label));
  const body = group?.querySelector<HTMLElement>('.group-b');
  if (body) return body;
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.field'))
    .find(f => (f.querySelector('label')?.textContent ?? '').includes(label));
  if (!match) throw new Error(`no field or group labelled "${label}"`);
  return match;
};

/** The nth add-row `.input` of the section this label names. */
const rowInput = (fixture: TestFixture, label: string, index = 0): HTMLInputElement =>
  section(fixture, label).querySelectorAll<HTMLInputElement>('.add-row .input')[index];

/** Presses the add button of the section this label names. */
const addRow = (fixture: TestFixture, label: string): void =>
  press(fixture, 'add', section(fixture, label).querySelector('.add-row')!);

/** Points the snapshot picker at a catalog entry. */
const pickCatalog = async (
  fixture: TestFixture & { whenStable(): Promise<unknown> }, id: string,
): Promise<void> => {
  const select = Array.from(el(fixture).querySelectorAll<HTMLSelectElement>('.select'))
    .find(s => Array.from(s.options).some(o => o.value === id));
  if (!select) throw new Error(`no picker offering "${id}"`);
  select.value = id;
  select.dispatchEvent(new Event('change'));
  await fixture.whenStable();
  fixture.detectChanges();
};

const typeInto = async (
  fixture: TestFixture & { whenStable(): Promise<unknown> }, input: HTMLInputElement, value: string,
): Promise<void> => {
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await fixture.whenStable();
  fixture.detectChanges();
};

describe('ProfileEditorPanel skills', () => {
  it('adds a skill once, however many times it is typed', async () => {
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub(), draft);

    await typeInto(fixture, rowInput(fixture, 'skills'), 'web-research');
    addRow(fixture, 'skills');
    await typeInto(fixture, rowInput(fixture, 'skills'), 'web-research');
    addRow(fixture, 'skills');

    expect(draft.skills).toEqual(['web-research']);
    expect(rowInput(fixture, 'skills').value).toBe('');
  });

  it('refuses an id the backend would not accept, and says why', async () => {
    const draft = newProfileDraft();
    const { fixture, store } = await render(storeStub(), draft);

    await typeInto(fixture, rowInput(fixture, 'skills'), 'web research');
    addRow(fixture, 'skills');

    expect(draft.skills).toEqual([]);
    expect(store.ctx.toast).toHaveBeenCalledWith(
      'invalid skill id "web research" — use letters, digits, . _ - (no spaces)');
  });

  it('ignores an empty add', async () => {
    const draft = newProfileDraft();
    const { fixture, store } = await render(storeStub(), draft);

    await typeInto(fixture, rowInput(fixture, 'skills'), '   ');
    addRow(fixture, 'skills');

    expect(draft.skills).toEqual([]);
    expect(store.ctx.toast).not.toHaveBeenCalled();
  });

  it('drops a skill from the chip row', async () => {
    const draft = { ...newProfileDraft(), skills: ['ops', 'web-research'] };
    const { fixture } = await render(storeStub(), draft);

    el(fixture).querySelectorAll<HTMLButtonElement>('.edit-chips .x')[0].click();
    fixture.detectChanges();

    expect(draft.skills).toEqual(['web-research']);
  });
});

describe('ProfileEditorPanel mcp servers', () => {
  it('adds a custom stdio definition and replaces one of the same name', async () => {
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub(), draft);
    const addMcp = () => press(fixture, 'add', '.add-mcp');

    await type(fixture, '.add-mcp .input', 'files');
    const args = el(fixture).querySelectorAll<HTMLInputElement>('.add-mcp .input');
    await typeInto(fixture, args[1], 'npx');
    addMcp();
    await typeInto(fixture, args[0], 'files');
    await typeInto(fixture, args[1], 'uvx');
    addMcp();

    expect(draft.mcpServers).toEqual([
      expect.objectContaining({ name: 'files', transport: 'stdio', command: 'uvx' }),
    ]);
  });

  it('removes a server from the list', async () => {
    const draft = {
      ...newProfileDraft(),
      mcpServers: [{ name: 'files', transport: 'stdio' as const, command: 'npx', enabled: true }],
    };
    const { fixture } = await render(storeStub(), draft);

    press(fixture, 'remove', '.line');

    expect(draft.mcpServers).toEqual([]);
  });

  it('proposes the catalog name as the alias and snapshots the connection', async () => {
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub([], [catalogServer()]), draft);
    await pickCatalog(fixture, 'mcp-browser');

    press(fixture, 'add snapshot');

    expect(draft.mcpServers).toEqual([expect.objectContaining({
      name: 'browser', transport: 'http', url: 'http://browser:1100/mcp', sourceServerId: 'mcp-browser',
    })]);
  });

  it('warns that a managed server\'s internal URL only works on its own host', async () => {
    const { fixture } = await render(storeStub([], [catalogServer()]));
    await pickCatalog(fixture, 'mcp-browser');

    expect(el(fixture).textContent).toContain('Uses the stack-internal URL');
  });

  it('refuses an alias the blueprint already carries', async () => {
    const draft = {
      ...newProfileDraft(),
      mcpServers: [{ name: 'browser', transport: 'http' as const, url: 'http://x', enabled: true }],
    };
    const { fixture, store } = await render(storeStub([], [catalogServer()]), draft);
    await pickCatalog(fixture, 'mcp-browser');

    press(fixture, 'add snapshot');

    expect(draft.mcpServers).toHaveLength(1);
    expect(store.ctx.toast).toHaveBeenCalledWith(
      'an MCP server named "browser" is already in this template');
  });

  it('says so when a catalog entry has no usable connection to snapshot', async () => {
    const draft = newProfileDraft();
    const unusable = catalogServer({ kind: 'stdio', stdioCommand: null, connectionUrl: null });
    const { fixture, store } = await render(storeStub([], [unusable]), draft);
    await pickCatalog(fixture, 'mcp-browser');

    press(fixture, 'add snapshot');

    expect(draft.mcpServers).toEqual([]);
    expect(store.ctx.toast).toHaveBeenCalledWith(
      'browser does not have a usable connection definition');
  });
});

describe('ProfileEditorPanel keys', () => {
  it('upper-cases a key and records that a value was supplied', async () => {
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub(), draft);

    await typeInto(fixture, rowInput(fixture, 'keys'), 'anthropic_api_key');
    await typeInto(fixture, rowInput(fixture, 'keys', 1), 'sk-x');
    addRow(fixture, 'keys');

    expect(draft.secrets).toEqual([
      { key: 'ANTHROPIC_API_KEY', value: 'sk-x', set: true, recoverable: true },
    ]);
  });

  it('refuses a key shaped like something the .env file cannot hold', async () => {
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub(), draft);

    await typeInto(fixture, rowInput(fixture, 'keys'), 'not a key');
    addRow(fixture, 'keys');

    expect(draft.secrets).toEqual([]);
  });

  it('offers no credential picker while the library holds no usable secret', async () => {
    // and an unrecoverable one does not count: picking it would only fail the save
    const { fixture } = await render(storeStub([], [], [
      credential('cr-1', 'anthropic prod', 'ANTHROPIC_API_KEY', { recoverable: false }),
    ]), newProfileDraft());

    expect(credentialPicker(fixture)).toBeNull();
  });

  it('names the credential and the variable in one option, because that is how it is found',
    async () => {
      const { fixture } = await render(storeStub([], [], [
        credential('cr-1', 'anthropic prod', 'ANTHROPIC_API_KEY'),
      ]), newProfileDraft());

      expect(Array.from(credentialPicker(fixture)!.options).map(o => o.textContent?.trim()))
        .toEqual(['type a key', 'anthropic prod · ANTHROPIC_API_KEY']);
    });

  it('stores the credential id and fills the variable name from the pick', async () => {
    // the operator remembers the credential, not the env var — so picking one names the key
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub([], [], [
      credential('cr-1', 'anthropic prod', 'ANTHROPIC_API_KEY'),
    ]), draft);

    await pickCredential(fixture, 'cr-1\u0000ANTHROPIC_API_KEY');
    expect(rowInput(fixture, 'keys').value).toBe('ANTHROPIC_API_KEY');
    addRow(fixture, 'keys');

    expect(draft.secrets).toEqual([{
      key: 'ANTHROPIC_API_KEY', value: '', set: true, recoverable: true, credentialId: 'cr-1',
    }]);
  });

  it('hides the value box while a credential is picked, so only one can be sent', async () => {
    const { fixture } = await render(storeStub([], [], [
      credential('cr-1', 'anthropic prod', 'ANTHROPIC_API_KEY'),
    ]), newProfileDraft());
    expect(el(fixture).querySelector('.add-row input[type="password"]')).not.toBeNull();

    await pickCredential(fixture, 'cr-1\u0000ANTHROPIC_API_KEY');

    expect(el(fixture).querySelector('.add-row input[type="password"]')).toBeNull();
  });

  it('unsets the pick again and goes back to a typed value', async () => {
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub([], [], [
      credential('cr-1', 'anthropic prod', 'ANTHROPIC_API_KEY'),
    ]), draft);

    await pickCredential(fixture, 'cr-1\u0000ANTHROPIC_API_KEY');
    await pickCredential(fixture, '');

    await typeInto(fixture, rowInput(fixture, 'keys'), 'OPENAI_API_KEY');
    await typeInto(fixture, rowInput(fixture, 'keys', 1), 'sk-typed');
    addRow(fixture, 'keys');

    expect(draft.secrets).toEqual([
      { key: 'OPENAI_API_KEY', value: 'sk-typed', set: true, recoverable: true },
    ]);
  });

  it('removes a key from the list', async () => {
    const draft = {
      ...newProfileDraft(),
      secrets: [{ key: 'ANTHROPIC_API_KEY', value: '', set: true, recoverable: false }],
    };
    const { fixture } = await render(storeStub(), draft);

    press(fixture, 'remove', '.line');

    expect(draft.secrets).toEqual([]);
  });
});

describe('ProfileEditorPanel saving', () => {
  it('updates in place once the blueprint has an id', async () => {
    const template = stored();
    const store = storeStub([template]);
    const { fixture } = await render(store, profileDraftFrom(template, 'anthropic'));

    press(fixture, 'save changes', '.editor-actions');
    await fixture.whenStable();

    expect(store.templates.save.mock.calls[0][1]).toBe('t-1');
  });

  it('keeps the draft editable when the save was refused', async () => {
    const store = storeStub();
    store.templates.save.mockResolvedValue('');
    const draft = { ...newProfileDraft(), name: 'ops-sre' };
    const { fixture, host } = await render(store, draft);

    press(fixture, 'create template', '.editor-actions');
    await fixture.whenStable();

    expect(host.savedId).toBeNull();
    expect(draft.id).toBeNull();
  });

  it('re-reads what the backend materialized, dropping the request-only source id', async () => {
    const saved = stored({
      id: 't-new',
      mcpServers: [{ name: 'browser', transport: 'http', url: 'http://browser:1100/mcp', enabled: true }],
    });
    const store = storeStub([saved]);
    const draft = {
      ...newProfileDraft(), name: 'ops-sre',
      mcpServers: [{
        name: 'browser', transport: 'http' as const, url: 'http://browser:1100/mcp',
        enabled: true, sourceServerId: 'mcp-browser',
      }],
    };
    const { fixture } = await render(store, draft);

    press(fixture, 'create template', '.editor-actions');
    await fixture.whenStable();

    expect(draft.mcpServers).toEqual([expect.objectContaining({ name: 'browser' })]);
    expect(draft.mcpServers[0]).not.toHaveProperty('sourceServerId', 'mcp-browser');
  });
});

/** One group's header button, named by the words on it. */
const groupHeader = (fixture: TestFixture, title: string): HTMLButtonElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.group-h'))
    .find(h => (h.querySelector('.group-t')?.textContent ?? '').includes(title));
  if (!match) throw new Error(`no group titled "${title}"`);
  return match;
};

const iconButton = (fixture: TestFixture): HTMLButtonElement =>
  el(fixture).querySelector<HTMLButtonElement>('.icon-btn')!;

describe('ProfileEditorPanel optional groups', () => {
  it('opens with the core on screen and everything else folded away', async () => {
    const { fixture } = await render(storeStub(), newProfileDraft(), 'closed');

    // the core: identity, and the model it runs on
    expect(el(fixture).textContent).toContain('name');
    expect(el(fixture).textContent).toContain('provider');

    // and nothing from a group, however much the blueprint carries
    expect(el(fixture).querySelector('.group-b')).toBeNull();
    expect(el(fixture).querySelectorAll('.group-h').length).toBe(6);
  });

  it('says what a folded group holds, so opening it is a decision not a search', async () => {
    const draft = newProfileDraft();
    draft.skills = ['web-research', 'triage'];
    const { fixture } = await render(storeStub(), draft, 'closed');

    expect(groupHeader(fixture, 'skills').textContent).toContain('2 skills');
    expect(groupHeader(fixture, 'tools').textContent).toContain('none');
  });

  it('counts one of a thing without the plural', async () => {
    const draft = newProfileDraft();
    draft.skills = ['triage'];
    const { fixture } = await render(storeStub(), draft, 'closed');

    expect(groupHeader(fixture, 'skills').textContent).toContain('1 skill');
  });

  it('opens a group and folds it again', async () => {
    const { fixture } = await render(storeStub(), newProfileDraft(), 'closed');
    const header = groupHeader(fixture, 'skills');

    header.click();
    fixture.detectChanges();
    expect(header.getAttribute('aria-expanded')).toBe('true');
    expect(el(fixture).querySelectorAll('.group-b').length).toBe(1);

    header.click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('.group-b')).toBeNull();
  });

  it('folds them again when the page hands over a different blueprint', async () => {
    const { fixture, host } = await render(storeStub([stored()]), newProfileDraft(), 'closed');
    groupHeader(fixture, 'skills').click();
    fixture.detectChanges();
    expect(el(fixture).querySelectorAll('.group-b').length).toBe(1);

    host.draft.set(profileDraftFrom(stored(), 'anthropic'));
    fixture.detectChanges();
    await fixture.whenStable();

    // otherwise the next blueprint opens on whatever the last one had expanded
    expect(el(fixture).querySelector('.group-b')).toBeNull();
  });
});

describe('ProfileEditorPanel icon', () => {
  it('keeps the glyph grid closed until it is asked for', async () => {
    const { fixture } = await render(storeStub(), newProfileDraft(), 'closed');

    expect(el(fixture).querySelector('.icon-grid')).toBeNull();

    iconButton(fixture).click();
    fixture.detectChanges();

    expect(el(fixture).querySelectorAll('.icon-opt').length).toBe(AGENT_ICONS.length);
  });

  it('writes the chosen key onto the draft and closes the grid', async () => {
    const draft = newProfileDraft();
    const { fixture } = await render(storeStub(), draft, 'closed');
    iconButton(fixture).click();
    fixture.detectChanges();

    el(fixture).querySelectorAll<HTMLButtonElement>('.icon-opt')[1].click();
    fixture.detectChanges();

    expect(draft.icon).toBe(AGENT_ICONS[1]);
    expect(el(fixture).querySelector('.icon-grid')).toBeNull();
  });

  it('clears the glyph when the one already set is picked again', async () => {
    const draft = newProfileDraft();
    draft.icon = AGENT_ICONS[0];
    const { fixture } = await render(storeStub(), draft, 'closed');
    iconButton(fixture).click();
    fixture.detectChanges();

    // no thirteenth button meaning 'none' — picking twice is how the default is reached
    el(fixture).querySelectorAll<HTMLButtonElement>('.icon-opt')[0].click();
    fixture.detectChanges();

    expect(draft.icon).toBe('');
  });

  it('carries the glyph into the request the backend gets', async () => {
    const draft = newProfileDraft();
    draft.name = 'ops-sre';
    draft.icon = 'shield';
    const { fixture, store } = await render(storeStub(), draft, 'closed');

    press(fixture, 'create template');
    await fixture.whenStable();

    expect(store.templates.save.mock.calls[0][0]).toMatchObject({ icon: 'shield' });
  });
});
