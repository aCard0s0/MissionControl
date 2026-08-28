# objects — the nouns

One card per noun. Clustered by **how an editor asks**, not by package or folder — which is why
[models/](models/) holds cards from three different Java packages and why
[dashboard/](dashboard/) holds a generated text file next to a WebSocket session.

| Cluster | What lands here |
|---|---|
| [docker/](docker/) | the daemon layer: hosts, containers, images. Owns no state. |
| [agents/](agents/) | hermes' own state, read from its files and written through its CLI. |
| [models/](models/) | the four nouns that all spell themselves "provider" or "model". |
| [mcp/](mcp/) | the catalog, the `mission-control-mcp` Compose project, and the links. |
| [dashboard/](dashboard/) | what Mission Control owns itself, plus two boundaries. |

Start at [_index.md](_index.md) — every noun has a line there, including the ones with no card.

## What a card is for

Answering two questions and nothing else: **what is X**, and **what else moves if I change X**.

A card is not documentation of behaviour. If you want to know what the code does, the card's
`See` link takes you there in one hop. If a card starts explaining as-built behaviour instead of
citing it, delete the explanation.

## Reading `If you change this`

**Hits** is first-order only — what the change reaches directly. It is not a transitive closure,
and following two cards' Hits lists is how you get the second order.

**Does not hit** is the more useful half. It names the *obvious next thing that is wrong*: the
noun a reasonable person would assume moves and which does not. Most wasted edits in this tree
are in that gap — `/api/providers` vs `/api/inference-endpoints` being the standing example, and
the reason the latter was renamed off `model-providers`.

## Status honesty

`verified` needs a commit and a date in the frontmatter. `stale` is fine and useful. A
`verified` card describing code someone changed last week is worse than no card, because it will
be believed.
