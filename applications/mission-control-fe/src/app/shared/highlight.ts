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
