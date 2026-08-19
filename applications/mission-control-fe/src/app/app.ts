import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AgentStore } from './core/store/agent-store';
import { ContainerStore } from './core/store/container-store';
import { HostStore } from './core/store/host-store';
import { LiveSync } from './core/store/live-sync';
import { StoreContext } from './core/store/store-context';
import { StatusDot } from './shared/status-dot';
import { TerminalPanel } from './shared/terminal-panel';
import { uptime } from './core/format';

const NAV = [
  { path: '/containers', label: 'Containers', exact: false },
  { path: '/overview', label: 'Overview', exact: false },
  { path: '/agents', label: 'Agents', exact: false },
  { path: '/profiles', label: 'Profiles', exact: false },
  { path: '/models', label: 'Models', exact: false },
  { path: '/mcp-servers', label: 'MCP Servers', exact: false },
  { path: '/board', label: 'Ops Board', exact: false },
  { path: '/calendar', label: 'Calendar', exact: false },
  { path: '/webhooks', label: 'Webhooks', exact: false },
];

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, StatusDot, TerminalPanel],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  protected readonly ctx = inject(StoreContext);
  protected readonly hosts = inject(HostStore);
  protected readonly liveSync = inject(LiveSync);
  protected readonly nav = NAV;
  protected readonly uptime = uptime;

  protected readonly now = signal(new Date());
  protected readonly pickerOpen = signal(false);
  protected readonly sideOpen = signal(false);
  protected readonly theme = signal<'dark' | 'light'>(this.savedTheme());

  protected readonly utc = computed(() =>
    this.now().toLocaleTimeString('en-GB', { hour12: false, timeZone: 'UTC' }) + ' UTC');
  protected readonly dateLine = computed(() =>
    this.now().toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' }).toUpperCase());

  /** What the fleet actually runs — never a literal, which goes stale on the first deploy. */
  protected readonly imageLine = computed(() => {
    const versions = new Set(this.containers.containers().map(c => c.version));
    if (!versions.size) return 'hermes-agent · no containers';
    return versions.size === 1
      ? `hermes-agent ${[...versions][0]}`
      : `hermes-agent · ${versions.size} versions`;
  });

  constructor() {
    // the header clock ticks for as long as the shell is mounted; stopping it on
    // destroy keeps a torn-down app (and a finished test) from being woken again
    const clock = setInterval(() => this.now.set(new Date()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(clock));
    effect(() => {
      const theme = this.theme();
      document.documentElement.dataset['theme'] = theme;
      try { localStorage.setItem('mc-theme', theme); } catch { /* private mode */ }
    });
  }

  protected toggleTheme(): void {
    this.theme.update(t => t === 'dark' ? 'light' : 'dark');
  }

  private savedTheme(): 'dark' | 'light' {
    try {
      if (localStorage.getItem('mc-theme') === 'light') return 'light';
    } catch { /* private mode */ }
    return 'dark';
  }

  protected toggleSide(): void {
    this.sideOpen.update(v => !v);
  }

  protected closeSide(): void {
    this.sideOpen.set(false);
  }

  protected pick(id: string): void {
    this.containers.select(id);
    this.pickerOpen.set(false);
    this.sideOpen.set(false);
  }
}
