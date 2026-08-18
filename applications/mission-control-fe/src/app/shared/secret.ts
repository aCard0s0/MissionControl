/**
 * Last-4 mask for an opaque credential hint, e.g. `…aF92`. Returns '' for an
 * empty value. Used for the agent .env setup view only —
 * profile-template secrets never round-trip a value (not even a suffix) to the
 * client; they expose a set/recoverable flag instead.
 */
export function maskTail(value: string | null | undefined): string {
  return value ? '…' + value.slice(-4) : '';
}
