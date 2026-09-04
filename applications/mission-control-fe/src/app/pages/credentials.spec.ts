import '@angular/compiler';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { CredentialsPage } from './credentials';
import { Credential, CredentialInput } from '../core/models';
import { buttonWith, el, fill, press, settle, text, type, stubConfirm } from '../testing/dom';
import { provideStores } from '../testing/store';

const entry = (key: string, patch: object = {}) =>
  ({ key, value: '', secret: true, set: true, recoverable: true, ...patch });

const credential = (id: string, name: string, patch: Partial<Credential> = {}): Credential => ({
  id, name, description: 'the production key',
  entries: [entry('ANTHROPIC_API_KEY')],
  createdAt: 1_000, updatedAt: 1_700_000_000_000, ...patch,
});

/** Only what the page reaches for. */
const storeStub = (initial: Credential[] = []) => {
  const list = signal(initial);
  return {
    credentials: {
      credentials: list,
      refresh: vi.fn().mockResolvedValue(undefined),
      save: vi.fn<(input: CredentialInput, id?: string) => Promise<string>>()
        .mockResolvedValue('cr-new'),
      remove: vi.fn().mockResolvedValue(true),
      providing: (envVar: string) => list().filter(c => c.entries.some(e => e.key === envVar)),
    },
    list,
  };
};

const render = (store: ReturnType<typeof storeStub>): ComponentFixture<CredentialsPage> => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
  const fixture = TestBed.createComponent(CredentialsPage);
  fixture.detectChanges();
  return fixture;
};

/** The nth variable row's key / value boxes. */
const keyBox = (fixture: ComponentFixture<CredentialsPage>, n = 0) =>
  el(fixture).querySelectorAll<HTMLInputElement>('.kv-row .key')[n];

