import '@angular/compiler';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Confirm, ConfirmDialog } from './confirm';
import { button, el, press, settle, text, type } from '../testing/dom';

describe('Confirm', () => {
  let fixture: ComponentFixture<ConfirmDialog>;
  let confirm: Confirm;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(ConfirmDialog);
    confirm = TestBed.inject(Confirm);
    fixture.detectChanges();
  });

  afterEach(() => confirm.answer(false));

  it('renders nothing until something is asked', () => {
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('shows the question and resolves true on the action', async () => {
    const answer = confirm.ask({ title: 'delete prompt', message: 'Delete "Triage"? This cannot be undone.' });
    await settle(fixture);

    expect(text(fixture)).toContain('delete prompt');
    expect(text(fixture)).toContain('Delete "Triage"?');
    expect(el(fixture).querySelector('.panel-h')!.classList).toContain('crit-h');
    press(fixture, 'delete');

    expect(await answer).toBe(true);
    await settle(fixture);
    expect(el(fixture).querySelector('.modal')).toBeNull();
  });

  it('resolves false on cancel, and on the backdrop', async () => {
    const first = confirm.ask({ title: 'delete', message: 'x' });
    await settle(fixture);
    press(fixture, 'cancel');
    expect(await first).toBe(false);

    const second = confirm.ask({ title: 'delete', message: 'y' });
    await settle(fixture);
    el(fixture).querySelector<HTMLElement>('.scrim')!.click();
    expect(await second).toBe(false);
  });

  it('holds the action until the name is typed exactly, then clears the box for the next ask', async () => {
    const answer = confirm.ask({ title: 'delete container', message: 'gone', typed: 'hermes-prod', action: 'delete permanently' });
    await settle(fixture);

    expect(button(fixture, 'delete permanently').disabled).toBe(true);
    await type(fixture, '#mc-confirm-typed', 'hermes-pro');
    expect(button(fixture, 'delete permanently').disabled).toBe(true);
    await type(fixture, '#mc-confirm-typed', 'hermes-prod');
    expect(button(fixture, 'delete permanently').disabled).toBe(false);
    press(fixture, 'delete permanently');
    expect(await answer).toBe(true);

    void confirm.ask({ title: 'delete container', message: 'again', typed: 'hermes-prod' });
    await settle(fixture);
    expect(el(fixture).querySelector<HTMLInputElement>('#mc-confirm-typed')!.value).toBe('');
    expect(button(fixture, 'delete').disabled).toBe(true);
  });

  it('answers an open question with no when another is asked over it', async () => {
    const first = confirm.ask({ title: 'delete', message: 'first' });
    const second = confirm.ask({ title: 'delete', message: 'second' });
    await settle(fixture);

    expect(await first).toBe(false);
    expect(text(fixture)).toContain('second');
    void second;
  });

  it('is a warning, not a deletion, when asked to warn', async () => {
    void confirm.ask({ title: 'no key', message: 'may fail to authenticate', action: 'deploy anyway', warn: true });
    await settle(fixture);

    expect(el(fixture).querySelector('.panel-h')!.classList).not.toContain('crit-h');
    expect(button(fixture, 'deploy anyway').classList).toContain('primary');
  });
});
