# Erasing personal data

> Marking the fields that are about a person as personal, kept encrypted under a key per data subject, and how an erasure request destroys that key so every copy of them, backups included, becomes unreadable.

Source: https://docs.ankka.cloud/platform/erasure/
A service built on ankka never deletes what it recorded: an entity's events stay in its journal, a
topic's messages stay on the broker until its retention removes them, and every backup keeps all of it.
That is what makes a ledger trustworthy, and it is also what makes "forget this person" hard. The
platform answers it by encryption: every field that is about a person is stored encrypted under a key
that belongs to that person alone, and erasing the person destroys the key. Every copy of every such
field — in the journal, a snapshot, a view's rows, a message on a topic, a backup, another project's own
store — becomes unreadable at the same moment, and nothing is rewritten. Every field that is not about a
person — an amount, an id, a time, a status — is untouched, so a ledger, a balance and a view all survive
an erasure.

## Personal fields and data subjects

A **data subject** is the person a field is about, named by an id the domain chooses, such as
`player/8c1f`. The id is 1 to 253 characters of letters, digits, `.`, `_`, `-`, `/` and `:`, and it
belongs to one project: a person known to two projects is two data subjects.

A **personal field** is a field typed `Personal`, built with the subject it is about:

```scala
final case class PlayerRegistered(
    playerId: String,
    email: Personal[String],
    name: Personal[String],
    currency: String,
    registeredAt: Long
)

effects.persist(
  PlayerRegistered(
    id,
    Personal.present(s"player/$id", request.email),
    Personal.present(s"player/$id", request.name),
    request.currency,
    now
  )
)
```

A type with `Personal` fields needs no further declaration: its codec is derived as before and picks up
the personal codec, at any depth — in an `Option`, a collection or another record. Every store then holds
the field as a **personal envelope**, the subject and its project readable beside the value encrypted:

```json
{"subject":"player/8c1f","project":"brand","data":"AQ…"}
```

Read a personal value through `toOption`, `getOrElse` or `fold`; there is no `get`, because a value can
be erased. A personal field prints as `Personal(player/8c1f)`, so an event written into a log line shows
no value.

The shopping cart sample keeps a cart's customer this way, in a key value entity of its own:

```scala
/**
 * The customer a cart belongs to. The name and email are personal fields of the data subject
 * `customer/<cartId>`: written encrypted under that customer's key, and read as erased everywhere
 * once the customer is erased — in the state, its backups, and every copy of them.
 */
final case class Customer(name: Personal[String], email: Personal[String])
```

It writes both fields under the customer's subject, and reads them back with the erased case handled:

```scala
def setDetails(details: CustomerDetails): Effect[CustomerDetails] =
  val customer = Customer(
    Personal.present(subject, details.name),
    Personal.present(subject, details.email)
  )
  effects.updateState(Some(customer)).thenReply(_ => details)
```

```scala
/** The details, each field the value or "erased": the erased case has to be handled. */
def getDetails: ReadOnlyEffect[CustomerDetails] =
  effects.reply(
    currentState.fold(CustomerDetails("", ""))(c =>
      CustomerDetails(c.name.getOrElse("erased"), c.email.getOrElse("erased"))
    )
  )
```

The platform encrypts what is typed `Personal` and cannot know about anything else. Marking is the
service's responsibility. A regulated service should mark at least names, contact details, dates of birth,
addresses, document numbers, IP addresses, and any free text a person wrote.

## Where the keys are

Every subject key lives in the installation's **keyring**, a platform component with a database of its
own that is no project's and is in no project's backup. Each subject key is wrapped by its project's key,
which is wrapped by the installation's root key. A service holds one channel to the keyring for every
key it needs, caches the keys it uses, and keeps reading cached keys through a keyring outage for a
bounded time; a write that needs a key it has not cached fails as unavailable until the keyring answers.

A subject's key is made by the first write of one of its fields, whichever service or instance writes
first, and never replaced.

