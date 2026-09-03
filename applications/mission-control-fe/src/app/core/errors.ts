// How a failed call becomes something an operator can read. Here rather than beside one
// caller: a rejection is displayed in three different shapes across this app — a toast, an
// inline panel error, a log-tail banner — and each used to re-derive the message its own way.
//
// There is no error class of our own. One carried the HTTP status for "a caller that has to
// tell 404 from 500", and in the whole app no caller ever read it: every one of them shows
// the words. A backend answer says what went wrong in its `error` body, which is what
// `ApiHttp` puts in the message.

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