describe('CredentialsPage', () => {
  // the whole file: `data-reveal` tweens on a real timer, and one finishing after jsdom
  // teardown exits the run non-zero while every test reports green
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the library on open and lists what came back', () => {
    const store = storeStub([credential('cr-1', 'anthropic prod')]);
    const fixture = render(store);

    expect(store.credentials.refresh).toHaveBeenCalled();
    expect(text(fixture)).toContain('anthropic prod');
    expect(text(fixture)).toContain('ANTHROPIC_API_KEY');
    expect(text(fixture)).toContain('the production key');
  });

  it('says how to start when the library is empty', () => {
    const fixture = render(storeStub());

    expect(text(fixture)).toContain('No credentials saved');
  });

  it('distinguishes an empty library from a search that matched nothing', async () => {
    const fixture = render(storeStub([credential('cr-1', 'anthropic prod')]));

    await type(fixture, '.find', 'telegram');
    fixture.detectChanges();

    expect(text(fixture)).toContain('Nothing matches that search');
  });

  it('searches names, descriptions and variable names', async () => {
    const fixture = render(storeStub([
      credential('cr-1', 'anthropic prod'),
      credential('cr-2', 'telegram ops', {
        description: 'the ops bot', entries: [entry('TELEGRAM_BOT_TOKEN')],
      }),
    ]));

    await type(fixture, '.find', 'TELEGRAM_BOT');
    fixture.detectChanges();
    expect(text(fixture)).toContain('telegram ops');
    expect(text(fixture)).not.toContain('anthropic prod');

    await type(fixture, '.find', 'ops bot');
    fixture.detectChanges();
    expect(text(fixture)).toContain('telegram ops');
  });

  it('marks a credential whose envelope this key can no longer open', () => {
    // kept on screen rather than hidden: the operator has to see the loss to replace it
    const fixture = render(storeStub([credential('cr-1', 'anthropic prod', {
      entries: [entry('ANTHROPIC_API_KEY', { recoverable: false })],
    })]));

    expect(text(fixture)).toContain('1 unreadable');
  });

  it('shows a plain entry as informational rather than as a stored secret', () => {
    const fixture = render(storeStub([credential('cr-1', 'telegram ops', {
      entries: [
        entry('TELEGRAM_BOT_TOKEN'),
        entry('TELEGRAM_HOME_CHANNEL', { secret: false, value: '#ops' }),
      ],
    })]));

    const chips = Array.from(el(fixture).querySelectorAll('.vars .chip'));
    expect(chips[1].classList.contains('info')).toBe(true);
    expect(chips[1].getAttribute('title')).toContain('#ops');
  });

  // ── the editor ────────────────────────────────────────────────────────────

  it('opens a new credential with one blank variable row', async () => {
    const fixture = render(storeStub());

    press(fixture, '+ new credential');
    await settle(fixture);

    expect(text(fixture)).toContain('NEW CREDENTIAL');
    expect(el(fixture).querySelectorAll('.kv-row').length).toBe(1);
  });

  it('saves the name, description and every variable, upper-casing the keys', async () => {
    const store = storeStub();
    const fixture = render(store);
    press(fixture, '+ new credential');
    await settle(fixture);

    await fill(fixture, 'name', 'Telegram ops');
    await fill(fixture, 'description', 'the ops bot');
    await type(fixture, '.kv-row .key', 'telegram_bot_token');
    await type(fixture, '.kv-row input[type="password"]', 'bot-live-1234');
    press(fixture, 'save credential');
    await settle(fixture);

    expect(store.credentials.save).toHaveBeenCalledWith({
      name: 'Telegram ops',
      description: 'the ops bot',
      entries: [{ key: 'TELEGRAM_BOT_TOKEN', value: 'bot-live-1234', secret: true }],
    }, undefined);
  });

  it('refuses a key no profile .env could hold, and says why', async () => {
    const store = storeStub();
    const fixture = render(store);
    press(fixture, '+ new credential');
    await settle(fixture);
    await fill(fixture, 'name', 'anthropic');
    await type(fixture, '.kv-row input[type="password"]', 'sk-1');

    await type(fixture, '.kv-row .key', 'lower-case');
    fixture.detectChanges();

    expect(text(fixture)).toContain('a profile .env variable is upper case');
    expect(buttonWith(fixture, 'save credential').disabled).toBe(true);
  });

  it('refuses two rows naming the same variable', async () => {
    const store = storeStub();
    const fixture = render(store);
    press(fixture, '+ new credential');
    await settle(fixture);
    await fill(fixture, 'name', 'anthropic');
    press(fixture, '+ variable');
    await settle(fixture);

    keyBox(fixture, 0).value = 'A_KEY';
    keyBox(fixture, 0).dispatchEvent(new Event('input'));
    keyBox(fixture, 1).value = 'A_KEY';
    keyBox(fixture, 1).dispatchEvent(new Event('input'));
    await settle(fixture);

    expect(text(fixture)).toContain('A_KEY is already in this credential');
    expect(buttonWith(fixture, 'save credential').disabled).toBe(true);
  });

  it('refuses a new secret with no value, rather than saving nothing under its name', async () => {
    const store = storeStub();
    const fixture = render(store);
    press(fixture, '+ new credential');
    await settle(fixture);
    await fill(fixture, 'name', 'anthropic');
    await type(fixture, '.kv-row .key', 'ANTHROPIC_API_KEY');
    await settle(fixture);

    expect(text(fixture)).toContain('a new secret needs a value');
    expect(buttonWith(fixture, 'save credential').disabled).toBe(true);
  });

  it('opens a stored secret with a blank box, which is how it says "keep"', async () => {
    const fixture = render(storeStub([credential('cr-1', 'anthropic prod')]));

    press(fixture, 'edit');
    await settle(fixture);

    const value = el(fixture).querySelector<HTMLInputElement>('.kv-row input[type="password"]')!;
    expect(value.value).toBe('');
    expect(value.placeholder).toContain('blank keeps current value');
    // and a blank stored secret is saveable, unlike a blank new one
    expect(buttonWith(fixture, 'save credential').disabled).toBe(false);
  });

  it('saves an edit against the credential id', async () => {
    const store = storeStub([credential('cr-1', 'anthropic prod')]);
    const fixture = render(store);
    press(fixture, 'edit');
    await settle(fixture);

    await fill(fixture, 'name', 'anthropic staging');
    press(fixture, 'save credential');
    await settle(fixture);

    expect(store.credentials.save).toHaveBeenCalledWith(expect.objectContaining({
      name: 'anthropic staging',
      entries: [{ key: 'ANTHROPIC_API_KEY', value: '', secret: true }],
    }), 'cr-1');
  });

  it('keeps the editor open with the values still in it when a save fails', async () => {
    // retyping a key because the backend blinked is the one thing this page must never cost
    const store = storeStub();
    store.credentials.save.mockResolvedValue('');
    const fixture = render(store);
    press(fixture, '+ new credential');
    await settle(fixture);
    await fill(fixture, 'name', 'anthropic');
    await type(fixture, '.kv-row .key', 'ANTHROPIC_API_KEY');
    await type(fixture, '.kv-row input[type="password"]', 'sk-1');

    press(fixture, 'save credential');
    await settle(fixture);

    expect(text(fixture)).toContain('NEW CREDENTIAL');
    expect(keyBox(fixture).value).toBe('ANTHROPIC_API_KEY');
  });

  it('closes the editor once a save lands', async () => {
    const store = storeStub();
    const fixture = render(store);
    press(fixture, '+ new credential');
    await settle(fixture);
    await fill(fixture, 'name', 'anthropic');
    await type(fixture, '.kv-row .key', 'ANTHROPIC_API_KEY');
    await type(fixture, '.kv-row input[type="password"]', 'sk-1');

    press(fixture, 'save credential');
    await settle(fixture);

    expect(text(fixture)).not.toContain('NEW CREDENTIAL');
  });

  it('removes a variable row, which is how an entry is deleted', async () => {
    const store = storeStub([credential('cr-1', 'telegram ops', {
      entries: [entry('TELEGRAM_BOT_TOKEN'), entry('TELEGRAM_HOME_CHANNEL')],
    })]);
    const fixture = render(store);
    press(fixture, 'edit');
    await settle(fixture);

    press(fixture, 'remove');
    await settle(fixture);
    press(fixture, 'save credential');
    await settle(fixture);

    expect(store.credentials.save).toHaveBeenCalledWith(expect.objectContaining({
      entries: [{ key: 'TELEGRAM_HOME_CHANNEL', value: '', secret: true }],
    }), 'cr-1');
  });

  it('warns that a delete leaves every key it already filled where it was written', async () => {
    const store = storeStub([credential('cr-1', 'anthropic prod')]);
    const fixture = render(store);
    const confirm = stubConfirm(true);

    press(fixture, 'delete');
    await settle(fixture);

    expect(confirm.mock.calls[0][0].message).toContain('stays where it was written');
    expect(store.credentials.remove).toHaveBeenCalledWith('cr-1');
  });

  it('does not delete when the confirm is declined', async () => {
    const store = storeStub([credential('cr-1', 'anthropic prod')]);
    const fixture = render(store);
    stubConfirm(false);

    press(fixture, 'delete');
    await settle(fixture);

    expect(store.credentials.remove).not.toHaveBeenCalled();
  });
});
