import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentStore } from '../core/store/agent-store';
import { ActivityStore } from '../core/store/activity-store';
import { SkillGroupStore } from '../core/store/skill-group-store';
import { SkillGuideStore } from '../core/store/skill-guide-store';
import { SkillStore } from '../core/store/skill-store';
import { Skill, SkillGroup, SkillGuide } from '../core/models';
import { SkillsPage } from './skills';
import { button, buttonWith, el, fill, press, settle, text, stubConfirm } from '../testing/dom';

const skill = (id: string, patch: Partial<Skill> = {}): Skill => ({
  id, kind: 'local', name: `skill-${id}`, description: `what ${id} does`, category: 'docs',
  repoUrl: '', version: '1.0', files: [{ path: 'SKILL.md', body: `# ${id}` }],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const storeStub = (skills: Skill[] = [skill('s-1')]) => {
  const list = signal(skills);
  return {
    skills: list,
    upstream: signal<Record<string, unknown>>({}),
    checkUpstream: vi.fn().mockResolvedValue(undefined),
    categories: signal([...new Set(skills.map(s => s.category))].sort()),
    refresh: vi.fn().mockResolvedValue(undefined),
    save: vi.fn().mockResolvedValue('s-new'),
    remove: vi.fn().mockResolvedValue(true),
    deploy: vi.fn().mockResolvedValue(true),
    importFrom: vi.fn().mockResolvedValue(true),
    byId: (id: string) => list().find(s => s.id === id) ?? null,
  };
};

const group = (id: string, patch: Partial<SkillGroup> = {}): SkillGroup => ({
  id, name: `group-${id}`, description: '', skillIds: [], guideId: '',
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const guide = (id: string, patch: Partial<SkillGuide> = {}): SkillGuide => ({
  id, name: `guide-${id}`, description: '', body: '# how', category: 'docs',
  skillIds: [], mcpServerIds: [], createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const groupStub = (groups: SkillGroup[] = []) => {
  const list = signal(groups);
  return {
    groups: list,
    refresh: vi.fn().mockResolvedValue(undefined),
    save: vi.fn().mockResolvedValue('sg-new'),
    remove: vi.fn().mockResolvedValue(true),
    byId: (id: string) => list().find(g => g.id === id) ?? null,
  };
};

const guideStub = (guides: SkillGuide[] = []) => {
  const list = signal(guides);
  return {
    guides: list,
    refresh: vi.fn().mockResolvedValue(undefined),
    byId: (id: string) => list().find(g => g.id === id) ?? null,
  };
};

const render = (
  store: ReturnType<typeof storeStub> = storeStub(),
  groups: ReturnType<typeof groupStub> = groupStub(),
  guides: ReturnType<typeof guideStub> = guideStub(),
) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: SkillStore, useValue: store },
      { provide: SkillGroupStore, useValue: groups },
      { provide: SkillGuideStore, useValue: guides },
      // the deploy dialog is declared by the page's template, so its own injections
      // have to resolve even in tests that never open it
      { provide: AgentStore, useValue: { forSelectedContainer: signal([]), resolve: () => null } },
      { provide: ActivityStore, useValue: { run: (_: string, w: () => unknown) => w() } },
    ],
  });
  const fixture = TestBed.createComponent(SkillsPage);
  fixture.detectChanges();
  return { fixture, store, groups, guides };
};

/** The row for one skill, found by its name the way an operator would. */
const row = (fixture: { nativeElement: unknown }, name: string): HTMLElement => {
  const match = Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.skill'))
    .find(s => (s.querySelector('.title')?.textContent ?? '').trim() === name);
  if (!match) throw new Error(`no skill row named "${name}"`);
  return match;
};

describe('SkillsPage', () => {
  // the page animates its entrance; a real clock would run those callbacks after
  // the fixture is gone
  beforeEach(() => { vi.useFakeTimers(); localStorage.clear(); });
  afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

  it('reads the library when it opens, so a deep link is not an empty page', () => {
    const { store } = render();

    expect(store.refresh).toHaveBeenCalled();
  });

  it('says which skills the library owns files for and which hermes installs', () => {
    const { fixture } = render(storeStub([
      skill('s-1', { name: 'pdf', kind: 'local' }),
      skill('s-2', { name: 'hubbed', kind: 'hub', files: [] }),
    ]));

    expect(row(fixture, 'pdf').textContent).toContain('local');
    expect(row(fixture, 'hubbed').textContent).toContain('hub');
  });

  it('filters by kind, because the two behave differently on deploy', async () => {
    const { fixture } = render(storeStub([
      skill('s-1', { name: 'pdf', kind: 'local' }),
      skill('s-2', { name: 'hubbed', kind: 'hub', files: [] }),
    ]));

    press(fixture, 'hub');
    await settle(fixture);

    expect(text(fixture)).toContain('hubbed');
    expect(text(fixture)).not.toContain('pdf');
  });

  it('keeps the description out of the collapsed row and reveals it on the click', async () => {
    // the list is for scanning; a paragraph per row is what made it unscannable
    const { fixture } = render(storeStub([skill('s-1', { name: 'pdf' })]));

    const disclose = () => row(fixture, 'pdf').querySelector<HTMLButtonElement>('.open-row')!;

    expect(text(fixture)).not.toContain('what s-1 does');

    disclose().click();
    await settle(fixture);
    expect(text(fixture)).toContain('what s-1 does');

    disclose().click();
    await settle(fixture);
    expect(text(fixture)).not.toContain('what s-1 does');
  });

  it('offers no disclosure on a row with nothing behind it', () => {
    const { fixture } = render(storeStub([
      skill('s-1', { name: 'bare', kind: 'hub', description: '', files: [] }),
    ]));

    expect(row(fixture, 'bare').querySelector('.open-row')).toBeNull();
  });

  it('teaches both ways in when the library is empty', () => {
    const { fixture } = render(storeStub([]));

    expect(text(fixture)).toContain('The library is empty');
    expect(text(fixture)).toContain('save to library');
  });

  it('offers to clear the filters when a search matches nothing', async () => {
    const { fixture } = render(storeStub([skill('s-1', { name: 'pdf' })]));

    const search = el(fixture).querySelector<HTMLInputElement>('.input.find')!;
    search.value = 'nothing here';
    search.dispatchEvent(new Event('input'));
    await settle(fixture);

    expect(text(fixture)).toContain('No skill matches');
    expect(button(fixture, 'clear filters')).toBeTruthy();
  });

  // ── groups ───────────────────────────────────────────────────────────────

  it('leaves the list flat and unlabelled when no group exists', () => {
    const { fixture } = render(storeStub([skill('s-1', { name: 'pdf' })]));

    expect(el(fixture).querySelector('.group-head')).toBeNull();
    expect(text(fixture)).toContain('pdf');
  });

  it('files each skill under the group that names it', async () => {
    const { fixture } = render(
      storeStub([skill('s-1', { name: 'pdf' }), skill('s-2', { name: 'sheets' })]),
      groupStub([group('sg-1', { name: 'documents', skillIds: ['s-1'] })]));
    await settle(fixture);

    const heads = Array.from(el(fixture).querySelectorAll<HTMLElement>('.group-head'))
      .map(h => (h.querySelector('.g-name')?.textContent ?? '').trim());
    expect(heads).toEqual(['documents', 'ungrouped']);
  });

  it('lists a skill under every group that claims it, rather than picking a winner', async () => {
    const { fixture } = render(
      storeStub([skill('s-1', { name: 'pdf' })]),
      groupStub([
        group('sg-1', { name: 'documents', skillIds: ['s-1'] }),
        group('sg-2', { name: 'reporting', skillIds: ['s-1'] }),
      ]));
    await settle(fixture);

    expect(el(fixture).querySelectorAll('.skill').length).toBe(2);
    expect(el(fixture).querySelector('.group-head.loose')).toBeNull();
  });

  it('names the guide a group points at', async () => {
    const { fixture } = render(
      storeStub([skill('s-1')]),
      groupStub([group('sg-1', { skillIds: ['s-1'], guideId: 'g-1' })]),
      guideStub([guide('g-1', { name: 'pdf-triage' })]));
    await settle(fixture);

    expect(text(fixture)).toContain('guide: pdf-triage');
  });

  it('says the guide is gone rather than showing a group with no link', async () => {
    // ids are not foreign keys here — the guide can be deleted after the group named it
    const { fixture } = render(
      storeStub([skill('s-1')]),
      groupStub([group('sg-1', { skillIds: ['s-1'], guideId: 'g-deleted' })]),
      guideStub([]));
    await settle(fixture);

    expect(text(fixture)).toContain('guide missing');
  });

  it('keeps an empty group visible until a filter is on', async () => {
    const { fixture } = render(
      storeStub([skill('s-1', { name: 'pdf' })]),
      groupStub([group('sg-1', { name: 'documents', skillIds: [] })]));
    await settle(fixture);

    // unfiltered: the header has to be there to be filled
    expect(text(fixture)).toContain('documents');

    press(fixture, 'hub');   // the kind filter, which matches neither
    await settle(fixture);

    expect(text(fixture)).not.toContain('documents');
  });

  it('sends the group the editor composed, with the guide it picked', async () => {
    const { fixture, groups } = render(
      storeStub([skill('s-1', { name: 'pdf' })]),
      groupStub(),
      guideStub([guide('g-1', { name: 'pdf-triage' })]));

    press(fixture, '+ new group');
    await settle(fixture);
    await fill(fixture, 'name', 'documents');
    press(fixture, 'pdf', '.picker');
    await settle(fixture);
    press(fixture, 'pdf-triage');
    await settle(fixture);
    press(fixture, 'save group');
    await settle(fixture);

    expect(groups.save).toHaveBeenCalledWith(
      { name: 'documents', description: '', skillIds: ['s-1'], guideId: 'g-1' }, undefined);
  });

  it('clears the guide when the one already picked is pressed again', async () => {
    // the association is the optional half; a picker you cannot un-pick makes it compulsory
    const { fixture, groups } = render(
      storeStub([skill('s-1')]), groupStub(), guideStub([guide('g-1', { name: 'pdf-triage' })]));

    press(fixture, '+ new group');
    await settle(fixture);
    await fill(fixture, 'name', 'documents');
    press(fixture, 'pdf-triage');
    await settle(fixture);
    press(fixture, 'pdf-triage');
    await settle(fixture);
    press(fixture, 'save group');
    await settle(fixture);

    expect(groups.save).toHaveBeenCalledWith(
      expect.objectContaining({ guideId: '' }), undefined);
  });

  it('will not save a group with no name', async () => {
    const { fixture, groups } = render();

    press(fixture, '+ new group');
    await settle(fixture);

    expect(buttonWith(fixture, 'save group').disabled).toBe(true);
    expect(groups.save).not.toHaveBeenCalled();
  });

  it('warns in the picker when another group already holds a skill', async () => {
    const { fixture } = render(
      storeStub([skill('s-1', { name: 'pdf' })]),
      groupStub([group('sg-1', { name: 'documents', skillIds: ['s-1'] })]));

    press(fixture, '+ new group');
    await settle(fixture);

    const chip = button(fixture, 'pdf', '.picker');
    expect(chip.classList).toContain('warn');
    expect(chip.title).toContain('already in the group documents');
  });

  it('says a group delete leaves its skills alone, and confirms first', async () => {
    const { fixture, groups } = render(
      storeStub([skill('s-1')]), groupStub([group('sg-1', { skillIds: ['s-1'] })]));
    await settle(fixture);
    const confirmed = stubConfirm(false);

    press(fixture, 'delete', '.group-head');
    await settle(fixture);

    expect(confirmed.mock.calls[0][0].message).toContain('only the filing goes');
    expect(groups.remove).not.toHaveBeenCalled();
  });

  it('loads a group into the editor with its skills and its guide', async () => {
    const { fixture } = render(
      storeStub([skill('s-1', { name: 'pdf' })]),
      groupStub([group('sg-1', {
        name: 'documents', description: 'paper things', skillIds: ['s-1'], guideId: 'g-1',
      })]),
      guideStub([guide('g-1', { name: 'pdf-triage' })]));
    await settle(fixture);

    press(fixture, 'edit', '.group-head');
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLInputElement>('#group-name')!.value).toBe('documents');
    expect(button(fixture, 'pdf', '.picker').classList).toContain('on');
    expect(button(fixture, 'pdf-triage').classList).toContain('on');
  });

  // ── the editor ───────────────────────────────────────────────────────────

  it('starts a new local skill with a SKILL.md already in it', async () => {
    const { fixture } = render();

    press(fixture, '+ new skill');
    await settle(fixture);

    const paths = Array.from(el(fixture).querySelectorAll<HTMLInputElement>('.file .path'));
    expect(paths.map(p => p.value)).toEqual(['SKILL.md']);
  });

  it('hides the files editor for a hub skill, which owns no content', async () => {
    const { fixture } = render();
    press(fixture, '+ new skill');
    await settle(fixture);
    expect(el(fixture).querySelector('.files')).toBeTruthy();

    press(fixture, 'hub', '.kind-pick');
    await settle(fixture);

    expect(el(fixture).querySelector('.files')).toBeNull();
    expect(text(fixture)).toContain('hermes skills install');
  });

  it('will not save a local skill without a SKILL.md, and says why', async () => {
    const { fixture, store } = render();
    press(fixture, '+ new skill');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf');

    press(fixture, 'remove');            // drop the starter SKILL.md
    await settle(fixture);

    expect(text(fixture)).toContain('SKILL.md required');
    expect(buttonWith(fixture, 'save skill').disabled).toBe(true);
    expect(store.save).not.toHaveBeenCalled();
  });

  it('will not save a file the backend would refuse as too large, and says why', async () => {
    const { fixture, store } = render();
    press(fixture, '+ new skill');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf');
    const body = el(fixture).querySelector<HTMLTextAreaElement>('.file textarea')!;
    body.value = 'x'.repeat(200_001);
    body.dispatchEvent(new Event('input'));
    await settle(fixture);

    expect(text(fixture)).toContain('SKILL.md is over 200,000 characters');
    expect(buttonWith(fixture, 'save skill').disabled).toBe(true);
    expect(store.save).not.toHaveBeenCalled();
  });

  it('sends the file set, trimming the paths an operator typed', async () => {
    const { fixture, store } = render();
    press(fixture, '+ new skill');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf');

    press(fixture, '+ add file');
    await settle(fixture);
    const paths = Array.from(el(fixture).querySelectorAll<HTMLInputElement>('.file .path'));
    paths[1].value = '  scripts/run.sh  ';
    paths[1].dispatchEvent(new Event('input'));
    await settle(fixture);

    press(fixture, 'save skill');
    await settle(fixture);

    expect(store.save).toHaveBeenCalledWith(
      expect.objectContaining({
        kind: 'local',
        name: 'pdf',
        files: [
          expect.objectContaining({ path: 'SKILL.md' }),
          expect.objectContaining({ path: 'scripts/run.sh' }),
        ],
      }),
      undefined);
  });

  it('sends no files for a hub skill even after one was typed', async () => {
    const { fixture, store } = render();
    press(fixture, '+ new skill');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf');

    press(fixture, 'hub', '.kind-pick');
    await settle(fixture);
    press(fixture, 'save skill');
    await settle(fixture);

    expect(store.save).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'hub', files: [] }), undefined);
  });

  it('keeps the editor and its text when a save fails', async () => {
    // retyping a skill because the backend blinked is the one thing this must never cost
    const store = storeStub();
    store.save = vi.fn().mockResolvedValue('');
    const { fixture } = render(store);
    press(fixture, '+ new skill');
    await settle(fixture);
    await fill(fixture, 'name', 'pdf');

    press(fixture, 'save skill');
    await settle(fixture);

    expect(el(fixture).querySelector('.editor')).toBeTruthy();
    expect(el(fixture).querySelector<HTMLInputElement>('#skill-name')!.value).toBe('pdf');
  });

  it('loads an existing skill into the editor with its files', async () => {
    const { fixture } = render(storeStub([skill('s-1', {
      name: 'pdf',
      files: [{ path: 'SKILL.md', body: '# pdf' }, { path: 'scripts/run.sh', body: 'echo' }],
    })]));

    press(fixture, 'edit');
    await settle(fixture);

    const paths = Array.from(el(fixture).querySelectorAll<HTMLInputElement>('.file .path'));
    expect(paths.map(p => p.value)).toEqual(['SKILL.md', 'scripts/run.sh']);
  });

  // ── delete ───────────────────────────────────────────────────────────────

  it('confirms a delete, and says the deployed copies are not touched', async () => {
    const { fixture, store } = render(storeStub([skill('s-1', { name: 'pdf' })]));
    const confirmed = stubConfirm(false);

    press(fixture, 'delete');
    await settle(fixture);

    expect(confirmed.mock.calls[0][0].message).toContain('stays there');
    expect(store.remove).not.toHaveBeenCalled();
  });

  it('removes the row once the delete is confirmed', async () => {
    const { fixture, store } = render(storeStub([skill('s-1', { name: 'pdf' })]));
    stubConfirm(true);

    press(fixture, 'delete');
    await settle(fixture);

    expect(store.remove).toHaveBeenCalledWith('s-1');
  });

  // ── deploy ───────────────────────────────────────────────────────────────

  it('tells a hub deploy it hands the name to hermes', async () => {
    const { fixture } = render(storeStub([skill('s-1', { name: 'hubbed', kind: 'hub', files: [] })]));

    press(fixture, 'deploy');
    await settle(fixture);

    expect(text(fixture)).toContain('hermes skills install hubbed');
  });

  it('offers a repository check only for a skill that has a repository', async () => {
    const { fixture } = render(storeStub([
      skill('s-1', { name: 'linked', repoUrl: 'https://github.com/o/r' }),
      skill('s-2', { name: 'unlinked', repoUrl: '' }),
    ]));

    expect(row(fixture, 'linked').textContent).toContain('check');
    expect(row(fixture, 'unlinked').textContent).not.toContain('check');
  });

  it('names the version an update was found at, which is the next question', async () => {
    const store = storeStub([skill('s-1', { name: 'pdf', repoUrl: 'https://github.com/o/r' })]);
    store.upstream.set({ 's-1': { status: 'update', latest: 'v2.0', detail: '', checkedAt: 1 } });
    const { fixture } = render(store);

    expect(row(fixture, 'pdf').textContent).toContain('update: v2.0');
  });

  it('says a check could not be made rather than that the skill is current', async () => {
    const store = storeStub([skill('s-1', { name: 'pdf', repoUrl: 'https://github.com/o/r' })]);
    store.upstream.set({ 's-1': { status: 'unavailable', latest: '', detail: '', checkedAt: null } });
    const { fixture } = render(store);

    expect(row(fixture, 'pdf').textContent).toContain('check failed');
  });

  it('says when SKILL.md names a different skill than the row does', async () => {
    // hermes resolves by frontmatter name, so the starter body's `name:` left unedited
    // deploys into skills/<row>/ and then shows up on the agent under the other name
    const { fixture } = render();
    press(fixture, '+ new skill');
    await settle(fixture);

    await fill(fixture, 'name', 'ui-made');

    expect(text(fixture)).toContain('the agent will list this as my-skill, not ui-made');
  });

  it('stays quiet once the two names agree', async () => {
    const { fixture } = render();
    press(fixture, '+ new skill');
    await settle(fixture);

    await fill(fixture, 'name', 'my-skill');

    expect(text(fixture)).not.toContain('hermes resolves by frontmatter name');
  });

  it('opens the deploy dialog for the skill whose button was pressed', async () => {
    const { fixture } = render(storeStub([skill('s-1', { name: 'pdf' })]));

    press(fixture, 'deploy');
    await settle(fixture);

    expect(el(fixture).querySelector('mc-deploy-dialog')).toBeTruthy();
    expect(text(fixture)).toContain('deploy skill — pdf');
    // the explanation is projected by this page, not owned by the dialog
    expect(text(fixture)).toContain('overlay, not a sync');
  });
});
