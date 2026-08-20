/**
 * Copy `text` to the clipboard, reporting whether it landed.
 *
 * `navigator.clipboard` needs a secure context, and the default deploy flavor is plain HTTP on
 * a tailnet name (`http://mission-control.<tailnet>.ts.net`) — which is not one. localhost is,
 * so the dev and `--ts=off` flavors would work and the flavor most people actually run would
 * silently do nothing. Hence the execCommand fallback: deprecated, but it is the only copy path
 * that exists over plain HTTP, and a copy button that does nothing is worse than a deprecation.
 *
 * The textarea is positioned off-screen rather than hidden — `display:none` and
 * `visibility:hidden` are both unselectable, so the copy would fail.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch { /* denied, or no permission — fall through to the legacy path */ }
  return legacyCopy(text);
}

function legacyCopy(text: string): boolean {
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.top = '-1000px';
  area.style.opacity = '0';
  document.body.appendChild(area);
  try {
    area.select();
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    area.remove();
  }
}
