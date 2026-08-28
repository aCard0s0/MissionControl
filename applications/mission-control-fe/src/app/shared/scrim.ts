import { Directive, output } from '@angular/core';

/**
 * The backdrop behind a modal: click it to dismiss, or press Escape.
 *
 * <p>Every dialog in the app had written the mouse half by hand — `(click)` on the scrim to
 * close, plus `(click)="$event.stopPropagation()"` on the modal so a click inside did not
 * count as a click outside — and none of them had the keyboard half. A dialog a mouse can
 * dismiss and a keyboard cannot is the ordinary shape of this bug: nothing looks broken, and
 * the way out exists for only one kind of user.
 *
 * <p>The inner `stopPropagation` goes with it. Asking whether the click landed on the
 * backdrop itself answers the same question without the modal having to know it sits on one,
 * and without swallowing an event something else might have wanted.
 *
 * <p>Escape is bound on the document rather than the host, because a backdrop is not
 * focusable — and making it focusable to receive the key would put a tab stop with no meaning
 * in front of the dialog's own controls. Only one modal is mounted at a time, so the document
 * listener has one subscriber.
 *
 * <p>Guards stay in the template — `(dismiss)="saveBusy() ? null : closed.emit()"` — because
 * whether a dialog may close mid-save is the dialog's business, not the backdrop's.
 */
@Directive({
  selector: '[mcScrim]',
  host: {
    '(click)': 'onClick($event)',
    '(document:keydown.escape)': 'dismiss.emit()',
  },
})
export class Scrim {
  /** A click on the backdrop, or Escape. */
  readonly dismiss = output<void>();

  /** Ignores clicks that bubbled up from the modal — only the backdrop itself dismisses. */
  protected onClick(event: Event): void {
    if (event.target === event.currentTarget) this.dismiss.emit();
  }
}
