import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityStore } from '../core/store/activity-store';
import { AgentStore } from '../core/store/agent-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { SkillGuideStore } from '../core/store/skill-guide-store';
import { SkillStore } from '../core/store/skill-store';
import { Skill, SkillGuide } from '../core/models';
import { SkillGuidesPanel } from './skill-guides-panel';
import { button, buttonWith, el, fill, press, settle, text, stubConfirm } from '../testing/dom';

const guide = (id: string, patch: Partial<SkillGuide> = {}): SkillGuide => ({
  id, name: `guide-${id}`, description: 'triage a broken export', body: 'Read the log first.',
  category: 'docs', skillIds: ['s-1'], mcpServerIds: ['m-1'],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const skill = (id: string, name: string): Skill => ({
  id, kind: 'local', name, description: '', category: 'docs', repoUrl: '', version: '1.0',
  files: [{ path: 'SKILL.md', body: '# x' }], createdAt: 1_000, updatedAt: 2_000,
});

const guideStub = (guides: SkillGuide[] = [guide('g-1')]) => {
  const list = signal(guides);
  return {
    guides: list,
    categories: signal([...new Set(guides.map(g => g.category))].sort()),
    refresh: vi.fn().mockResolvedValue(undefined),
    save: vi.fn().mockResolvedValue('g-new'),
    remove: vi.fn().mockResolvedValue(true),
    deploy: vi.fn().mockResolvedValue([]),
    byId: (id: string) => list().find(g => g.id === id) ?? null,
  };
};

const render = (
  store: ReturnType<typeof guideStub> = guideStub(),
  skills: Skill[] = [skill('s-1', 'pdf-tools')],
  servers: { id: string; name: string }[] = [{ id: 'm-1', name: 'postgres' }],
) => {
  const skillList = signal(skills);
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: SkillGuideStore, useValue: store },
      {
        provide: SkillStore,
        useValue: {
          skills: skillList,
          byId: (id: string) => skillList().find(s => s.id === id) ?? null,
        },
      },
      { provide: McpCatalogStore, useValue: { servers: signal(servers) } },
      { provide: AgentStore, useValue: { forSelectedContainer: signal([]), resolve: () => null } },
      { provide: ActivityStore, useValue: { run: (_: string, w: () => unknown) => w() } },
    ],
  });
  const fixture = TestBed.createComponent(SkillGuidesPanel);
  fixture.detectChanges();
  return { fixture, store };
};

const row = (fixture: { nativeElement: unknown }, name: string): HTMLElement => {
  const match = Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.guide'))
    .find(g => (g.querySelector('.title')?.textContent ?? '').trim() === name);
  if (!match) throw new Error(`no guide row named "${name}"`);
  return match;
};

describe('SkillGuidesPanel', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('reads the library when it opens', () => {
    const { store } = render();

    expect(store.refresh).toHaveBeenCalled();
  });

  it('resolves the ids a guide holds into the names an operator recognises', () => {
    const { fixture } = render(guideStub([guide('g-1', { name: 'pdf-triage' })]));

    const card = row(fixture, 'pdf-triage');
    expect(card.textContent).toContain('pdf-tools');
    expect(card.textContent).toContain('postgres');
  });

  it('marks a part that is gone, before the operator clicks deploy rather than after', () => {
    // the guide outlived the skill; the deploy would skip it, and this says so first
    const { fixture } = render(
      guideStub([guide('g-1', { name: 'pdf-triage', skillIds: ['s-gone'] })]), []);

    const card = row(fixture, 'pdf-triage');
    expect(card.textContent).toContain('s-gone');
    expect(card.textContent).toContain('⚠');
  });

  it('teaches what a guide is when there are none', () => {
    const { fixture } = render(guideStub([]));

    expect(text(fixture)).toContain('umbrella skill');
  });

  it('offers to clear the filters when a search matches nothing', async () => {
    const { fixture } = render(guideStub([guide('g-1', { name: 'pdf-triage' })]));

    const search = el(fixture).querySelector<HTMLInputElement>('.input.find')!;
    search.value = 'nothing here';
    search.dispatchEvent(new Event('input'));
    await settle(fixture);

    expect(text(fixture)).toContain('No guide matches');
    expect(button(fixture, 'clear filters')).toBeTruthy();
  });

  // ── editor ───────────────────────────────────────────────────────────────

  it('starts a new guide with a body that suggests the shape', async () => {
    const { fixture } = render();

    press(fixture, '+ new guide');
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLTextAreaElement>('#guide-body')!.value)
      .toContain('When to use this');
  });

  it('will not save without a name and a body', async () => {
    const { fixture, store } = render();
    press(fixture, '+ new guide');
    await settle(fixture);

    expect(buttonWith(fixture, 'save guide').disabled).toBe(true);
    expect(store.save).not.toHaveBeenCalled();
  });

  it('sends the ids in the order they were picked, which is the order the agent reads', async () => {
    const { fixture, store } = render(guideStub([]), [
      skill('s-1', 'pdf-tools'), skill('s-2', 'log-reader'),
    ]);
    press(fixture, '+ new guide');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf-triage');

    // picked second-then-first: the order is the operator's, not the library's
    press(fixture, 'log-reader');
    await settle(fixture);
    press(fixture, 'pdf-tools');
    await settle(fixture);
    press(fixture, 'save guide');
    await settle(fixture);

    expect(store.save).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'pdf-triage', skillIds: ['s-2', 's-1'] }), undefined);
  });

  it('unpicks a skill that is pressed twice', async () => {
    const { fixture, store } = render(guideStub([]));
    press(fixture, '+ new guide');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf-triage');

    press(fixture, 'pdf-tools');
    await settle(fixture);
    press(fixture, 'pdf-tools');
    await settle(fixture);
    press(fixture, 'save guide');
    await settle(fixture);

    expect(store.save).toHaveBeenCalledWith(
      expect.objectContaining({ skillIds: [] }), undefined);
  });

  it('keeps the editor and its prose when a save fails', async () => {
    const store = guideStub();
    store.save = vi.fn().mockResolvedValue('');
    const { fixture } = render(store);
    press(fixture, '+ new guide');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf-triage');

    press(fixture, 'save guide');
    await settle(fixture);

    expect(el(fixture).querySelector('.editor')).toBeTruthy();
    expect(el(fixture).querySelector<HTMLInputElement>('#guide-name')!.value).toBe('pdf-triage');
  });

  it('loads an existing guide into the editor with its picks', async () => {
    const { fixture } = render(guideStub([guide('g-1', { name: 'pdf-triage' })]));

    press(fixture, 'edit');
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLInputElement>('#guide-name')!.value).toBe('pdf-triage');
    expect(text(fixture)).toContain('1 picked');
  });

  it('says a delete leaves what it already deployed alone', async () => {
    const { fixture, store } = render();
    const confirmed = stubConfirm(false);

    press(fixture, 'delete');
    await settle(fixture);

    expect(confirmed.mock.calls[0][0].message).toContain('stays on its agents');
    expect(store.remove).not.toHaveBeenCalled();
  });

  it('opens the deploy dialog for the guide whose button was pressed', async () => {
    const { fixture } = render(guideStub([guide('g-1', { name: 'pdf-triage' })]));

    press(fixture, 'deploy');
    await settle(fixture);

    expect(el(fixture).querySelector('mc-deploy-dialog')).toBeTruthy();
    expect(text(fixture)).toContain('deploy guide — pdf-triage');
    // the explanation is projected by this panel, not owned by the dialog
    expect(text(fixture)).toContain('umbrella skill');
  });
});
