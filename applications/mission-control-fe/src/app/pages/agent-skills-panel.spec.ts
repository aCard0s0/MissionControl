import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { AgentProfile, SkillContent, SkillRef } from '../core/models';
import { AgentSkillsPanel } from './agent-skills-panel';
import { buttonWith, el } from '../testing/dom';
import { agent, skill } from '../testing/models';

/** Only what the panel actually reaches for on the store. */
const storeStub = (content: SkillContent | null = null) => ({
  toggleSkill: vi.fn(),
  removeSkill: vi.fn(),
  addSkill: vi.fn(),
  getSkillContent: vi.fn().mockResolvedValue(content),
  saveSkillContent: vi.fn().mockResolvedValue(true),
});

@Component({
  imports: [AgentSkillsPanel],
  template: `<mc-agent-skills-panel [agent]="agent()" />`,
})
class Host {
  readonly agent = signal(profile([skill('daily-briefing'), skill('web-research', { enabled: false })]));
}

const render = (store: ReturnType<typeof storeStub>, agent?: AgentProfile) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(Host);
  if (agent) fixture.componentInstance.agent.set(agent);
  fixture.detectChanges();
  return fixture;
};

/** One profile, carrying the skills under test. */
const profile = (skills: SkillRef[]): AgentProfile => agent('a-1', { name: 'ops-bot', skills });

describe('AgentSkillsPanel', () => {
  it('lists every skill and counts only the enabled ones', () => {
    const fixture = render(storeStub());

    expect(el(fixture).querySelectorAll('.skill-item')).toHaveLength(2);
    expect(el(fixture).textContent).toContain('1/2 enabled');
  });

  it('sends a toggle to the store rather than flipping the row locally', () => {
    const store = storeStub();
    const fixture = render(store);
    buttonWith(fixture, 'ON').click();

    expect(store.toggleSkill).toHaveBeenCalledWith('a-1', 's-daily-briefing');
  });

  it('refuses to install a skill with no name, and clears the field once it does', async () => {
    const store = storeStub();
    const fixture = render(store);
    const add = buttonWith(fixture, 'add skill');
    expect(add.disabled).toBe(true);

    const input = el(fixture).querySelector<HTMLInputElement>('.add-row .input')!;
    input.value = ' pdf-tools ';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    buttonWith(fixture, 'add skill').click();
    expect(store.addSkill).toHaveBeenCalledWith('a-1', expect.objectContaining({
      name: 'pdf-tools', source: 'hub', enabled: true,
    }));
    await fixture.whenStable();       // ngModel writes the cleared value on a microtask
    fixture.detectChanges();
    expect(el(fixture).querySelector<HTMLInputElement>('.add-row .input')!.value).toBe('');
  });

  it('loads SKILL.md into the editor when a row is expanded, and folds it away again', async () => {
    const store = storeStub({
      name: 'daily-briefing', path: '~/.hermes/profiles/ops-bot/skills/daily-briefing',
      body: '# daily-briefing\n', files: ['SKILL.md'],
    });
    const fixture = render(store);

    buttonWith(fixture, 'view / edit').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.getSkillContent).toHaveBeenCalledWith('a-1', expect.objectContaining({ name: 'daily-briefing' }));
    expect(el(fixture).querySelector<HTMLTextAreaElement>('textarea')!.value)
      .toBe('# daily-briefing\n');
    expect(el(fixture).textContent).toContain('skills/daily-briefing/SKILL.md');

    buttonWith(fixture, 'close').click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('textarea')).toBeNull();
  });

  it('says so when a skill has no readable SKILL.md', async () => {
    const fixture = render(storeStub(null));
    buttonWith(fixture, 'view / edit').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('SKILL.md not available');
  });

  it('only offers a save once the body differs from what was loaded', async () => {
    const store = storeStub({ name: 'daily-briefing', path: '/p', body: 'original', files: [] });
    const fixture = render(store);
    buttonWith(fixture, 'view / edit').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(buttonWith(fixture, 'save SKILL.md').disabled).toBe(true);

    const editor = el(fixture).querySelector<HTMLTextAreaElement>('textarea')!;
    editor.value = 'edited';
    editor.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(el(fixture).textContent).toContain('unsaved changes');
    buttonWith(fixture, 'save SKILL.md').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(store.saveSkillContent).toHaveBeenCalledWith(
      'a-1', expect.objectContaining({ name: 'daily-briefing' }), 'edited');
    expect(el(fixture).textContent).toContain('saved ✓');
  });
});
