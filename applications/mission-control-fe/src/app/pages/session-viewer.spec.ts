import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatMessage, SessionInfo } from '../core/models';
import { SessionViewer } from './session-viewer';
import { TestFixture, button, buttonWith, el, press, settle, text, type } from '../testing/dom';

const session = (id = 's-1', title = 'Morning briefing'): SessionInfo => ({
  id, title, platform: 'telegram',
  startedAt: 1_700_000_000_000, messages: 2, status: 'closed',
});

@Component({
  imports: [SessionViewer],
  template: `
    <mc-session-viewer [session]="session()" [messages]="messages()" [loading]="loading()"
                       (closed)="closes = closes + 1"
                       (downloadRequested)="downloads = downloads + 1" />`,
})
class Host {
  readonly session = signal(session());
  readonly messages = signal<ChatMessage[]>([]);
  readonly loading = signal(false);
  closes = 0;
  downloads = 0;
}

const msg = (role: string, content: string, patch: Partial<ChatMessage> = {}): ChatMessage =>
  ({ role, content, ts: 1, ...patch });

const render = (messages: ChatMessage[] = [], loading = false) => {
  TestBed.resetTestingModule();
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.messages.set(messages);
  fixture.componentInstance.loading.set(loading);
  fixture.detectChanges();
  return fixture;
};

const search = async (fixture: TestFixture, query: string): Promise<void> =>
  type(fixture, '.search .input', query);

describe('SessionViewer transcript', () => {
  it('renders one row per message, with the session identity in the header', () => {
    const fixture = render([msg('user', 'status?'), msg('assistant', 'all green')]);

    expect(text(fixture)).toContain('Morning briefing');
    expect(el(fixture).querySelectorAll('.trow')).toHaveLength(2);
    expect(text(fixture)).toContain('all green');
  });

  it('shows the loader instead of the toolbar until the history arrives', () => {
    const fixture = render([], true);

    expect(text(fixture)).toContain('loading chat history…');
    expect(el(fixture).querySelector('.session-toolbar')).toBeNull();
  });

  it('says so when a session recorded nothing', () => {
    expect(text(render([]))).toContain('No messages recorded');
  });

  it('reports a close request to the host rather than hiding itself', () => {
    const fixture = render([msg('user', 'hi')]);
    buttonWith(fixture, 'close').click();

    expect(fixture.componentInstance.closes).toBe(1);
    expect(el(fixture).querySelector('.session-modal')).not.toBeNull();
  });
});

describe('SessionViewer search', () => {
  it('counts every hit the highlighter marks, across all three message fields', async () => {
    const fixture = render([
      msg('assistant', 'deploy ok', { reasoning: 'deploy twice: deploy' }),
      msg('tool', '', { toolCalls: '{"name":"deploy"}' }),
    ]);
    await search(fixture, 'deploy');

    expect(el(fixture).querySelectorAll('mark.jt-hit')).toHaveLength(4);
    expect(text(fixture)).toContain('1/4');
  });

  it('clamps the position when the role filter shrinks the result set', async () => {
    const fixture = render([msg('user', 'deploy'), msg('tool', 'deploy')]);
    await search(fixture, 'deploy');
    buttonWith(fixture, 'tool').click();     // hide the tool role
    fixture.detectChanges();

    expect(text(fixture)).toContain('1/1');
    expect(el(fixture).querySelectorAll('.trow')).toHaveLength(1);
  });

  it('hands the same filtered messages to the JSON view', () => {
    const fixture = render([msg('user', 'status?'), msg('system', 'boot')]);
    buttonWith(fixture, 'json').click();
    fixture.detectChanges();

    expect(el(fixture).querySelector('mc-json-tree')).not.toBeNull();
    expect(text(fixture)).toContain('status?');
  });
});

describe('SessionViewer message expansion', () => {
  const long = msg('assistant', 'x'.repeat(900));

  it('clamps a long message until it is expanded', () => {
    const fixture = render([long]);
    expect(el(fixture).querySelector('.trow.clamped')).not.toBeNull();

    buttonWith(fixture, 'show more').click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('.trow.clamped')).toBeNull();
  });

  it('never clamps while searching, so a hit cannot hide behind the fold', async () => {
    const fixture = render([long]);
    await search(fixture, 'x');

    expect(el(fixture).querySelector('.trow.clamped')).toBeNull();
  });
});

