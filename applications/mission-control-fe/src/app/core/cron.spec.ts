import { describe, expect, it } from 'vitest';
import { describeSchedule, SCHEDULE_PRESETS } from './cron';

describe('describeSchedule dispatch', () => {
  it('prompts with the accepted forms when the field is empty or blank', () => {
    for (const raw of ['', '   ', '\t\n']) {
      const help = describeSchedule(raw);
      expect(help.valid).toBe(false);
      expect(help.kind).toBe('empty');
    }
  });

  it('passes an "every …" phrase through, since hermes parses those itself', () => {
    expect(describeSchedule('every monday 9am')).toMatchObject({
      valid: true, kind: 'every', text: 'natural phrase — runs every monday 9am',
    });
  });

  it('matches the "every" prefix case-insensitively, and echoes it lowercased', () => {
    expect(describeSchedule('EVERY 30m').text).toBe('natural phrase — runs every 30m');
  });

  it('reads a bare duration as a one-shot delay, pluralizing only past one', () => {
    expect(describeSchedule('1s').text).toBe('duration — runs once, 1 second from now');
    expect(describeSchedule('30m').text).toBe('duration — runs once, 30 minutes from now');
    expect(describeSchedule('1h').text).toBe('duration — runs once, 1 hour from now');
    expect(describeSchedule('2d').text).toBe('duration — runs once, 2 days from now');
    expect(describeSchedule('30M').kind).toBe('duration');
  });

  it('rejects a duration in a unit hermes has no name for', () => {
    // 'w' and 'y' would otherwise fall through to the cron branch and be
    // reported as a field-count problem, which tells the operator nothing.
    expect(describeSchedule('2w').kind).toBe('invalid');
    expect(describeSchedule('30').kind).toBe('invalid');
  });

  it('parses a date-only timestamp at local midnight, not UTC midnight', () => {
    // Date.parse('2026-08-18') is UTC per spec, which renders as the 17th
    // anywhere west of Greenwich. Comparing against a locally-constructed
    // midnight fails in exactly the timezones the workaround exists for.
    const localMidnight = new Date(2026, 7, 18).toLocaleString('en-GB');
    expect(describeSchedule('2026-08-18')).toMatchObject({
      valid: true, kind: 'iso', text: `timestamp — runs once at ${localMidnight}`,
    });
  });

  it('keeps the time component when one is supplied', () => {
    const local = new Date(2026, 7, 18, 14, 30).toLocaleString('en-GB');
    expect(describeSchedule('2026-08-18T14:30').text).toBe(`timestamp — runs once at ${local}`);
  });

  it('says a date-shaped string is unparseable rather than rendering Invalid Date', () => {
    expect(describeSchedule('2026-13-45')).toMatchObject({
      valid: false, kind: 'invalid',
      text: 'looks like a timestamp but does not parse — use ISO 8601',
    });
  });

  it('names the field count when the expression is neither cron nor a known phrase', () => {
    expect(describeSchedule('0 9 * *').text).toContain('(got 4)');
    expect(describeSchedule('@daily').text).toContain('(got 1)');
    expect(describeSchedule('0 9 * * * *').text).toContain('(got 6)');
  });
});

describe('describeSchedule cron fields', () => {
  it('collapses a fixed minute and hour into a zero-padded clock time', () => {
    expect(describeSchedule('0 9 * * *').text).toBe('at 09:00, every day');
    expect(describeSchedule('0 0 1 * *').text).toBe('at 00:00, on day 1 of the month');
  });

  it('falls back to per-field wording when either minute or hour is not a fixed number', () => {
    expect(describeSchedule('*/15 * * * *').text)
      .toBe('min: every 15 minutes, hour: every hour, every day');
    expect(describeSchedule('0 9,17 * * *').text)
      .toBe('min: 0, hour: 9, 17, every day');
  });

  it('spells out cron OR semantics when day-of-month and weekday are both restricted', () => {
    // standard cron fires when EITHER matches — reading it as AND is the
    // classic way to schedule a job that never runs when you expect it to
    expect(describeSchedule('0 0 1 * 1').text).toBe('at 00:00, on day 1 of the month or on Mon');
    expect(describeSchedule('0 0 1 * *').text).toBe('at 00:00, on day 1 of the month');
    expect(describeSchedule('0 0 * * 1').text).toBe('at 00:00, on Mon');
  });

  it('names months and weekdays instead of echoing their numbers', () => {
    expect(describeSchedule('0 0 1 6 *').text).toBe('at 00:00, on day 1 of the month, in Jun');
    expect(describeSchedule('0 9 * * 1-5').text).toBe('at 09:00, on Mon–Fri');
  });

  it('treats weekday 0 and 7 as the same Sunday, as cron does', () => {
    expect(describeSchedule('0 9 * * 0').text).toBe('at 09:00, on Sun');
    expect(describeSchedule('0 9 * * 7').text).toBe('at 09:00, on Sun');
  });

  it('says "every day" only when nothing narrows the date at all', () => {
    expect(describeSchedule('0 9 * * *').text).toContain('every day');
    expect(describeSchedule('0 9 1 * *').text).not.toContain('every day');
    expect(describeSchedule('0 9 * 6 *').text).not.toContain('every day');
  });

  it('returns one annotated field per cron position for the editor to render', () => {
    const fields = describeSchedule('0 9 * * 1-5').fields;
    expect(fields?.map(f => [f.label, f.value, f.desc])).toEqual([
      ['min', '0', '0'],
      ['hour', '9', '9'],
      ['day', '*', 'every day of month'],
      ['month', '*', 'every month'],
      ['weekday', '1-5', 'Mon–Fri'],
    ]);
  });

  it('carries no fields when the expression is not cron, so the editor has nothing to draw', () => {
    expect(describeSchedule('30m').fields).toBeUndefined();
    expect(describeSchedule('60 * * * *').fields).toBeUndefined();
  });
});

