import { AfterViewInit, Directive, ElementRef, OnDestroy, inject, output } from '@angular/core';

/**
 * What can hold focus. `:not([disabled])` and `:not([tabindex="-1"])` do the filtering the
 * browser would do; there is no visibility check because `@if` removes hidden content from the
 * DOM rather than hiding it, and jsdom — where this is tested — has no layout to ask.
 */
const FOCUSABLE = [
  'a[href]', 'button:not([disabled])', 'input:not([disabled])', 'select:not([disabled])',
  'textarea:not([disabled])', '[tabindex]:not([tabindex="-1"])',
].join(', ');

/**
 * The backdrop behind a modal: click it to dismiss, press Escape, and while it is open the
 * keyboard cannot leave it.
 *
 * <p>Every dialog had written the mouse half by hand — `(click)` on the scrim to close, plus
 * `(click)="$event.stopPropagation()"` on the modal so a click inside did not count as a click
 * outside — and none had the keyboard half. A dialog a mouse can dismiss and a keyboard cannot
 * is the quiet version of this bug: nothing looks broken, and the way out exists for one kind
 * of user.
 *
 * <p>The inner `stopPropagation` goes with it. Asking whether the click landed on the backdrop
 * itself answers the same question without the modal having to know it sits on one, and
 * without swallowing an event something else might have wanted.
 *
 * <p>Escape binds on the document rather than the host, because a backdrop is not focusable —
 * and making it focusable to receive the key would put a tab stop with no meaning in front of
 * the dialog's own controls. Only one modal is mounted at a time, so that listener has one
 * subscriber.
 *
 * <p><b>Focus is moved in, held, and given back.</b> Without that, `aria-modal` is a claim the
 * page does not honour: a keyboard user tabs straight out of an open dialog into the page
 * behind it, where the thing they tab onto is covered by the backdrop and cannot be clicked.
 * Tab from the last control wraps to the first and Shift+Tab wraps the other way, and on close
 * focus returns to whatever opened the dialog — a list may have re-rendered underneath, so the
 * restore checks the element is still in the document.
 *
 * <p>The trap is skipped when the backdrop holds nothing focusable. Three of these are bare
 * click-catchers behind a menu rather than dialogs, and moving focus into an empty box would
 * stranded the keyboard where there is nothing to operate.
 *
 * <p>Guards stay in the template — `(dismiss)="saveBusy() ? null : closed.emit()"` — because
 * whether a dialog may close mid-save is the dialog's business, not the backdrop's.
 */
@Directive({
  selector: '[mcScrim]',
  host: {
    '(click)': 'onClick($event)',
    '(document:keydown.escape)': 'dismiss.emit()',
    '(keydown.tab)': 'onTab($event, false)',
    '(keydown.shift.tab)': 'onTab($event, true)',
  },
})
export class Scrim implements AfterViewInit, OnDestroy {
  /** A click on the backdrop, or Escape. */
  readonly dismiss = output<void>();

  private readonly host: HTMLElement = inject(ElementRef).nativeElement;

  /** Whatever had focus when the dialog appeared — captured before focus is moved in. */
  private readonly opener = document.activeElement as HTMLElement | null;

  ngAfterViewInit(): void {
    this.focusable()[0]?.focus();
  }

  ngOnDestroy(): void {
    // the opener may have been re-rendered away while the dialog was open
    if (this.opener?.isConnected) this.opener.focus();
  }

  /** Ignores clicks that bubbled up from the modal — only the backdrop itself dismisses. */
  protected onClick(event: Event): void {
    if (event.target === event.currentTarget) this.dismiss.emit();
  }

  /** Wraps at both ends, so Tab cannot walk out of the dialog into the covered page. */
  protected onTab(event: Event, backwards: boolean): void {
    const items = this.focusable();
    if (!items.length) return;      // a bare click-catcher: nothing to hold
    const edge = backwards ? items[0] : items[items.length - 1];
    if (document.activeElement !== edge) return;
    (backwards ? items[items.length - 1] : items[0]).focus();
    event.preventDefault();
  }

  private focusable(): HTMLElement[] {
    return Array.from(this.host.querySelectorAll<HTMLElement>(FOCUSABLE));
  }
}
