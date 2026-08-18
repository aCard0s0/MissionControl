import {
  ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, input, output, signal,
  untracked, viewChild,
} from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { clock } from '../core/format';
import { ChatMessage, SessionInfo } from '../core/models';
import { JsonTree } from '../shared/json-tree';
import { countJsonMatches, countMatches, highlightHtml } from '../shared/highlight';

/** canonical role order for the toolbar filter chips */
const ROLE_ORDER = ['user', 'assistant', 'tool', 'system'];

/** a message is "long" (collapsible) past this many chars or lines */
const LONG_CHARS = 700;
const LONG_LINES = 14;

/** How long to wait for the highlights to repaint before re-marking the hit. */
const REMARK_DELAY = 40;

/** One chat message prepared for the terminal-style transcript. */
interface RenderedMessage {
  idx: number;
  role: string;
  toolName: string | null | undefined;
  ts: number;
  long: boolean;
  contentHtml: SafeHtml | null;
  reasoningHtml: SafeHtml | null;
  toolCallsHtml: SafeHtml | null;
}

/**
 * Modal transcript for one recorded session: chat or raw JSON, a role filter,
 * and search with match navigation. Owns only view state — the messages are
 * handed in, and download/close are the host's business.
 */
@Component({
  selector: 'mc-session-viewer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [JsonTree],
  templateUrl: './session-viewer.html',
  styleUrl: './session-viewer.scss',
})
export class SessionViewer {
  readonly session = input.required<SessionInfo>();
  readonly messages = input<ChatMessage[]>([]);
  readonly loading = input(false);

  readonly closed = output<void>();
  readonly downloadRequested = output<void>();

  protected readonly clock = clock;

  protected readonly view = signal<'chat' | 'json'>('chat');
  protected readonly search = signal('');
  protected readonly matchIndex = signal(0);
  /** roles hidden by the toolbar role filter (empty = show all). */
  private readonly hiddenRoles = signal<Set<string>>(new Set());
  /** indices of long messages the user expanded. */
  private readonly expandedMsgs = signal<Set<number>>(new Set());

  private readonly sanitizer = inject(DomSanitizer);
  private readonly body = viewChild<ElementRef<HTMLElement>>('body');

  constructor() {
    // a different session in the same modal starts from a clean toolbar
    effect(() => {
      this.session().id;
      untracked(() => {
        this.view.set('chat');
        this.search.set('');
        this.matchIndex.set(0);
        this.hiddenRoles.set(new Set());
        this.expandedMsgs.set(new Set());
      });
    });
  }

  /** raw role-filtered messages, tagged with their original index. */
  private readonly visibleMessages = computed(() => {
    const hidden = this.hiddenRoles();
    const out: Array<{ idx: number; m: ChatMessage }> = [];
    this.messages().forEach((m, idx) => { if (!hidden.has(m.role)) out.push({ idx, m }); });
    return out;
  });

  /**
   * Chat messages with their content/reasoning/tool-calls pre-highlighted for the
   * current query. Precomputing (vs. a method binding) keeps the [innerHTML]
   * references stable across unrelated change-detection passes, so navigating
   * matches doesn't re-render the marks and wipe the `.current` highlight.
   */
  protected readonly renderedMessages = computed<RenderedMessage[]>(() => {
    const q = this.search();
    return this.visibleMessages().map(({ idx, m }) => {
      const content = m.content ?? '';
      return {
        idx,
        role: m.role,
        toolName: m.toolName,
        ts: m.ts,
        long: content.length > LONG_CHARS || content.split('\n').length > LONG_LINES,
        contentHtml: m.content ? this.trust(m.content, q) : null,
        reasoningHtml: m.reasoning ? this.trust(m.reasoning, q) : null,
        toolCallsHtml: m.toolCalls ? this.trust(prettyJson(m.toolCalls), q) : null,
      };
    });
  });

  /** role-filtered raw messages, fed to the JSON view so both views agree. */
  protected readonly rawMessages = computed(() => this.visibleMessages().map(x => x.m));

