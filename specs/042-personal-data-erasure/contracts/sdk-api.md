# Contract: the SDK API

Held by `features/erasure/personal-fields.feature`, `agents.feature`, `objects.feature` and
`languages.feature`. One surface per SDK; the names below are the Scala ones with each language's in
its row.

## The type

| | Scala | Python | TypeScript | Rust |
|---|---|---|---|---|
| present | `Personal.present(subject, value)` | `personal(subject, value)` | `present(subject, value)` | `Personal::present(subject, value)` |
| for lookup | `Personal.lookup(subject, value)` | `personal(subject, value, lookup=True)` | `present(subject, value, {lookup: true})` | `Personal::lookup(subject, value)` |
| erased | `Personal.Erased(subject)` | `Erased(subject)` | `erased(subject)` | `Personal::Erased{subject}` |
| read | `p.toOption`, `p.map`, `match` | `p.value` (None when erased), `match` | `p.kind === "present"` | `match`, `as_ref()` |
| in a type | a field of type `Personal[A]` in a case class whose codec is `Codecs.make` | a dataclass field annotated `Personal[str]` under `json_codec` | `s.personal(s.string())` in a record schema | a field `Personal<String>` under `Json<T>` |
| subject rule | `Personal.DataSubject.problems` | `DataSubjectError` | `DataSubjectError` | `Error::DataSubject` |

A service with no keyring (a route listed by the CLI, a unit test with no test kit) writing a
present value is answered `Unavailable` naming the keyring; nothing is written.

## The erasure handler

```scala
Ankka.service
  .register(Players)
  .withErasureHandler { ctx =>
    val objects = ctx.objects.erase()            // every version under subjects/<subject>/
    ErasureOutcome.Done(objects = Some(objects))
  }
```

Python: `service.on_erasure(async def handler(ctx: ErasureContext) -> ErasureOutcome)`;
TypeScript: `service.onErasure(async (ctx) => …)`; Rust: `#[ankka::erasure_handler] fn erase(ctx: &ErasureContext) -> ErasureOutcome`
(the `ankka1_erase` export). The handler runs on every application and again on every reapplication;
`ctx.reapply` says which. It is the one callback; a service registers at most one. `ctx.objects.erase()`
without a bucket is `Refused("no bucket")`, and the completion records it.

## Lookup tokens

`clients.lookupToken(value)` on `EndpointClients`, `WorkflowContext` (steps only), `ConsumerContext`
and `AgentContext`; `client.lookup_token(value)` / `clients.lookupToken(value)` / the `lookup_token`
import. The value is a declared query's parameter:

```scala
val byEmail = query("by-email")("SELECT payload FROM ankka_view_profiles WHERE payload::jsonb->'email'->>'lookup' = :email")
// endpoint: viewClient.ask(Profiles.byEmail, "email" -> clients.lookupToken(email))
```

`QueryCheck` refuses a declared query that reads a personal field's `data` or compares the field
itself. The documentation says a token leaks equality and that whoever holds the lookup key and the
table can test guesses.

## Agents

`componentClient.forAgent(sessionId).withSubject("player/8c1f")` on the first turn tags the session;
`TaskBuilder.withSubject(subject)` tags a task and the instance that works it. After an erasure the
session's history reads as erased, a new turn starts with no earlier messages and a system note, and
a tagged instance is terminated.

## Responses and requests

A `Personal` in an HTTP response body is the envelope, never the value; an endpoint that answers
a value maps it (`p.toOption`). A request body type should not use `Personal`; the endpoint
constructs it from the caller's plain field and the subject it knows.

## The test kit

```scala
val kit = AnkkaTestKit.start(…)                        // in-memory keyring, project "local"
kit.erase("player/8c1f")                               // applies through ErasureRuntime, synchronously
kit.keyringOutage { … }                                // the handle answers nothing inside
kit.assertNoPersonalValue("ada@example.com", "Ada Byron")
val before = kit.snapshotDatabase(); …; kit.restoreDatabase(before)   // the service is stopped and started around it
kit.grants.allow(principal = "service:payments/cardholders", topic = "brand/players", decrypt = true)
```

Python `IntegrationTestKit.erase(subject)` / `TypeScript kit.erase(subject)` / Rust
`kit.erase(subject)` drive the same through the sidecar's own in-process keyring.