A service run with `docker compose` reaches the local keyring the compose file starts, with
`ANKKA_KEYRING_URL=http://localhost:9020`. A service with no keyring refuses every personal field as
unavailable, naming the keyring.

## Asking for an erasure

A member asks for an erasure of one data subject in one project:

```bash
ankka projects erasures request player/8c1f -p brand
ankka projects erasures request player/8c1f -p brand --not-before 2031-10-08 --reason aml-retention
ankka projects erasures list -p brand
ankka projects erasures certificate e-4f2a91c03b6d -p brand
```

An erasure request with no not-before date is applied at once. The platform writes it to the erasure log,
destroys the subject's key in the keyring, and tells every service of the project, each of which:

- drops the key from every instance's cache, so within a minute no instance reads a personal field of the
  subject — every such field reads as erased, in an entity, a view, a consumer's delivery;
- rewrites its view rows' envelopes of the subject to the erased form, removing their lookup tokens;
- forgets the conversations of agent sessions about the subject;
- runs its **erasure handler**, if it has one, for what only the service can do.

The request records each service's completion, and becomes final once every deletion it made is final.
A member then fetches the **erasure certificate**: the request, the subject, who asked for it, each
service's completion and when the erasure became final. It holds nothing personal.

A second request for a subject already erased is answered with the applied request. An erasure for a
subject the keyring has never seen leaves a tombstone, so no later write makes a key for it.

### Holds

Law often requires some data about a person to be kept for years after they ask to be forgotten. An
erasure request may carry a **not-before date** and a reason: it is held until the date, and then applied
with nobody acting. A held request can be withdrawn, or replaced by a later request from whoever asked
for it. Only an owner may override a hold, with a reason that is recorded — a regulator's order to
destroy, say — and the erasure is then applied at once. An applied request cannot be withdrawn.

The domain decides the date; the platform keeps it.

### A service asking

A service asks for an erasure as code, through the client it calls other services with, and the control
plane knows it by its certificate and names it as who asked:

```scala
Erasures.ask(context.services, "brand", "player/8c1f", correlationId = Some("dsar-2031-118"))
```

It is admitted only by a grant of the `erasure` right in the project it asks in, its own project
included: a service holds no right to erase merely by being deployed. A request it is not granted is
refused, and the refusal is kept in the project's history (`ankka projects history -p brand`) beside the
erasures asked for, applied and failed.

### A person in two projects

A person known to two projects is two data subjects, and erasing one does not erase the other. The domain
asks for an erasure in each project, and may give both requests one correlation id. Listing a project's
erasure requests by correlation id answers the requests of every project the member may read, each with
where it stands.

## What a service does of its own

The platform cannot see what a service writes to its bucket — any S3 client writes there directly — so a
service that keeps a person's objects registers an erasure handler, and keeps those objects under the
subject's prefix, `subjects/<subject>/`:

```scala
Ankka.service
  .register(Kyc)
  .withErasureHandler(ctx => ErasureOutcome.Done(objects = Some(ctx.objects.erase())))
```

`ctx.objects.erase()` deletes every object under the prefix, every version where the object store keeps
versions, and reports how many and when their deletion is final. An object kept outside the prefix is not
erased by it. The handler runs on every application of the erasure and again after it, so an object
written under the prefix later is removed on the next pass; it must be safe to run again. A service with
no bucket that calls it is refused, and its completion says so.

## Views and lookup tokens

A view's row holds a personal field encrypted, like every store, so a declared query cannot compare it.
Mark a field you need to find rows by with `Personal.lookup(subject, value)`: the row then also carries a
**lookup token**, a keyed hash of the value under the project's lookup key, and a declared query matches
the token:

```scala
val byEmail = query("by-email")(
  "SELECT payload FROM ankka_view_profiles WHERE payload::jsonb->'email'->>'lookup' = :email"
)
viewClient.forView(Profiles).ask(Profiles.byEmail, "email" -> Personal.lookupToken(email))
```

A lookup token leaks equality: anyone holding both the lookup key and the table can test a guessed value
against it. A token is written into a view's rows only, never a journal or a topic, and an erasure removes
the subject's tokens. A declared query that reads a personal field's ciphertext is refused when the view
starts.

