import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const TONE: Record<string, 'ok' | 'warn' | 'crit' | 'idle' | 'info'> = {
  running: 'ok', active: 'ok', up: 'ok', connected: 'ok', ok: 'ok', open: 'ok',
  idle: 'warn', degraded: 'warn', warn: 'warn',
  unhealthy: 'crit', error: 'crit', down: 'crit', fail: 'crit',
  stopped: 'idle', dormant: 'idle', off: 'idle', disabled: 'idle', closed: 'idle', disconnected: 'idle',
  unknown: 'info', connecting: 'info',
};

/** Status is always dot + word — never color alone. */
@Component({
  selector: 'mc-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="dot" [class]="tone()" [class.live]="live()"></span>
    <span class="word" [class]="tone()">{{ label() || status() }}</span>
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-family: var(--font-mono);
      font-size: 10.5px;
      letter-spacing: .08em;
      text-transform: uppercase;
    }
    .dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
    .dot.ok   { background: var(--acc); }
    .dot.warn { background: var(--amber); }
    .dot.crit { background: var(--red); }
    .dot.idle { background: var(--faint); }
    .dot.info { background: var(--cyan); }
    .dot.live.ok   { box-shadow: 0 0 6px var(--acc);  animation: breathe 2.4s ease-in-out infinite; }
    .dot.live.crit { box-shadow: 0 0 6px var(--red);  animation: breathe 1.1s ease-in-out infinite; }
    .word.ok   { color: var(--acc); }
    .word.warn { color: var(--amber); }
    .word.crit { color: var(--red); }
    .word.idle { color: var(--faint); }
    .word.info { color: var(--cyan); }
    @keyframes breathe { 50% { opacity: .45; } }
    @media (prefers-reduced-motion: reduce) { .dot.live { animation: none; } }
  `,
})
export class StatusDot {
  readonly status = input.required<string>();
  readonly label = input('');
  /** live=true adds the breathing glow — reserved for genuinely live signals */
  readonly live = input(false);

  readonly tone = computed(() => TONE[this.status()] ?? 'info');
}
