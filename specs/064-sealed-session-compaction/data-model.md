# Data model: Compaction of a session started with a data subject

Nothing stored changes shape. This file says what is read differently.

## `SessionMemoryEvent` (unchanged cases)

| Case | Written when | To the compactor |
|---|---|---|
| `AiMessageAdded` | a turn's answer, session with no data subject | consider the session |
| `Started(subject)` | a session's first call names a data subject; in the clear | ignored |
| `Sealed(Personal.Present(subject, json))` | every later event of that session; `json` is the plain event's own JSON | read through `opened` |
| `Sealed(Personal.Erased(subject))` | the same event read once the subject key is destroyed | ignored: nothing to compact |
| `HistoryCompacted` and every other case | as today | ignored |

## `SessionMemoryEvent.opened` (new, `private[agent]`)

```text
opened(event) =
  Sealed(Present(_, json))  → Some(the event json decodes to, with SessionMemoryCodecs.event)
  Sealed(Erased(_))         → None
  any other event           → Some(event)
```

- Total: every event has an answer. A sealed body never holds another `Sealed` (the entity seals
  plain events only), so one opening is enough.
- Does no I/O and asks for no key: the platform decrypted the body when it decoded the event.
- `SessionMemoryEntity.applyEvent`'s `Sealed` case becomes: `opened` gives `Some(e)` → apply `e`;
  `None` → `SessionHistory.erasedFrom(currentState)`. Its behaviour is unchanged.

## The compactor's rule

```text
onMessage(event) =
  opened(event) is Some(AiMessageAdded) → considerCompacting()
  otherwise                             → ignore
```

`considerCompacting` is unchanged: over `maxHistoryBytes`, at least `minMessagesToCompact` beyond
`keepRecentMessages`, a summary shorter than what it replaces. It reads the session's history from
the entity, which holds it in the clear in memory, and writes through `SessionMemoryEntity.compact`,
which seals the `HistoryCompacted` it records for a session with a data subject.

## What a compaction failure leaves

| Where | What | Changed |
|---|---|---|
| `SessionCompactor.failures` | `"<session id>: <failure message>"`, in memory | no |
| the service's log | one `WARN`: `compaction of session '<session id>' failed: <kind>` | new |

`<kind>` is the failure's class name, with the error code for a `CommandError`
(`ModelCallFailed`, `TimeoutException`, `CommandError(Unavailable)`). Never the failure's message.

## Glossary

`summary` and `compaction size`, accepted 2026-11-10.
