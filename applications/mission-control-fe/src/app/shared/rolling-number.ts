import {
  ChangeDetectionStrategy, Component, effect, input, signal, untracked,
} from '@angular/core';
import { rollNumber } from '../core/motion';

/** Number that rolls to its new value when the input changes. */
@Component({
  selector: 'mc-num',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `{{ shown() }}`,
  styles: `:host { font-variant-numeric: tabular-nums; }`,
})
export class RollingNumber {
  readonly value = input.required<number>();
  readonly decimals = input(0);
  readonly suffix = input('');

  protected readonly shown = signal('0');
  private current = 0;

  constructor() {
    effect(() => {
      const target = this.value();
      // a ratio with no denominator yet — RAM before the first stats sample — is not a
      // number to roll to, and "NaN%" is not a reading
      if (!Number.isFinite(target)) { this.shown.set('—'); return; }
      const from = untracked(() => this.current);
      rollNumber(from, target, v => {
        this.current = v;
        this.shown.set(v.toFixed(this.decimals()) + this.suffix());
      }, 0.5);
    });
  }
}
