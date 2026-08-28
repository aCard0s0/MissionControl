import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { Scrim } from './scrim';

@Component({
  standalone: true,
  imports: [Scrim],
  template: `
    @if (open()) {
      <div class="scrim" mcScrim (dismiss)="dismissed.set(dismissed() + 1)">
        <div class="modal">
          <button id="first">first</button>
          <input id="middle" />
          <button id="inside">save</button>
        </div>
      </div>
    }`,
})
class Host {
  readonly dismissed = signal(0);
  readonly open = signal(true);
}

/** A backdrop with nothing in it — the shape behind a menu, not a dialog. */
@Component({
  standalone: true,
  imports: [Scrim],
  template: `<div class="scrim" mcScrim (dismiss)="dismissed.set(dismissed() + 1)"></div>`,
})
class BareHost {
  readonly dismissed = signal(0);
}

const mount = () => {
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const el = (sel: string) => fixture.nativeElement.querySelector(sel) as HTMLElement;
  return { fixture, host: fixture.componentInstance, el };
};

/** A Tab that the browser would act on, so the directive gets a chance to preventDefault. */
const tab = (from: HTMLElement, shift = false) => {
  const event = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: shift, bubbles: true, cancelable: true });
  from.dispatchEvent(event);
  return event;
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

describe('Scrim focus', () => {
  it('moves focus into the dialog, so the keyboard starts where the eye is', () => {
    const { el } = mount();

    expect(document.activeElement).toBe(el('#first'));
  });

  it('wraps forward off the last control instead of leaving for the covered page', () => {
    const { el } = mount();
    el('#inside').focus();

    const event = tab(el('#inside'));

    expect(document.activeElement).toBe(el('#first'));
    expect(event.defaultPrevented).toBe(true);
  });

  it('wraps backward off the first control', () => {
    const { el } = mount();
    el('#first').focus();

    const event = tab(el('#first'), true);

    expect(document.activeElement).toBe(el('#inside'));
    expect(event.defaultPrevented).toBe(true);
  });

  it('leaves a Tab in the middle of the dialog to the browser', () => {
    const { el } = mount();
    el('#middle').focus();

    const event = tab(el('#middle'));

    // not our business: the browser moves to the next control on its own
    expect(event.defaultPrevented).toBe(false);
  });

  it('does not trap a backdrop that holds nothing focusable', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();

    const fixture = TestBed.createComponent(BareHost);
    fixture.detectChanges();

    // a click-catcher behind a menu: moving focus in would strand the keyboard
    expect(document.activeElement).toBe(opener);
    const scrim = fixture.nativeElement.querySelector('.scrim') as HTMLElement;
    expect(tab(scrim).defaultPrevented).toBe(false);
    opener.remove();
  });

  it('gives focus back to whatever opened the dialog', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();

    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    fixture.componentInstance.open.set(false);
    fixture.detectChanges();

    expect(document.activeElement).toBe(opener);
    opener.remove();
  });

  it('does not throw when the opener was re-rendered away while the dialog was open', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();

    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    opener.remove();                       // a list refreshed underneath the dialog
    fixture.componentInstance.open.set(false);

    expect(() => fixture.detectChanges()).not.toThrow();
  });
});
