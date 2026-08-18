const esc = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/**
 * Escapes `text` for safe innerHTML, wrapping case-insensitive matches of
 * `query` in `<mark class="jt-hit">`. The result is already HTML-escaped, so
 * callers can trust it via DomSanitizer without XSS risk.
 */
export function highlightHtml(text: string | null | undefined, query: string): string {
  const t = text ?? '';
  const q = (query ?? '').trim();
  if (!q) return esc(t);
  const lc = t.toLowerCase();
  const ql = q.toLowerCase();
  let out = '';
  let i = 0;
  let idx = lc.indexOf(ql, i);
  while (idx >= 0) {
    out += esc(t.slice(i, idx)) + '<mark class="jt-hit">' + esc(t.slice(idx, idx + q.length)) + '</mark>';
    i = idx + q.length;
    idx = lc.indexOf(ql, i);
  }
  return out + esc(t.slice(i));
}

/**
 * Non-overlapping, case-insensitive occurrence count — counts exactly what
 * {@link highlightHtml} marks, so a "3/7" toolbar can be computed without
 * waiting for the marks to paint.
 */
export function countMatches(text: string | null | undefined, query: string): number {
  if (!query) return 0;
  const t = (text ?? '').toLowerCase();
  const q = query.toLowerCase();
  let n = 0;
  let i = t.indexOf(q);
  while (i >= 0) { n++; i = t.indexOf(q, i + q.length); }
  return n;
}

/** Primitive rendering used by the JSON tree (must match json-tree's primText). */
function primText(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'string') return JSON.stringify(value);
  return String(value);
}

/**
 * Counts query matches exactly as `mc-json-tree` highlights them — key plus
 * value per node, including the `{n}`/`[n]` collapsed summaries — so a toolbar
 * count agrees with the marks in the DOM. `key` is null at the root.
 */
export function countJsonMatches(value: unknown, key: string | null, query: string): number {
  let n = key !== null ? countMatches(key, query) : 0;
  if (Array.isArray(value)) {
    n += countMatches(`[${value.length}]`, query);
    value.forEach((v, i) => { n += countJsonMatches(v, String(i), query); });
  } else if (value !== null && typeof value === 'object') {
    const keys = Object.keys(value as Record<string, unknown>);
    n += countMatches(`{${keys.length}}`, query);
    for (const k of keys) n += countJsonMatches((value as Record<string, unknown>)[k], k, query);
  } else {
    n += countMatches(primText(value), query);
  }
  return n;
}
