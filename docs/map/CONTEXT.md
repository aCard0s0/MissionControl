# How to walk this map

Three hops, maximum. If you need a fourth, the map is wrong — say so.

1. **[CLAUDE.md](CLAUDE.md)** — where things live, the four-way `provider`/`model` collision,
   the universes. Read this whole file; it is short on purpose.
2. **[objects/_index.md](objects/_index.md)** or **[effects/CONTEXT.md](effects/CONTEXT.md)** —
   one line per noun, or one line per change you are about to make.
3. **one card** — the noun's shape, its first-order waterfall, and a `See` link to source.

Then open the source. The card is the index; the code is the answer.

## What this map is not

- Not a spec. [../architecture.md](../architecture.md) is the spec, and it is good — 377 lines
  of load-bearing "why", including the whole terminal design, the webhook-exposure reasoning
  and the image-update contract. Cards point into it by section.
- Not an API reference. [../api.md](../api.md) is that, and `applications/api-contract.txt` is
  the machine-checked one.
- Not a test guide. [../testing.md](../testing.md) is that. Read it before writing a test —
  most of what is worth testing here sits behind a Docker daemon or an async executor.

## Reading the source: two hazards

**The javadoc is load-bearing.** This codebase explains itself in class-level comments, and
several of them record a defect that motivated the current shape — `ManagedContainer`,
`ProfileSpec`, `HermesCli`, `ContainerUpdateService.remap`. Read the comment before the
method. Where a comment and the code disagree, the code wins and the card says so.

**`grep -r` can double-hit.** Claude Code puts worktrees under `.claude/worktrees/`, and a
worktree is a second full copy of this repo: every recursive grep then finds every match twice,
in two files that look equally real. There is none on disk right now and the directory is
gitignored, but one appears whenever a session opens a branch that way — so exclude it by
habit rather than checking first.

```bash
grep -rn 'thing' --exclude-dir=node_modules --exclude-dir=target \
  --exclude-dir=.angular --exclude-dir=.claude .
```

Check `git worktree list` if a path surprises you.

## Keeping it honest

A card is `verified` only with a commit and a date in its frontmatter. `stale` is a legitimate
status. A confident wrong date is not. When you change a noun, the cheapest correct move is to
update its card in the same commit — or set `status: stale` and move on. Do not leave a
`verified` card describing code you just changed.

That much is convention, and convention did not hold: the map was written against one commit
and eight commits later twenty citations pointed at blank lines and stray braces, because a
table rename had shifted `schema.sql` under seven cards at once. So the part a machine can
check, it checks. `MapIntegrityTest` fails the backend build when a cited file has gone, when a
cited line is past the end of it, when a citation lands on a lone brace — the signature of a
line number that moved — when a link goes nowhere, or when an object card is missing from
`objects/_index.md`.

It cannot tell whether a card is **right**; that still needs a person reading the source. It
only bounds how wrong one can quietly become. So `status: verified` remains a claim you are
making, not one the build made for you.
