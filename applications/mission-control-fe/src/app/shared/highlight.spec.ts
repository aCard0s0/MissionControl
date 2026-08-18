import { describe, expect, it } from 'vitest';
import { countJsonMatches, countMatches, highlightHtml } from './highlight';

// This output is handed to DomSanitizer.bypassSecurityTrustHtml and rendered via
// [innerHTML] in json-tree and agent-detail — so it is the only thing standing
// between an agent's tool output (or an MCP server's JSON) and script execution.
describe('highlightHtml escaping', () => {
  it('neutralizes a tag that would otherwise execute once innerHTML renders it', () => {
    const out = highlightHtml('<img src=x onerror=alert(1)>', '');
    expect(out).toBe('&lt;img src=x onerror=alert(1)&gt;');
    expect(out).not.toContain('<img');
  });

  it('escapes the payload even when it is the search hit itself', () => {
    const out = highlightHtml('before <script>evil()</script> after', '<script>');
    expect(out).toBe('before <mark class="jt-hit">&lt;script&gt;</mark>evil()&lt;/script&gt; after');
    expect(out).not.toContain('<script>');
  });

  it('escapes quotes, so a hit inside a JSON string cannot break out of an attribute', () => {
    expect(highlightHtml('{"k":"v"}', '')).toBe('{&quot;k&quot;:&quot;v&quot;}');
  });

  it('escapes the ampersand first, so pre-escaped text renders literally instead of decoding', () => {
    // &lt; must survive as text. Escaping < before & would emit &lt; and the
    // browser would decode it back into a real angle bracket.
    expect(highlightHtml('&lt;script&gt;', '')).toBe('&amp;lt;script&amp;gt;');
  });

  it('escapes every segment around a hit, not only the first', () => {
    const out = highlightHtml('<a>x<b>x<c>', 'x');
    expect(out).toBe('&lt;a&gt;<mark class="jt-hit">x</mark>&lt;b&gt;<mark class="jt-hit">x</mark>&lt;c&gt;');
  });
});

describe('highlightHtml matching', () => {
  it('marks case-insensitively while preserving the text as written', () => {
    expect(highlightHtml('Error ERROR error', 'error')).toBe(
      '<mark class="jt-hit">Error</mark> <mark class="jt-hit">ERROR</mark> <mark class="jt-hit">error</mark>');
  });

  it('advances past each hit, so overlapping runs are counted once — agent-detail occ() mirrors this', () => {
    expect(highlightHtml('aaaa', 'aa')).toBe(
      '<mark class="jt-hit">aa</mark><mark class="jt-hit">aa</mark>');
  });

  it('treats the query literally, so regex metacharacters search for themselves', () => {
    // indexOf, not RegExp: a query of ".*" must not match everything, and no
    // user-supplied pattern can be turned into a catastrophic backtrack.
    expect(highlightHtml('a.*b', '.*')).toBe('a<mark class="jt-hit">.*</mark>b');
    expect(highlightHtml('abc', '.*')).toBe('abc');
  });

  it('trims the query, so a half-typed search with a trailing space still hits', () => {
    expect(highlightHtml('find me', '  me  ')).toBe('find <mark class="jt-hit">me</mark>');
  });

  it('marks nothing for a blank or whitespace-only query rather than matching everywhere', () => {
    expect(highlightHtml('<b>text</b>', '')).toBe('&lt;b&gt;text&lt;/b&gt;');
    expect(highlightHtml('<b>text</b>', '   ')).toBe('&lt;b&gt;text&lt;/b&gt;');
  });

  it('tolerates an absent query, which a template binding supplies before the user types', () => {
    expect(highlightHtml('<b>x</b>', undefined as unknown as string)).toBe('&lt;b&gt;x&lt;/b&gt;');
  });

  it('returns empty for absent text rather than printing null into the DOM', () => {
    expect(highlightHtml(null, 'q')).toBe('');
    expect(highlightHtml(undefined, 'q')).toBe('');
    expect(highlightHtml(null, '')).toBe('');
  });
});

// The toolbar renders "3/7" from these counts while the marks are painted by
// highlightHtml (chat) or json-tree (JSON view). If they disagree, the operator
// pages past hits that don't exist — so the counts are pinned to the markup.
describe('countMatches', () => {
  const marks = (html: string): number => (html.match(/<mark class="jt-hit">/g) ?? []).length;

  it('agrees with the number of marks highlightHtml paints', () => {
    for (const [text, query] of [
      ['alpha beta alpha', 'alpha'],
      ['aaaa', 'aa'],
      ['<script>x</script>', 'script'],
      ['Ends with hit', 'hit'],
    ] as const) {
      expect(countMatches(text, query)).toBe(marks(highlightHtml(text, query)));
    }
  });

  it('counts non-overlapping hits, as the highlighter consumes them', () => {
    expect(countMatches('aaaa', 'aa')).toBe(2);
  });

  it('ignores case on both sides', () => {
    expect(countMatches('Hermes hermes HERMES', 'hermes')).toBe(3);
  });

  it('counts nothing for an empty query or absent text', () => {
    expect(countMatches('anything', '')).toBe(0);
    expect(countMatches(null, 'x')).toBe(0);
    expect(countMatches(undefined, 'x')).toBe(0);
  });
});

describe('countJsonMatches', () => {
  it('counts the collapsed summaries the tree shows, not just the data', () => {
    // the array row renders "[2]", so a query of "2" hits the summary and the value
    expect(countJsonMatches([1, 2], null, '2')).toBe(2);
    expect(countJsonMatches({ a: 1, b: 2, c: 3 }, null, '3')).toBe(2);
  });

  it('counts string values quoted, the way the tree prints them', () => {
    expect(countJsonMatches({ role: 'user' }, null, 'user')).toBe(1);
    expect(countJsonMatches({ role: 'user' }, null, '"user"')).toBe(1);
  });

  it('counts keys, including array indices', () => {
    expect(countJsonMatches({ tool: null }, null, 'tool')).toBe(1);
    expect(countJsonMatches(['zero'], null, '0')).toBe(1);
  });

  it('skips the root key, which the tree renders without one', () => {
    expect(countJsonMatches('x', null, 'x')).toBe(1);
    expect(countJsonMatches('x', 'x', 'x')).toBe(2);
  });

  it('renders null and numbers unquoted, so those spellings are what match', () => {
    expect(countJsonMatches({ error: null }, null, 'null')).toBe(1);
    expect(countJsonMatches({ latencyMs: 120 }, null, '120')).toBe(1);
    expect(countJsonMatches({ latencyMs: 120 }, null, '"120"')).toBe(0);
  });
});
