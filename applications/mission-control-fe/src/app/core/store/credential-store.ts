import { inject, Injectable, signal, WritableSignal } from '@angular/core';
import { Credential, CredentialInput } from '../models';
import { StoreContext } from './store-context';
import { toCredential } from './wire-mappers';

/**
 * Saved credentials — the keys and tokens the pickers offer.
 *
 * A thin slice with nothing to poll: these are rows the operator maintains, read once at boot.
 * Kept by name rather than newest-first, which the backend already does — this list is a
 * dropdown, and an option that moves because an unrelated row was renamed makes the picker
 * unreadable.
 *
 * No value ever lands here, in either direction. A secret arrives as set/recoverable flags, and
 * the pickers that consume this slice post a credential's id for the server to resolve.
 */
@Injectable({ providedIn: 'root' })
export class CredentialStore {
  readonly credentials: WritableSignal<Credential[]> = signal([]);

  private readonly ctx = inject(StoreContext);

  async refresh(): Promise<void> {
    try {
      this.credentials.set((await this.ctx.api.credentials.list()).map(toCredential));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Every credential holding an entry for `envVar` — what a picker on that row may offer. */
  providing(envVar: string): Credential[] {
    return this.credentials().filter(c => c.entries.some(e => e.key === envVar));
  }

  /** Create (no id) or update (id). Returns the id, or '' on failure. */
  async save(input: CredentialInput, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.ctx.api.credentials.update(id, input)
        : await this.ctx.api.credentials.create(input);
      this.upsert(toCredential(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure('save credential', e);
      return '';
    }
  }

  async remove(id: string): Promise<boolean> {
    try {
      await this.ctx.api.credentials.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete credential', e);
      return false;
    }
    this.credentials.update(cs => cs.filter(c => c.id !== id));
    return true;
  }

  private upsert(credential: Credential): void {
    this.credentials.update(cs => [
      ...cs.filter(c => c.id !== credential.id), credential,
    ].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })));
  }
}
