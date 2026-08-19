import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { JsonTree } from './json-tree';
import { el, text } from '../testing/dom';

/** Hosts the tree so `data` and `query` can be driven the way a page drives them. */
@Component({
  selector: 'mc-json-tree-host',
  imports: [JsonTree],
  template: '<mc-json-tree [data]="data()" [query]="query()" />',
})
class Host {
  readonly data = signal<unknown>(null);
  readonly query = signal('');
}

const render = (data: unknown, query = '') => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.data.set(data);
  fixture.componentInstance.query.set(query);
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance };
};

type Fixture = ReturnType<typeof render>['fixture'];

const rows = (fixture: Fixture): string[] =>
  Array.from(el(fixture).querySelectorAll('.jt-row'))
    .map(r => (r.textContent ?? '').replace(/\s+/g, ' ').trim());

const toggle = (fixture: Fixture, index: number): void => {
  Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.jt-tog'))[index].click();
  fixture.detectChanges();
};

describe('JsonTree rendering', () => {
  it('summarizes a container by how much is inside it', () => {
    const { fixture } = render({ a: 1, b: [1, 2, 3] });

    expect(rows(fixture)[0]).toContain('{2}');
    expect(rows(fixture)).toContain('▾b:[3]');
  });

  it('quotes strings and spells out the values JSON has no other rendering for', () => {
    const { fixture } = render({ s: 'hi', n: 1.5, t: true, nil: null });

    expect(rows(fixture).join(' ')).toContain('"hi"');
    expect(rows(fixture).join(' ')).toContain('1.5');
    expect(rows(fixture).join(' ')).toContain('true');
    expect(rows(fixture).join(' ')).toContain('null');
  });

  it('indents each level so nesting is readable at a glance', () => {
    const { fixture } = render({ outer: { inner: 1 } });

    const indents = Array.from(el(fixture).querySelectorAll<HTMLElement>('.jt-row'))
      .map(r => r.style.paddingLeft);
    expect(indents).toEqual(['8px', '22px', '36px']);
  });

  it('says a document is empty rather than rendering nothing at all', () => {
    const { fixture } = render(undefined);
    fixture.componentInstance.data.set(undefined);

    expect(rows(fixture).length).toBe(1);
    expect(rows(fixture)[0]).toContain('undefined');
  });

  it('offers no toggle on a leaf, or on an empty container', () => {
    const { fixture } = render({ empty: {}, none: [], leaf: 1 });

    expect(el(fixture).querySelectorAll('.jt-tog:not(.ph)').length).toBe(1);   // only the root
  });
});

describe('JsonTree collapsing', () => {
  it('hides a node\'s descendants, and brings them back', () => {
    const { fixture } = render({ a: { b: { c: 1 } }, d: 2 });
    expect(rows(fixture).length).toBe(5);

    toggle(fixture, 1);                       // collapse `a`
    expect(rows(fixture)).toEqual(['▾{2}', '▸a:{1}', 'd:2']);

    toggle(fixture, 1);
    expect(rows(fixture).length).toBe(5);
  });

  it('collapses the root, leaving only the summary', () => {
    const { fixture } = render({ a: 1, b: 2 });

    toggle(fixture, 0);

    expect(rows(fixture)).toEqual(['▸{2}']);
  });
});

describe('JsonTree search', () => {
  it('marks every match, in keys and in values alike', () => {
    const { fixture } = render({ region: 'eu-west', other: 'region-2' }, 'region');

    const marks = Array.from(el(fixture).querySelectorAll('mark.jt-hit'));
    expect(marks.length).toBe(2);
    expect(marks.map(m => m.textContent)).toEqual(['region', 'region']);
  });

  it('reveals collapsed nodes while searching, so no match can hide', () => {
    const { fixture, host } = render({ a: { deep: 'needle' } });
    toggle(fixture, 1);
    expect(text(fixture)).not.toContain('needle');

    host.query.set('needle');
    fixture.detectChanges();

    expect(text(fixture)).toContain('needle');
    expect(el(fixture).querySelector('mark.jt-hit')).not.toBeNull();
  });

  it('marks nothing for a blank query', () => {
    const { fixture } = render({ region: 'eu-west' }, '   ');

    expect(el(fixture).querySelector('mark.jt-hit')).toBeNull();
  });
});

describe('JsonTree limits', () => {
  // the only test here that renders the cap itself: 8000 rows through change
  // detection, which is why vitest-base.config.ts raises the timeout
  it('stops at the node cap and says the output was cut', () => {
    const { fixture } = render(Array.from({ length: 9_000 }, (_, i) => i));

    expect(text(fixture)).toContain('output truncated (8000 nodes)');
    expect(rows(fixture).length).toBe(8_000);
  });

  it('says nothing about truncation for a document that fits', () => {
    const { fixture } = render([1, 2, 3]);

    expect(text(fixture)).not.toContain('truncated');
  });
});
