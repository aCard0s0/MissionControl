import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'containers' },
  { path: 'containers', loadComponent: () => import('./pages/containers').then(m => m.ContainersPage), title: 'Containers · Mission Control' },
  { path: 'overview', loadComponent: () => import('./pages/overview').then(m => m.OverviewPage), title: 'Overview · Mission Control' },
  { path: 'agents', loadComponent: () => import('./pages/agents').then(m => m.AgentsPage), title: 'Agents · Mission Control' },
  { path: 'agents/:id', loadComponent: () => import('./pages/agent-detail').then(m => m.AgentDetailPage), title: 'Agent · Mission Control' },
  { path: 'profiles', loadComponent: () => import('./pages/agent-profiles').then(m => m.AgentProfilesPage), title: 'Blueprints · Mission Control' },
  { path: 'models', loadComponent: () => import('./pages/models').then(m => m.ModelsPage), title: 'Models · Mission Control' },
  { path: 'mcp-servers', loadComponent: () => import('./pages/mcp-servers').then(m => m.McpServersPage), title: 'MCP Servers · Mission Control' },
  { path: 'prompts', loadComponent: () => import('./pages/prompts').then(m => m.PromptsPage), title: 'Prompts · Mission Control' },
  { path: 'skills', loadComponent: () => import('./pages/skills').then(m => m.SkillsPage), title: 'Skills · Mission Control' },
  { path: 'board', loadComponent: () => import('./pages/board').then(m => m.BoardPage), title: 'Ops Board · Mission Control' },
  { path: 'calendar', loadComponent: () => import('./pages/calendar').then(m => m.CalendarPage), title: 'Calendar · Mission Control' },
  { path: 'webhooks', loadComponent: () => import('./pages/webhooks').then(m => m.WebhooksPage), title: 'Webhooks · Mission Control' },
  { path: 'reference', loadComponent: () => import('./pages/reference').then(m => m.ReferencePage), title: 'CLI Reference · Mission Control' },
  { path: 'server-logs', loadComponent: () => import('./pages/server-logs').then(m => m.ServerLogsPage), title: 'Server Logs · Mission Control' },
  { path: '**', redirectTo: 'containers' },
];
