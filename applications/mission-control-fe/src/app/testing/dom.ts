import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { Confirm } from '../shared/confirm';

/**
 * The handful of DOM helpers every component spec needs. They existed once per
 * spec file before; keeping one copy means a page's tests read as what an
 * operator does — press this, fill that — instead of re-deriving how to find a
 * button each time.
 *
 * Everything here throws with the label it was given rather than returning null,
 * so a spec that drifts from its template fails on the missing control instead
 * of on a downstream `undefined`.
 *
 * Test-only: excluded from the app build (tsconfig.app.json) and from coverage.
 */
export interface RenderedFixture {
  readonly nativeElement: unknown;
}

/** A fixture that can also be driven — anything that acts on the page needs it. */
export interface TestFixture extends RenderedFixture {
  detectChanges(): void;
  whenStable?(): Promise<unknown>;
}

/** The fixture's rendered root. */
export const el = (fixture: RenderedFixture): HTMLElement => fixture.nativeElement as HTMLElement;

/** All rendered text, with runs of whitespace collapsed so assertions can be
 *  written the way the page reads rather than the way it is indented. */
export const text = (fixture: RenderedFixture): string =>
  (el(fixture).textContent ?? '').replace(/\s+/g, ' ').trim();

/**
 * Lets pending work land and repaints. Under fake timers that means advancing
 * them (`ms` covers a poll interval); otherwise it awaits the fixture, so a spec
 * gets the right behaviour without choosing.
 */
export const settle = async (fixture: TestFixture, ms = 0): Promise<void> => {
  if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(ms);
  else await fixture.whenStable?.();
  fixture.detectChanges();
};

const scopeOf = (fixture: RenderedFixture, within?: string | Element): Element => {
  if (!within) return el(fixture);
  if (typeof within !== 'string') return within;
  const scope = el(fixture).querySelector(within);
  if (!scope) throw new Error(`no element matching "${within}"`);
  return scope;
};

/** The button with this exact trimmed label, optionally scoped to one subtree. */
export const button = (
  fixture: RenderedFixture, label: string, within?: string | Element,
): HTMLButtonElement => {
  const match = Array.from(scopeOf(fixture, within).querySelectorAll('button'))
    .find(b => (b.textContent ?? '').trim() === label);
  if (!match) throw new Error(`no button labelled "${label}"`);
  return match;
};

/** The button whose label contains this text, ignoring case — for controls whose
 *  label carries a live count or state suffix. */
export const buttonWith = (
  fixture: RenderedFixture, label: string, within?: string | Element,
): HTMLButtonElement => {
  const buttons = Array.from(scopeOf(fixture, within).querySelectorAll('button'));
  const wanted = label.toLowerCase();
  const labelOf = (b: HTMLButtonElement) => (b.textContent ?? '').trim().toLowerCase();
  const match = buttons.find(b => labelOf(b) === wanted) ?? buttons.find(b => labelOf(b).includes(wanted));
  if (!match) throw new Error(`no button matching "${label}"`);
  return match;
};

/** Clicks {@link button} and repaints. */
export const press = (fixture: TestFixture, label: string, within?: string | Element): void => {
  button(fixture, label, within).click();
  fixture.detectChanges();
};

/** The `.field` whose label starts with this text — a form's own labels are the
 *  only stable handle on it, and they read the way an operator sees them. */
export const field = (fixture: RenderedFixture, label: string): HTMLElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.field'))
    .find(f => (f.querySelector('label')?.textContent ?? '').trim().toLowerCase()
      .startsWith(label.toLowerCase()));
  if (!match) throw new Error(`no field labelled "${label}"`);
  return match;
};

type Editable = HTMLInputElement | HTMLTextAreaElement;

const set = async (input: Editable | HTMLSelectElement, value: string, fixture: TestFixture, event: string) => {
  input.value = value;
  input.dispatchEvent(new Event(event));
  await settle(fixture);
};

/** Types into the input of the `.field` carrying this label. */
export const fill = async (fixture: TestFixture, label: string, value: string): Promise<void> => {
  const input = field(fixture, label).querySelector<Editable>('.input');
  if (!input) throw new Error(`field "${label}" has no .input`);
  await set(input, value, fixture, 'input');
};

/** Picks an option in the select of the `.field` carrying this label. */
export const choose = async (fixture: TestFixture, label: string, value: string): Promise<void> => {
  const select = field(fixture, label).querySelector<HTMLSelectElement>('.select');
  if (!select) throw new Error(`field "${label}" has no .select`);
  await set(select, value, fixture, 'change');
};

/** Types into the control this CSS selector addresses — for inputs outside a
 *  labelled `.field`. */
export const type = async (fixture: TestFixture, selector: string, value: string): Promise<void> => {
  const input = el(fixture).querySelector<Editable>(selector);
  if (!input) throw new Error(`no input matching "${selector}"`);
  await set(input, value, fixture, 'input');
};

/**
 * Answers the app's confirmation dialog without rendering it. Spy on the root service
 * *after* the page is rendered — `render()` resets the TestBed, and with it the instance.
 * The spy's first call argument is the {@link ConfirmRequest}, so assert on `.message`.
 */
export const stubConfirm = (answer: boolean) =>
  vi.spyOn(TestBed.inject(Confirm), 'ask').mockResolvedValue(answer);