describe('SessionViewer session switch', () => {
  it('starts a different session from a clean toolbar', async () => {
    const fixture = render([msg('user', 'deploy')]);
    await search(fixture, 'deploy');
    expect(text(fixture)).toContain('1/1');

    fixture.componentInstance.session.set(session('s-2', 'Evening recap'));
    fixture.componentInstance.messages.set([msg('user', 'deploy')]);
    fixture.detectChanges();

    expect(text(fixture)).toContain('Evening recap');
    expect(el(fixture).querySelector<HTMLInputElement>('.search .input')!.value).toBe('');
    expect(el(fixture).querySelectorAll('mark.jt-hit')).toHaveLength(0);
  });
});

describe('SessionViewer toolbar', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('offers a filter chip per role present, in a canonical order', () => {
    const fixture = render([
      msg('tool', 'ran'), msg('user', 'go'), msg('audit', 'noted'), msg('assistant', 'ok'),
    ]);

    expect(Array.from(el(fixture).querySelectorAll('.rchip')).map(c => c.textContent?.trim()))
      .toEqual(['user', 'assistant', 'tool', 'audit']);
  });

  it('hides a role and brings it back', () => {
    const fixture = render([msg('user', 'go'), msg('tool', 'ran')]);

    press(fixture, 'tool');
    expect(el(fixture).querySelectorAll('.trow')).toHaveLength(1);

    press(fixture, 'tool');
    expect(el(fixture).querySelectorAll('.trow')).toHaveLength(2);
  });

  it('says the filter hid everything, rather than showing a blank transcript', () => {
    const fixture = render([msg('user', 'go'), msg('tool', 'ran')]);

    press(fixture, 'user');
    press(fixture, 'tool');

    expect(el(fixture).querySelectorAll('.trow')).toHaveLength(0);
    expect(text(fixture)).toContain('No messages match the role filter.');
  });

  it('marks each role with its own prompt glyph', () => {
    const fixture = render([
      msg('user', 'go'), msg('assistant', 'ok'), msg('tool', 'ran'),
      msg('system', 'boot'), msg('audit', 'noted'),
    ]);

    expect(Array.from(el(fixture).querySelectorAll('.pr')).map(g => g.textContent?.trim()))
      .toEqual(['❯', '⟩', '⚙', '#', '•']);
  });

  it('asks the host to download rather than writing a file itself', () => {
    const fixture = render([msg('user', 'go')]);

    press(fixture, 'download');

    expect(fixture.componentInstance.downloads).toBe(1);
  });
});

describe('SessionViewer match navigation', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    // jsdom has no layout, so scrolling the active hit into view is a no-op here
    Element.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    Reflect.deleteProperty(Element.prototype, 'scrollIntoView');
  });

  const hits = (fixture: TestFixture): number[] =>
    Array.from(el(fixture).querySelectorAll('mark.jt-hit'))
      .flatMap((m, i) => m.classList.contains('current') ? [i] : []);

  it('steps forward through the hits and wraps at the end', async () => {
    const fixture = render([msg('user', 'deploy deploy deploy')]);
    await search(fixture, 'deploy');

    press(fixture, '↓');
    expect(hits(fixture)).toEqual([1]);
    press(fixture, '↓');
    expect(hits(fixture)).toEqual([2]);
    press(fixture, '↓');
    expect(hits(fixture)).toEqual([0]);
    expect(text(fixture)).toContain('1/3');
  });

  it('steps backward and wraps at the start', async () => {
    const fixture = render([msg('user', 'deploy deploy')]);
    await search(fixture, 'deploy');

    press(fixture, '↑');

    expect(hits(fixture)).toEqual([1]);
    expect(text(fixture)).toContain('2/2');
  });

  it('does nothing with no hits to step through', async () => {
    const fixture = render([msg('user', 'deploy')]);
    await search(fixture, 'rollback');

    expect(button(fixture, '↓').disabled).toBe(true);
    expect(text(fixture)).toContain('0/0');
  });

  it('restarts from the first hit when the view changes', async () => {
    const fixture = render([msg('user', 'deploy deploy')]);
    await search(fixture, 'deploy');
    press(fixture, '↓');

    press(fixture, 'json');
    await settle(fixture, 50);

    expect(text(fixture)).toContain('1/');
  });
});
