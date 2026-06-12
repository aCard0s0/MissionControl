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

export function clock(ts: number): string {
  return new Date(ts).toLocaleTimeString('en-GB', { hour12: false });
}

export function shortDate(ts: number): string {
  return new Date(ts).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
}

export function mb(v: number): string {
  return v >= 1024 ? `${(v / 1024).toFixed(1)} GB` : `${Math.round(v)} MB`;
}
