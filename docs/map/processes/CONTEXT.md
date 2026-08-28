# processes — the movements that actually run

Five. Not a catalogue of every method call — a movement earns a card when it crosses a boundary,
has an ordering that matters, and can fail halfway.

| Card | Movement |
|---|---|
| [deploy-agent](deploy-agent.md) | create a Hermes container, its volume, its seed profiles |
| [upgrade-image](upgrade-image.md) | move a container onto a newer tag without losing its data |
| [profile-edit](profile-edit.md) | read hermes' files, write through hermes' CLI |
| [mcp-apply](mcp-apply.md) | render a Compose project and apply it under a per-host lock |
| [hydrate-poll](hydrate-poll.md) | the frontend's clock: probe, load once, then poll per domain |

## Not here, deliberately

**The terminal** is a movement, and a big one — but `docs/architecture.md` already argues its
design in depth, so a card would either duplicate it or be a pointer. It is a
[noun card](../objects/dashboard/terminal-session.md) that cites that section instead.

## The shape they share

Four of the five are multi-resource operations with a rollback or a lock, and all four put the
guard in one place rather than at each call site:

- deploy runs everything after volume creation inside a rollback guard
- upgrade parks the original rather than removing it, and restores on any failure
- MCP apply serializes per host on a `ReentrantLock`
- profile writes go through one CLI seam and one file seam

If you are adding a fifth caller to any of these, the guard is already there. Do not add a
second one beside it.
