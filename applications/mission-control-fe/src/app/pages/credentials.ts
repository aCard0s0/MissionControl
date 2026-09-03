import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CredentialStore } from '../core/store/credential-store';
import { Credential } from '../core/models';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';

/**
 * What a profile `.env` key may look like — mirrors `EnvEntry.KEY_PATTERN` on the server, which
 * every writer below the API goes through. Deliberately narrower than a POSIX variable name:
 * only the upper-case form hermes reads out of a `.env`.
 */
const ENV_KEY = /^[A-Z][A-Z0-9_]{1,63}$/;

/** One entry row in the editor. `set`/`recoverable` describe what is already stored, so a
 *  blank value box can mean "keep" rather than "empty". */
interface EntryRow {
  key: string;
  value: string;
  secret: boolean;
  set: boolean;
  recoverable: boolean;
}

const blankRow = (): EntryRow =>
  ({ key: '', value: '', secret: true, set: false, recoverable: true });

/**
 * The credential library: keys and tokens saved once, then offered as a dropdown wherever one
 * is typed — an agent's Setup tab, the create-agent dialog, a blueprint's keys tab.
 *
 * A credential is a *bundle*, which is the only structural decision on this page. A messaging
 * platform is a bot token plus a home channel and a self-hosted provider is a base URL plus a
 * key, so one row per variable would make an operator save and pick the halves separately.
 *
 * No value on this page ever came from the server. A secret arrives as `set`/`recoverable`
 * flags and its box starts blank, which is how the editor says "keep what you hold" — the same
 * contract the MCP catalog editor and the blueprint keys tab already use.
 *
 * Flat, with a search box and no groups. The three group families that exist file libraries
 * with hundreds of rows; a handful of credentials does not need a fourth.
 */
@Component({
  selector: 'mc-credentials',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Reveal],
  templateUrl: './credentials.html',
  styleUrl: './credentials.scss',
})
export class CredentialsPage {
  protected readonly store = inject(CredentialStore);
  protected readonly ago = ago;

  protected readonly query = signal('');

  protected readonly editorOpen = signal(false);
  /** The credential being edited, or null while composing a new one. */
  protected readonly editId = signal<string | null>(null);
  protected readonly saving = signal(false);

  // Plain fields, not signals: the editor's template writes them through `ngModel`, and that
  // event is what re-evaluates `canSave()`.
  protected fName = '';
  protected fDescription = '';
  /** A signal, unlike the fields above: the add/remove buttons write it. Rows themselves are
   *  mutated in place, because `ngModel` binds straight into them. */
  protected readonly rows = signal<EntryRow[]>([]);

  protected readonly visible = computed(() => {
    const needle = this.query().trim().toLowerCase();
    if (!needle) return this.store.credentials();
    return this.store.credentials().filter(c =>
      [c.name, c.description, ...c.entries.map(e => e.key)]
        .some(field => field.toLowerCase().includes(needle)));
  });

  constructor() {
    // LiveSync loads these at boot; this covers a deep link that lands here first
    void this.store.refresh();
  }

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  /** How many entries this key can no longer open. Reported rather than hidden: the operator
   *  has to see the loss to replace it, and every picker offering one will refuse the write
   *  until they do. */
  protected broken(credential: Credential): number {
    return credential.entries.filter(e => e.secret && e.set && !e.recoverable).length;
  }

  // ── the editor ────────────────────────────────────────────────────────────

  protected newCredential(): void {
    this.editId.set(null);
    this.fName = '';
    this.fDescription = '';
    this.rows.set([blankRow()]);
    this.editorOpen.set(true);
  }

  protected edit(credential: Credential): void {
    this.editId.set(credential.id);
    this.fName = credential.name;
    this.fDescription = credential.description;
    // a secret's box starts blank whatever is stored — there is no ciphertext to show, and a
    // blank submission is what tells the server to keep it
    this.rows.set(credential.entries.map(e => ({
      key: e.key,
      value: e.secret ? '' : e.value,
      secret: e.secret,
      set: e.set,
      recoverable: e.recoverable,
    })));
    this.editorOpen.set(true);
  }

  protected cancel(): void {
    this.editorOpen.set(false);
    this.editId.set(null);
  }

  protected addRow(): void {
    this.rows.update(rows => [...rows, blankRow()]);
  }

  /** An entry removed here is removed on save: the editor submits the whole list, so what it
   *  leaves out is what it deleted. */
  protected removeRow(index: number): void {
    this.rows.update(rows => rows.filter((_, i) => i !== index));
  }

  protected keyValid(row: EntryRow): boolean {
    return ENV_KEY.test(row.key.trim().toUpperCase());
  }

  /** Blank on a stored secret means keep; blank on a new one has nothing to keep, and the
   *  server refuses it rather than reporting a save that stored nothing. */
  protected valueMissing(row: EntryRow): boolean {
    return row.secret && !row.value.trim() && !row.set;
  }

  protected canSave(): boolean {
    const rows = this.rows();
    return !!this.fName.trim()
      && !this.saving()
      && rows.length > 0
      && rows.every((r, i) =>
        this.keyValid(r) && !this.valueMissing(r) && !this.duplicateKey(r, i));
  }

  protected duplicateKey(row: EntryRow, index: number): boolean {
    const key = row.key.trim().toUpperCase();
    return !!key && this.rows().some((r, i) => i !== index && r.key.trim().toUpperCase() === key);
  }

  protected async save(): Promise<void> {
    if (!this.canSave()) return;
    this.saving.set(true);
    const id = await this.store.save({
      name: this.fName.trim(),
      description: this.fDescription.trim(),
      entries: this.rows().map(r => ({
        key: r.key.trim().toUpperCase(),
        value: r.value.trim(),
        secret: r.secret,
      })),
    }, this.editId() ?? undefined);
    this.saving.set(false);
    // a failed save keeps the editor open with the values still in it — retyping a key
    // because the backend blinked is the one thing this page must never cost
    if (id) this.cancel();
  }

  protected async remove(credential: Credential): Promise<void> {
    if (!confirm(`Delete credential "${credential.name}"? `
      + 'Every key it already filled stays where it was written.')) return;
    if (!await this.store.remove(credential.id)) return;
    if (this.editId() === credential.id) this.cancel();
  }
}
