import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

@Component({
  selector: 'mc-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.viewBox]="'0 0 ' + w() + ' ' + h()" preserveAspectRatio="none" aria-hidden="true">
      <path [attr.d]="areaPath()" class="area" />
      <path [attr.d]="linePath()" class="line" />
      <circle [attr.cx]="lastX()" [attr.cy]="lastY()" r="1.8" class="tip" />
    </svg>
  `,
  styles: `
    :host { display: block; line-height: 0; }
    svg { width: 100%; height: 100%; overflow: visible; }
    .line { fill: none; stroke: currentColor; stroke-width: 1.4; vector-effect: non-scaling-stroke; }
    .area { fill: currentColor; opacity: .09; stroke: none; }
    .tip { fill: currentColor; }
  `,
})
export class Sparkline {
  readonly data = input.required<number[]>();
  readonly w = input(120);
  readonly h = input(32);
  readonly max = input<number | null>(null);

  private readonly pts = computed(() => {
    const d = this.data();
    if (d.length < 2) return [];
    const w = this.w(), h = this.h();
    const top = this.max() ?? Math.max(...d, 1);
    const pad = 2;
    return d.map((v, i) => [
      (i / (d.length - 1)) * w,
      pad + (1 - Math.min(v / top, 1)) * (h - pad * 2),
    ] as const);
  });

  readonly linePath = computed(() =>
    this.pts().map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join(' '));

  readonly areaPath = computed(() => {
    const p = this.pts();
    if (!p.length) return '';
    return `${this.linePath()} L${this.w()},${this.h()} L0,${this.h()} Z`;
  });

  readonly lastX = computed(() => this.pts().at(-1)?.[0] ?? 0);
  readonly lastY = computed(() => this.pts().at(-1)?.[1] ?? 0);
}
