import {
  ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { JsonTree } from '../shared/json-tree';
import { highlightHtml } from '../shared/highlight';
import { ago, clock, until } from '../core/format';
import { ChatMessage, LogEntry, McpServer, SessionInfo, SkillContent, SkillRef } from '../core/models';
import { ApiAgentSetup } from '../core/hermes-api';

type Tab = 'overview' | 'setup' | 'skills' | 'mcp' | 'jobs' | 'activity' | 'files' | 'sessions';

interface SessionView {
  title: string;
  session: SessionInfo;
  messages: ChatMessage[];
  loading: boolean;
}

/** canonical role order for the toolbar filter chips */
const ROLE_ORDER = ['user', 'assistant', 'tool', 'system'];
/** a message is "long" (collapsible) past this many chars or lines */
const LONG_CHARS = 700;
const LONG_LINES = 14;

/** non-overlapping, case-insensitive occurrence count — mirrors highlightHtml. */
function occ(text: string | null | undefined, q: string): number {
  if (!q) return 0;
  const t = (text ?? '').toLowerCase();
  const ql = q.toLowerCase();
  let n = 0;
  let i = t.indexOf(ql);
  while (i >= 0) { n++; i = t.indexOf(ql, i + ql.length); }
  return n;
}

/** primitive rendering used by the JSON tree (must match json-tree.primText). */
function jsonPrim(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'string') return JSON.stringify(value);
  return String(value);
}

/** Counts query matches exactly as the JSON tree highlights them (key + value
 *  per node, including {n}/[n] summaries) so the toolbar count matches the DOM. */
function jsonOcc(value: unknown, key: string | null, q: string): number {
  let n = key !== null ? occ(key, q) : 0;
  if (Array.isArray(value)) {
    n += occ(`[${value.length}]`, q);
    value.forEach((v, i) => { n += jsonOcc(v, String(i), q); });
  } else if (value !== null && typeof value === 'object') {
    const keys = Object.keys(value as Record<string, unknown>);
    n += occ(`{${keys.length}}`, q);
    for (const k of keys) n += jsonOcc((value as Record<string, unknown>)[k], k, q);
  } else {
    n += occ(jsonPrim(value), q);
  }
  return n;
}

