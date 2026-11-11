# Personal data and erasure

> Mark the fields about a person as personal fields of a data subject, read them as erased once the subject is erased, erase a subject's objects, and ask for an erasure from code.

Source: https://docs.ankka.cloud/build/personal-data/
A service that keeps records about people keeps them in a journal it never rewrites, in snapshots, in
view tables, on topics, and in the backups of all of those. When a person asks to be forgotten, none of
those copies can be found and rewritten one by one, and a backup cannot be rewritten at all.

ankka answers this by encrypting instead of deleting. A field that is about a person is a **personal
field** of one **data subject**, and every store holds it encrypted under that data subject's **subject
key**. The keys are kept by the installation's **keyring**, apart from every project's database and
backup. An **erasure** destroys the data subject's key; from then on every copy of every personal field
of that data subject, in every table, topic and backup, reads as **erased**, and nothing was rewritten.
Everything that is not a personal field (an amount, an id, a time) reads as it was written, so a ledger
keeps balancing after an erasure.

What an installation must run for this, and how an erasure is applied, is in
[Erasure on the platform](../platform/erasure.md).

## Marking a field

A personal field is a field whose type is `Personal` of its value. It is built from a data subject and
the value, and the type's codec picks it up with nothing else declared, at any depth of a record.

A **data subject** is 1 to 253 characters from letters, digits, `.`, `_`, `-`, `/` and `:`. Every SDK
refuses anything else with the same message. Name subjects by a kind and an id, `player/8c1f`, and never
by a name, an email address or a document number: the data subject is in the clear everywhere (see
[The data subject is the residual](#the-data-subject-is-the-residual)).

**Scala**

```scala
/** What `record` is asked: the data subject and the email kept as a personal field of it. */
final case class RecordContact(subject: String, email: String)

/** The email, a personal field: sealed under the data subject's key in the journal. */
final case class ContactRecorded(email: Personal[String])

final case class Contact(email: Option[Personal[String]])

final class PersonalContact extends EventSourcedEntity[Contact, ContactRecorded]:
  def emptyState: Contact                         = Contact(None)
  def applyEvent(event: ContactRecorded): Contact = Contact(Some(event.email))
  def record(ask: RecordContact): Effect[String]  =
    effects
      .persist(ContactRecorded(Personal(DataSubject(ask.subject), ask.email)))
      .thenReply(_ => "done")
  def email: ReadOnlyEffect[Contact] = effects.reply(currentState)

object PersonalContact
    extends EventSourcedEntity.Companion[PersonalContact, Contact, ContactRecorded](
      componentId = ComponentId("personal-contact"),
      stateSerializer = Codecs.serializer[Contact]("personal-contact-state"),
      eventSerializer = Codecs.serializer[ContactRecorded]("personal-contact-event")
    ):
  given Serializer[RecordContact] = Codecs.serializer[RecordContact]("record-contact")
  def create(context: EventSourcedEntityContext) = new PersonalContact
  val record                                     = command("record")(_.record)
  val email                                      = query("email")(_.email)
```

`DataSubject(text)` throws `IllegalArgumentException` outside the rule.

`DataSubject.from(text)` answers an `Either` instead of throwing.

**Java**

```java
@Manifest("player-registered")
public record PlayerRegistered(String playerId, Personal<String> email, String currency) {}

@Command("register")
public Effect<Done> register(String email) {
  var id = context().entityId();
  var subject = DataSubject.of("player/" + id);
  return effects().persist(new PlayerRegistered(id, Personal.of(subject, email), "EUR")).thenDone();
}
```

**Kotlin**

```kotlin
import com.thinkmorestupidless.ankka.kotlin.personal
import com.thinkmorestupidless.ankka.kotlin.subject

@Manifest("player-registered")
data class PlayerRegistered(val playerId: String, val email: Personal<String>, val currency: String)

    @Command("register")
    fun register(email: String): Effect<Done> = effects {
        val id = context().entityId()
        persist(PlayerRegistered(id, personal(subject("player/$id"), email), "EUR")).thenDone()
    }
```

**Python**

```python
from ankka.personal import DataSubject, Personal


@dataclass(frozen=True)
class PlayerRegistered:
    playerId: str
    email: Personal[str]
    currency: str


def registered(player_id: str, email: str) -> PlayerRegistered:
    return PlayerRegistered(player_id, Personal.of(DataSubject(f"player/{player_id}"), email), "EUR")
```

`DataSubject` raises `ValueError` for a string outside the rule.

**TypeScript**

```ts
import { personal, s, subject, type Infer } from "ankka"

export const PlayerRegistered = s.record("PlayerRegistered", {
  playerId: s.string,
  email: s.personal(s.string),
  currency: s.string,
})
export type PlayerRegistered = Infer<typeof PlayerRegistered>

export function registered(id: string, email: string): PlayerRegistered {
  return { playerId: id, email: personal(subject(`player/${id}`), email), currency: "EUR" }
}
```

**Rust**

```rust
use ankka::{DataSubject, Personal};

#[derive(Serialize, Deserialize)]
struct PlayerRegistered {
    #[serde(rename = "playerId")]
    player_id: String,
    email: Personal<String>,
    currency: String,
}

fn registered(id: &str, email: String) -> Result<PlayerRegistered, CommandError> {
    let subject = DataSubject::new(format!("player/{id}"))?;
    Ok(PlayerRegistered {
        player_id: id.to_string(),
        email: Personal::present(subject, email),
        currency: "EUR".into(),
    })
}
```

**Go**

```go
type PlayerRegistered struct {
	PlayerID string                 `json:"playerId"`
	Email    ankka.Personal[string] `json:"email"`
	Currency string                 `json:"currency"`
}

event := PlayerRegistered{PlayerID: id, Email: ankka.PersonalOf(ankka.MustSubject("player/"+id), email), Currency: "EUR"}
```

`ankka.Subject(text)` answers an error instead of panicking. A zero `Personal` has no data subject and
cannot be written, so a field that may be absent is a pointer, `*ankka.Personal[string]`.

A field marked this way is held encrypted wherever ankka's serializers write it: an event in the
journal, a snapshot, a key value entity's state, a view's row, a workflow's state, a message published to
a topic, and what an agent keeps of a conversation about the data subject (see
[Agents](#agents)). It is printed as `Personal(player/8c1f)` in every language, never with its value, so
a log line, an exception message or a trace attribute carries no personal data.

In a store the field is a **personal envelope**:

```json
{"$personal": 1, "project": "brand", "subject": "player/8c1f", "c": "<base64>"}
```

`c` is the value's own JSON, encrypted with AES-256-GCM under the subject key. The envelope is bound to
its data subject, its project and the manifest of the record it is in, so an envelope copied into another
record does not decrypt: it reads as corrupt, never as another person's value. The data subject and the
project are in the clear beside it.

Every SDK writes the same envelope for the same value, so an event recorded by a service in one language
is read by a service in any other.

## Reading a field

A personal field reads as **present**, with its value, or as **erased**, with its data subject alone.
Decoding never fails because a data subject was erased: an entity recovers, a view rebuilds, a query
answers and a consumer is handed its message, each with erased in the fields of that data subject.

| Language | Present or erased |
|---|---|
| Scala | `case Personal.Present(subject, value)` / `case Personal.Erased(subject)`; `p.orElse(default)`, `p.isErased` |
| Java | a sealed switch over `Personal.Present` and `Personal.Erased`; `p.orElse(other)`, `p.isErased()` |
| Kotlin | the Java types; `p.valueOrNull` is the value or `null` |
| Python | `isinstance(p, Erased)`; `p.or_else(default)`, `p.is_erased` |
| TypeScript | `p.kind` is `"present"` or `"erased"`; `p.orElse(fallback)`, `p.isErased` |
| Go | `if v, ok := p.Value(); ok`; `p.OrElse(def)`, `p.Erased()` |
| Rust | `match` on `Personal::Present { subject, value }` and `Personal::Erased { subject }`; `p.value()`, `p.or_else(default)`, `p.is_erased()` |

Write a handler so that an erased field is an ordinary case: a reply that shows `erased`, a row that
keeps its other columns, a total that is still a total.

## Writing a field

The subject key is made by the keyring on the first write for its data subject, once, whichever instance
writes first. After that each instance keeps the keys it has used in a cache (see
[Erasure on the platform](../platform/erasure.md#the-keyring-client)).

A write can be refused, and nothing is written when it is:

| Refusal | Code | When |
|---|---|---|
| `the data subject 'player/8c1f' is erased; nothing was written` | `Conflict` | a write of a personal field of an erased data subject, including a first write for a data subject erased before anything was written |
| `the keyring is unavailable …` | `Unavailable` | the service reaches no keyring, or the keyring did not answer within `ankka.keyring.fetch-timeout` |
| `the personal envelope of 'player/8c1f' is corrupt: …` | `Internal` | a read of an envelope whose authentication fails, or whose version this reader does not know |

The refusal's details carry `personal` with `erased`, `unavailable` or `corrupt`. An entity whose
recovery is refused as unavailable is recovered again with backoff, never read as erased.

While the keyring cannot be reached, a key the instance already holds keeps serving for at most 60 seconds
(`ankka.keyring.cache.outage-bound`, which may not exceed `ankka.keyring.notice-bound`): an instance that
cannot hear the keyring cannot hear an erasure, so it drops every key it holds then, and reads and writes of
personal fields are refused as unavailable until the keyring answers again.

Where the keys come from depends on where the service runs:

- **On an installation**, from the installation's keyring. A service that holds personal fields says so
  in its descriptor, `"personal": true` (see the [service descriptor](../reference/service-descriptor.md)),
  and an installation whose erasure is off refuses it. So does a service with an erasure handler, even one
  that holds no personal field: the declaration is what gives it the keyring's notices, and the store's
  soft-delete window, and an erasure request in its project waits for its completion.
- **On your machine**, with a database, from a local keyring in the schema `ankka_keyring` of the
  service's own database, made on first use. It is for development only: its keys are in the same
  database, and the same backups, as what they encrypt.
- **In a test**, from the test kit's keyring in memory (see [Testing](#testing)).

A service run with no database and no keyring address reaches no keyring, and every read and write of a
personal field is refused as unavailable, naming the keyring.

## At the edge

Outside the platform's stores there is nothing to encrypt for. When an endpoint answers with a record
that holds a personal field, or reads one a caller sent, the field is its value with its data subject
beside it, or the data subject alone once erased:

```json
{"email": {"subject": "player/8c1f", "value": "ada@example.com"}}
{"email": {"subject": "player/8c1f"}}
```

A body in either shape is read as present or erased. An endpoint exposes what its handler returns, behind
its ACL, as it does every other field; nothing rewrites a reply. Decide what an endpoint returns with that
in mind.

## Views

A view's row may hold personal fields like any record, and the table holds them as envelopes. A read of a
row, and a declared query that returns rows, give back the value. In Scala the view knows where its row's
personal fields are from the row's type, with nothing declared: `email`, `address.line1` in a nested
record, `contacts[*].value` in a list.

A declared query that compares a personal field compares ciphertext, which never matches. Such a query is
refused when the service starts, naming the view, the query and the field. One kind of comparison is
allowed: equality with a value the caller gives, on a field the view marks for **lookup**.

**Scala**

```scala
object Members
    extends View.Companion[MembersView, Joined, MemberRow](
      componentId = ComponentId("members"),
      source = ChangeSource.eventsOf(MemberEntity),
      rowSerializer = Codecs.serializer[MemberRow]("member-row")
    ):
  def create(ctx: ViewComponentContext) = new MembersView
  lookup(_.email)
  val byEmail =
    query("by-email")(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :email")
  val byCity = query("by-city")(s"SELECT payload FROM $table WHERE payload::jsonb->>'city' = :city")
```

The runtime keeps a **lookup token** beside each row for each lookup field, an HMAC-SHA-256 of the field
and its value under the project's **lookup key**, which the keyring holds. The query is answered by
comparing the token of the value the caller gives with the row's token; the value itself is never
compared, and never stored in the clear. A `LIKE`, a range, an `ORDER BY`, a literal in the statement or a
comparison inside a subquery on a personal field is refused at start, as is any comparison on a field not
marked for lookup. The data subject beside an envelope is in the clear and may be compared
(`payload::jsonb->'email'->>'subject'`). A lookup field is one value of the row: a field inside a
collection (`contacts[*].email`) cannot be marked for lookup, and marking one fails when the companion is
built.

**A lookup token leaks equality.** Two rows with the same email have the same token, so anyone holding
the table learns which rows share a value, and anyone holding both the table and the lookup key can test a
guess. Mark for lookup only the fields a query must find by, and keep the lookup key out of reach as you
would a subject key: it never leaves the keyring and no dump holds it.

When a data subject is erased, each view of the service rewrites the rows that hold that subject's
fields: each of its envelopes is reduced to the data subject alone, and the row's lookup tokens of that
subject's fields are dropped. Every other field of the row, another data subject's fields and their
lookup tokens included, is left as it was. A view
rebuilt by raising its version after an erasure writes its rows with erased in that subject's fields.

Lookup fields are declared in the Scala SDK, on a view's or a keyed view's companion.

## Entities in memory

An erasure reaches every entity the next time it is recovered. An entity already in memory holds what it
decrypted when it recovered, and keeps reading it until it is passivated or its instance restarts; its
next write of a personal field of the erased subject is refused, and its next recovery reads the field
as erased. Views, consumers, every new key fetch and every recovery read the subject as erased within 60
seconds of the erasure.

## Agents

A person's own words are in a conversation, and no field can mark them. A session, an autonomous
agent's instance or a task can instead be started with a data subject, and then everything the platform
keeps of it (every message, tool call, tool result, instruction, attachment and result) is kept sealed
under that data subject's key. With [compaction](agents.md#compaction) on, so is the summary that
replaces a session's oldest messages.

**Scala**

```scala
val calls =
  componentClient.forAgent(SessionId("support-91"), subject = Some(DataSubject("player/8c1f")))
calls.call(WeatherAgent.ask).invoke("my card was declined")

componentClient.forAutonomousAgent(
  Operator,
  "case-91",
  subject = Some(DataSubject("player/8c1f"))
)
```

A task takes one with `.withSubject(DataSubject("player/8c1f"))` on its builder.

**Python**

```python
calls = client.for_agent("helper", "support-91", subject="player/8c1f")
await calls.call("ask").invoke("my card was declined", reply=str)

resolver = client.for_autonomous_agent(Resolver, "case-91", subject="player/8c1f")
```

A task takes one with `subject=` where it is created.

**TypeScript**

```typescript
const calls = client.forAgent("helper", "support-91", { subject: "player/8c1f" })
await calls.call("ask", s.string, s.string).invoke("my card was declined")

const resolver = client.forAutonomousAgent("resolver", "case-91", { subject: "player/8c1f" })
```

A task takes one with `subject` in the options of `client.tasks.create`.

**Rust**

```rust
let client = client.with_data_subject(DataSubject::new("player/8c1f")?);
// every call to an agent made through it names the data subject
```

A task takes one with `NewTask::subject`, an instance with `InstanceCalls::with_subject`.

**Go**

```go
ctx = ankka.WithDataSubject(ctx, ankka.MustSubject("player/8c1f"))
// every call to an agent made with ctx names the data subject
answer, err := ankka.Invoke[string](ctx, "helper", "support-91", "ask", "my card was declined")

instance := ankka.Instance("resolver", "case-91").WithSubject(ankka.MustSubject("player/8c1f"))
```

A task takes one in `TaskOptions.Subject`.

The data subject is fixed by the first call; a later call naming another data subject is refused. Once
the subject is erased:

- the session's conversation reads as empty, and the model is told at the start of the next turn that the
  earlier conversation was erased at the request of the person it was about;
- a turn in that session is answered, and nothing of it is recorded;
- an instance started with the subject is terminated, and its tasks' instructions, inputs and results read
  as erased, as does a task started with it.

A session started with no data subject is not changed by any erasure: it cannot be erased. Start every
session about a person with that person's data subject. What a session sent to its model provider has
left the installation, and an erasure does not reach it.

## Objects

The platform encrypts what its serializers write. It cannot see what a service writes to a bucket: a
passport scan is bytes. Keep each data subject's objects under that subject's **subject prefix**, and
erase them in the service's erasure handler.

The subject prefix is `subjects/`, the data subject with every `/` written `%2F`, and `/`: `player`'s is
`subjects/player/`, and `player/8c1f`'s is `subjects/player%2F8c1f/`. A data subject cannot hold `%`, so
no subject's prefix holds another's. Build it with the SDK rather than by hand: `ObjectRules.subjectPrefix`
in Scala, `subject_prefix` in Python, `subjectPrefix` in TypeScript, `ankka.SubjectPrefix` in Go.

`bucket.erase(subject)` deletes every object under the subject prefix, and on a store that keeps versions
every version of each, and answers how many it removed and when they are gone for good: at once, or after
the store's soft-delete window (`ANKKA_S3_SOFT_DELETE_DAYS`, which the platform sets for a service that
declares `"personal": true` on a store that keeps one). Objects outside the prefix
are kept. Run again, it erases what was kept there since, and answers 0 when nothing was.

## The erasure handler

A service's **erasure handler** is its own part of an erasure: what the platform cannot see. It runs on
one instance of the service for each application of an erasure in its project, and again on each re-run
(see [Erasure on the platform](../platform/erasure.md#applying-an-erasure)), so it must do nothing the second
time that it did not do the first. It reports how many objects it erased and when they are gone. A run that
throws is recorded as failed, with why, in the service's completion, and runs again on the next pass; the
platform's own duties (keys, rows, tokens, sessions) do not wait for it. A service has at most one. A service
with one declares `"personal": true` in its descriptor, or on an installation it hears no notice and
its handler never runs.

**Scala**

```scala
/** The service's part of an erasure: the subject's objects in its `kyc` bucket. */
def withKycErasure(service: ServiceBuilder): ServiceBuilder =
  service.withErasureHandler(ErasureHandler { ctx => subject =>
    val removed = ctx.storage.bucket("kyc").erase(subject)
    ErasureReport(removed.count, removed.finality)
  })
```

The context has the service's `client`, `secrets`, `services`, `storage` and `erasures`, the erasure's
sequence `erasureSeq`, and `rerun`.

**Java**

```java
static ServiceBuilder players() {
  return Ankka.service("players")
      .register(Players.class)
      .withErasureHandler(
          (ctx, subject) -> ErasureReport.of(ctx.storage().defaultBucket().erase(subject)));
}
```

`ErasureHandler`, `ErasureContext` and `ErasureReport` are in `com.thinkmorestupidless.ankka.javasdk.erasure`.
The context has `componentClient()`, `services()`, `secrets()`, `storage()` and `erasures()`, the
erasure's sequence `erasureSeq()`, and `rerun()`. A report is `ErasureReport.immediate(count)`,
`ErasureReport.after(count, within)`, or `ErasureReport.of(...)` over what `Bucket.erase` answered.

**Kotlin**

```kotlin
/** The players service, whose part of an erasure is the subject's objects in its bucket. */
fun players(): ServiceBuilder =
    Ankka.service("players")
        .register(PlayerEntity::class.java)
        .withErasureHandler { ctx, subject ->
            ErasureReport.of(ctx.storage().defaultBucket().erase(subject))
        }
```

The handler and its context are the Java SDK's.

**Python**

```python
@service.erasure_handler
async def erase(ctx: ErasureContext, subject: DataSubject) -> ErasureReport:
    removed = await ctx.storage.bucket("kyc").erase(subject)
    return ErasureReport(objects_erased=removed.count, finality=removed.finality)
```

**TypeScript**

```ts
const service = Ankka.service({ client }).erasureHandler(async (ctx, subject) => {
  const removed = await ctx.storage.bucket("kyc").erase(subject)
  return { objectsErased: removed.count, finality: removed.finality }
})
```

**Rust**

```rust
Service::new("players").erasure_handler(|ctx, subject| {
    let storage = ctx.storage().expect("an erasure handler has the buckets");
    Ok(storage.bucket("kyc").erase(subject)?)
})
```

`service!` exports the handler as `ankka1_erase`.

**Go**

```go
svc.ErasureHandler(func(ctx context.Context, subject ankka.DataSubject) (ankka.ErasureReport, error) {
	bucket, err := ankka.StorageFrom(ctx).Bucket("kyc")
	if err != nil {
		return ankka.ErasureReport{}, err
	}
	removed, err := bucket.Erase(ctx, subject)
	return ankka.ErasureReport{ObjectsErased: removed.Count, Finality: removed.Finality}, err
})
```

`ankka.ErasureFrom(ctx)` is the erasure's sequence and whether this run is a re-run.

A handler that erases from a bucket the service's descriptor does not give is refused, naming the bucket,
and the service's completion says so.

An object written under a subject prefix after the erasure is erased by the next re-run, for as long as
the installation re-runs handlers after an erasure (30 days unless the installation says otherwise). After
that, only a new erasure request for the same data subject runs the handler again.

## Asking for an erasure from code

A member asks for an erasure by hand, through the command line, the console or the platform's MCP server.
A service asks from code, as itself, when its domain decides a person is to be forgotten: an account
closed, a retention period ended.

**Scala**

```scala
val asked = erasures.ask(
  DataSubject("player/8c1f"),
  notBefore = Some(LocalDate.parse("2031-10-08")),
  reason = Some("aml-retention"),
  correlation = Some("closure-4411")
)
// asked.id, asked.state: "held", "applying" or "applied"
```

An endpoint reaches it as `clients.erasures`, a consumer and an erasure handler as `ctx.erasures`.

**Java**

```java
ErasureAsked asked =
    erasures()
        .ask(
            DataSubject.of("player/" + id),
            ErasureAsk.defaults()
                .withNotBefore(LocalDate.parse("2031-10-08"))
                .withReason("aml-retention")
                .withCorrelation("closure-4411"));
// asked.id(), asked.state(): "held", "applying" or "applied"
```

An endpoint and a consumer reach it as `erasures()`, an erasure handler as `ctx.erasures()`;
`ErasureAsk.inProject("payments")` asks in another project. A refusal is a `CommandException`.

**Kotlin**

```kotlin
val asked = erasures().ask(
    subject("player/$id"),
    ErasureAsk.defaults().withReason("aml-retention").withCorrelation("closure-4411"),
)
```

**Python**

```python
asked = await self.erasures.ask("player/8c1f", not_before=date(2031, 10, 8), reason="aml-retention")
```

**TypeScript**

```ts
const asked = await this.erasures.ask({ subject: "player/8c1f", reason: "aml-retention", correlation: "closure-4411" })
```

**Rust**

```rust
let erasures = ctx.erasures().expect("an endpoint may ask");
let asked = erasures.ask(AskErasure::of(subject).reason("aml-retention"))?;
```

A WebAssembly module asks through the `erasure_ask` import, wherever it may make a request.

**Go**

```go
asked, err := ankka.Erasures(ctx).Ask(ankka.AskErasure{
	Subject:     ankka.MustSubject("player/8c1f"),
	NotBefore:   ankka.NewLocalDate(2031, time.October, 8),
	Reason:      "aml-retention",
	Correlation: "closure-4411",
})
// asked.ID, asked.State: "held", "applying" or "applied"
```

The request is asked in the service's own project unless it names another. The service needs the
`erasure` grant of the project it asks in, its own project included, and is recorded as who asked:

```bash
ankka projects grants make service:brand/players erasure -p brand
ankka projects grants make service:brand/players erasure -p payments
```

Without the grant the ask is refused as `Forbidden`, and the refusal is recorded in the history of the
project it was asked in. While the installation's erasure is off, an ask is refused as `Conflict`. A data
subject outside the rule is refused as `BadRequest`.

An erasure is asked of endpoints, consumers and erasure handlers. Workflow steps and timed actions have no
`erasures`: ask from the endpoint or consumer that starts them.

A **not-before date** holds the request: nothing is destroyed until the date passes, and then the platform
applies it with no one acting. Give one when a law keeps the data for a period (anti-money-laundering
records, a licence condition); the domain chooses the date and the platform keeps it.

**A person known to two projects is two data subjects.** An erasure applies in one project. Ask in each,
in another project under its grant, and give both requests one **correlation** id, so that a member
listing either project's requests by that id finds both.

## Asking on your machine

A service run on your machine erases from its local keyring:

```bash
ankka local erasures file player/8c1f                    # every service running here
ankka local erasures file player/8c1f --service players  # one
ankka local erasures file player/8c1f --rerun            # as a re-run, to run the handler again
```

Each running service destroys the key, carries out its duties (views, sessions, its erasure handler) and
answers what it did. The command reaches each service through its observability endpoint, so it works
only against services running on this machine.

## Testing

`AnkkaTestKit` gives the service a keyring in memory of its own, kept across `restartService()` as an
installation's keyring outlives a restart:

**Scala**

```scala
kit.erase(DataSubject("player/77a0")) // destroys the key now and sends the notice
kit.restartService()                  // read from the database, not from memory
assertEquals(card("77a0").call(ContactCardEntity.email).invoke(), "erased")

kit.assertNoPersonalValue("ida@example.com") // fails naming the table and column holding it
```

**Java**

```java
/** Registers a player, erases them, and reads the email back after a restart. */
static String emailAfterErasure(AnkkaTestKit kit) {
  var players = kit.componentClient();
  players.forEventSourcedEntity("8c1f").call(Players::register).invoke("ada@example.com");

  kit.erase("player/8c1f"); // destroys the key now and sends the notice
  kit.restartService(); // read from the database, not from memory: a fresh client, too
  var restarted = kit.componentClient();
  var email = restarted.forEventSourcedEntity("8c1f").call(Players::email).invoke();

  kit.assertNoPersonalValue("ada@example.com"); // fails naming the table and column holding it
  return email; // "erased"
}
```

**Kotlin**

```kotlin
erase("player/8c1f") // destroys the key now and sends the notice
restartService() // read from the database, not from memory
assertEquals("erased", componentClient().forEventSourcedEntity("8c1f").call(PlayerEntity::email).invoke())

assertNoPersonalValue("ada@example.com") // fails naming the table and column holding it
```

**Python**

```python
seq = await kit.keyring.erase("member/8c1f")
assert seq >= 1
await kit.restart()
member = kit.client.for_event_sourced_entity("member", "8c1f")
assert await member.call("email").invoke(reply=str) == "erased"
await kit.assert_no_personal_value("ada@example.com", "grace@example.com")
```

**TypeScript**

```ts
test("a personal field is sealed, erased and refused in an outage through the sidecar", async () => {
  const kit = await AnkkaTestKit.start(Ankka.service().register(Member))
  try {
    const member = (id: string) => kit.client.of(Member, id)
    assert.equal(await member("8c1f").call(Member.handlers.join).invoke("ada@example.com"), "joined")
    await kit.assertNoPersonalValue("ada@example.com")
    // The assertion can fail: the envelope names its data subject in the clear.
    await assert.rejects(kit.assertNoPersonalValue("member/8c1f"), /event_journal/)

    // An outage refuses a key the sidecar has not fetched, and an erasure, as unavailable.
    await kit.keyring.outage(600)
    await assert.rejects(member("9d2e").call(Member.handlers.join).invoke("grace@example.com"), (e: unknown) => {
      assert.ok(e instanceof CommandError, String(e))
      assert.equal(e.code, ErrorCode.Unavailable, e.message)
      return true
    })
    await assert.rejects(kit.keyring.erase("member/8c1f"), /503/)
    await kit.keyring.recover()

    assert.ok((await kit.keyring.erase("member/8c1f")) >= 1)
    await kit.restart()
    assert.equal(await member("8c1f").call(Member.handlers.email).invoke(), "erased")
    await kit.assertNoPersonalValue("ada@example.com", "grace@example.com")
  } finally {
    await kit.stop()
  }
})
```

**Rust**

```rust
#[test]
fn an_erased_subjects_field_reads_back_erased_and_the_record_stands() {
    let keyring = InMemoryKeyring::new();
    let mut kit = EventSourcedTestKit::<PersonalContact>::new("c-1");
    let recorded = with_keyring(&keyring, || kit.command("record", ada()));
    assert_eq!(recorded.reply::<String>(), Ok("done".to_string()));
    // The value was sealed under the subject's key, never written as it is.
    let seals = keyring.seals();
    assert!(seals.iter().all(|s| s.subject == "player/8c1f"));
    assert!(seals.iter().any(|s| s.value == br#""ada@example.com""#));

    keyring.erase("player/8c1f"); // destroys the key now
    let read: Contact = with_keyring(&keyring, || kit.command("email", ()))
        .reply()
        .unwrap();
    let email = read.email.expect("the record stands");
    assert!(email.is_erased());
    assert_eq!(email.or_else("erased".to_string()), "erased");
}
```

**Go**

```go
func TestAnErasedSubjectReadsAsErasedAfterARestart(t *testing.T) {
	service := ankka.NewService(conformance.PersonalContact, conformance.API).
		ErasureHandler(func(context.Context, ankka.DataSubject) (ankka.ErasureReport, error) {
			return ankka.ErasureReport{}, nil
		})
	kit := testkit.StartT(t, service)
	ctx := t.Context()
	h := kit.HTTP()

	contact := conformance.RecordContact{Subject: "player/8c1f", Email: "ada@example.com"}
	r, err := h.Post(ctx, "/conformance/personal/card-1", contact)
	if err != nil || r.Status != 200 {
		t.Fatalf("recording answered %v, %v", r, err)
	}
	// Destroys the key now and sends the notice; the kit's keyring outlives a restart as an
	// installation's does.
	if _, err := kit.Keyring().Erase(ctx, "player/8c1f"); err != nil {
		t.Fatal(err)
	}
	if err := kit.Restart(ctx); err != nil {
		t.Fatal(err)
	}
	// Read from the database, not from memory.
	record, err := testkit.GetJSON[conformance.PersonalRecord](ctx, kit.HTTP(), "/conformance/personal/card-1")
	if err != nil || record.Outcome != "erased" {
		t.Fatalf("the contact reads %+v, %v\n%s", record, err, kit.SidecarLogs(ctx))
	}
	// Fails naming the table and column holding the value, as written, in base64 or in hex.
	kit.AssertNoPersonalValue(t, "ada@example.com")
}
```

`kit.assertNoPersonalValue(values*)` reads every table of the service's database and fails, naming the
table and column, where any of the values appears as written, in base64 or in hex;
`kit.assertNoPersonalValueIn(broker)(values*)` searches the records published to an in-memory broker too.
Break the marking of a field once and watch it fail: that is what proves the field is marked.
`kit.keyring` answers whether it holds a key (`holdsKey`), how often a key was fetched, and the notices it
sent; `kit.keyring.outage(duration)` makes it refuse for a while, erasures included, and
`kit.keyring.recover()` ends the outage. A subject whose key the service holds is still read and written in
an outage; one it does not hold is refused as unavailable. In Java the same keyring is `kit.keyring()`,
with `erase(subject)`, `outage(Duration)` and `recover()`.

The Python, TypeScript and Go integration kits offer the same three through the sidecar, whose keyring is
the local one, kept in the kit's database:

| | Erase | Outage | Assert |
|---|---|---|---|
| Python | `await kit.keyring.erase("player/8c1f")` | `await kit.keyring.outage(600)`, `await kit.keyring.recover()` | `await kit.assert_no_personal_value("ada@example.com")` |
| TypeScript | `await kit.keyring.erase("player/8c1f")` | `await kit.keyring.outage(600)`, `await kit.keyring.recover()` | `await kit.assertNoPersonalValue("ada@example.com")` |
| Go | `kit.Keyring().Erase(ctx, "player/8c1f")` | `kit.Keyring().Outage(ctx, 10*time.Minute)`, `kit.Keyring().Recover(ctx)` | `kit.AssertNoPersonalValue(t, "ada@example.com")`, or `kit.CheckNoPersonalValue(ctx, ...)` for the error |

An erasure answers once the sidecar has carried it out, and an outage lasts until it ends or the sidecar is
restarted. The assertion searches every column of every table in the kit's database, cast to text, for
each value as written, in base64 (standard and URL-safe) and in hex, by the same rules as the JVM kit's,
and fails naming the table and column. The local keyring keeps each data subject in the clear in its own
tables, so assert on personal values, never on data subjects.

## What to mark

The platform encrypts what is typed `Personal` and cannot know what is not. For a regulated service, mark:

- names, and anything that is part of one;
- contact details: email addresses, phone numbers, postal addresses;
- dates of birth;
- document numbers: passports, identity cards, tax numbers, account numbers that identify a person;
- network identifiers: IP addresses, device ids;
- free text a person wrote: a message, a note, a support request, a review;
- anything derived from those that still identifies the person.

Leave unmarked what the ledger must keep: amounts, currencies, times, states, and the ids that join one
record to another. Keep the data subject itself out of every free text field.

## The data subject is the residual

The data subject is in the clear in every envelope, journal row, row key, lookup and topic record,
because everything that must survive an erasure is keyed by it. Where it can be linked to a person it is
itself personal data, and it is the part an erasure leaves. Choose it to link to as little as possible: a
kind and an id the service issued, `player/8c1f`, never a name, an email or a document number.

## Records written before a field was marked

Changing an existing field's type to `Personal` changes what a stored record holds: a record written
before holds the plain value where the codec now expects an envelope, and fails to decode. Treat it as
any breaking change to a stored type (see [Serialization and evolution](serialization.md)): add a new
event case whose fields are marked, write it from then on, and keep the old one.

Records written before stay as they were, in the clear, and an erasure does not make them unreadable. A
service whose existing history must be covered moves each entity to a new id through a migration that
writes the history again with the fields marked; the old journal must then be dealt with as the data it
is.

## What erasure is not

An erasure makes data unreadable; it removes no bytes. Ciphertext stays in journals, topics and backups
until their retention removes it. It does not find personal data that was not marked, it does not reach
what left the installation (a model provider, a partner's own copy, an export), and it applies in one
project. The full list is in [Limitations](../reference/limitations.md#personal-data-and-erasure).
