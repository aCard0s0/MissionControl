import { Injectable } from '@angular/core';
import { ApiCredential } from '../hermes-api';
import { Credential, CredentialInput } from '../models';
import { byName, LibraryStore } from './library-store';
import { toCredential } from './wire-mappers';

/**
 * Saved credentials — the keys and tokens the pickers offer.
 *
 * A thin slice with nothing to poll: these are rows the operator maintains, read once at boot.
 * Kept by name because this list is a dropdown — see {@link byName}.
 *
 * No value ever lands here, in either direction. A secret arrives as set/recoverable flags, and
 * the pickers that consume this slice post a credential's id for the server to resolve.
 */
@Injectable({ providedIn: 'root' })
export class CredentialStore extends LibraryStore<Credential, ApiCredential, CredentialInput> {
  readonly credentials = this.items;

  protected readonly noun = 'credential';
  protected readonly toModel = toCredential;
  protected override readonly order = byName;

  protected wire() {
    return this.ctx.api.credentials;
  }

  /** Every credential holding an entry for `envVar` — what a picker on that row may offer. */
  providing(envVar: string): Credential[] {
    return this.credentials().filter(c => c.entries.some(e => e.key === envVar));
  }
}
