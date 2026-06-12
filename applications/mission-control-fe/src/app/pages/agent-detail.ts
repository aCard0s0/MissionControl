import {
  ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago, clock, until } from '../core/format';
import { McpServer } from '../core/models';
import { ApiAgentSetup } from '../core/hermes-api';

type Tab = 'overview' | 'setup' | 'soul' | 'skills' | 'mcp' | 'jobs' | 'activity' | 'files';

@Component({
  selector: 'mc-agent-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot, Reveal],
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
  protected readonly tabs: Tab[] = ['overview', 'setup', 'soul', 'skills', 'mcp', 'jobs', 'activity', 'files'];

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

  protected configDraft = signal('');
  protected readonly configDirty = computed(() => this.configDraft() !== (this.agent()?.configYaml ?? ''));
  protected readonly configSaved = signal(false);

  protected readonly agentJobs = computed(() =>
    this.store.containerJobs().filter(j => j.agentId === this.id()));

  protected readonly agentLogs = computed(() =>
    this.store.containerLogs().filter(l => l.agentId === this.id()).slice(0, 30));

  protected readonly pinging = signal(false);
  protected readonly removing = signal(false);
  protected confirmText = '';

  protected fileView = signal<'SOUL.md' | 'MEMORY.md' | 'config.yaml'>('SOUL.md');

  // mcp add form
  protected mcpName = '';
  protected mcpTransport: McpServer['transport'] = 'http';
  protected mcpUrl = '';
  protected mcpCommand = '';
  protected mcpArgs = '';
  // skill add form
  protected skillName = '';
  protected skillSource: 'hub' | 'user' = 'hub';

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
        if (untracked(this.tab) === 'setup') untracked(() => void this.loadSetup());
      }
      lastId = id;
      lastSoul = soul;
      lastConfig = config;
    });
  }

  protected selectTab(t: Tab): void {
    this.tab.set(t);
    if (t === 'setup' && !this.setup() && !this.setupLoading()) void this.loadSetup();
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

  protected saveSoul(): void {
    const a = this.agent();
    if (!a || !this.soulDirty()) return;
    this.store.updateSoul(a.id, this.soulDraft());
    this.soulSaved.set(true);
    setTimeout(() => this.soulSaved.set(false), 1800);
  }

  protected saveConfig(): void {
    const a = this.agent();
    if (!a || !this.configDirty()) return;
    this.store.updateAgentConfig(a.id, this.configDraft());
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

  protected addMcp(): void {
    const a = this.agent();
    const name = this.mcpName.trim();
    if (!a || !name) return;
    if (this.mcpTransport === 'stdio') {
      const cmd = this.mcpCommand.trim();
      if (!cmd) return;
      this.store.addMcp(a.id, name, this.mcpTransport, { command: cmd, args: this.mcpArgs.trim() || undefined });
    } else {
      const url = this.mcpUrl.trim();
      if (!url) return;
      this.store.addMcp(a.id, name, this.mcpTransport, { url });
    }
    this.mcpName = '';
    this.mcpUrl = '';
    this.mcpCommand = '';
    this.mcpArgs = '';
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

  protected confirmRemove(): void {
    const a = this.agent();
    if (!a || this.confirmText !== a.name) return;
    this.store.removeAgent(a.id);
    this.router.navigate(['/agents']);
  }
}
