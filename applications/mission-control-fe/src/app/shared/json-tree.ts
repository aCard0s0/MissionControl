import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { highlightHtml, primText } from './highlight';

type Kind = 'obj' | 'arr' | 'prim';

interface Row {
  path: string;
  depth: number;
  key: string | null;   // null for the root
  kind: Kind;
  preview: string;       // rendered text (primitive value, or {n}/[n] summary)
  openable: boolean;
}

interface RenderRow extends Row {
  keyHtml: SafeHtml | null;
  valHtml: SafeHtml;
}

const MAX_ROWS = 8000;

/**
 * Collapsible, searchable JSON tree. Flattened (not self-recursive) so it works
 * on any Angular version. When `query` is set, every node is shown and matches
 * are highlighted with `<mark class="jt-hit">` (queryable by the host for
 * prev/next navigation).
 */
@Component({
  selector: 'mc-json-tree',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @for (r of renderRows(); track r.path) {
      <div class="jt-row" [style.paddingLeft.px]="8 + r.depth * 14">
        @if (r.openable) {
          <button class="jt-tog" (click)="toggle(r.path)">{{ collapsed().has(r.path) ? '▸' : '▾' }}</button>
        } @else {
          <span class="jt-tog ph"></span>
        }
        @if (r.key !== null) {
          <span class="jt-key" [innerHTML]="r.keyHtml"></span><span class="jt-colon">:</span>
        }
        <span class="jt-val jt-{{ r.kind }}" [innerHTML]="r.valHtml"></span>
      </div>
    }
    @if (truncated()) { <div class="jt-trunc">… output truncated ({{ MAX }} nodes)</div> }
    @if (!renderRows().length) { <div class="jt-empty">empty</div> }
  `,
  styles: [`
    :host { display: block; font-family: var(--font-mono); font-size: 11.5px; line-height: 1.65; }
    .jt-row { display: flex; align-items: baseline; gap: 2px; }
    .jt-tog { flex: none; width: 14px; background: none; border: 0; color: var(--faint); cursor: pointer; font-size: 9px; padding: 0; }
    .jt-tog.ph { cursor: default; }
    .jt-key { color: var(--cyan); white-space: nowrap; flex: none; }
    .jt-colon { color: var(--faint); margin-right: 4px; flex: none; }
    .jt-val { white-space: pre-wrap; word-break: break-word; min-width: 0; }
    .jt-val.jt-prim { color: var(--text); }
    .jt-val.jt-obj, .jt-val.jt-arr { color: var(--faint); }
    .jt-trunc, .jt-empty { color: var(--faint); padding: 4px 0 0 8px; font-size: 10.5px; }
  `],
})
export class JsonTree {
  private readonly san = inject(DomSanitizer);
  protected readonly MAX = MAX_ROWS;

  readonly data = input<unknown>(null);
  readonly query = input<string>('');

  protected readonly collapsed = signal<Set<string>>(new Set());

  private readonly rows = computed<Row[]>(() => {
    const out: Row[] = [];
    this.flatten(this.data(), null, '$', 0, out);
    return out;
  });

  protected readonly truncated = computed(() => this.rows().length >= MAX_ROWS);

  protected readonly visibleRows = computed<Row[]>(() => {
    const all = this.rows();
    if (this.query().trim()) return all;     // searching: reveal everything so matches show
    const col = this.collapsed();
    const out: Row[] = [];
    let hideDepth = Infinity;
    for (const r of all) {
      if (r.depth > hideDepth) continue;     // descendant of a collapsed node
      hideDepth = Infinity;
      out.push(r);
      if (r.openable && col.has(r.path)) hideDepth = r.depth;
    }
    return out;
  });

  // Highlighted HTML precomputed per (visibleRows, query) so the [innerHTML]
  // bindings keep stable references across unrelated change-detection passes —
  // otherwise Angular would re-render the marks every CD and wipe the
  // host's `.current` match highlight.
  protected readonly renderRows = computed<RenderRow[]>(() => {
    const q = this.query();
    return this.visibleRows().map(r => ({
      ...r,
      keyHtml: r.key !== null ? this.trust(r.key, q) : null,
      valHtml: this.trust(r.preview, q),
    }));
  });

  private trust(text: string, q: string): SafeHtml {
    return this.san.bypassSecurityTrustHtml(highlightHtml(text, q));
  }

  protected toggle(path: string): void {
    this.collapsed.update(set => {
      const next = new Set(set);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  }

  private flatten(value: unknown, key: string | null, path: string, depth: number, out: Row[]): void {
    if (out.length >= MAX_ROWS) return;
    if (Array.isArray(value)) {
      out.push({ path, depth, key, kind: 'arr', preview: `[${value.length}]`, openable: value.length > 0 });
      value.forEach((v, i) => this.flatten(v, String(i), `${path}.${i}`, depth + 1, out));
    } else if (value !== null && typeof value === 'object') {
      const keys = Object.keys(value as Record<string, unknown>);
      out.push({ path, depth, key, kind: 'obj', preview: `{${keys.length}}`, openable: keys.length > 0 });
      for (const k of keys) this.flatten((value as Record<string, unknown>)[k], k, `${path}.${k}`, depth + 1, out);
    } else {
      out.push({ path, depth, key, kind: 'prim', preview: primText(value), openable: false });
    }
  }
}
