import { describe, expect, it } from 'vitest';
import { resizeFrame, terminalSocketUrl } from './terminal-socket';

/**
 * `apiBaseUrl` reaches this from `config.js`, which an operator or a Docker entrypoint writes
 * by hand — so it arrives in every shape a person writes a base URL in. The cases below are
 * the ones the previous `.replace(/^http/, 'ws')` got wrong: it answered correctly for an
 * absolute http(s) base and built an unusable URL for everything else, and a bad URL surfaces
 * only as a shell that never connects.
 */
describe('terminalSocketUrl', () => {
  it('speaks ws to an http backend and wss to an https one', () => {
    expect(terminalSocketUrl('http://mc.test', 'dh-local', 'c-1'))
      .toMatch(/^ws:\/\/mc\.test\/ws\/terminal\?/);
    expect(terminalSocketUrl('https://mc.test', 'dh-local', 'c-1'))
      .toMatch(/^wss:\/\/mc\.test\/ws\/terminal\?/);
  });

  it('resolves an empty base against the page, which is what same-origin means', () => {
    const url = new URL(terminalSocketUrl('', 'dh-local', 'c-1'));

    expect(url.protocol).toBe('ws:');
    expect(url.host).toBeTruthy();      // the page's, whatever the harness serves from
    expect(url.pathname).toBe('/ws/terminal');
    expect(url.search).toBe('?hostId=dh-local&containerId=c-1');
  });

  it('keeps a path the base carries, so a proxied deployment still resolves', () => {
    expect(terminalSocketUrl('http://mc.test/mission', 'dh-local', 'c-1'))
      .toMatch(/^ws:\/\/mc\.test\/mission\/ws\/terminal\?/);
  });

  // a trailing slash is the most common way to write a base, and ApiHttp already ignores it
  it('reads a base with a trailing slash as the same base', () => {
    expect(terminalSocketUrl('http://mc.test/', 'dh-local', 'c-1'))
      .toBe(terminalSocketUrl('http://mc.test', 'dh-local', 'c-1'));
  });

  // '//host' inherits the page's scheme; the old rewrite left it schemeless and unusable
  it('takes the page\'s scheme for a scheme-relative base', () => {
    expect(terminalSocketUrl('//mc.test', 'dh-local', 'c-1'))
      .toMatch(/^ws:\/\/mc\.test\/ws\/terminal\?/);
  });

  it('escapes the host and container it addresses', () => {
    expect(terminalSocketUrl('http://mc.test', 'dh/1', 'c 1'))
      .toContain('hostId=dh%2F1&containerId=c%201');
  });

  // not a right answer for a misconfigured deployment, but a well-formed one — which is what
  // makes it visible in a network panel instead of dying inside the WebSocket constructor
  it('reads a base the parser cannot stand alone as relative to the page', () => {
    const url = new URL(terminalSocketUrl('mc.test', 'dh-local', 'c-1'));

    expect(url.protocol).toBe('ws:');
    expect(url.pathname).toBe('/mc.test/ws/terminal');
  });
});

describe('resizeFrame', () => {
  // the one structured frame in the protocol; the backend matches on `type`
  it('is the text frame the backend reads a new grid from', () => {
    expect(resizeFrame(120, 30)).toBe('{"type":"resize","cols":120,"rows":30}');
  });
});
