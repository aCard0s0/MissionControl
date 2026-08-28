---
type: object
cluster: dashboard
universe: live
status: verified
verified: main @ 976a9c9 · 2026-08-28
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
  inside still reaches the document (`shared/scrim.spec.ts:34`).
- **Escape binds on the document, not the host.** A backdrop is not focusable, and making it
  focusable to receive the key would put a tab stop with no meaning in front of the dialog's
  own controls.
- **Guards stay in the template.** `(dismiss)="saveBusy() ? null : closed.emit()"` — whether a
  dialog may close mid-save is the dialog's business, not the backdrop's.

## Shape

`shared/scrim.ts:24` — selector `[mcScrim]`, one output `dismiss`.

| Host binding | Does |
|---|---|
| `(click)` | dismisses only when `event.target === event.currentTarget` |
| `(document:keydown.escape)` | dismisses |

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
- **Does not hit:** focus management. Nothing traps focus inside a dialog or restores it on
  close; a keyboard user can still tab out of an open modal into the page behind it. That is
  the next thing to do here, and it is not done.

## Surfaces

| Surface | Role |
|---|---|
| fifteen page templates | declare it |
| the document | one `keydown.escape` listener per mounted backdrop |

## See

- Source: `applications/mission-control-fe/src/app/shared/scrim.ts`
- Spec: `applications/mission-control-fe/src/app/shared/scrim.spec.ts`
