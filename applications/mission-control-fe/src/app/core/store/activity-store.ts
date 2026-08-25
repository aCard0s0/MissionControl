import { Injectable, computed, signal } from '@angular/core';

/** One operation the operator started that has not finished yet. */
export interface Activity {
  readonly id: number;
  /** Present tense, as the shell reads it out: `deploying ops-bot`. */
  readonly label: string;
  readonly startedAt: number;
}

/**
 * The operations that outlive the control which started them.
 *
 * <p>A deploy is the backend's work, not the dialog's: it keeps running whether
 * or not the modal that submitted it is still on screen. A `busy` signal inside
 * that modal dies with it, so an operator who closed it — or simply navigated —
 * had no way left to tell a slow deploy from a finished one.
 *
 * <p>So the tracking lives here instead: root-provided, rendered once by the
 * shell, and therefore visible from every page. Components keep their own local
 * `busy` for the button they own; this is what answers "is anything still
 * running?" after that button is gone.
 */
@Injectable({ providedIn: 'root' })
export class ActivityStore {
  private readonly entries = signal<Activity[]>([]);
  private nextId = 1;

  /** What is running now, in the order it was started. */
  readonly active = this.entries.asReadonly();

  readonly busy = computed(() => this.entries().length > 0);

  /** Registers an operation and answers the handle that ends it. */
  begin(label: string): number {
    const id = this.nextId++;
    this.entries.update(list => [...list, { id, label, startedAt: Date.now() }]);
    return id;
  }

  /**
   * Ends `id`. An unknown or already-ended id is ignored rather than an error,
   * so a caller that cannot tell whether it already finished can say so twice.
   */
  end(id: number): void {
    this.entries.update(list => list.filter(a => a.id !== id));
  }

  /**
   * Brackets `work` with an entry that clears however it settles — including on
   * a throw, or the shell would go on advertising an operation that is over.
   */
  async run<T>(label: string, work: () => Promise<T>): Promise<T> {
    const id = this.begin(label);
    try {
      return await work();
    } finally {
      this.end(id);
    }
  }
}
