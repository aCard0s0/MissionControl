import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AgentStore } from './core/store/agent-store';
import { ContainerStore } from './core/store/container-store';
import { HostStore } from './core/store/host-store';
import { LiveSync } from './core/store/live-sync';
import { StoreContext } from './core/store/store-context';
import { NavIconView } from './shared/nav-icon';
import { Notifications } from './shared/notifications';
import { StatusDot } from './shared/status-dot';
import { TerminalPanel } from './shared/terminal-panel';
import { uptime } from './core/format';
import { Scrim } from './shared/scrim';

const NAV = [
  { path: '/containers', label: 'Containers', icon: 'box', exact: false },
  { path: '/overview', label: 'Overview', icon: 'chart', exact: false },
  { path: '/agents', label: 'Agents', icon: 'user', exact: false },
  { path: '/profiles', label: 'Blueprints', icon: 'layers', exact: false },
  { path: '/models', label: 'Models', icon: 'chip', exact: false },
  { path: '/credentials', label: 'Credentials', icon: 'key', exact: false },
  { path: '/mcp-servers', label: 'MCP Servers', icon: 'plug', exact: false },
  { path: '/prompts', label: 'Prompts', icon: 'message', exact: false },
  { path: '/skills', label: 'Skills', icon: 'wrench', exact: false },
  { path: '/board', label: 'Ops Board', icon: 'board', exact: false },
  { path: '/calendar', label: 'Calendar', icon: 'calendar', exact: false },
  { path: '/webhooks', label: 'Webhooks', icon: 'bolt', exact: false },
  { path: '/reference', label: 'CLI Reference', icon: 'terminal', exact: false },
  { path: '/server-logs', label: 'Server Logs', icon: 'doc', exact: false },
];

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NavIconView, Notifications, StatusDot, TerminalPanel, Scrim],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  host: { '[class.side-collapsed]': 'sideCollapsed()' },
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
  /**
   * The narrow-viewport drawer. Transient by design: it closes on navigation, because it
   * covers the page it navigated to.
   */
  protected readonly sideOpen = signal(false);

  /**
   * The wide-viewport collapse, which is a preference rather than a mode — so it persists,
   * and navigating must not undo it.
   *
   * <p>Deliberately not the same signal as {@link sideOpen}. Sharing one would mean either
   * the drawer stops closing on navigation, or every click on a nav item collapses a sidebar
   * the operator wants to keep.
   */
  protected readonly sideCollapsed = signal(this.savedCollapsed());
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

  protected toggleCollapsed(): void {
    this.sideCollapsed.update(v => {
      try { localStorage.setItem('mc-side-collapsed', String(!v)); } catch { /* private mode */ }
      return !v;
    });
  }

  private savedCollapsed(): boolean {
    try {
      return localStorage.getItem('mc-side-collapsed') === 'true';
    } catch { /* private mode */ }
    return false;
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
