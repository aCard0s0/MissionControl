import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { Confirm } from '../shared/confirm';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AgentRemoval } from '../core/store/agent-removal';
import { AgentSetupStore } from '../core/store/agent-setup-store';
import { AgentStore } from '../core/store/agent-store';
import { JobStore } from '../core/store/job-store';
import { StoreContext } from '../core/store/store-context';
import { TemplateStore } from '../core/store/template-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { AgentMcpPanel } from './agent-mcp-panel';
import { AgentSetupPanel } from './agent-setup-panel';
import { AgentSkillsPanel } from './agent-skills-panel';
import { LogView } from '../shared/log-view';
import { ago, until } from '../core/format';
import { errorMessage } from '../core/errors';
import { ChatMessage, LogEntry, SessionInfo } from '../core/models';
import { SessionViewer } from './session-viewer';
import { Scrim } from '../shared/scrim';

type Tab = 'overview' | 'setup' | 'skills' | 'mcp' | 'jobs' | 'activity' | 'files' | 'sessions';

/** The session the modal is showing, and the messages loaded for it so far. */
interface SessionView {
  session: SessionInfo;
  messages: ChatMessage[];
  loading: boolean;
}

@Component({
  selector: 'mc-agent-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot, Reveal, LogView,
    AgentMcpPanel, AgentSetupPanel, AgentSkillsPanel, SessionViewer, Scrim],
  templateUrl: './agent-detail.html',
  styleUrl: './agent-detail.scss',
})
export class AgentDetailPage {
  protected readonly agents = inject(AgentStore);
  private readonly confirm = inject(Confirm);
  protected readonly ctx = inject(StoreContext);
  protected readonly jobs = inject(JobStore);
  protected readonly removal = inject(AgentRemoval);
  protected readonly setup = inject(AgentSetupStore);
  protected readonly templates = inject(TemplateStore);
  private readonly router = inject(Router);

  protected readonly ago = ago;
  protected readonly until = until;

  /** `agents/:id`, bound by the router (withComponentInputBinding). */
  readonly id = input<string | null>(null);

  protected readonly agent = computed(() => this.agents.byId(this.id()));

  protected readonly tabs: Tab[] = ['overview', 'setup', 'skills', 'mcp', 'jobs', 'activity', 'files', 'sessions'];
  protected readonly activeTab = signal<Tab>('overview');

  /** `?tab=` — how another page links straight at one tab of this profile, bound by
   *  the router. It seeds {@link activeTab}; pressing a tab afterwards stays local. */
  readonly tab = input<string | null>(null);

  protected soulDraft = signal('');
  protected readonly soulDirty = computed(() => this.soulDraft() !== (this.agent()?.soul ?? ''));
  protected readonly soulSaved = signal(false);
  protected readonly soulSaving = signal(false);

  protected configDraft = signal('');
  protected readonly configDirty = computed(() => this.configDraft() !== (this.agent()?.configYaml ?? ''));
  protected readonly configSaved = signal(false);
  protected readonly configSaving = signal(false);

  /** Overview counts the skills the profile actually runs with. */
  protected readonly enabledSkills = computed(() =>
    this.agent()?.skills.filter(s => s.enabled).length ?? 0);

  protected readonly agentJobs = computed(() =>
    this.jobs.forSelectedContainer().filter(j => j.agentId === this.id()));

  protected readonly agentLogEntries = signal<LogEntry[]>([]);
  protected readonly agentLogsLoading = signal(false);
  protected readonly agentLogsError = signal<string | null>(null);
  protected readonly agentLogsUpdatedAt = signal<number | null>(null);
  private readonly agentLogsInFlight = new Set<string>();

  protected readonly pinging = signal(false);
  protected readonly pauseOpen = signal(false);
  protected readonly pausing = signal(false);
  protected pauseReason = '';

  // capture this agent into a reusable profile template
  protected readonly capturing = signal(false);
  protected readonly capturingBusy = signal(false);
  protected captureName = '';

  protected fileView = signal<'SOUL.md' | 'MEMORY.md' | 'config.yaml'>('SOUL.md');