  /**
   * Match count, computed deterministically from the text the active view
   * highlights — counting the actual DOM marks would race the render (the marks
   * paint a frame after the query changes). gotoMatch still reads the DOM at
   * click time (settled) to scroll/mark the active hit.
   */
  protected readonly matchCount = computed(() => {
    const q = this.search().trim();
    if (!q) return 0;
    const msgs = this.rawMessages();
    if (this.view() === 'json') return countJsonMatches(msgs, null, q);
    let n = 0;
    for (const m of msgs) {
      n += countMatches(m.content, q) + countMatches(m.reasoning, q)
        + countMatches(prettyJson(m.toolCalls), q);
    }
    return n;
  });

  /** 1-based position shown in the toolbar, clamped to the count so a shrinking
   *  result set (role filter, view switch) can never render e.g. "5/2". */
  protected readonly matchPos = computed(() => {
    const total = this.matchCount();
    return total ? Math.min(this.matchIndex() + 1, total) : 0;
  });

  /** distinct roles present in the session, in canonical order (for filter chips). */
  protected readonly roles = computed(() => {
    const present = new Set(this.messages().map(m => m.role));
    const known = ROLE_ORDER.filter(r => present.has(r));
    const extra = [...present].filter(r => !ROLE_ORDER.includes(r)).sort();
    return [...known, ...extra];
  });

  protected roleVisible(role: string): boolean {
    return !this.hiddenRoles().has(role);
  }

  protected toggleRole(role: string): void {
    this.hiddenRoles.update(s => toggled(s, role));
    this.matchIndex.set(0);
    this.markCurrentSoon();
  }

  protected isMsgExpanded(idx: number): boolean {
    return this.expandedMsgs().has(idx);
  }

  protected expandMsg(idx: number): void {
    this.expandedMsgs.update(s => toggled(s, idx));
    this.markCurrentSoon();   // revealed text may add marks; re-mark the active hit
  }

  /** terminal-style prompt glyph per role. */
  protected msgGlyph(role: string): string {
    switch (role) {
      case 'user': return '❯';
      case 'assistant': return '⟩';
      case 'tool': return '⚙';
      case 'system': return '#';
      default: return '•';
    }
  }

  protected onSearch(value: string): void {
    this.search.set(value);
    this.matchIndex.set(0);
    this.markCurrentSoon();
  }

  protected setView(view: 'chat' | 'json'): void {
    this.view.set(view);
    this.matchIndex.set(0);
    this.markCurrentSoon();
  }

  protected gotoMatch(dir: number): void {
    const els = this.matchEls();
    if (!els.length) return;
    let i = this.matchIndex() + dir;
    if (i < 0) i = els.length - 1;
    if (i >= els.length) i = 0;
    this.matchIndex.set(i);
    this.markCurrent(i, true, els);   // reuse the same list — no second DOM query
  }

  private matchEls(): HTMLElement[] {
    const root = this.body()?.nativeElement;
    return root ? Array.from(root.querySelectorAll<HTMLElement>('.jt-hit')) : [];
  }

  /** Marks the active hit (count comes from the computed; this is cosmetic). */
  private markCurrent(active: number, scroll: boolean, els: HTMLElement[] = this.matchEls()): void {
    els.forEach((el, i) => el.classList.toggle('current', i === active));
    if (scroll && els[active]) els[active].scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  /** Best-effort re-mark of the active hit after the highlights repaint. */
  private markCurrentSoon(): void {
    setTimeout(() => this.markCurrent(this.matchIndex(), false), REMARK_DELAY);
  }

  private trust(text: string | null | undefined, query: string): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(highlightHtml(text, query));
  }
}

/** Pretty-prints a JSON string (tool_calls); falls back to the raw text. */
function prettyJson(json: string | null | undefined): string {
  if (!json) return '';
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

const toggled = <T>(set: ReadonlySet<T>, value: T): Set<T> => {
  const next = new Set(set);
  if (!next.delete(value)) next.add(value);
  return next;
};
