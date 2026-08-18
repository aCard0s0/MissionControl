import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentDetailPage } from './agent-detail';

// A real HermesStore in mock mode, so this covers the page against the store's
// actual seeded shape — one tab at a time, the way an operator moves through it.
const render = (agentId = 'a-atlas') => {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: agentId })) } },
    ],
  });
  const fixture = TestBed.createComponent(AgentDetailPage);
  fixture.detectChanges();
  return fixture;
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

const openTab = (fixture: { nativeElement: unknown; detectChanges(): void }, name: string): void => {
  const tab = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.tabbar .tab'))
    .find(b => (b.textContent ?? '').trim() === name);
  if (!tab) throw new Error(`no tab named "${name}"`);
  tab.click();
  fixture.detectChanges();
};

describe('AgentDetailPage', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      dataMode: 'mock', apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('opens on the overview of the profile named in the route', () => {
    const fixture = render();

    expect(el(fixture).textContent).toContain('atlas');
    expect(el(fixture).textContent).toContain('Ops & infrastructure');
    expect(el(fixture).querySelector('.tabbar')).not.toBeNull();
  });

  it('says so when the route names a profile the active container does not have', () => {
    const fixture = render('a-nonexistent');

    expect(el(fixture).textContent).toContain('Agent not found');
  });

  it('hands the skills tab to its own panel', () => {
    const fixture = render();
    openTab(fixture, 'skills');

    expect(el(fixture).querySelector('mc-agent-skills-panel')).not.toBeNull();
    expect(el(fixture).querySelectorAll('.skill-item').length).toBeGreaterThan(0);
  });

  it('hands the mcp tab to its own panel', () => {
    const fixture = render();
    openTab(fixture, 'mcp');

    expect(el(fixture).querySelector('mc-agent-mcp-panel')).not.toBeNull();
    expect(el(fixture).textContent).toContain('CONNECT FROM MCP CATALOG');
  });

  it('lists the profile\'s recorded sessions and opens one in the viewer', async () => {
    const fixture = render();
    openTab(fixture, 'sessions');
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    const rows = el(fixture).querySelectorAll('.sess-row');
    expect(rows.length).toBeGreaterThan(0);

    const view = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.sess-row button'))
      .find(b => (b.textContent ?? '').trim() === 'view')!;
    view.click();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    expect(el(fixture).querySelector('mc-session-viewer')).not.toBeNull();
    expect(el(fixture).querySelectorAll('.trow').length).toBeGreaterThan(0);
  });

  it('shows the profile files read-only, and switches which one is shown', () => {
    const fixture = render();
    openTab(fixture, 'files');

    expect(el(fixture).textContent).toContain('SOUL.md');
    const memory = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.file-tab'))
      .find(b => (b.textContent ?? '').trim() === 'MEMORY.md')!;
    memory.click();
    fixture.detectChanges();

    const shown = el(fixture).querySelector('pre.file')?.textContent ?? '';
    expect(shown).toContain('# MEMORY.md');
    expect(el(fixture).querySelector('pre.file')?.getAttribute('contenteditable')).toBeNull();
  });
});