  // sessions tab — lazy loaded on first entry (like setup)
  protected readonly sessions = signal<SessionInfo[] | null>(null);
  protected readonly sessionsLoading = signal(false);
  protected readonly viewingSession = signal<SessionView | null>(null);


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
        this.sessions.set(null);
        this.viewingSession.set(null);
        if (untracked(this.activeTab) === 'sessions') untracked(() => void this.loadSessions());
      }
      lastId = id;
      lastSoul = soul;
      lastConfig = config;
    });

    // A ?tab= link (the overview's skills/MCP chips) opens on that tab. It seeds the
    // tab rather than owning it: pressing a tab here stays local, so flipping through
    // them neither rewrites the URL nor fills the back button with tab changes.
    effect(() => {
      const t = this.tab();
      if (t && this.tabs.includes(t as Tab)) untracked(() => this.selectTab(t as Tab));
    });

    // Poll only while Activity is visible. The cleanup runs on tab/agent changes
    // and component destruction, so hidden agent pages do not leak intervals.
    effect(onCleanup => {
      const a = this.agent();
      if (!a || this.activeTab() !== 'activity') return;
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
    this.activeTab.set(t);
    if (t === 'sessions' && this.sessions() === null && !this.sessionsLoading()) void this.loadSessions();
  }

  protected async loadAgentLogs(): Promise<void> {
    const a = this.agent();
    if (!a || this.agentLogsInFlight.has(a.id)) return;
    this.agentLogsInFlight.add(a.id);
    this.agentLogsLoading.set(true);
    this.agentLogsError.set(null);
    try {
      const lines = await this.agents.logTail(a.id, 100);
      if (this.agent()?.id === a.id && this.activeTab() === 'activity') {
        this.agentLogEntries.set(lines);
        this.agentLogsUpdatedAt.set(Date.now());
      }
    } catch (e) {
      if (this.agent()?.id === a.id && this.activeTab() === 'activity') {
        this.agentLogsError.set(errorMessage(e, 'agent log refresh failed'));
      }
    } finally {
      this.agentLogsInFlight.delete(a.id);
      if (this.agent()?.id === a.id) this.agentLogsLoading.set(false);
    }
  }

  protected async loadSessions(): Promise<void> {
    const a = this.agent();
    if (!a || this.sessionsLoading()) return;
    this.sessionsLoading.set(true);
    try {
      const list = await this.setup.sessions(a.id).catch(() => null);
      if (this.agent()?.id === a.id) this.sessions.set(list ?? []);
    } finally {
      this.sessionsLoading.set(false);
    }
  }

  protected viewSession(s: SessionInfo): void {
    const a = this.agent();
    if (!a) return;
    this.viewingSession.set({ session: s, messages: [], loading: true });
    this.setup.sessionMessages(a.id, s.id)
      .then(messages => {
        // ignore stale responses: the modal closed, the session was swapped, or
        // the agent changed (session ids are per-profile, so ids can collide)
        if (this.agent()?.id === a.id && this.viewingSession()?.session.id === s.id) {
          this.viewingSession.set({ session: s, messages: messages ?? [], loading: false });
        }
      })
      .catch(e => {
        this.ctx.toast(`session load failed: ${errorMessage(e)}`);
        this.viewingSession.set(null);
      });
  }

  protected async downloadSession(s: SessionInfo): Promise<void> {
    const a = this.agent();
    if (!a) return;
    let messages = this.viewingSession()?.session.id === s.id ? this.viewingSession()?.messages : null;
    if (!messages) messages = await this.setup.sessionMessages(a.id, s.id).catch(() => null);
    if (messages == null) { this.ctx.toast('session download failed'); return; }
    const blob = new Blob([JSON.stringify(messages, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${a.name}-${s.id}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  protected async deleteSession(s: SessionInfo): Promise<void> {
    const a = this.agent();
    if (!a) return;
    if (!await this.confirm.ask({
      title: 'delete session',
      message: `Delete session “${s.title}”? This cannot be undone.`,
    })) return;
    await this.setup.deleteSession(a.id, s.id)
      .then(() => {
        this.sessions.update(list => (list ?? []).filter(x => x.id !== s.id));
        if (this.viewingSession()?.session.id === s.id) this.viewingSession.set(null);
      })
      .catch(e => this.ctx.toast(`session delete failed: ${errorMessage(e)}`));
  }

  protected async saveSoul(): Promise<void> {
    const a = this.agent();
    if (!a || !this.soulDirty() || this.soulSaving()) return;
    this.soulSaving.set(true);
    const saved = await this.agents.updateSoul(a.id, this.soulDraft());
    this.soulSaving.set(false);
    if (!saved || this.agent()?.id !== a.id) return;
    this.soulSaved.set(true);
    setTimeout(() => this.soulSaved.set(false), 1800);
  }

  protected async saveConfig(): Promise<void> {
    const a = this.agent();
    if (!a || !this.configDirty() || this.configSaving()) return;
    this.configSaving.set(true);
    const saved = await this.agents.updateConfig(a.id, this.configDraft());
    this.configSaving.set(false);
    if (!saved || this.agent()?.id !== a.id) return;
    this.configSaved.set(true);
    setTimeout(() => this.configSaved.set(false), 1800);
  }

  protected ping(): void {
    const a = this.agent();
    if (!a) return;
    this.pinging.set(true);
    this.agents.pingIntegrations(a.id);
    setTimeout(() => this.pinging.set(false), 1100);
  }

  protected openPause(): void {
    this.pauseReason = '';
    this.pauseOpen.set(true);
  }

  protected async confirmPause(): Promise<void> {
    const a = this.agent();
    if (!a || this.pausing()) return;
    this.pausing.set(true);
    const ok = await this.agents.pause(a.id, this.pauseReason.trim() || undefined);
    this.pausing.set(false);
    if (ok) this.pauseOpen.set(false);
  }

  protected async resume(): Promise<void> {
    const a = this.agent();
    if (!a || this.pausing()) return;
    this.pausing.set(true);
    await this.agents.resume(a.id);
    this.pausing.set(false);
  }

  protected async remove(): Promise<void> {
    const a = this.agent();
    if (!a) return;
    if (!await this.confirm.ask({
      title: 'delete profile',
      message: `Deletes ${a.name} — its config, memory, sessions, skills, jobs, and webhooks. Cannot be undone.`,
      typed: a.name,
      action: 'delete permanently',
    })) return;
    // the roster is where the operator belongs either way: a refusal toasts
    // there, and waiting here would leave them on a page about to be empty
    void this.removal.remove(a.id);
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
    const id = await this.templates.capture(a.id, name);
    this.capturingBusy.set(false);
    if (id) {
      this.capturing.set(false);
      this.ctx.toast(`saved template "${name}"`);
      this.router.navigate(['/profiles']);
    }
  }
}
