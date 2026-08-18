import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { ApiModelProvider } from '../core/hermes-api';
import { ProfileTemplate } from '../core/models';
import { AgentProfilesPage } from './agent-profiles';

const llm: ApiModelProvider[] = [
  { key: 'nous', label: 'Nous Portal', needsKey: false, oauth: true, hasCatalog: true, envVar: null },
  { key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true,
    envVar: 'ANTHROPIC_API_KEY' },
];

const template = (patch: Partial<ProfileTemplate> = {}): ProfileTemplate => ({
  id: 'pt-ops', name: 'ops-sre', description: 'Production SRE copilot.',
  provider: 'anthropic', model: 'claude-fable-5', baseUrl: '', cwd: '/opt/data',
  soul: '# SOUL.md — ops-sre\n', memory: '# MEMORY.md\n',
  skills: ['daily-briefing', 'web-research'],
  mcpServers: [
    { name: 'github', transport: 'http', url: 'https://api.github.test/mcp', enabled: true },
    { name: 'grafana', transport: 'stdio', command: 'mcp-grafana', args: '--url http://g:3000', enabled: true },
  ],
  secrets: [
    { key: 'ANTHROPIC_API_KEY', set: true, recoverable: true },
    { key: 'GITHUB_TOKEN', set: true, recoverable: true },
  ],
  createdAt: 1, updatedAt: 2, ...patch,
});

/** Only what the page, the editor pane and the deploy dialog reach for. */
const storeStub = (templates: ProfileTemplate[] = [template()]) => ({
  profileTemplates: signal(templates),
  templateById: (id: string | null) => templates.find(t => t.id === id) ?? null,
  saveTemplate: vi.fn().mockResolvedValue('pt-new'),
  deleteTemplate: vi.fn().mockResolvedValue(undefined),
  deployTemplate: vi.fn().mockResolvedValue('a-new'),
  mcpServers: signal([]),
  mcpServerById: () => null,
  llmProviders: signal(llm),
  modelProviders: signal([]),
  containers: signal([]),
  selectedContainerId: signal(''),
  toast: vi.fn(),
});

const render = () => {
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: HermesStore, useValue: storeStub() }],
  });
  const fixture = TestBed.createComponent(AgentProfilesPage);
  fixture.detectChanges();
  return fixture;
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

type Fixture = { nativeElement: unknown; detectChanges(): void };

const press = (fixture: Fixture, label: string, within?: string | Element): void => {
  const scope = typeof within === 'string' ? el(fixture).querySelector(within)
    : (within ?? el(fixture));
  if (!scope) throw new Error(`no element matching "${String(within)}"`);
  const match = Array.from(scope.querySelectorAll('button'))
    .find(b => (b.textContent ?? '').trim() === label);
  if (!match) throw new Error(`no button labelled "${label}"`);
  (match as HTMLButtonElement).click();
  fixture.detectChanges();
};

/** The `.field` whose label starts with this text. */
const field = (fixture: Fixture, label: string): HTMLElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.field'))
    .find(f => (f.querySelector('label')?.textContent ?? '').trim().toLowerCase()
      .startsWith(label.toLowerCase()));
  if (!match) throw new Error(`no field labelled "${label}"`);
  return match;
};

const settle = async (fixture: Fixture): Promise<void> => {
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
};

const type = async (fixture: Fixture, selector: string, value: string): Promise<void> => {
  const input = el(fixture).querySelector<HTMLInputElement>(selector)!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await settle(fixture);
};

/** Opens the blueprint whose card carries this name. */
const openTemplate = (fixture: Fixture, name: string): void => {
  const card = Array.from(el(fixture).querySelectorAll<HTMLElement>('.tmpl'))
    .find(t => (t.querySelector('.nm')?.textContent ?? '').trim() === name);
  if (!card) throw new Error(`no template card named "${name}"`);
  card.click();
  fixture.detectChanges();
};

