import { describe, expect, it } from 'vitest';
import { applyPreset, compactAccess, emptyAccess, randomSecret } from './host-access';

describe('host access presets', () => {
  const fixed = () => 'deadbeefdeadbeefdeadbeefdeadbeef';

  it('the dashboard preset opens 9119 on loopback behind a generated password', () => {
    const a = applyPreset(emptyAccess(), 'dashboard', fixed);
    expect(a.ports).toEqual([{ containerPort: 9119, hostPort: 9119, hostIp: '127.0.0.1' }]);
    expect(a.env).toEqual([
      { key: 'HERMES_DASHBOARD', value: '1' },
      { key: 'HERMES_DASHBOARD_BASIC_AUTH_USERNAME', value: 'operator' },
      { key: 'HERMES_DASHBOARD_BASIC_AUTH_PASSWORD', value: fixed() },
    ]);
  });

  it('the API server preset makes hermes listen on every interface, or the port reaches nothing', () => {
    const a = applyPreset(emptyAccess(), 'api-server', fixed);
    expect(a.ports.map(p => p.containerPort)).toEqual([8642]);
    expect(a.env).toContainEqual({ key: 'API_SERVER_HOST', value: '0.0.0.0' });
    expect(a.env).toContainEqual({ key: 'API_SERVER_KEY', value: fixed() });
  });

  it('a preset never overwrites a row the operator already has', () => {
    const mine = { ...emptyAccess(), ports: [{ containerPort: 9119, hostPort: 19119, hostIp: '0.0.0.0' }],
      env: [{ key: 'HERMES_DASHBOARD_BASIC_AUTH_PASSWORD', value: 'chosen' }] };
    const a = applyPreset(applyPreset(mine, 'dashboard', fixed), 'dashboard', fixed);
    expect(a.ports).toEqual(mine.ports);
    expect(a.env.filter(e => e.key === 'HERMES_DASHBOARD_BASIC_AUTH_PASSWORD')).toEqual([{ key: 'HERMES_DASHBOARD_BASIC_AUTH_PASSWORD', value: 'chosen' }]);
    expect(a.env.length).toBe(3);
  });

  it('presets are pure and the repo preset adds a mount to fill in', () => {
    const before = emptyAccess();
    const a = applyPreset(before, 'repo');
    expect(before.mounts).toEqual([]);
    expect(a.mounts).toEqual([{ source: '', target: '/work', readOnly: false }]);
  });

  it('compacting drops unfinished rows and trims the rest', () => {
    const a = compactAccess({
      ports: [{ containerPort: 0, hostPort: 0, hostIp: '' }, { containerPort: 8644, hostPort: 8644, hostIp: ' 127.0.0.1 ' }],
      env: [{ key: '  ', value: 'x' }, { key: ' A ', value: '1' }],
      mounts: [{ source: '', target: '/work', readOnly: false }, { source: ' /srv/r ', target: ' /work ', readOnly: true }],
    });
    expect(a).toEqual({
      ports: [{ containerPort: 8644, hostPort: 8644, hostIp: '127.0.0.1' }],
      env: [{ key: 'A', value: '1' }],
      mounts: [{ source: '/srv/r', target: '/work', readOnly: true }],
    });
  });

  it('a generated secret is 32 hex characters and never repeats', () => {
    const one = randomSecret();
    expect(one).toMatch(/^[0-9a-f]{32}$/);
    expect(randomSecret()).not.toBe(one);
  });
});