## Agents

A conversation holds a person's own words, which no field can mark. Tag the session with the subject it
is about before its first turn:

```scala
componentClient.forAgent(sessionId).withSubject("player/8c1f").call(Support.chat).invoke(message)
```

Every turn of a tagged session is kept under the subject's key. After the subject is erased the session's
history reads as nothing, and a new turn starts with only a note that the earlier conversation was erased;
the turns after it are kept as a session's with no data subject.

A task for an autonomous agent is tagged the same way, when it is created:

```scala
componentClient.tasks.create(Tasks.resolve, instructions).withSubject("player/8c1f").create()
```

Its instructions, attachments and result are kept under the subject's key. An erasure of the subject
cancels each of its tasks that has not ended and terminates the agent instance working on it; the tasks'
instructions and results read as nothing from then on.

A session started with no data subject cannot be erased: nothing ties its conversation to a person, so no
erasure reaches it. Tag every session that is about someone. What a session sent to its model provider is
beyond the installation and beyond an erasure.

## Other projects and machines outside

A message published to a topic carries its personal fields encrypted under the producing project's keys,
and a consumer in another project reads them only under a grant that allows decryption; whatever it keeps
of them stays encrypted under the producing project's keys, so an erasure there reaches it with no
request of its own. A consumer without that grant reads every personal field as erased.

The keyring asks the grants at every fetch of a key, not once when a service connects: a grant revoked
refuses the next fetch, and what a consumer's instances already hold expires from their caches within
`ankka.erasure.cache.expiry` (5 minutes by default). A grantee is never made a key of the producing
project; a refused fetch is counted on the subject's key.

### An outside machine

A machine outside the installation never holds a key. With a grant that allows decryption, it asks the
keyring for one field's value at a time, presenting a token from one of the installation's issuers that
names it (`machine: <org>/<name>`):

```text
POST /decrypt
Authorization: Bearer <token>

{"subject": "player/8c1f", "project": "brand", "data": "<the envelope's data>"}
```

The keyring answers the value, as the JSON it was written as, and counts the decryption on the subject's
key. It refuses a machine whose grant does not allow decryption, whose grant was revoked, or whose subject
has been erased — from the next request on — and counts each refusal. Erasure reaches what the machine
asks for later; what the machine itself stored of an earlier answer is beyond the platform's reach, and
erasing it is the machine owner's duty.

## Backups and restores

A restored database cannot bring an erased subject back. A service's database restored to a point before
an erasure still holds only ciphertext whose key is gone, and the service applies every erasure it has not
applied — redacting its rows again — before it reports ready. The keyring's own database, restored to
before an erasure, applies the erasure log before it answers anything: the log is kept in two places
outside it, the control plane's database and a bucket of the platform's.

## A checklist for a regulated service

- Mark every field about a person `Personal`: names, contact details, dates of birth, addresses, document
  numbers, IP addresses and any free text a person wrote.
- Name data subjects by ids that say nothing about the person.
- Keep a subject's objects under its prefix, so an erasure finds them.
- Tag every agent session and task about a person with its data subject.
- Give the service an erasure handler for what only it can do: anything it keeps outside its entities,
  views, sessions and bucket, such as a record in another system.
- Ask for an erasure the law makes you delay with a not-before date and its reason, rather than waiting to
  ask.
- Give one person's requests in several projects one correlation id.
- Keep each erasure certificate as the record that the erasure was done.

## What remains

- **The subject id.** It stays readable in every envelope, journal row, view key and topic record, because
  the ledger the law requires is keyed by it. Choose an id that is not itself a name, an email or a
  document number.
- **Physical removal follows retention.** The ciphertext stays in journals, topics and backups until topic
  retention or backup retention removes it; the erasure is what makes it unreadable before then.
- **Data written before a field was marked** stays as it was written.
- **A local run's envelopes** are under the local keyring's keys and cannot be read once the service is
  deployed to a project.