describe('AgentProfilesPage', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('lists the stored blueprints and waits before opening one', () => {
    const fixture = render();

    expect(el(fixture).querySelectorAll('.tmpl').length).toBeGreaterThan(0);
    expect(el(fixture).textContent).toContain('ops-sre');
    expect(el(fixture).querySelector('.editor')).toBeNull();
    expect(el(fixture).querySelector('.placeholder')).not.toBeNull();
  });

  it('opens a blueprint with every part of it loaded', async () => {
    const fixture = render();

    openTemplate(fixture, 'ops-sre');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('edit — ops-sre');
    expect(field(fixture, 'name').querySelector<HTMLInputElement>('.input')!.value).toBe('ops-sre');
    expect(field(fixture, 'provider').querySelector<HTMLSelectElement>('.select')!.value)
      .toBe('anthropic');
    expect(field(fixture, 'soul').querySelector<HTMLTextAreaElement>('.input')!.value)
      .toContain('# SOUL.md');
    // skills as chips, MCP servers and keys as rows
    expect(el(fixture).querySelectorAll('.edit-chips .chip').length).toBe(2);
    expect(el(fixture).textContent).toContain('grafana');
    expect(el(fixture).textContent).toContain('ANTHROPIC_API_KEY');
    // a stored key is never read back into the form
    expect(el(fixture).querySelector<HTMLInputElement>('.key-inp')!.value).toBe('');
  });

  it('starts a new blueprint from the two files an operator fills in', async () => {
    const fixture = render();

    press(fixture, '+ new profile');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('new profile template');
    expect(field(fixture, 'soul').querySelector<HTMLTextAreaElement>('.input')!.value)
      .toContain('# SOUL.md');
    expect(el(fixture).textContent).toContain('no skills');
    expect(el(fixture).textContent).toContain('no mcp servers');
    expect(el(fixture).textContent).toContain('no keys');
  });

  it('refuses to save a blueprint with no name', async () => {
    const fixture = render();
    press(fixture, '+ new profile');
    await settle(fixture);

    const save = () => Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.editor-actions .btn'))
      .find(b => (b.textContent ?? '').trim() === 'create template')!;
    expect(save().disabled).toBe(true);

    await type(fixture, '.field .input', 'ops-copy');
    expect(save().disabled).toBe(false);
  });

  it('adds a skill and takes it away again', async () => {
    const fixture = render();
    press(fixture, '+ new profile');
    await settle(fixture);

    const skills = field(fixture, 'skills');
    const input = skills.querySelector<HTMLInputElement>('.add-row .input')!;
    input.value = 'web-research';
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
    press(fixture, 'add', skills);

    expect(skills.querySelectorAll('.chip').length).toBe(1);
    expect(skills.textContent).toContain('web-research');

    skills.querySelector<HTMLButtonElement>('.chip .x')!.click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('no skills');
  });

  it('keeps an invalid skill id out of the blueprint a deploy would install', async () => {
    const fixture = render();
    press(fixture, '+ new profile');
    await settle(fixture);

    const skills = field(fixture, 'skills');
    const input = skills.querySelector<HTMLInputElement>('.add-row .input')!;
    input.value = 'web research';
    input.dispatchEvent(new Event('input'));
    await settle(fixture);
    press(fixture, 'add', skills);

    expect(skills.querySelectorAll('.chip').length).toBe(0);
    // the field keeps what was typed, so it can be corrected rather than retyped
    expect(input.value).toBe('web research');
  });

  it('adds a custom MCP definition and removes it', async () => {
    const fixture = render();
    press(fixture, '+ new profile');
    await settle(fixture);

    const add = el(fixture).querySelector('.add-mcp')!;
    const inputs = add.querySelectorAll<HTMLInputElement>('.input');
    inputs[0].value = 'grafana';
    inputs[0].dispatchEvent(new Event('input'));
    inputs[1].value = 'mcp-grafana';
    inputs[1].dispatchEvent(new Event('input'));
    await settle(fixture);
    press(fixture, 'add', '.add-mcp');

    expect(el(fixture).querySelectorAll('.line').length).toBe(1);
    expect(el(fixture).textContent).toContain('mcp-grafana');

    press(fixture, 'remove', '.line');
    expect(el(fixture).textContent).toContain('no mcp servers');
  });

  it('adds a key, and refuses one the backend would not accept as a variable', async () => {
    const fixture = render();
    press(fixture, '+ new profile');
    await settle(fixture);

    const keys = field(fixture, 'keys');
    const rows = keys.querySelectorAll<HTMLInputElement>('.add-row .input');
    rows[0].value = 'not a var';
    rows[0].dispatchEvent(new Event('input'));
    await settle(fixture);
    press(fixture, 'add', keys);
    expect(keys.querySelectorAll('.line').length).toBe(0);

    rows[0].value = 'openai_api_key';
    rows[0].dispatchEvent(new Event('input'));
    rows[1].value = 'sk-test';
    rows[1].dispatchEvent(new Event('input'));
    await settle(fixture);
    press(fixture, 'add', keys);

    // the key is upper-cased to the shape the .env file uses
    expect(keys.textContent).toContain('OPENAI_API_KEY');
  });

  it('hands a deploy to its own dialog', async () => {
    const fixture = render();

    openTemplate(fixture, 'ops-sre');
    await settle(fixture);
    press(fixture, 'deploy →', '.editor-actions');
    await settle(fixture);

    expect(el(fixture).querySelector('mc-profile-deploy-dialog')).not.toBeNull();
    expect(el(fixture).textContent).toContain('deploy — ops-sre');
  });

  it('closes the editor without saving', async () => {
    const fixture = render();
    openTemplate(fixture, 'ops-sre');
    await settle(fixture);

    press(fixture, 'cancel', '.editor-actions');

    expect(el(fixture).querySelector('.editor')).toBeNull();
    expect(el(fixture).querySelector('.tmpl.active')).toBeNull();
  });
});