@Component({
  selector: 'mc-agent-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot, Reveal, JsonTree],
  templateUrl: './agent-detail.html',
  styleUrl: './agent-detail.scss',
})
export class AgentDetailPage {
  protected readonly store = inject(HermesStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly ago = ago;
  protected readonly clock = clock;
  protected readonly until = until;

  private readonly id = toSignal(this.route.paramMap.pipe(map(p => p.get('id'))), { initialValue: null });

  protected readonly agent = computed(() => this.store.agentById(this.id()));

  protected readonly tab = signal<Tab>('overview');
  protected readonly tabs: Tab[] = ['overview', 'setup', 'skills', 'mcp', 'jobs', 'activity', 'files', 'sessions'];

  // setup tab — loaded on first entry, refreshed on demand (hermes status is slow)
  protected readonly setup = signal<ApiAgentSetup | null>(null);
  protected readonly setupLoading = signal(false);
  /** env var (or '.env' for the init call) with a write in flight. */
  protected readonly envBusy = signal<string | null>(null);
  /** tokenVar of the expanded messaging row. */
  protected readonly msgOpen = signal<string | null>(null);
  protected envDrafts: Record<string, string> = {};

  protected soulDraft = signal('');
  protected readonly soulDirty = computed(() => this.soulDraft() !== (this.agent()?.soul ?? ''));
  protected readonly soulSaved = signal(false);
  protected readonly soulSaving = signal(false);

  protected configDraft = signal('');
  protected readonly configDirty = computed(() => this.configDraft() !== (this.agent()?.configYaml ?? ''));
  protected readonly configSaved = signal(false);
  protected readonly configSaving = signal(false);

  protected readonly agentJobs = computed(() =>
    this.store.containerJobs().filter(j => j.agentId === this.id()));

  protected readonly agentLogEntries = signal<LogEntry[]>([]);
  protected readonly agentLogsLoading = signal(false);
  protected readonly agentLogsError = signal<string | null>(null);
  protected readonly agentLogsUpdatedAt = signal<number | null>(null);
  private readonly agentLogsInFlight = new Set<string>();

  protected readonly pinging = signal(false);
  protected readonly removing = signal(false);
  protected confirmText = '';

  // capture this agent into a reusable profile template
  protected readonly capturing = signal(false);
  protected readonly capturingBusy = signal(false);
  protected captureName = '';

  protected fileView = signal<'SOUL.md' | 'MEMORY.md' | 'config.yaml'>('SOUL.md');

  // sessions tab — lazy loaded on first entry (like setup)
  protected readonly sessions = signal<SessionInfo[] | null>(null);
  protected readonly sessionsLoading = signal(false);
  protected readonly viewingSession = signal<SessionView | null>(null);
  protected readonly sessionView = signal<'chat' | 'json'>('chat');
  protected readonly sessionSearch = signal('');
  protected readonly matchIndex = signal(0);
  /** roles hidden by the toolbar role filter (empty = show all). */
  protected readonly hiddenRoles = signal<Set<string>>(new Set());
  /** indices of long messages the user expanded. */
  protected readonly expandedMsgs = signal<Set<number>>(new Set());
  private readonly sanitizer = inject(DomSanitizer);

  // mcp add / edit form
  protected mcpName = '';
  protected mcpTransport: McpServer['transport'] = 'http';
  protected mcpUrl = '';
  protected mcpCommand = '';
  protected mcpArgs = '';
  /** original server name when editing an existing server, else null */
  protected readonly editingMcp = signal<string | null>(null);
  /** mcp server id with a retest in flight */
  protected readonly mcpTesting = signal<string | null>(null);
  protected readonly mcpProbeBusy = signal(false);
  // skill add form
  protected skillName = '';
  protected skillSource: 'hub' | 'user' = 'hub';
  // skill explore/edit (SKILL.md viewer)
  protected readonly expandedSkill = signal<string | null>(null);   // skill id, or null
  protected readonly skillContent = signal<SkillContent | null>(null);
  protected readonly skillBody = signal('');
  protected readonly skillLoading = signal(false);
  protected readonly skillSaving = signal(false);
  protected readonly skillSaved = signal(false);
  protected readonly skillDirty = computed(() => {
    const c = this.skillContent();
    return c !== null && this.skillBody() !== c.body;
  });

  constructor() {
    // Reset the drafts when a different agent loads. While the same agent is
    // shown, only sync a clean draft — the 12s agent poll replaces the agent
    // object, and clobbering an in-progress edit would lose the user's text.
    let lastId: string | null = null;
    let lastSoul = '';
    let lastConfig = '';
    effect(() => {
      const a = this.agent();
      const id = a?.id ?? null;
      const soul = a?.soul ?? '';
      const config = a?.configYaml ?? '';
      if (id !== lastId || untracked(this.soulDraft) === lastSoul) {
        this.soulDraft.set(soul);
      }
      if (id !== lastId || untracked(this.configDraft) === lastConfig) {
        this.configDraft.set(config);
      }
      if (id !== lastId) {
        this.setup.set(null);
        this.envDrafts = {};
        this.msgOpen.set(null);
        this.sessions.set(null);
        this.viewingSession.set(null);
        if (untracked(this.tab) === 'setup') untracked(() => void this.loadSetup());
        if (untracked(this.tab) === 'sessions') untracked(() => void this.loadSessions());
        if (untracked(this.tab) === 'mcp') untracked(() => void this.probeMcpServers());
      }
      lastId = id;
      lastSoul = soul;
      lastConfig = config;
    });

    // Poll only while Activity is visible. The cleanup runs on tab/agent changes
    // and component destruction, so hidden agent pages do not leak intervals.
    effect(onCleanup => {
      const a = this.agent();
      if (!a || this.tab() !== 'activity') return;
      untracked(() => {
        this.agentLogEntries.set([]);
        this.agentLogsError.set(null);
        this.agentLogsUpdatedAt.set(null);
        void this.loadAgentLogs();
      });
      const timer = setInterval(() => void this.loadAgentLogs(), 5_000);
      onCleanup(() => clearInterval(timer));
    });

  }

  protected selectTab(t: Tab): void {
    this.tab.set(t);
    if (t === 'setup' && !this.setup() && !this.setupLoading()) void this.loadSetup();
    if (t === 'sessions' && this.sessions() === null && !this.sessionsLoading()) void this.loadSessions();
    if (t === 'mcp') void this.probeMcpServers();
  }

  protected async loadAgentLogs(): Promise<void> {
    const a = this.agent();
    if (!a || this.agentLogsInFlight.has(a.id)) return;
    this.agentLogsInFlight.add(a.id);
    this.agentLogsLoading.set(true);
    this.agentLogsError.set(null);
    try {
      const lines = await this.store.agentLogTail(a.id, 100);
      if (this.agent()?.id === a.id && this.tab() === 'activity') {
        this.agentLogEntries.set(lines);
        this.agentLogsUpdatedAt.set(Date.now());
      }
    } catch (e: any) {
      if (this.agent()?.id === a.id && this.tab() === 'activity') {
        this.agentLogsError.set(e?.message ?? 'agent log refresh failed');
      }
    } finally {
      this.agentLogsInFlight.delete(a.id);
      if (this.agent()?.id === a.id) this.agentLogsLoading.set(false);
    }
  }

  protected async loadSetup(): Promise<void> {
    const a = this.agent();
    if (!a || this.setupLoading()) return;
    this.setupLoading.set(true);
    try {
      const s = await this.store.agentSetup(a.id).catch(() => null);
      if (this.agent()?.id === a.id) this.setup.set(s);
    } finally {
      this.setupLoading.set(false);
    }
    // The agent switched while hermes status ran — the effect's reload attempt
    // was blocked by setupLoading, so load the new agent's setup now.
    const current = this.agent();
    if (current && current.id !== a.id && this.tab() === 'setup') void this.loadSetup();
  }

  protected async loadSessions(): Promise<void> {
    const a = this.agent();
    if (!a || this.sessionsLoading()) return;
    this.sessionsLoading.set(true);
    try {
      const list = await this.store.agentSessions(a.id).catch(() => null);
      if (this.agent()?.id === a.id) this.sessions.set(list ?? []);
    } finally {
      this.sessionsLoading.set(false);
    }
  }

  protected viewSession(s: SessionInfo): void {
    const a = this.agent();
    if (!a) return;
    this.sessionView.set('chat');
    this.sessionSearch.set('');
    this.matchIndex.set(0);
    this.hiddenRoles.set(new Set());
    this.expandedMsgs.set(new Set());
    this.viewingSession.set({ title: s.title, session: s, messages: [], loading: true });
    this.store.agentSessionMessages(a.id, s.id)
      .then(messages => {
        // ignore stale responses: the modal closed, the session was swapped, or
        // the agent changed (session ids are per-profile, so ids can collide)
        if (this.agent()?.id === a.id && this.viewingSession()?.session.id === s.id) {
          this.viewingSession.set({ title: s.title, session: s, messages: messages ?? [], loading: false });
        }
      })
      .catch(e => {
        this.store.toast(`session load failed: ${e.message}`);
        this.viewingSession.set(null);
      });
  }

  protected async downloadSession(s: SessionInfo): Promise<void> {
    const a = this.agent();
    if (!a) return;
    let messages = this.viewingSession()?.session.id === s.id ? this.viewingSession()?.messages : null;
    if (!messages) messages = await this.store.agentSessionMessages(a.id, s.id).catch(() => null);
    if (messages == null) { this.store.toast('session download failed'); return; }
    const blob = new Blob([JSON.stringify(messages, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${a.name}-${s.id}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  // ── session viewer: search highlight + match navigation ──────────────────

  /** raw role-filtered messages, tagged with their original index. */
  protected readonly visibleMessages = computed(() => {
    const v = this.viewingSession();
    const hidden = this.hiddenRoles();
    if (!v) return [];
    const out: Array<{ idx: number; m: ChatMessage }> = [];
    v.messages.forEach((m, idx) => { if (!hidden.has(m.role)) out.push({ idx, m }); });
    return out;
  });

  /**
   * Chat messages with their content/reasoning/tool-calls pre-highlighted for the
   * current query. Precomputing (vs. a method binding) keeps the [innerHTML]
   * references stable across unrelated change-detection passes, so navigating
   * matches doesn't re-render the marks and wipe the `.current` highlight.
   */
  protected readonly renderedMessages = computed(() => {
    const q = this.sessionSearch();
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
        toolCallsHtml: m.toolCalls ? this.trust(this.pretty(m.toolCalls), q) : null,
      };
    });
  });

  /** role-filtered raw messages, fed to the JSON view so both views agree. */
  protected readonly visibleRawMessages = computed(() => this.visibleMessages().map(x => x.m));

  /**
   * Match count, computed deterministically from the text the active view
   * highlights — counting the actual DOM marks would race the render (the marks
   * paint a frame after the query changes). gotoMatch still reads the DOM at
   * click time (settled) to scroll/mark the active hit.
   */
  protected readonly matchCount = computed(() => {
    const q = this.sessionSearch().trim();
    if (!q) return 0;
    const msgs = this.visibleMessages().map(x => x.m);
    if (this.sessionView() === 'json') return jsonOcc(msgs, null, q);
    let n = 0;
    for (const m of msgs) {
      n += occ(m.content, q) + occ(m.reasoning, q) + occ(this.pretty(m.toolCalls), q);
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
  protected readonly sessionRoles = computed(() => {
    const v = this.viewingSession();
    if (!v) return [];
    const present = new Set(v.messages.map(m => m.role));
    const known = ROLE_ORDER.filter(r => present.has(r));
    const extra = [...present].filter(r => !ROLE_ORDER.includes(r)).sort();
    return [...known, ...extra];
  });

  protected roleVisible(role: string): boolean {
    return !this.hiddenRoles().has(role);
  }

  protected toggleRole(role: string): void {
    this.hiddenRoles.update(s => {
      const next = new Set(s);
      if (next.has(role)) next.delete(role); else next.add(role);
      return next;
    });
    this.matchIndex.set(0);
    this.markCurrentSoon();
  }

  protected isMsgExpanded(idx: number): boolean {
    return this.expandedMsgs().has(idx);
  }

  protected expandMsg(idx: number): void {
    this.expandedMsgs.update(s => {
      const next = new Set(s);
      if (next.has(idx)) next.delete(idx); else next.add(idx);
      return next;
    });
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

  private trust(text: string | null | undefined, q: string): SafeHtml {
    return this.sanitizer.bypassSecurityTrustHtml(highlightHtml(text, q));
  }

  /** Pretty-prints a JSON string (tool_calls); falls back to the raw text. */
  private pretty(json: string | null | undefined): string {
    if (!json) return '';
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }

  protected onSessionSearch(value: string): void {
    this.sessionSearch.set(value);
    this.matchIndex.set(0);
    this.markCurrentSoon();
  }

  protected setSessionView(view: 'chat' | 'json'): void {
    this.sessionView.set(view);
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
    return Array.from(document.querySelectorAll<HTMLElement>('.session-body .jt-hit'));
  }

  /** Marks the active hit (count comes from the computed; this is cosmetic). */
  private markCurrent(active: number, scroll: boolean, els: HTMLElement[] = this.matchEls()): void {
    els.forEach((el, i) => el.classList.toggle('current', i === active));
    if (scroll && els[active]) els[active].scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  /** Best-effort re-mark of the active hit after the highlights repaint. */
  private markCurrentSoon(): void {
    setTimeout(() => this.markCurrent(this.matchIndex(), false), 40);
  }

  protected deleteSession(s: SessionInfo): void {
    const a = this.agent();
    if (!a) return;
    if (!confirm(`Delete session “${s.title}”? This cannot be undone.`)) return;
    this.store.deleteAgentSession(a.id, s.id)
      .then(() => {
        this.sessions.update(list => (list ?? []).filter(x => x.id !== s.id));
        if (this.viewingSession()?.session.id === s.id) this.viewingSession.set(null);
      })
      .catch(e => this.store.toast(`session delete failed: ${e.message}`));
  }

  protected initEnv(): void {
    const a = this.agent();
    if (!a) return;
    this.envBusy.set('.env');
    this.store.initAgentEnv(a.id)
      .catch(() => null)
      .then(s => { if (s) this.setup.set(s); })
      .finally(() => this.envBusy.set(null));
  }

  protected setEnv(key: string): void {
    const value = (this.envDrafts[key] ?? '').trim();
    if (!value) return;
    this.applyEnv(key, value);
  }

  protected clearEnv(key: string): void {
    this.applyEnv(key, null);
  }

  private applyEnv(key: string, value: string | null): void {
    const a = this.agent();
    if (!a) return;
    this.envBusy.set(key);
    this.store.setAgentEnv(a.id, [{ key, value }])
      .catch(() => null)
      .then(s => {
        if (!s) return;
        this.setup.set(s);
        delete this.envDrafts[key];
      })
      .finally(() => this.envBusy.set(null));
  }

  protected toggleMsg(tokenVar: string): void {
    this.msgOpen.update(v => v === tokenVar ? null : tokenVar);
  }

  protected enabledSkills(a: { skills: { enabled: boolean }[] }): number {
    return a.skills.filter(s => s.enabled).length;
  }

  protected fileContent(): string {
    const a = this.agent();
    if (!a) return '';
    switch (this.fileView()) {
      case 'SOUL.md': return a.soul;
      case 'MEMORY.md': return a.memoryMd;
      case 'config.yaml': return a.configYaml;
    }
  }

  protected async saveSoul(): Promise<void> {
    const a = this.agent();
    if (!a || !this.soulDirty() || this.soulSaving()) return;
    this.soulSaving.set(true);
    const saved = await this.store.updateSoul(a.id, this.soulDraft());
    this.soulSaving.set(false);
    if (!saved || this.agent()?.id !== a.id) return;
    this.soulSaved.set(true);
    setTimeout(() => this.soulSaved.set(false), 1800);
  }

  protected async saveConfig(): Promise<void> {
    const a = this.agent();
    if (!a || !this.configDirty() || this.configSaving()) return;
    this.configSaving.set(true);
    const saved = await this.store.updateAgentConfig(a.id, this.configDraft());
    this.configSaving.set(false);
    if (!saved || this.agent()?.id !== a.id) return;
    this.configSaved.set(true);
    setTimeout(() => this.configSaved.set(false), 1800);
  }

  protected ping(): void {
    const a = this.agent();
    if (!a) return;
    this.pinging.set(true);
    this.store.pingIntegrations(a.id);
    setTimeout(() => this.pinging.set(false), 1100);
  }

  /** valid when the form can be submitted (transport-specific required field present) */
  protected mcpFormValid(): boolean {
    if (!this.mcpName.trim()) return false;
    return this.mcpTransport === 'stdio' ? !!this.mcpCommand.trim() : !!this.mcpUrl.trim();
  }

  protected async saveMcp(): Promise<void> {
    const a = this.agent();
    const name = this.mcpName.trim();
    if (!a || !name) return;
    const opts = this.mcpTransport === 'stdio'
      ? { command: this.mcpCommand.trim(), args: this.mcpArgs.trim() || undefined }
      : { url: this.mcpUrl.trim() };
    if (this.mcpTransport === 'stdio' ? !opts.command : !opts.url) return;

    // editing with a rename: drop the old server, then add the renamed one.
    const editing = this.editingMcp();
    if (editing && editing !== name) {
      const old = a.mcp.find(m => m.name === editing);
      if (old && !(await this.store.removeMcp(a.id, old.id))) return;
    }
    if (!(await this.store.addMcp(a.id, name, this.mcpTransport, opts))) return;
    this.resetMcpForm();
    const saved = this.agent()?.mcp.find(m => m.name === name);
    if (saved && saved.status !== 'disabled') await this.runMcpTest(saved);
  }

  protected editMcp(m: McpServer): void {
    this.editingMcp.set(m.name);
    this.mcpName = m.name;
    this.mcpTransport = m.transport;
    this.mcpUrl = m.url ?? '';
    this.mcpCommand = m.command ?? '';
    this.mcpArgs = m.args ?? '';
  }

  protected resetMcpForm(): void {
    this.editingMcp.set(null);
    this.mcpName = '';
    this.mcpTransport = 'http';
    this.mcpUrl = '';
    this.mcpCommand = '';
    this.mcpArgs = '';
  }

  protected testMcp(m: McpServer): void {
    if (this.mcpTesting()) return;
    void this.runMcpTest(m);
  }

  private async runMcpTest(m: McpServer): Promise<void> {
    const a = this.agent();
    if (!a || m.status === 'disabled') return;
    this.mcpTesting.set(m.id);
    try {
      await this.store.testMcp(a.id, m.name);
    } finally {
      if (this.mcpTesting() === m.id) this.mcpTesting.set(null);
    }
  }

  private async probeMcpServers(): Promise<void> {
    const a = this.agent();
    if (!a || this.mcpProbeBusy()) return;
    this.mcpProbeBusy.set(true);
    try {
      for (const server of a.mcp.filter(m => m.status !== 'disabled')) {
        if (this.agent()?.id !== a.id || this.tab() !== 'mcp') break;
        await this.runMcpTest(server);
      }
    } finally {
      this.mcpProbeBusy.set(false);
    }
  }

  protected mcpCount(a: { mcp: McpServer[] }, status: McpServer['status'] | 'unchecked'): number {
    if (status === 'unchecked') {
      return a.mcp.filter(m => m.status === 'unknown' || m.status === 'checking').length;
    }
    return a.mcp.filter(m => m.status === status).length;
  }

  protected addSkill(): void {
    const a = this.agent();
    const name = this.skillName.trim();
    if (!a || !name) return;
    this.store.addSkill(a.id, {
      name, source: this.skillSource, version: '0.1.0',
      description: this.skillSource === 'hub' ? 'Installed from Skills Hub' : 'Local user skill',
      enabled: true,
    });
    this.skillName = '';
  }

  /** Expand a skill row and load its SKILL.md for inspection/editing. */
  protected async viewSkill(s: SkillRef): Promise<void> {
    const a = this.agent();
    if (!a) return;
    if (this.expandedSkill() === s.id) { this.cancelSkillEdit(); return; }
    this.expandedSkill.set(s.id);
    this.skillContent.set(null);
    this.skillBody.set('');
    this.skillSaved.set(false);
    this.skillLoading.set(true);
    const content = await this.store.getSkillContent(a.id, s);
    // ignore the response if the user collapsed or switched skills mid-load
    if (this.expandedSkill() !== s.id) { this.skillLoading.set(false); return; }
    this.skillContent.set(content);
    this.skillBody.set(content?.body ?? '');
    this.skillLoading.set(false);
  }

  protected async saveSkill(s: SkillRef): Promise<void> {
    const a = this.agent();
    if (!a || !this.skillDirty() || this.skillSaving()) return;
    this.skillSaving.set(true);
    const ok = await this.store.saveSkillContent(a.id, s, this.skillBody());
    this.skillSaving.set(false);
    if (!ok) return;
    this.skillContent.update(c => c ? { ...c, body: this.skillBody() } : c);
    this.skillSaved.set(true);
    setTimeout(() => this.skillSaved.set(false), 1800);
  }

  protected cancelSkillEdit(): void {
    this.expandedSkill.set(null);
    this.skillContent.set(null);
    this.skillBody.set('');
    this.skillLoading.set(false);
  }

  protected confirmRemove(): void {
    const a = this.agent();
    if (!a || this.confirmText !== a.name) return;
    this.store.removeAgent(a.id);
    this.router.navigate(['/agents']);
  }

  protected openCapture(): void {
    const a = this.agent();
    this.captureName = a ? `${a.name}-template` : '';
    this.capturing.set(true);
  }

  protected async confirmCapture(): Promise<void> {
    const a = this.agent();
    if (!a || this.capturingBusy()) return;
    this.capturingBusy.set(true);
    const name = this.captureName.trim() || `${a.name}-template`;
    const id = await this.store.captureTemplate(a.id, name);
    this.capturingBusy.set(false);
    if (id) {
      this.capturing.set(false);
      this.store.toast(`saved template "${name}"`);
      this.router.navigate(['/profiles']);
    }
  }
}
