import { ContainerResources } from './models';

/**
 * What a Hermes container is documented to need, and what this app deploys.
 *
 * <p>From the vendor's Docker guide
 * (https://hermes-agent.nousresearch.com/docs/user-guide/docker):
 *
 * | resource            | minimum | recommended               |
 * |---------------------|---------|---------------------------|
 * | memory              | 1 GB    | 2–4 GB                    |
 * | cpu                 | 1 core  | 2 cores                   |
 * | disk (data volume)  | 500 MB  | 2+ GB, grows with use     |
 *
 * <p>Browser automation (Playwright/Chromium) is the memory-hungry feature: 1 GB
 * is enough without it, at least 2 GB with it.
 *
 * <p>The backend holds the same numbers and enforces them — this copy is what
 * the form fills itself in with, not the authority.
 */
export const HERMES_BASELINE: ContainerResources = { memoryMb: 2048, cpus: 2 };

/** Offered as buttons, so the common case is one click rather than a typed number. */
export const MEMORY_PRESETS_MB = [1024, 2048, 4096, 8192, 16384] as const;
export const CPU_PRESETS = [1, 2, 4, 8] as const;

/** `2 GB` / `512 MB` — GB once the value divides evenly, which every preset does. */
export function formatMemory(mb: number): string {
  return mb >= 1024 && mb % 1024 === 0 ? `${mb / 1024} GB` : `${mb} MB`;
}

/**
 * What to say about a chosen size, or null when there is nothing worth saying.
 *
 * <p>Only two cases are worth a word: at the vendor's floor browser automation
 * will not fit, and well above the recommendation the operator is spending host
 * memory the docs do not ask for. Everything between is unremarkable and says
 * nothing, so the note means something when it does appear.
 */
export function memoryNote(mb: number): string | null {
  if (mb < 2048) return 'below 2 GB, browser automation (Playwright/Chromium) will not fit';
  if (mb > 4096) return 'above the recommended 2–4 GB — reserved on the host whether used or not';
  return null;
}
