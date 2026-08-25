import { describe, expect, it } from 'vitest';
import { McpCatalogServer, ModelProvider, ProfileTemplate } from '../core/models';
import {
  ProfileDraft, catalogTemplateSnapshot, detachedTemplateMcp, envKeyValid, newProfileDraft,
  profileDraftFrom, profileDraftToInput, profileDraftValid, skillIdValid,
} from './profile-editor';
import { externalCatalogServer } from '../testing/models';

/** The external endpoint these snapshots are taken from. */
const server = (patch: Partial<McpCatalogServer> = {}): McpCatalogServer =>
  externalCatalogServer('mcp-1', { name: 'Tools', url: 'https://tools.example.test/mcp', ...patch });

describe('Agent Profile MCP catalog snapshots', () => {
  it('sends only a transient source id with the connection preview', () => {
    expect(catalogTemplateSnapshot(server({}), 'research-tools')).toEqual({
      name: 'research-tools', transport: 'http',
      url: 'https://tools.example.test/mcp', enabled: true, sourceServerId: 'mcp-1',
    });
  });

  it('prefers a managed cross-host URL over stack-internal DNS', () => {
    const snapshot = catalogTemplateSnapshot(server({
      kind: 'managed', hostId: 'dh-local',
      connectionUrl: 'http://tools:1100/mcp',
      crossHostUrl: 'https://mcp.example.test/mcp',
    }), 'tools');

    expect(snapshot?.url).toBe('https://mcp.example.test/mcp');
  });

  it('preserves stdio argument boundaries in the preview', () => {
    const snapshot = catalogTemplateSnapshot(server({
      kind: 'stdio', transport: 'stdio', stdioCommand: 'npx',
      args: ['-y', '@acme/server', 'two words'], url: null,
    }), 'local-tools');

    expect(snapshot).toMatchObject({
      command: 'npx', args: "-y @acme/server 'two words'", sourceServerId: 'mcp-1',
    });
  });

  it('drops the transient source id after the first successful save', () => {
    const pending = catalogTemplateSnapshot(server({}), 'tools')!;

    expect(detachedTemplateMcp(pending)).not.toHaveProperty('sourceServerId');
  });
});

const ollama: ModelProvider[] = [{
  id: 'mp-1', name: 'workstation', url: 'http://10.0.0.5:11434', kind: 'ollama',
  status: 'connected', version: null, detail: null,
}];

const stored = (patch: Partial<ProfileTemplate> = {}): ProfileTemplate => ({
  id: 't-1', name: 'ops-sre', icon: '', description: 'runs the fleet', category: 'ops', provider: 'anthropic',
  model: 'claude-opus-5', baseUrl: '', cwd: '', soul: '# SOUL.md\n', memory: '# MEMORY.md\n',
  skills: ['web-research'], mcpServers: [], secrets: [], createdAt: 1, updatedAt: 2, ...patch,
});

const draft = (patch: Partial<ProfileDraft> = {}): ProfileDraft =>
  ({ ...newProfileDraft(), name: ' ops-sre ', ...patch });

describe('Agent Profile draft', () => {
  it('starts a new template with the two files an operator fills in', () => {
    const fresh = newProfileDraft();

    expect(fresh.id).toBeNull();
    expect(fresh.cwd).toBe('/opt/data');
    expect(fresh.soul).toContain('# SOUL.md');
    expect(fresh.memory).toContain('# MEMORY.md');
    expect(fresh.skills).toEqual([]);
    // blank, not 'general': the backend owns the default, so an operator who never
    // touches the field cannot end up filing one blueprint under a literal 'general'
    // while another carries whatever the frontend guessed
    expect(fresh.category).toBe('');
  });

  it('loads a stored template under the option its provider resolves to', () => {
    const loaded = profileDraftFrom(stored({ provider: 'ollama' }), 'ollama: workstation');

    expect(loaded).toMatchObject({
      id: 't-1', name: 'ops-sre', category: 'ops', provider: 'ollama: workstation',
      model: 'claude-opus-5',
    });
    // a template saved before cwd existed still opens on the default
    expect(loaded.cwd).toBe('/opt/data');
  });

  it('never reads a stored secret value back into the form', () => {
    const loaded = profileDraftFrom(
      stored({ secrets: [{ key: 'ANTHROPIC_API_KEY', set: true, recoverable: true }] }), 'anthropic');

    expect(loaded.secrets).toEqual([
      { key: 'ANTHROPIC_API_KEY', value: '', set: true, recoverable: true },
    ]);
  });

  it('copies nested lists, so abandoning an edit cannot mutate the stored template', () => {
    const template = stored({
      mcpServers: [{ name: 'tools', transport: 'http', url: 'https://a.test/mcp', enabled: true }],
    });
    const loaded = profileDraftFrom(template, 'anthropic');

    loaded.skills.push('extra');
    loaded.mcpServers[0].name = 'edited';

    expect(template.skills).toEqual(['web-research']);
    expect(template.mcpServers[0].name).toBe('tools');
  });
});

