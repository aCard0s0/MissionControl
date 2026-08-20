import { afterEach, describe, expect, it, vi } from 'vitest';
import { copyText } from './copy-text';

/** Swaps `navigator.clipboard` for the test and hands back the undo. */
const withClipboard = (clipboard: unknown): (() => void) => {
  const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true });
  return () => {
    if (original) Object.defineProperty(navigator, 'clipboard', original);
    else Reflect.deleteProperty(navigator as unknown as Record<string, unknown>, 'clipboard');
  };
};

type Doc = Document & { execCommand?: (command: string) => boolean };

describe('copyText', () => {
  const undo: (() => void)[] = [];

  afterEach(() => {
    while (undo.length) undo.pop()!();
    Reflect.deleteProperty(document as Doc, 'execCommand');
    vi.unstubAllGlobals();
  });

  it('uses the clipboard API where the page has one', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    undo.push(withClipboard({ writeText }));

    expect(await copyText('hermes status')).toBe(true);
    expect(writeText).toHaveBeenCalledWith('hermes status');
  });

  it('falls back to execCommand where it does not — the tailnet deploy is plain HTTP', async () => {
    // navigator.clipboard is undefined outside a secure context, and the default
    // `./mc start` flavor serves http://mission-control.<tailnet>.ts.net
    undo.push(withClipboard(undefined));
    const execCommand = vi.fn().mockReturnValue(true);
    (document as Doc).execCommand = execCommand;

    expect(await copyText('hermes doctor')).toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('falls back when the clipboard API exists but refuses', async () => {
    undo.push(withClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) }));
    const execCommand = vi.fn().mockReturnValue(true);
    (document as Doc).execCommand = execCommand;

    expect(await copyText('hermes logs -f')).toBe(true);
    expect(execCommand).toHaveBeenCalledWith('copy');
  });

  it('reports failure rather than pretending, so the button can stay silent', async () => {
    undo.push(withClipboard(undefined));
    (document as Doc).execCommand = vi.fn().mockReturnValue(false);

    expect(await copyText('hermes status')).toBe(false);
  });

  it('leaves no textarea behind on any path', async () => {
    undo.push(withClipboard(undefined));
    (document as Doc).execCommand = vi.fn(() => { throw new Error('not allowed'); });

    expect(await copyText('hermes status')).toBe(false);
    expect(document.querySelectorAll('textarea')).toHaveLength(0);
  });
});
