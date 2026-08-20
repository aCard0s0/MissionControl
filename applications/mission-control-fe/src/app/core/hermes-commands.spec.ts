import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  HERMES_COMMANDS, HERMES_COMMAND_GROUPS, HERMES_DOCS,
  agentSessionCommand, hermesDocsUrl, hermesLine, searchHermesCommands,
} from './hermes-commands';

const DOC = 'docs/hermes-cli.md';
/** What marks a directory as the repo root rather than just some ancestor of the cwd. */
const REPO_MARKERS = ['applications/mission-control-fe', 'docs'];

/**
 * Finds the repo root by walking up from the working directory, because the working directory
 * is not ours to assume: `ng test` roots at the frontend project, `vitest` from the repo root
 * roots there, and an IDE runner roots wherever it likes. A fixed `../../` resolved outside the
 * repo entirely under the last of those. `import.meta.url` is not the answer either — the
 * Angular builder rewrites it to a non-file URL.
 *
 * Null when there is no checkout above us at all, which is the Dockerfile's frontend stage:
 * it copies only `applications/mission-control-fe/`, and `.dockerignore` drops `docs` and every
 * `*.md` besides. The parity check is about two files in the repo agreeing, so a build context
 * that deliberately excludes one of them has nothing to check — see the skip below.
 */
const findRepoRoot = (): string | null => {
  let dir = process.cwd();
  for (;;) {
    if (REPO_MARKERS.every(marker => existsSync(join(dir, marker)))) return dir;
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
};

const repoRoot = findRepoRoot();

/**
 * The repo-side copy of the same reference, read as text so the two cannot drift apart. Empty
 * only where {@link findRepoRoot} found no checkout — and a checkout that has one marker but
 * not the doc is a deleted doc, which must fail rather than quietly stop being checked.
 */
const doc = repoRoot ? readFileSync(join(repoRoot, DOC), 'utf8') : '';

/** Every `hermes <cmd>` the doc lists in a table's first column. `-p` rows are the
 *  Mission-Control-runs-these table, which is prose about invocation, not a command list. */
const documented = new Set(
  [...doc.matchAll(/^\| `hermes ([a-z][a-z0-9-]*(?: [a-z][a-z0-9-]*)?)` \|/gm)].map(m => m[1]),
);

// the two parity tests, and only those, need the repo beside us
describe.skipIf(!repoRoot)(`hermes command catalog against ${DOC}`, () => {
  it('lists every command the repo reference documents', () => {
    const catalog = new Set(HERMES_COMMANDS.map(c => c.cmd));
    const missing = [...documented].filter(cmd => !catalog.has(cmd));
    expect(missing, 'documented in docs/hermes-cli.md but absent from the catalog').toEqual([]);
  });

  it('documents every command it lists — a row with no docs link is a dead end', () => {
    const missing = HERMES_COMMANDS.map(c => c.cmd).filter(cmd => !documented.has(cmd));
    expect(missing, 'in the catalog but absent from docs/hermes-cli.md').toEqual([]);
  });
});

describe('hermes command catalog', () => {
  it('names each command exactly once, across every group', () => {
    const names = HERMES_COMMANDS.map(c => c.cmd);
    expect(new Set(names).size).toBe(names.length);
  });

  it('spells commands as they are typed after `hermes`', () => {
    for (const c of HERMES_COMMANDS) {
      expect(c.cmd, `"${c.cmd}" repeats the binary name`).not.toMatch(/^hermes\b/);
      expect(c.cmd, `"${c.cmd}" leads with a flag`).not.toMatch(/^-/);
      expect(c.summary.length, `"${c.cmd}" has no summary`).toBeGreaterThan(0);
    }
  });

  it('gives every group at least one command to show', () => {
    for (const group of HERMES_COMMAND_GROUPS) {
      expect(group.commands.length, `group "${group.title}" is empty`).toBeGreaterThan(0);
      expect(group.blurb.length).toBeGreaterThan(0);
    }
  });

  it('links a command with an anchor to its own section, and one without to the page', () => {
    const anchored = HERMES_COMMANDS.find(c => c.cmd === 'cron');
    expect(hermesDocsUrl(anchored!)).toBe(`${HERMES_DOCS}#hermes-cron`);

    const bare = HERMES_COMMANDS.find(c => !c.anchor);
    expect(hermesDocsUrl(bare!)).toBe(HERMES_DOCS);
  });

  it('flags only the commands that rewrite the install, not the ones that touch a profile', () => {
    const flagged = HERMES_COMMANDS.filter(c => c.install).map(c => c.cmd);
    expect(flagged).toEqual(['update', 'claw', 'uninstall']);
  });
});

describe('profile scoping', () => {
  it('scopes a named profile with -p', () => {
    expect(agentSessionCommand('ops-bot')).toBe('hermes -p ops-bot');
  });

  it('invokes the default profile bare — hermes takes -p only for named ones', () => {
    expect(agentSessionCommand('default')).toBe('hermes');
  });

  it('accepts the punctuation hermes allows in a profile directory name', () => {
    expect(agentSessionCommand('ops.bot_2-v1')).toBe('hermes -p ops.bot_2-v1');
  });

  it('refuses a name carrying shell metacharacters so nothing is typed blind', () => {
    for (const name of ['ops; rm -rf /', 'ops bot', 'ops$(id)', 'ops`id`', 'ops&&id', '../escape', '']) {
      expect(agentSessionCommand(name)).toBeUndefined();
    }
  });
});

describe('command lines', () => {
  const cron = HERMES_COMMANDS.find(c => c.cmd === 'cron')!;

  it('reads as a runnable line with no profile', () => {
    expect(hermesLine(cron)).toBe('hermes cron');
  });

  it('carries the scoped profile so a command cannot silently read `default`', () => {
    expect(hermesLine(cron, 'ops-bot')).toBe('hermes -p ops-bot cron');
  });

  it('falls back to the bare invocation for a profile name it will not type', () => {
    // an operator still gets a runnable line and adds the -p themselves; the alternative is
    // pasting `hermes -p ops; rm -rf / cron` into a live shell
    expect(hermesLine(cron, 'ops; rm -rf /')).toBe('hermes cron');
  });
});

describe('search', () => {
  it('returns every group untouched for an empty query', () => {
    expect(searchHermesCommands('   ')).toBe(HERMES_COMMAND_GROUPS);
  });

  it('matches a command by name', () => {
    const hits = searchHermesCommands('webhook').flatMap(g => g.commands).map(c => c.cmd);
    expect(hits).toContain('webhook');
  });

  it('matches on a flag, because that is often all an operator remembers', () => {
    const hits = searchHermesCommands('--skip-venv').flatMap(g => g.commands).map(c => c.cmd);
    expect(hits).toEqual(['security audit']);
  });

  it('matches on the summary, so a command can be found by what it does', () => {
    const hits = searchHermesCommands('vulnerability').flatMap(g => g.commands).map(c => c.cmd);
    expect(hits).toEqual(['security audit']);
  });

  it('drops groups with nothing left rather than showing empty headings', () => {
    for (const group of searchHermesCommands('cron')) {
      expect(group.commands.length).toBeGreaterThan(0);
    }
  });

  it('finds nothing for a query nothing matches', () => {
    expect(searchHermesCommands('kubernetes')).toEqual([]);
  });
});