describe('Agent Profile draft validation', () => {
  it('needs a name the backend would accept, and nothing else', () => {
    expect(profileDraftValid(draft())).toBe(true);
    expect(profileDraftValid(draft({ name: '  ' }))).toBe(false);
    expect(profileDraftValid(draft({ name: 'ops sre' }))).toBe(false);
    expect(profileDraftValid(draft({ name: '-ops' }))).toBe(false);
    expect(profileDraftValid(draft({ name: 'ops.sre_2-b' }))).toBe(true);
  });

  it('holds skill ids to what installSkill accepts, so a deploy cannot roll back', () => {
    expect(skillIdValid('web-research')).toBe(true);
    expect(skillIdValid('a.b_c-1')).toBe(true);
    expect(skillIdValid('web research')).toBe(false);
    expect(skillIdValid('-leading')).toBe(false);
    expect(skillIdValid('')).toBe(false);
  });

  it('holds env keys to the server\'s own shape, including its 64-char cap', () => {
    expect(envKeyValid('ANTHROPIC_API_KEY')).toBe(true);
    expect(envKeyValid('A1')).toBe(true);
    expect(envKeyValid('A')).toBe(false);
    expect(envKeyValid('lower_case')).toBe(false);
    expect(envKeyValid('1LEADING')).toBe(false);
    expect(envKeyValid('A'.repeat(64))).toBe(true);
    expect(envKeyValid('A'.repeat(65))).toBe(false);
  });
});

describe('Agent Profile save request', () => {
  it('trims what the operator typed and sends the files verbatim', () => {
    const input = profileDraftToInput(draft({
      description: ' runs the fleet ', category: ' Incident Response ',
      model: ' claude-opus-5 ', cwd: ' /opt/data ',
      soul: '  leading space is content  ',
    }), ollama);

    expect(input).toMatchObject({
      name: 'ops-sre', description: 'runs the fleet', category: 'Incident Response',
      model: 'claude-opus-5', cwd: '/opt/data',
      soul: '  leading space is content  ',
    });
  });

  it('flattens an ollama option into the bare provider plus its endpoint', () => {
    const input = profileDraftToInput(draft({ provider: 'ollama: workstation' }), ollama);

    expect(input.provider).toBe('ollama');
    expect(input.baseUrl).toBe('http://10.0.0.5:11434/v1');
  });

  it('keeps an endpoint the operator typed over the instance\'s own', () => {
    const input = profileDraftToInput(
      draft({ provider: 'ollama: workstation', baseUrl: ' http://elsewhere:11434/v1 ' }), ollama);

    expect(input.baseUrl).toBe('http://elsewhere:11434/v1');
  });

  it('still stores bare ollama when the instance is no longer registered', () => {
    const input = profileDraftToInput(draft({ provider: 'ollama: gone' }), []);

    expect(input.provider).toBe('ollama');
    expect(input.baseUrl).toBe('');
  });

  it('sends only the key and its value, never the stored-secret flags', () => {
    const input = profileDraftToInput(draft({
      secrets: [
        { key: 'ANTHROPIC_API_KEY', value: '', set: true, recoverable: true },
        { key: 'OPENAI_API_KEY', value: 'sk-new', set: false, recoverable: true },
      ],
    }), ollama);

    expect(input.secrets).toEqual([
      { key: 'ANTHROPIC_API_KEY', value: '' },
      { key: 'OPENAI_API_KEY', value: 'sk-new' },
    ]);
  });
});
