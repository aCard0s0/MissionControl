import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ago, clock, mb, shortDate, until, uptime } from './format';

const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;
const NOW = new Date(2026, 7, 18, 14, 30, 45).getTime();

describe('relative time formatting', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });

  afterEach(() => { vi.useRealTimers(); });

  it('shows an em dash for a container that has no start time', () => {
    expect(uptime(null)).toBe('—');
    expect(uptime(0)).toBe('—');   // epoch 0 is "never started", not 56 years up
  });

  it('switches uptime units exactly at the hour and the day', () => {
    expect(uptime(NOW - (HOUR - 1))).toBe('59m');
    expect(uptime(NOW - HOUR)).toBe('1h 0m');
    expect(uptime(NOW - (DAY - 1))).toBe('23h 59m');
    expect(uptime(NOW - DAY)).toBe('1d 0h');
  });

  it('truncates uptime rather than rounding up, so it never overstates', () => {
    expect(uptime(NOW - (90 * MIN))).toBe('1h 30m');
    expect(uptime(NOW - (2 * DAY + 5 * HOUR + 59 * MIN))).toBe('2d 5h');
  });

  it('reads a null timestamp as never, not as this instant', () => {
    expect(ago(null)).toBe('never');
    expect(ago(0)).toBe('never');
  });

  it('switches ago units at the minute, hour and day', () => {
    expect(ago(NOW)).toBe('just now');
    expect(ago(NOW - (MIN - 1))).toBe('just now');
    expect(ago(NOW - MIN)).toBe('1m ago');
    expect(ago(NOW - (HOUR - 1))).toBe('59m ago');
    expect(ago(NOW - HOUR)).toBe('1h ago');
    expect(ago(NOW - (DAY - 1))).toBe('23h ago');
    expect(ago(NOW - DAY)).toBe('1d ago');
  });

  it('calls a schedule due the moment it is reached, and while it is overdue', () => {
    expect(until(NOW)).toBe('due');
    expect(until(NOW - DAY)).toBe('due');
    expect(until(NOW + 1)).toBe('<1m');
  });

  it('switches until units at the minute, hour and day', () => {
    expect(until(NOW + MIN)).toBe('in 1m');
    expect(until(NOW + HOUR - 1)).toBe('in 59m');
    expect(until(NOW + HOUR)).toBe('in 1h 0m');
    expect(until(NOW + 90 * MIN)).toBe('in 1h 30m');
    expect(until(NOW + DAY - 1)).toBe('in 23h 59m');
    expect(until(NOW + DAY)).toBe('in 1d');
  });
});

describe('absolute time formatting', () => {
  it('renders a 24-hour clock, so 13:00 is never shown as 1:00', () => {
    expect(clock(new Date(2026, 7, 18, 13, 5, 9).getTime())).toBe('13:05:09');
    expect(clock(new Date(2026, 7, 18, 0, 0, 0).getTime())).toBe('00:00:00');
  });

  it('renders a short day-month date in local time', () => {
    expect(shortDate(new Date(2026, 7, 18).getTime())).toBe('18 Aug');
    expect(shortDate(new Date(2026, 0, 1).getTime())).toBe('01 Jan');
  });
});

describe('mb', () => {
  it('switches to gigabytes exactly at 1024 MB', () => {
    expect(mb(1023)).toBe('1023 MB');
    expect(mb(1024)).toBe('1.0 GB');
    expect(mb(1536)).toBe('1.5 GB');
  });

  it('rounds megabytes to whole numbers and gigabytes to one decimal', () => {
    expect(mb(511.6)).toBe('512 MB');
    expect(mb(0.4)).toBe('0 MB');
    expect(mb(0)).toBe('0 MB');
    expect(mb(2600)).toBe('2.5 GB');
  });
});
