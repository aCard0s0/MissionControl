import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Semicircular arc gauge for a 0–100 percentage. */
@Component({
  selector: 'mc-gauge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 100 56" aria-hidden="true">
      <path d="M 8 52 A 42 42 0 0 1 92 52" class="track" />
      <path d="M 8 52 A 42 42 0 0 1 92 52" class="value"
            [attr.stroke-dasharray]="arcLen"
            [attr.stroke-dashoffset]="dashOffset()"
            [style.stroke]="color()" />
    </svg>
  `,
  styles: `
    :host { display: block; line-height: 0; }
    svg { width: 100%; }
    path { fill: none; stroke-width: 7; stroke-linecap: round; }
    .track { stroke: var(--line); }
    .value { transition: stroke-dashoffset .6s cubic-bezier(.22,1,.36,1), stroke .3s; }
  `,
})
export class Gauge {
  readonly value = input.required<number>();   // 0–100
  readonly warnAt = input(70);
  readonly critAt = input(88);

  readonly arcLen = Math.PI * 42;

  /** The input held to the arc: a first sample with no total is NaN, a two-core container
   *  reports 200% — neither should draw past the track or paint the arc red. */
  private readonly clamped = computed(() => {
    const v = this.value();
    return Number.isFinite(v) ? Math.min(Math.max(v, 0), 100) : 0;
  });

  readonly dashOffset = computed(() => this.arcLen * (1 - this.clamped() / 100));

  readonly color = computed(() => {
    const v = this.clamped();
    if (v >= this.critAt()) return 'var(--red)';
    if (v >= this.warnAt()) return 'var(--amber)';
    return 'var(--acc)';
  });
}
