import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ChatMessage, SessionInfo } from '../core/models';
import { SessionViewer } from './session-viewer';

const session = (id = 's-1', title = 'Morning briefing'): SessionInfo => ({
  id, title, platform: 'telegram',
  startedAt: 1_700_000_000_000, messages: 2, status: 'closed',
});

@Component({
  imports: [SessionViewer],
  template: `
    <mc-session-viewer [session]="session()" [messages]="messages()" [loading]="loading()"
                       (closed)="closes = closes + 1" />`,
})
class Host {
  readonly session = signal(session());
  readonly messages = signal<ChatMessage[]>([]);
  readonly loading = signal(false);
  closes = 0;
}

const msg = (role: string, content: string, patch: Partial<ChatMessage> = {}): ChatMessage =>
  ({ role, content, ts: 1, ...patch });

const render = (messages: ChatMessage[] = [], loading = false) => {
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.messages.set(messages);
  fixture.componentInstance.loading.set(loading);
  fixture.detectChanges();
  return fixture;
};

const text = (fixture: { nativeElement: HTMLElement }): string =>
  fixture.nativeElement.textContent ?? '';

const search = (fixture: { nativeElement: HTMLElement; detectChanges(): void }, query: string): void => {
  const input = fixture.nativeElement.querySelector<HTMLInputElement>('.search .input')!;
  input.value = query;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
};

const buttonWith = (fixture: { nativeElement: HTMLElement }, label: string): HTMLButtonElement => {
  const match = Array.from(fixture.nativeElement.querySelectorAll('button'))
    .find(b => (b.textContent ?? '').trim().toLowerCase().includes(label.toLowerCase()));
  if (!match) throw new Error(`no button matching "${label}"`);
  return match as HTMLButtonElement;
};

describe('SessionViewer transcript', () => {
  it('renders one row per message, with the session identity in the header', () => {
    const fixture = render([msg('user', 'status?'), msg('assistant', 'all green')]);

    expect(text(fixture)).toContain('Morning briefing');
    expect(fixture.nativeElement.querySelectorAll('.trow')).toHaveLength(2);
    expect(text(fixture)).toContain('all green');
  });

  it('shows the loader instead of the toolbar until the history arrives', () => {
    const fixture = render([], true);

    expect(text(fixture)).toContain('loading chat history…');
    expect(fixture.nativeElement.querySelector('.session-toolbar')).toBeNull();
  });

  it('says so when a session recorded nothing', () => {
    expect(text(render([]))).toContain('No messages recorded');
  });

  it('reports a close request to the host rather than hiding itself', () => {
    const fixture = render([msg('user', 'hi')]);
    buttonWith(fixture, 'close').click();

    expect(fixture.componentInstance.closes).toBe(1);
    expect(fixture.nativeElement.querySelector('.session-modal')).not.toBeNull();
  });
});

describe('SessionViewer search', () => {
  it('counts every hit the highlighter marks, across all three message fields', () => {
    const fixture = render([
      msg('assistant', 'deploy ok', { reasoning: 'deploy twice: deploy' }),
      msg('tool', '', { toolCalls: '{"name":"deploy"}' }),
    ]);
    search(fixture, 'deploy');

    expect(fixture.nativeElement.querySelectorAll('mark.jt-hit')).toHaveLength(4);
    expect(text(fixture)).toContain('1/4');
  });

  it('clamps the position when the role filter shrinks the result set', () => {
    const fixture = render([msg('user', 'deploy'), msg('tool', 'deploy')]);
    search(fixture, 'deploy');
    buttonWith(fixture, 'tool').click();     // hide the tool role
    fixture.detectChanges();

    expect(text(fixture)).toContain('1/1');
    expect(fixture.nativeElement.querySelectorAll('.trow')).toHaveLength(1);
  });

  it('hands the same filtered messages to the JSON view', () => {
    const fixture = render([msg('user', 'status?'), msg('system', 'boot')]);
    buttonWith(fixture, 'json').click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('mc-json-tree')).not.toBeNull();
    expect(text(fixture)).toContain('status?');
  });
});

describe('SessionViewer message expansion', () => {
  const long = msg('assistant', 'x'.repeat(900));

  it('clamps a long message until it is expanded', () => {
    const fixture = render([long]);
    expect(fixture.nativeElement.querySelector('.trow.clamped')).not.toBeNull();

    buttonWith(fixture, 'show more').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.trow.clamped')).toBeNull();
  });

  it('never clamps while searching, so a hit cannot hide behind the fold', () => {
    const fixture = render([long]);
    search(fixture, 'x');

    expect(fixture.nativeElement.querySelector('.trow.clamped')).toBeNull();
  });
});

describe('SessionViewer session switch', () => {
  it('starts a different session from a clean toolbar', () => {
    const fixture = render([msg('user', 'deploy')]);
    search(fixture, 'deploy');
    expect(text(fixture)).toContain('1/1');

    fixture.componentInstance.session.set(session('s-2', 'Evening recap'));
    fixture.componentInstance.messages.set([msg('user', 'deploy')]);
    fixture.detectChanges();

    expect(text(fixture)).toContain('Evening recap');
    expect(fixture.nativeElement.querySelector('.search .input').value).toBe('');
    expect(fixture.nativeElement.querySelectorAll('mark.jt-hit')).toHaveLength(0);
  });
});
