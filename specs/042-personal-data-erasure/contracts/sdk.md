# Contract: the SDK surface

What a service developer writes, in every language. Values are in `data-model.md`.

## Marking a field

```scala
final case class PlayerRegistered(
    playerId: String,
    email: Personal[String],
    name: Personal[String],
    dateOfBirth: Personal[LocalDate],
    currency: String,
    registeredAt: Instant)

val subject = DataSubject("player/" + playerId)   // refuses an id outside FR-002's rule
PlayerRegistered(playerId, Personal(subject, email), Personal(subject, name), Personal(subject, dob), "EUR", now)
```

```java
record PlayerRegistered(String playerId, Personal<String> email, Personal<String> name, String currency) {}
Personal.of(DataSubject.of("player/" + id), email)
```

```python
@dataclass
class PlayerRegistered:
    player_id: str
    email: Personal[str]
Personal.of(DataSubject("player/8c1f"), "ada@example.com")
```

```typescript
const PlayerRegistered = s.object({ playerId: s.string(), email: s.personal(s.string()) });
personal(subject("player/8c1f"), "ada@example.com")
```

```go
type PlayerRegistered struct {
    PlayerID string                 `json:"playerId"`
    Email    ankka.Personal[string] `json:"email"`
}
ankka.PersonalOf(ankka.MustSubject("player/8c1f"), "ada@example.com")
```

```rust
#[derive(Serialize, Deserialize)]
struct PlayerRegistered { player_id: String, email: Personal<String> }
Personal::present(DataSubject::new("player/8c1f")?, "ada@example.com".into())
```

A derived codec picks the personal codec up with nothing else declared, at any depth.

## Reading a field

Each language matches on present or erased: Scala `case Personal.Present(_, v)` /
`case Personal.Erased(_)`; Java and Kotlin a sealed switch; Python `isinstance(p, Erased)`;
TypeScript `p.kind`; Go `if v, ok := p.Value(); ok`; Rust `match`. Every SDK offers
`orElse(default)` for convenience. Decoding never throws for an erased subject (FR-011).

## Errors

| Situation | Error (every language) |
|---|---|
| No keyring installed (no service runs, or erasure off) | `Unavailable`: "the keyring is unavailable …" |
| Write for an erased subject | `Erased`: "the data subject 'player/8c1f' is erased; nothing was written" |
| Envelope whose authentication fails | `Corrupt`: "the personal envelope of 'player/8c1f' failed authentication" |
| Keyring did not answer within `fetch-timeout` | `Unavailable` (the entity's recovery is retried, R4) |
| Process SDK under a runtime below 1.23 | refused at start: "personal fields need protocol 1.23 …" |

## The erasure handler

```scala
Ankka.service("players")
  .withErasureHandler(ErasureHandler { ctx => subject =>
    val removed = ctx.storage.bucket("kyc").erase(subject)   // count and finality
    ErasureReport(objectsErased = removed.count, finality = removed.finality)
  })
```

Java/Kotlin: `.erasureHandler((ctx, subject) -> …)`. Python: `@service.erasure_handler`. TypeScript:
`service.erasureHandler(async (ctx, subject) => …)`. Go: `svc.ErasureHandler(func(ctx, subject) …)`.
Rust: `#[ankka::erasure_handler]` exporting `erase`. One per service; run on every application,
including re-runs; must be idempotent. A handler that throws is recorded as failed for that run and
run again on the next pass (FR-015); the platform's own duties do not wait for it.

## Object storage

`ctx.storage.bucket(name).erase(subject): ErasedObjects(count, finality)` where `finality` is
`Immediate` or `After(duration)`. It deletes every object, and on GCS every version, under
`subjects/<subject>/`. Present on every SDK's storage client (055); a module through the `storage`
import's new `erase` op.

## Agents

```scala
agentClient.session(sessionId, subject = Some(DataSubject("player/8c1f"))).ask(...)
autonomous.start(instanceId, subject = Some(...))
```

The subject is fixed at the session's or instance's first call; a later call naming another subject
is refused.

## Views

```scala
View("players-by-email").rows[PlayerRow].lookup(_.email)   // marks email for lookup; personal fields derived
```

A declared query may compare a lookup field by equality only (`jsonText(payload, 'email') = :email`
becomes a token comparison). Any other predicate on a personal field is refused at start, naming the
view, the query and the field. Rust views declare `personal_fields`/`lookup_fields` by hand.

## Filing an erasure from a service

```scala
ctx.erasures.ask(project = None /* own */, subject, notBefore = Some(date), reason = Some("aml-retention"), correlation = Some(id))
```

Answers the request's id and state; refused as `Forbidden` without the `erasure` grant.
Every SDK; a process through `Client.AskErasure` (1.23).

## Test kit

- `AnkkaTestKit` installs an in-memory keyring; `kit.keyring.erase(subject)` destroys a key at once
  and sends the notice; `kit.keyring.outage(duration)` makes it refuse; `kit.assertNoPersonalValue(values*)`
  dumps every table of the service's database and fails naming the table and column where any of the
  values appears in plain, base64 or hex (FR-030, SC-001).
- Python, TypeScript and Go integration kits expose the same three through their sidecar.
