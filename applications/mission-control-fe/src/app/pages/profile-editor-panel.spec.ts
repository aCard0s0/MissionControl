import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { ProfileTemplate } from '../core/models';
import { ProfileDraft, newProfileDraft, profileDraftFrom } from './profile-editor';
import { ProfileEditorPanel } from './profile-editor-panel';

const stored = (patch: Partial<ProfileTemplate> = {}): ProfileTemplate => ({
  id: 't-1', name: 'ops-sre', description: '', provider: 'anthropic', model: 'claude-opus-5',
  baseUrl: '', cwd: '/opt/data', soul: '', memory: '', skills: [], mcpServers: [],
  secrets: [], createdAt: 1, updatedAt: 1, ...patch,
});

/** Only what the panel reaches for on the store, so nothing here touches a backend. */
const storeStub = (templates: ProfileTemplate[] = []) => ({
  mcpServers: signal([]),
  mcpServerById: () => null,
  llmProviders: signal([]),
  modelProviders: signal([]),
  templateById: (id: string | null) => templates.find(t => t.id === id) ?? null,
  saveTemplate: vi.fn().mockResolvedValue('t-new'),
  toast: vi.fn(),
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
const render = async (store: ReturnType<typeof storeStub>, draft = newProfileDraft()) => {
  TestBed.configureTestingModule({
    // the pane links to /agents, so RouterLink needs a router present
    providers: [provideRouter([]), { provide: HermesStore, useValue: store }],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.draft.set(draft);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, store, host: fixture.componentInstance };
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

type Fixture = { nativeElement: unknown; detectChanges(): void; whenStable(): Promise<unknown> };

const press = (fixture: Fixture, label: string, within?: string): void => {
  const scope = within ? el(fixture).querySelector(within) : el(fixture);
  if (!scope) throw new Error(`no element matching "${within}"`);
  const match = Array.from(scope.querySelectorAll('button'))
    .find(b => (b.textContent ?? '').trim() === label);
  if (!match) throw new Error(`no button labelled "${label}"`);
  (match as HTMLButtonElement).click();
  fixture.detectChanges();
};

const fill = async (fixture: Fixture, selector: string, value: string): Promise<void> => {
  const input = el(fixture).querySelector<HTMLInputElement>(selector)!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await fixture.whenStable();
  fixture.detectChanges();
};

const submit = (fixture: Fixture): HTMLButtonElement =>
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
    await fill(fixture, '.add-row .input', 'web-research');
    expect(el(fixture).querySelector<HTMLInputElement>('.add-row .input')!.value)
      .toBe('web-research');

    host.draft.set(profileDraftFrom(stored(), 'anthropic'));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el(fixture).querySelector<HTMLInputElement>('.add-row .input')!.value).toBe('');
  });

  it('clears them for a second new draft too, not only a different blueprint', async () => {
    const { fixture, host } = await render(storeStub());
    await fill(fixture, '.add-row .input', 'web-research');

    host.draft.set(newProfileDraft());
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el(fixture).querySelector<HTMLInputElement>('.add-row .input')!.value).toBe('');
  });

  it('saves the mapped request and reports the id back to the page', async () => {
    const draft = { ...newProfileDraft(), name: ' ops-sre ' };
    const { fixture, store, host } = await render(storeStub(), draft);

    press(fixture, 'create template', '.editor-actions');
    await fixture.whenStable();

    expect(store.saveTemplate.mock.calls[0][0]).toMatchObject({ name: 'ops-sre' });
    expect(store.saveTemplate.mock.calls[0][1]).toBeUndefined();
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
    store.saveTemplate.mockReturnValue(new Promise(() => { /* never settles */ }));
    const { fixture } = await render(store, { ...newProfileDraft(), name: 'ops-sre' });

    press(fixture, 'create template', '.editor-actions');
    expect(submit(fixture).textContent?.trim()).toBe('saving…');
    submit(fixture).click();

    expect(store.saveTemplate).toHaveBeenCalledTimes(1);
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
    expect(store.saveTemplate).not.toHaveBeenCalled();
  });
});
