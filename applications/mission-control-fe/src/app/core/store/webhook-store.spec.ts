import { describe, expect, it, vi } from 'vitest';
import { AgentProfile, Webhook } from '../models';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';
import { WebhookStore } from './webhook-store';

const context = () =>
  new StoreContext({ apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' });

const agent = (id: string, containerId = 'c-1'): AgentProfile => ({
  id, containerId, name: id, role: '', state: 'idle', provider: 'nous', model: 'm',
  apiKeyMasked: '', cwd: '', soul: '', memoryMd: '', configYaml: '', skills: [], mcp: [],
  integrations: [], sessions: [], msgsToday: 0, tokensToday: 0, errorRate: 0, lastActive: 0,
});

const hook = (id: string, agentId: string): Webhook => ({
  id, agentId, name: `hook ${id}`, slug: `/hooks/${id}`, secretMasked: '…abcd',
  events: ['*'], active: true, deliveries: [],
});

const store = (hooks: Webhook[] = [], agents: AgentProfile[] = [agent('a-1')]) => {
  const ctx = context();
  const containers = new ContainerStore(ctx);
  const agentStore = new AgentStore(ctx, containers);
  agentStore.agents.set(agents);
  const webhooks = new WebhookStore(ctx, agentStore);
  webhooks.webhooks.set(hooks);
  return { ctx, containers, webhooks };
};

// Like jobs, inbound webhooks have no endpoint yet.
describe('WebhookStore', () => {
  it('holds nothing, because nothing serves it', () => {
    const ctx = context();
    const agents = new AgentStore(ctx, new ContainerStore(ctx));
    expect(new WebhookStore(ctx, agents).webhooks()).toEqual([]);
  });

  it('says so instead of registering a hook nothing would deliver to', () => {
    const { ctx, webhooks } = store();

    webhooks.add('a-1', 'Grafana alerts', '/hooks/atlas/grafana', ['alert.firing']);

    expect(webhooks.webhooks()).toEqual([]);
    expect(ctx.liveError()).toContain('not available');
  });

  it('shows only the hooks owned by the selected container\'s profiles', () => {
    const { containers, webhooks } = store(
      [hook('w-1', 'a-1'), hook('w-2', 'a-2')],
      [agent('a-1', 'c-1'), agent('a-2', 'c-2')]);

    containers.select('c-1');
    expect(webhooks.forSelectedContainer().map(w => w.id)).toEqual(['w-1']);

    containers.select('c-2');
    expect(webhooks.forSelectedContainer().map(w => w.id)).toEqual(['w-2']);
  });

  it('enables and disables one hook', () => {
    const { webhooks } = store([hook('w-1', 'a-1')]);

    webhooks.toggle('w-1');
    expect(webhooks.webhooks()[0].active).toBe(false);

    webhooks.toggle('w-1');
    expect(webhooks.webhooks()[0].active).toBe(true);
  });

  it('removes one hook by id', () => {
    const { webhooks } = store([hook('w-1', 'a-1'), hook('w-2', 'a-1')]);

    webhooks.remove('w-1');

    expect(webhooks.webhooks().map(w => w.id)).toEqual(['w-2']);
  });

  it('drops the hooks of a profile that is gone, one or many', () => {
    const { webhooks } = store([hook('w-1', 'a-1'), hook('w-2', 'a-2'), hook('w-3', 'a-3')]);

    webhooks.dropByAgent('a-1');
    expect(webhooks.webhooks().map(w => w.id)).toEqual(['w-2', 'w-3']);

    webhooks.dropByAgents(new Set(['a-2', 'a-3']));
    expect(webhooks.webhooks()).toEqual([]);
  });
});
