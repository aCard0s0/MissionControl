---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 93c41f3 · 2026-09-04
entity: applications/mission-control-fe/src/app/shared/confirm.ts
---

# Confirm

The one question every destructive action asks. `Confirm` service plus the `ConfirmDialog`
that renders it, `shared/confirm.ts`; the dialog is mounted once in `app.html`, beside the
notifications.

## Why this shape

Ten pages deleted things through the browser's `confirm()` while the container and profile
deletes had proper dialogs, so the same act looked like two different apps — and a headless
browser dismisses `confirm()` silently, which is how an automated pass "deleted" a prompt and
found it still there.

- **A service, not a component per page.** `ask(request)` returns a promise of the answer;
  the page keeps only its question. The dialog reads `pending()` and is not part of any
  page's state.
- **One open question at a time.** Asking over an open dialog answers the first with `false`.
  Two questions at once is a bug upstream, and the safe reading of it is "no".
- **`typed` is for the irreversible.** A container, a profile, an MCP server: the operator
  retypes the name. A dashboard-owned record asks without it — the friction has to match
  what is lost. `warn: true` is the same dialog for a warning the operator may proceed past
  (a blueprint deployed without its key): plain heading, primary button.
- **It sits on the [scrim](scrim.md).** Escape, click-outside, focus trap and `inert` come
  from there; this component adds only the question.

## In tests

`stubConfirm(answer)` in `testing/dom.ts` spies on the root service — after `render()`, which
resets the TestBed. The spy's first argument is the `ConfirmRequest`; assert on `.message`.

## Not yet routed through it

The three dialogs that already had the typed phrase keep their own markup: container delete
(`pages/containers.html`), profile delete (`pages/agent-detail.html`), MCP server delete
(`pages/mcp-servers.html`). Same look, same classes; folding them in is the next step, not a
prerequisite.
