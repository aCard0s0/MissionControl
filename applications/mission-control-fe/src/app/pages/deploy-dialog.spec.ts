import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ActivityStore } from '../core/store/activity-store';
import { AgentStore } from '../core/store/agent-store';
import { AgentRef } from '../core/api/agent-ref';
import { DeployedPart } from '../core/models';
import { DeployDialog } from './deploy-dialog';
import { buttonWith, el, settle, text } from '../testing/dom';

const part = (patch: Partial<DeployedPart> = {}): DeployedPart =>
  ({ kind: 'skill', name: 'pdf-tools', status: 'deployed', detail: '', ...patch });

const AGENT = { id: 'c1--ops', name: 'ops', containerId: 'c1' };
const REF = { hostId: 'dh-1', containerId: 'c1', name: 'ops' };

/** A host, so the projected explanation is exercised the way a page supplies it. */
@Component({
  imports: [DeployDialog],
  template: `<mc-deploy-dialog [label]="'pdf-tools'" noun="skill" [run]="run"
                               (closed)="closed = closed + 1">
    <p>the projected explanation</p>
  </mc-deploy-dialog>`,
})
class Host {
  run: (agent: AgentRef) => Promise<DeployedPart[] | null> = () => Promise.resolve([part()]);
  closed = 0;
}

const render = (
  run?: (agent: AgentRef) => Promise<DeployedPart[] | null>,
  agents: Record<string, unknown> = {},
) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      {
        provide: AgentStore,
        useValue: {
          agents: signal([AGENT]),
          resolve: vi.fn().mockReturnValue({ agent: AGENT, ref: REF }),
          ...agents,
        },
      },
      { provide: ActivityStore, useValue: { run: (_: string, w: () => unknown) => w() } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  if (run) fixture.componentInstance.run = run;
  fixture.detectChanges();
  return fixture;
};

const chooseAgent = async (fixture: ReturnType<typeof render>, id: string) => {
  const select = el(fixture).querySelector<HTMLSelectElement>('#deploy-agent')!;
  select.value = id;
  select.dispatchEvent(new Event('change'));
  await settle(fixture);
};

const deployNow = async (fixture: ReturnType<typeof render>) => {
  await chooseAgent(fixture, AGENT.id);
  buttonWith(fixture, 'deploy skill').click();
  await settle(fixture);
};

describe('DeployDialog', () => {
  it('shows the explanation its caller projected', () => {
    const fixture = render();

    expect(text(fixture)).toContain('the projected explanation');
  });

  it('will not deploy until an agent is chosen', async () => {
    const run = vi.fn().mockResolvedValue([]);
    const fixture = render(run);

    expect(buttonWith(fixture, 'deploy skill').disabled).toBe(true);
    await chooseAgent(fixture, AGENT.id);

    expect(buttonWith(fixture, 'deploy skill').disabled).toBe(false);
    expect(run).not.toHaveBeenCalled();
  });

  it('hands the deploy the profile the chosen agent resolves to, not just its container', async () => {
    const run = vi.fn().mockResolvedValue([]);
    const fixture = render(run);

    await deployNow(fixture);

    expect(run).toHaveBeenCalledWith(REF);
  });

  it('closes on a success with nothing to enumerate', async () => {
    // one skill either landed or it did not; there is no report worth reading
    const fixture = render(() => Promise.resolve([]));

    await deployNow(fixture);

    expect(fixture.componentInstance.closed).toBe(1);
  });

  it('stays open on a report and says what each part did', async () => {
    // closing on a green tick would hide exactly the case worth seeing
    const fixture = render(() => Promise.resolve([
      part(),
      part({ kind: 'mcp', name: 'postgres', status: 'failed', detail: 'not running' }),
    ]));

    await deployNow(fixture);

    expect(fixture.componentInstance.closed).toBe(0);
    expect(text(fixture)).toContain('1 of 2 parts landed');
    expect(text(fixture)).toContain('postgres');
    expect(text(fixture)).toContain('not running');
    expect(el(fixture).querySelectorAll('.report li').length).toBe(2);
  });

  it('says nothing was rolled back when a part did not land', async () => {
    const fixture = render(() => Promise.resolve([part({ status: 'failed', detail: 'nope' })]));

    await deployNow(fixture);

    expect(text(fixture)).toContain('nothing was rolled back');
  });

  it('carries a part status as a word, not only as a colour', async () => {
    const fixture = render(() => Promise.resolve([part({ status: 'skipped', detail: 'gone' })]));

    await deployNow(fixture);

    expect(text(fixture)).toContain('skipped');
  });

  it('does not offer to deploy again from the report, so a click cannot double-apply', async () => {
    const fixture = render(() => Promise.resolve([part()]));

    await deployNow(fixture);

    const labels = Array.from(el(fixture).querySelectorAll('button'))
      .map(b => (b.textContent ?? '').trim());
    expect(labels).not.toContain('deploy skill');
    expect(labels).toContain('close');
  });

  it('says so when the deploy failed outright', async () => {
    const fixture = render(() => Promise.resolve(null));

    await deployNow(fixture);

    expect(text(fixture)).toContain('nothing was deployed');
    expect(fixture.componentInstance.closed).toBe(0);
  });

  it('says so when there is no agent to deploy to', () => {
    const fixture = render(undefined, { agents: signal([]) });

    expect(text(fixture)).toContain('no agents available');
  });

  it('does not touch its signals after the dialog is gone', async () => {
    // the deploy runs on the container and outlives the window it was started from
    let settleDeploy: (r: DeployedPart[] | null) => void = () => { /* replaced below */ };
    const fixture = render(() => new Promise<DeployedPart[] | null>(r => { settleDeploy = r; }));
    await chooseAgent(fixture, AGENT.id);

    buttonWith(fixture, 'deploy skill').click();
    fixture.destroy();
    settleDeploy([part()]);

    await expect(Promise.resolve()).resolves.toBeUndefined();
  });
});
