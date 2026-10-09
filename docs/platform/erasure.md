---
title: Erasing personal data
description: Marking the fields that are about a person as personal, how the platform keeps them encrypted under a key per data subject, and how an erasure request destroys that key so every copy becomes unreadable — the journal, views, topics, agent sessions, objects and backups — while every other field stays as it was.
kind: guide
related: [platform/secrets.md, platform/object-storage.md, platform/databases.md, reference/cli.md, reference/control-plane-api.md, reference/limitations.md]
---

# Erasing personal data

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

### A person in two projects

A person known to two projects is two data subjects, and erasing one does not erase the other. The domain
asks for an erasure in each project, and may give both requests one correlation id so an auditor reading
either finds the other.

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
history reads as nothing, and a new turn starts with only a note that the earlier conversation was erased.
What a session sent to its model provider is beyond the installation and beyond an erasure.

## Other projects and machines outside

A message published to a topic carries its personal fields encrypted under the producing project's keys,
and a consumer in another project reads them only under a grant that allows decryption; whatever it keeps
of them stays encrypted under the producing project's keys, so an erasure there reaches it with no
request of its own. A consumer without that grant reads every personal field as erased.

## Backups and restores

A restored database cannot bring an erased subject back. A service's database restored to a point before
an erasure still holds only ciphertext whose key is gone, and the service applies every erasure it has not
applied — redacting its rows again — before it reports ready. The keyring's own database, restored to
before an erasure, applies the erasure log before it answers anything: the log is kept in two places
outside it, the control plane's database and a bucket of the platform's.

## What remains

- **The subject id.** It stays readable in every envelope, journal row, view key and topic record, because
  the ledger the law requires is keyed by it. Choose an id that is not itself a name, an email or a
  document number.
- **Physical removal follows retention.** The ciphertext stays in journals, topics and backups until topic
  retention or backup retention removes it; the erasure is what makes it unreadable before then.
- **Data written before a field was marked** stays as it was written.
- **A local run's envelopes** are under the local keyring's keys and cannot be read once the service is
  deployed to a project.
