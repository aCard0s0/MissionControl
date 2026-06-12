import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HermesStore } from './core/hermes-store';
import { StatusDot } from './shared/status-dot';
import { uptime } from './core/format';

const NAV = [
  { path: '/containers', label: 'Containers', exact: false },
  { path: '/overview', label: 'Overview', exact: false },
  { path: '/agents', label: 'Agents', exact: false },
  { path: '/board', label: 'Ops Board', exact: false },
  { path: '/calendar', label: 'Calendar', exact: false },
  { path: '/webhooks', label: 'Webhooks', exact: false },
];

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, StatusDot],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly store = inject(HermesStore);
  protected readonly nav = NAV;
  protected readonly uptime = uptime;

  protected readonly now = signal(new Date());
  protected readonly pickerOpen = signal(false);

  protected readonly utc = computed(() =>
    this.now().toLocaleTimeString('en-GB', { hour12: false, timeZone: 'UTC' }) + ' UTC');
  protected readonly dateLine = computed(() =>
    this.now().toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' }).toUpperCase());

  constructor() {
    setInterval(() => this.now.set(new Date()), 1000);
  }

  protected pick(id: string): void {
    this.store.selectContainer(id);
    this.pickerOpen.set(false);
  }
}
