import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesApi } from './hermes-api';

// Every method on HermesApi is a one-line delegation to the private `req`, so
// these exercise `req` through whichever caller reaches the branch: URL
// composition, the error-body unwrap, and the empty-body case are the only
// places the client can decide anything.

interface Call { url: string; init: RequestInit }

describe('HermesApi request handling', () => {
  let calls: Call[];

  const respond = (body: BodyInit | null, init: ResponseInit = {}) => {
    calls = [];
    vi.stubGlobal('fetch', vi.fn((url: string, i: RequestInit) => {
      calls.push({ url, init: i });
      return Promise.resolve(new Response(body, init));
    }));
  };

  beforeEach(() => { calls = []; });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('strips trailing slashes from the base so paths never double up', () => {
    respond('[]');
    new HermesApi('http://mc.local///').hosts();
    expect(calls[0].url).toBe('http://mc.local/api/hosts');
  });

  it('treats an empty base as same-origin, which is how the combined image ships', () => {
    respond('[]');
    new HermesApi('').hosts();
    expect(calls[0].url).toBe('/api/hosts');
  });

  it('sends JSON content-type on every request, including bodyless ones', async () => {
    respond('{}');
    await new HermesApi('').checkHost('h1');
    expect((calls[0].init.headers as Record<string, string>)['Content-Type'])
      .toBe('application/json');
    expect(calls[0].init.method).toBe('POST');
  });

  it('parses a JSON body into the typed result', async () => {
    respond(JSON.stringify({ status: 'ok', version: '1.2.3', dockerConnected: true }));
    await expect(new HermesApi('').health())
      .resolves.toEqual({ status: 'ok', version: '1.2.3', dockerConnected: true });
  });

  it('resolves undefined for an empty body instead of throwing on JSON.parse', async () => {
    // 204s and bodyless 200s are the normal reply to DELETE across this API;
    // JSON.parse('') would turn every successful delete into a failure
    respond(null, { status: 204 });
    await expect(new HermesApi('').deleteHost('h1')).resolves.toBeUndefined();
  });

  it('surfaces the backend error message rather than a bare status code', async () => {
    respond(JSON.stringify({ error: 'host still owns two containers' }), { status: 409 });
    await expect(new HermesApi('').deleteHost('h1'))
      .rejects.toThrow('host still owns two containers');
  });

  it('falls back to the status code when the error body is not JSON', async () => {
    // an nginx/gateway 502 is HTML, and JSON.parse on it must not mask the failure
    respond('<html>502 Bad Gateway</html>', { status: 502 });
    await expect(new HermesApi('').hosts()).rejects.toThrow('502');
  });

  it('falls back to the status code when the JSON body carries no error field', async () => {
    respond(JSON.stringify({ message: 'nope' }), { status: 500 });
    await expect(new HermesApi('').hosts()).rejects.toThrow('500');
  });

  it('rejects on any non-2xx, even one carrying a well-formed payload', async () => {
    respond(JSON.stringify([{ id: 'h1' }]), { status: 403 });
    await expect(new HermesApi('').hosts()).rejects.toThrow('403');
  });

  it('serializes the request body the backend expects', async () => {
    respond('{}');
    await new HermesApi('').addHost('prod', 'tcp://10.0.0.2:2375');
    expect(calls[0].init.method).toBe('POST');
    expect(JSON.parse(calls[0].init.body as string))
      .toEqual({ name: 'prod', url: 'tcp://10.0.0.2:2375' });
  });
});

describe('HermesApi path composition', () => {
  let urls: string[];

  beforeEach(() => {
    urls = [];
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      urls.push(url);
      return Promise.resolve(new Response('{}'));
    }));
  });

  afterEach(() => { vi.unstubAllGlobals(); });

  it('percent-encodes identifiers so a slash cannot walk out of its path segment', async () => {
    // profile names come from operator input; an unescaped '/' or '..' would
    // address a different endpoint than the one the caller named
    const api = new HermesApi('');
    await api.deleteAgent('h/1', 'c 2', '../admin');
    expect(urls[0]).toBe('/api/agents/h%2F1/c%202/..%2Fadmin');
  });

  it('encodes a catalog server id in both the path and the operation suffix', async () => {
    const api = new HermesApi('');
    await api.startMcpServer('srv/one');
    await api.mcpServerLogs('srv/one', 50);
    expect(urls[0]).toBe('/api/mcp-servers/srv%2Fone/start');
    expect(urls[1]).toBe('/api/mcp-servers/srv%2Fone/logs?tail=50');
  });

  it('routes each mcp lifecycle verb to its own operation, not a shared toggle', async () => {
    const api = new HermesApi('');
    await api.startMcpServer('s1');
    await api.stopMcpServer('s1');
    await api.applyMcpServer('s1');
    await api.checkMcpServer('s1');
    expect(urls).toEqual([
      '/api/mcp-servers/s1/start', '/api/mcp-servers/s1/stop',
      '/api/mcp-servers/s1/apply', '/api/mcp-servers/s1/check',
    ]);
  });

  it('defaults the log tail rather than fetching the whole buffer', async () => {
    const api = new HermesApi('');
    await api.mcpServerLogs('s1');
    await api.logs('h1', 'c1');
    await api.agentLogs('h1', 'c1', 'ops');
    expect(urls.every(u => u.includes('tail=100'))).toBe(true);
  });
});

describe('HermesApi timeouts', () => {
  let timeouts: number[];

  beforeEach(() => {
    timeouts = [];
    const real = AbortSignal.timeout.bind(AbortSignal);
    vi.spyOn(AbortSignal, 'timeout').mockImplementation((ms: number) => {
      timeouts.push(ms);
      return real(ms);
    });
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('{}'))));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('bounds an ordinary request so a slow backend cannot pile up pending polls', async () => {
    await new HermesApi('').containers();
    expect(timeouts).toEqual([15_000]);
  });

  it('gives a container update far longer, because a cold host pulls the image first', async () => {
    // 15s here would abort a legitimate update mid-pull and leave the operator
    // staring at a failure while the recreate is still running on the host
    await new HermesApi('').updateContainer('h1', 'c1', 'v2026.7.7');
    expect(timeouts).toEqual([300_000]);
  });
});
