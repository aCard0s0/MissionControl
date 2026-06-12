import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'containers' },
  { path: 'containers', loadComponent: () => import('./pages/containers').then(m => m.ContainersPage), title: 'Containers · Mission Control' },
  { path: 'overview', loadComponent: () => import('./pages/overview').then(m => m.OverviewPage), title: 'Overview · Mission Control' },
  { path: 'agents', loadComponent: () => import('./pages/agents').then(m => m.AgentsPage), title: 'Agents · Mission Control' },
  { path: 'agents/:id', loadComponent: () => import('./pages/agent-detail').then(m => m.AgentDetailPage), title: 'Agent · Mission Control' },
  { path: 'board', loadComponent: () => import('./pages/board').then(m => m.BoardPage), title: 'Ops Board · Mission Control' },
  { path: 'calendar', loadComponent: () => import('./pages/calendar').then(m => m.CalendarPage), title: 'Calendar · Mission Control' },
  { path: 'webhooks', loadComponent: () => import('./pages/webhooks').then(m => m.WebhooksPage), title: 'Webhooks · Mission Control' },
  { path: '**', redirectTo: 'containers' },
];
