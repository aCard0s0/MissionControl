import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HermesStore } from './core/hermes-store';
import { StatusDot } from './shared/status-dot';
import { TerminalPanel } from './shared/terminal-panel';
import { uptime } from './core/format';

const NAV = [
  { path: '/containers', label: 'Containers', exact: false },
  { path: '/overview', label: 'Overview', exact: false },
  { path: '/agents', label: 'Agents', exact: false },
  { path: '/models', label: 'Models', exact: false },
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
  protected readonly store = inject(HermesStore);
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

  constructor() {
    setInterval(() => this.now.set(new Date()), 1000);
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
    this.store.selectContainer(id);
    this.pickerOpen.set(false);
    this.sideOpen.set(false);
  }
}
