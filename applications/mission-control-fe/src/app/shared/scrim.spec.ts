import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Scrim } from './scrim';

@Component({
  standalone: true,
  imports: [Scrim],
  template: `
    <div class="scrim" mcScrim (dismiss)="dismissed.set(dismissed() + 1)">
      <div class="modal"><button id="inside">save</button></div>
    </div>`,
})
class Host {
  readonly dismissed = signal(0);
}

const mount = () => {
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const el = (sel: string) => fixture.nativeElement.querySelector(sel) as HTMLElement;
  return { fixture, host: fixture.componentInstance, el };
};

describe('Scrim', () => {
  it('dismisses on a click that lands on the backdrop itself', () => {
    const { host, el } = mount();

    el('.scrim').click();

    expect(host.dismissed()).toBe(1);
  });

  it('ignores a click inside the modal, without stopping the event', () => {
    const { host, el } = mount();
    let reachedTheDocument = false;
    document.addEventListener('click', () => { reachedTheDocument = true; }, { once: true });

    el('#inside').click();

    // the old markup swallowed this with stopPropagation on the modal; nothing needs it to
    expect(host.dismissed()).toBe(0);
    expect(reachedTheDocument).toBe(true);
  });

  it('dismisses on Escape, which is the half every dialog was missing', () => {
    const { host } = mount();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(host.dismissed()).toBe(1);
  });

  it('stops listening for Escape once the backdrop is gone', () => {
    const { fixture, host } = mount();
    fixture.destroy();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(host.dismissed()).toBe(0);
  });
});
