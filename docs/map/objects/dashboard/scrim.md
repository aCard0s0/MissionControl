---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 379fe7e · 2026-08-29
entity: applications/mission-control-fe/src/app/shared/scrim.ts
---

# Scrim

The backdrop behind a modal, and the only way any dialog in the app is dismissed without a
button: click outside, or press Escape. `Scrim` directive, `shared/scrim.ts`, applied as
`mcScrim` on fifteen backdrops.

## Why this shape

Every dialog had written the mouse half by hand — `(click)` on the backdrop to close, plus
`(click)="$event.stopPropagation()"` on the modal so a click inside did not count as a click
outside — and **none had the keyboard half**. No Escape handler existed anywhere in the app.
A dialog a mouse can dismiss and a keyboard cannot is the quiet version of this bug: nothing
looks broken, and the way out exists for one kind of user.

Three decisions inside it are not obvious:

- **Target filtering replaces `stopPropagation`.** Asking whether the click landed on the
  backdrop itself answers the same question without the modal having to know it sits on one,
  and without swallowing an event something else might have wanted. A spec pins that a click
  inside still reaches the document (`shared/scrim.spec.ts:60`).
- **Escape binds on the document, not the host.** A backdrop is not focusable, and making it
  focusable to receive the key would put a tab stop with no meaning in front of the dialog's
  own controls.
- **Guards stay in the template.** `(dismiss)="saveBusy() ? null : closed.emit()"` — whether a
  dialog may close mid-save is the dialog's business, not the backdrop's.
- **Focus is moved in, held, and given back** (`shared/scrim.ts:75`). Without it `aria-modal` is
  a claim the page does not honour: a keyboard user tabs straight out of an open dialog onto
  controls the backdrop is covering. Tab off the last control wraps to the first and Shift+Tab
  wraps the other way; on close focus returns to the opener, checked for `isConnected` because
  a list may have re-rendered underneath.
- **The page behind is marked `inert`** (`shared/scrim.ts:90`), which takes it out of the tab
  order *and* out of the accessibility tree. The trap holds the keyboard; without this a screen
  reader's own cursor still walks content the backdrop is covering, and `aria-modal` is only
  advisory. A sibling that was already inert is left alone, so cleanup cannot clear one this
  backdrop did not set.
- **Both are skipped when the backdrop holds nothing focusable.** Three of these are bare
  click-catchers, and the thing they reveal — the sidebar, a context menu — is a *sibling* of
  the backdrop. Inerting siblings would disable exactly what the scrim exists to show, and
  moving focus into an empty box would strand the keyboard for the same reason.

## Shape

`shared/scrim.ts:54` — selector `[mcScrim]`, one output `dismiss`.

| Host binding | Does |
|---|---|
| `(click)` | dismisses only when `event.target === event.currentTarget` |
| `(document:keydown.escape)` | dismisses |
| `(keydown.tab)` / `(keydown.shift.tab)` | wraps at the ends; a Tab in the middle is left to the browser |

The modal it wraps carries `role="dialog"` and `aria-modal="true"`, which no dialog announced
before.

## Connected to

- **owns:** nothing
- **owned-by:** whichever component declares the backdrop
- **joins:** every dialog — containers (4), agent-detail (3), mcp-servers (2), plus
  agent-create, mcp-server-editor, mcp-server-logs, profile-deploy, session-viewer, and the
  bare scrims in `app.html` and `terminal-panel.html`
- **looks-like-but-is-not:** `.field-cap`, the other a11y convention added at the same time —
  a caption over something a `<label>` cannot name (a row of preset buttons, a picker that
  opens), where the group carries `aria-labelledby` instead. Styled with labels in
  `styles.scss`.

## If you change this

- **Hits:** all fifteen backdrops at once — that is the point of it being one directive.
  Removing the target filter would make every click inside a modal close it.
- **Does not hit:** the three bare click-catchers, which are deliberately outside both the trap
  and the inerting — a menu that is a sibling of its own backdrop cannot be inerted without
  being switched off. Nor does it hit stacked dialogs: one modal is mounted at a time here, and
  a second opened over the first would inert it.

## Surfaces

| Surface | Role |
|---|---|
| fifteen page templates | declare it |
| the document | one `keydown.escape` listener per mounted backdrop |

## See

- Source: `applications/mission-control-fe/src/app/shared/scrim.ts`
- Spec: `applications/mission-control-fe/src/app/shared/scrim.spec.ts`
