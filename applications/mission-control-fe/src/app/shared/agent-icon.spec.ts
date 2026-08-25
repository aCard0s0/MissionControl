import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { AGENT_ICONS, AgentIconView, isAgentIcon } from './agent-icon';
import { el } from '../testing/dom';

@Component({
  imports: [AgentIconView],
  template: `<mc-agent-icon [icon]="icon()" />`,
})
class Host {
  readonly icon = signal('');
}

const render = (icon = '') => {
  TestBed.resetTestingModule();
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.icon.set(icon);
  fixture.detectChanges();
  return fixture;
};

/** Every drawable element inside the glyph, whatever shape it is made of. */
const shapes = (fixture: { nativeElement: unknown }): number =>
  el(fixture).querySelectorAll('svg > *').length;

describe('AgentIconView', () => {
  it('draws every key the picker offers', () => {
    for (const icon of AGENT_ICONS) {
      const fixture = render(icon);
      expect(shapes(fixture), icon).toBeGreaterThan(0);
    }
  });

  it('falls back to a glyph rather than to nothing when the key is unset', () => {
    // a blueprint saved before the library had glyphs still has to line up with
    // its neighbours in the list
    expect(shapes(render(''))).toBeGreaterThan(0);
  });

  it('falls back for a key it does not know, as a later version could send', () => {
    expect(shapes(render('dragon'))).toBeGreaterThan(0);
  });

  it('takes the colour of whatever it sits in rather than carrying its own', () => {
    const svg = el(render('shield')).querySelector('svg')!;
    expect(getComputedStyle(svg).stroke).toBe('currentcolor');
  });

  it('recognises exactly the keys it can draw', () => {
    expect(isAgentIcon('shield')).toBe(true);
    expect(isAgentIcon('dragon')).toBe(false);
    expect(isAgentIcon('')).toBe(false);
    expect(isAgentIcon(null)).toBe(false);
  });
});
