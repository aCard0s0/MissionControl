// What a failed call is, and how it becomes something an operator can read.
// Both live here rather than beside one caller: a rejection is displayed in
// three different shapes across this app — a toast, an inline panel error, a
// log-tail banner — and each used to re-derive the message its own way.

/**
 * A non-2xx answer from the backend, carrying the status alongside whatever the
 * body said. {@link ApiHttp} throws this so a caller that wants to react to the
 * status — not only show the text — has it without re-parsing a message.
 */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * The message a thrown value carries, tolerating the shapes that are not
 * `Error`: a rejected fetch, a DOMException, a plain `{ message }` object.
 *
 * An object that names no message answers nothing rather than stringifying —
 * `[object Object]` in a toast tells an operator strictly less than the caller's
 * own fallback does.
 */
function rawMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === 'object' && error !== null) {
    const message = (error as { message?: unknown }).message;
    return typeof message === 'string' ? message : '';
  }
  return error === undefined || error === null ? '' : String(error);
}

/**
 * Whatever a rejected promise carried, as something safe to show an operator.
 * `fallback` is what to say when the failure carried no words of its own —
 * an aborted request and a rejected `fetch` both do that.
 */
export function errorMessage(error: unknown, fallback = 'unknown error'): string {
  return rawMessage(error).trim() || fallback;
}
