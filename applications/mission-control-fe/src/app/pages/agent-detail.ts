import {
  ChangeDetectionStrategy, Component, computed, effect, inject, signal,
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

type Tab = 'overview' | 'soul' | 'skills' | 'mcp' | 'jobs' | 'activity' | 'files';

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
  protected readonly tabs: Tab[] = ['overview', 'soul', 'skills', 'mcp', 'jobs', 'activity', 'files'];

  protected soulDraft = signal('');
  protected readonly soulDirty = computed(() => this.soulDraft() !== (this.agent()?.soul ?? ''));
  protected readonly soulSaved = signal(false);

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
  // skill add form
  protected skillName = '';
  protected skillSource: 'hub' | 'user' = 'hub';

  constructor() {
    // reset the draft whenever a different agent loads
    effect(() => {
      const a = this.agent();
      this.soulDraft.set(a?.soul ?? '');
    });
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
    this.store.addMcp(a.id, name, this.mcpTransport);
    this.mcpName = '';
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
