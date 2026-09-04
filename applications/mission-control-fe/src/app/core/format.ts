const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

export function uptime(startedAt: number | null): string {
  if (!startedAt) return '—';
  const d = Date.now() - startedAt;
  if (d < HOUR) return `${Math.floor(d / MIN)}m`;
  if (d < DAY) return `${Math.floor(d / HOUR)}h ${Math.floor((d % HOUR) / MIN)}m`;
  return `${Math.floor(d / DAY)}d ${Math.floor((d % DAY) / HOUR)}h`;
}

/**
 * Second-resolution elapsed, for an operation an operator is actively waiting on.
 *
 * Deliberately not {@link uptime}, which starts at minutes: a deploy reads as
 * '0m' for its whole first minute, which is exactly when the operator is asking
 * whether anything is happening at all.
 */
export function elapsed(since: number): string {
  const d = Math.max(0, Date.now() - since);
  return d < MIN
    ? `${Math.floor(d / 1000)}s`
    : `${Math.floor(d / MIN)}m ${Math.floor((d % MIN) / 1000)}s`;
}

export function ago(ts: number | null): string {
  if (!ts) return 'never';
  const d = Date.now() - ts;
  if (d < MIN) return 'just now';
  if (d < HOUR) return `${Math.floor(d / MIN)}m ago`;
  if (d < DAY) return `${Math.floor(d / HOUR)}h ago`;
  return `${Math.floor(d / DAY)}d ago`;
}

export function until(ts: number): string {
  const d = ts - Date.now();
  if (d <= 0) return 'due';
  if (d < MIN) return '<1m';
  if (d < HOUR) return `in ${Math.floor(d / MIN)}m`;
  if (d < DAY) return `in ${Math.floor(d / HOUR)}h ${Math.floor((d % HOUR) / MIN)}m`;
  return `in ${Math.floor(d / DAY)}d`;
}

/** `13:05:09`, in UTC — the header clock is UTC, and so are the container's own log lines,
 *  so a local clock beside either read as a different moment. */
export function clock(ts: number): string {
  return new Date(ts).toLocaleTimeString('en-GB', { hour12: false, timeZone: 'UTC' });
}

export function shortDate(ts: number): string {
  return new Date(ts).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
}

/** `APRIL 2026` — the month a calendar grid is showing. */
export function monthStamp(date: Date): string {
  return date.toLocaleDateString('en-GB', { month: 'long', year: 'numeric' }).toUpperCase();
}

/** `WED 15 APR` — one day, as the calendar's detail pane heads it. */
export function dayStamp(date: Date): string {
  const weekday = date.toLocaleDateString('en-GB', { weekday: 'short' });
  return `${weekday} ${shortDate(date.getTime())}`.toUpperCase();
}

/**
 * Date and time for a log line. A tail can span midnight — and a stopped container's
 * tail is often days old — so a bare clock reads as "today" for entries that are not.
 */
export function logStamp(ts: number): string {
  const day = new Date(ts).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', timeZone: 'UTC' });
  return `${day} ${clock(ts)}`;
}

/**
 * A share as a whole percentage, or `—` while there is nothing to divide by. RAM before the
 * first stats sample has a total of zero, and `NaN%` is not a reading anyone can act on.
 */
export function pct(part: number, total: number): string {
  const share = part / total * 100;
  return total > 0 && Number.isFinite(share) ? `${Math.round(share)}%` : '—';
}

export function mb(v: number): string {
  return v >= 1024 ? `${(v / 1024).toFixed(1)} GB` : `${Math.round(v)} MB`;
}
