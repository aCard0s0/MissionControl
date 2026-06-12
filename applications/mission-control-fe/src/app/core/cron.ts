// Human annotation for Hermes scheduler expressions. Hermes accepts:
// 5-field cron ("0 9 * * 1-5"), "every …" phrases ("every monday 9am"),
// durations ("30m" = run once after the delay), and ISO timestamps.

export interface CronField {
  label: string;
  value: string;
  desc: string;
}

export interface ScheduleHelp {
  valid: boolean;
  kind: 'cron' | 'every' | 'duration' | 'iso' | 'empty' | 'invalid';
  text: string;
  fields?: CronField[];
}

const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']; // index 0 and 7 = Sunday
const MONTHS = ['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const FIELD_SPECS = [
  { label: 'min', name: 'minute', min: 0, max: 59 },
  { label: 'hour', name: 'hour', min: 0, max: 23 },
  { label: 'day', name: 'day of month', min: 1, max: 31 },
  { label: 'month', name: 'month', min: 1, max: 12 },
  { label: 'weekday', name: 'day of week', min: 0, max: 7 },
] as const;

function name(idx: number, n: number): string {
  if (idx === 3) return MONTHS[n] ?? String(n);
  if (idx === 4) return DOW[n] ?? String(n);
  return String(n);
}

function describeField(idx: number, value: string): string | null {
  const spec = FIELD_SPECS[idx];
  const inRange = (n: number) => Number.isInteger(n) && n >= spec.min && n <= spec.max;

  if (value === '*') return `every ${spec.name}`;

  const step = value.match(/^\*\/(\d+)$/);
  if (step) {
    const n = +step[1];
    if (n < 1 || n > spec.max) return null;   // cron cannot express a step beyond the field range
    return n === 1 ? `every ${spec.name}` : `every ${n} ${spec.name}s`;
  }

  const range = value.match(/^(\d+)-(\d+)$/);
  if (range) {
    const [a, b] = [+range[1], +range[2]];
    return inRange(a) && inRange(b) && a <= b ? `${name(idx, a)}–${name(idx, b)}` : null;
  }

  if (/^\d+(,\d+)*$/.test(value)) {
    const ns = value.split(',').map(Number);
    return ns.every(inRange) ? ns.map(n => name(idx, n)).join(', ') : null;
  }

  return null;
}

function composeCronText(parts: string[], descs: string[]): string {
  const [min, hour, , , dow] = parts;
  const bits: string[] = [];

  if (/^\d+$/.test(min) && /^\d+$/.test(hour)) {
    bits.push(`at ${hour.padStart(2, '0')}:${min.padStart(2, '0')}`);
  } else {
    bits.push(`min: ${descs[0]}`, `hour: ${descs[1]}`);
  }

  // standard cron: when BOTH day-of-month and day-of-week are restricted,
  // the job fires when EITHER matches
  if (parts[2] !== '*' && dow !== '*') {
    bits.push(`on day ${descs[2]} of the month or on ${descs[4]}`);
  } else if (parts[2] !== '*') {
    bits.push(`on day ${descs[2]} of the month`);
  } else if (dow !== '*') {
    bits.push(`on ${descs[4]}`);
  }
  if (parts[3] !== '*') bits.push(`in ${descs[3]}`);
  if (parts[2] === '*' && parts[3] === '*' && dow === '*') bits.push('every day');

  return bits.join(', ');
}

export function describeSchedule(raw: string): ScheduleHelp {
  const s = raw.trim();

  if (!s) {
    return { valid: false, kind: 'empty', text: '5-field cron, "every …" phrase, duration ("30m"), or ISO timestamp' };
  }

  if (/^every\s+.+/i.test(s)) {
    return { valid: true, kind: 'every', text: `natural phrase — runs ${s.toLowerCase()}` };
  }

  const dur = s.match(/^(\d+)([smhd])$/i);
  if (dur) {
    const units: Record<string, string> = { s: 'second', m: 'minute', h: 'hour', d: 'day' };
    const n = +dur[1];
    const u = units[dur[2].toLowerCase()];
    return { valid: true, kind: 'duration', text: `duration — runs once, ${n} ${u}${n === 1 ? '' : 's'} from now` };
  }

  if (/^\d{4}-\d{2}-\d{2}/.test(s)) {
    // date-only ISO strings parse as UTC midnight per spec, which shifts the
    // displayed day in UTC-negative timezones — force local-time parsing
    const t = Date.parse(/^\d{4}-\d{2}-\d{2}$/.test(s) ? s + 'T00:00' : s);
    return Number.isNaN(t)
      ? { valid: false, kind: 'invalid', text: 'looks like a timestamp but does not parse — use ISO 8601' }
      : { valid: true, kind: 'iso', text: `timestamp — runs once at ${new Date(t).toLocaleString('en-GB')}` };
  }

  const parts = s.split(/\s+/);
  if (parts.length === 5) {
    const descs: string[] = [];
    for (let i = 0; i < 5; i++) {
      const d = describeField(i, parts[i]);
      if (d === null) {
        return {
          valid: false, kind: 'invalid',
          text: `bad ${FIELD_SPECS[i].name} field "${parts[i]}" — use *, N, N-M, N,M or */N (${FIELD_SPECS[i].min}–${FIELD_SPECS[i].max})`,
        };
      }
      descs.push(d);
    }
    return {
      valid: true, kind: 'cron',
      text: composeCronText(parts, descs),
      fields: parts.map((value, i) => ({ label: FIELD_SPECS[i].label, value, desc: descs[i] })),
    };
  }

  return {
    valid: false, kind: 'invalid',
    text: `unrecognized — expected 5 cron fields (got ${parts.length}), an "every …" phrase, a duration, or an ISO timestamp`,
  };
}

export const SCHEDULE_PRESETS: { label: string; value: string }[] = [
  { label: 'every 30m', value: 'every 30m' },
  { label: 'hourly', value: '0 * * * *' },
  { label: 'daily 09:00', value: '0 9 * * *' },
  { label: 'weekdays 07:00', value: '0 7 * * 1-5' },
  { label: 'mondays 09:00', value: '0 9 * * 1' },
  { label: 'monthly 1st', value: '0 0 1 * *' },
];