describe('describeSchedule field rejections', () => {
  it('names the offending field and its legal range, not just "invalid"', () => {
    expect(describeSchedule('60 * * * *').text)
      .toBe('bad minute field "60" — use *, N, N-M, N,M or */N (0–59)');
    expect(describeSchedule('0 9 * * 5-1').text)
      .toBe('bad day of week field "5-1" — use *, N, N-M, N,M or */N (0–7)');
  });

  it('rejects an out-of-range value in every position', () => {
    for (const expr of ['60 * * * *', '* 24 * * *', '* * 32 * *', '* * * 13 *', '* * * * 8']) {
      expect(describeSchedule(expr), expr).toMatchObject({ valid: false, kind: 'invalid' });
    }
  });

  it('rejects day-of-month 0, which cron has no such day for', () => {
    expect(describeSchedule('* * 0 * *').valid).toBe(false);
    expect(describeSchedule('* * * 0 *').valid).toBe(false);
    expect(describeSchedule('0 0 * * *').valid).toBe(true);   // minute/hour 0 are legal
  });

  it('rejects a descending range, which would silently never fire', () => {
    expect(describeSchedule('0 9 * * 5-1').valid).toBe(false);
    expect(describeSchedule('0 9 * * 1-5').valid).toBe(true);
    expect(describeSchedule('0 9 * * 3-3').valid).toBe(true);
  });

  it('rejects a step of zero or one larger than the field itself', () => {
    expect(describeSchedule('*/0 * * * *').valid).toBe(false);
    expect(describeSchedule('*/60 * * * *').valid).toBe(false);
    expect(describeSchedule('*/59 * * * *').valid).toBe(true);
    expect(describeSchedule('*/1 * * * *').text).toContain('every minute');
  });

  it('rejects list and range syntax cron does not accept in these fields', () => {
    for (const expr of ['1-2-3 * * * *', '1,,2 * * * *', '1, 2 * * * *', 'a * * * *', '*/x * * * *']) {
      expect(describeSchedule(expr), expr).toMatchObject({ valid: false });
    }
  });

  it('accepts a comma list and renders it in field order', () => {
    expect(describeSchedule('0,30 9 * * *').fields?.[0].desc).toBe('0, 30');
    expect(describeSchedule('0 9 * * 1,3,5').fields?.[4].desc).toBe('Mon, Wed, Fri');
  });
});

describe('SCHEDULE_PRESETS', () => {
  it('offers only expressions the annotator itself accepts', () => {
    // a preset the validator rejects would put the create-job form in an
    // invalid state the moment an operator clicks the chip
    for (const preset of SCHEDULE_PRESETS) {
      expect(describeSchedule(preset.value), preset.label).toMatchObject({ valid: true });
    }
  });

  it('describes each preset as the label promises', () => {
    const text = (value: string) => describeSchedule(value).text;
    expect(text('0 * * * *')).toBe('min: 0, hour: every hour, every day');
    expect(text('0 9 * * *')).toBe('at 09:00, every day');
    expect(text('0 7 * * 1-5')).toBe('at 07:00, on Mon–Fri');
    expect(text('0 9 * * 1')).toBe('at 09:00, on Mon');
    expect(text('0 0 1 * *')).toBe('at 00:00, on day 1 of the month');
  });
});
