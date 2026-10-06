# Research: Service Rollback

Each decision names the files it was read from. Paths are from the repository root; `…/` stands
for `src/main/scala/com/thinkmorestupidless/ankka`.

## R1. A rollback is a `ServiceApplied` with one new field, not a new event

**Decision**: `ServiceEvent.ServiceApplied` gains `rolledBackTo: Option[Long] = None` as its last
field. A rollback persists one with the next generation, the target's descriptor and
`rolledBackTo = Some(target)`.

**Rationale**: `ServiceApplied` is matched in three places in main code (`Service.fold` in
`controlplane/…/controlplane/domain/model.scala`, `ServiceRowsView.onChange` in
`application/ServiceRows.scala`, and the entity) and whatever reacts to an apply (the listing row,
`ProjectionTrigger`) must react to a rollback identically. One event with a defaulted field makes
that true by construction; a `ServiceRolledBack` event would need a new case in each, and a missed
case is a rollback the cluster never sees. It is also the rolling-update-safe shape: the shared
codec skips a field it does not know, so a control plane node still on the previous version reads a
rollback as an apply, which is what it is. A new event type would fail to decode there.

**Alternatives considered**: a `ServiceRolledBack(generation, target)` event carrying no
descriptor, with the fold looking the descriptor up in state. Rejected: the event would no longer
state what it applied, so a view or consumer reading it alone could not act on it, which is the
property `ServiceApplied`'s own comment gives as the reason it carries its generation.

**Cost**: every positional pattern on `ServiceApplied(_, _, _, _, _)` gains a sixth position (two
in main code, two in `EventCompatibilitySuite`).

## R2. The kept descriptors are a vector on `Service`, newest first, and never leave the entity whole

**Decision**: `Service` gains `kept: Vector[KeptDescriptor] = Vector.empty`, where
`KeptDescriptor(generation: Long, descriptor: ServiceDescriptor)`, newest first, capped at
`Service.KeptDescriptors = 50` in `onApplied`. `ServiceEntity.desiredState` replies with
`kept` emptied.

**Rationale**: a vector capped with `take` is the shape `history` already has, and a `Map[Long, …]`
would need its own ordering for "oldest kept" and "most recent that differs". The default lets a
snapshot written before the feature decode (R6).

`desiredState` replies with the whole `Service` and is called by `ServiceProjector` for every
service on every sweep, and by the endpoint for `expose` and for mount states
(`ServiceEndpoint.scala:139`, `:174`). The reply crosses Pekko remoting when the entity is on
another node, and nothing in `reference.conf` raises Artery's default frame of 256 KiB. Fifty
descriptors of forty variables each encode to about 200 KiB (R3), so a `Service` carrying `kept`
would be dropped in transit for exactly the services with the largest descriptors, and the
projector would stop reconciling them. Nothing that reads `desiredState` needs a past descriptor,
so the reply carries none. A past descriptor leaves the entity one at a time, through the two
queries of R4.

**Alternatives considered**: a second entity or a table holding past descriptors. Rejected: the
spec keeps them in state so a rollback reads no journal, and a second store is a second thing to
keep in step with the generation.

## R3. Fifty descriptors are kept; the limit that matters is the remoting frame, not the journal

**Decision**: fifty, by count. The spec's open question is closed.

**Measured** (the wire form the shared codec writes, which omits a field at its default):

| Descriptor | One | Fifty | Fifty, plus fifty history entries |
|---|---|---|---|
| the samples' (`samples/shopping-cart-web/service.json` and the templates) | 0.2 KiB | 9 KiB | 25 KiB |
| typical: 12 variables, 4 labels, resources, a registry image | 1.4 KiB | 68 KiB | 83 KiB |
| large: 40 variables, 10 labels | 4.0 KiB | 199 KiB | 214 KiB |
| very large: 120 variables, 20 labels | 11.2 KiB | 560 KiB | 575 KiB |

A history entry with an actor, an image and a digest is about 315 bytes.

**Rationale**: SC-004 asks that the snapshot stay "under the journal's payload limits". The
journal and the snapshot store are `bytea` columns written through pekko-persistence-r2dbc, which
set no limit short of Postgres's own; a 575 KiB snapshot, written once per hundred events and
replacing the one before it, is unremarkable there. The limit that would have bitten is the
256 KiB remoting frame, and R2 removes it: with `kept` emptied from `desiredState`, no reply
carries more than one descriptor, which is what `apply`'s own request already carries. With that
gone there is no reason to keep fewer than the history shows, and keeping the same fifty has a
useful consequence the console relies on (R11): every history entry that recorded a descriptor
is still kept.

**Alternatives considered**: a byte budget beside the count. Rejected for now: it makes "how far
back" depend on descriptor sizes, and nothing bounds one descriptor's size today, so a budget
would be a second limit on a thing with no first one.

**Held by a test**: `ServiceEntitySuite` encodes a `Service` with fifty typical descriptors through
`ServiceEntity`'s own state serializer and asserts the size, and asserts that the `desiredState`
reply for the same service is under 4 KiB.

**Measured** through the real serializers (fifty descriptors of twelve forty-character variables
and a registry image, fifty history entries with actor, image and digest): the state is 67,990
bytes. The `desiredState` reply was 13,899 bytes with `history` still in it, all of it the fifty
entries; no reader of that reply uses history, so it carries none either, which leaves it smaller
than before the feature.

## R4. The endpoint resolves the target and reserves; the entity decides

**Decision**: one pure function on `Service` answers "which descriptor would this rollback apply":
`rollbackTarget(requested: Option[Long]): Either[RollbackRefusal, KeptDescriptor]`, built on
`descriptorAt(generation: Long)`. The entity exposes each as a query (`rollback-target`,
`descriptor-at`) and the `rollback` command takes an explicit generation and runs
`rollbackTarget(Some(n))` again before persisting.

The endpoint's `POST …/rollback` does, in order: authorize for write; ask the entity for the
target; run the descriptor's `problems`; reserve the target's instances with the organization;
send `rollback` with the resolved generation; put the reservation back if that fails. This is
`putBody`'s apply (`ServiceEndpoint.scala:72-103`) with one read in front.

**Rationale**: FR-003 wants the quota and suspension checks an apply gets, and those are the
endpoint's: `authz.project(…, write = true)` refuses a disabled organization
(`auth/Authorization.scala:107`), and `OrganizationUsage.reserveService` needs the instance count
before the entity is asked. An entity cannot see the organization (CLAUDE.md, "cross-entity checks
live in the endpoint"). So the endpoint must know the target's descriptor first, and the entity
must still be the authority, since it is the single writer.

Resolving the default target in the endpoint and sending the entity an explicit generation also
gives the better answer under concurrency. Two members who roll back with no generation named at
the same moment both resolve to the same generation; the first is applied, and the second finds
the service already has that descriptor and is refused as changing nothing. Had the entity
resolved the default itself, the second would resolve against the new state and roll the first
one back.

**Consequence for the spec**: the edge case "two members roll back at the same moment … the other
is applied at N+2 to whatever it named" no longer holds when they name the same generation,
because the second clarification refuses a target whose descriptor the service already has. The
edge case and its scenario are amended: of two rollbacks to one generation, one is made and the
other refused.

**Alternatives considered**: the entity's command taking `Option[Long]`. Rejected for the
concurrency reason above. The endpoint comparing digests itself from `desiredState`. Rejected: R2
removes the descriptors from that reply.

## R5. Reading a past descriptor is `GET …/descriptor?generation=N`, not `…/history/{generation}`

**Decision**: `GET /services/{projectId}/{name}/descriptor?generation=N` returns the
`ServiceDescriptor` recorded at N. `generation` is required. FR-008 is amended.

**Rationale**: `HttpEndpoint`'s routes take at most two path parameters
(`modules/http/…/http/HttpEndpoint.scala:343-361`; `docs/build/http-endpoints.md` says so), and
the spec's route has three. Reading the generation as a query parameter uses what the endpoint
already does for `logs` (`query.optional`, `query.required` in `RequestContext.scala`). The reply
is the descriptor itself, so `ankka services history cart --generation 1 > service.json` is a file
`ankka services apply` accepts.

**Alternatives considered**: adding three-parameter overloads to `HttpEndpoint`. Rejected: it
widens a published library's API, and its documentation, for one control plane route.
`GET …/history?generation=N` on the existing route. Rejected: one route would answer two types.

## R6. Old history: what a snapshot kept shows neither; what is replayed from events shows both

**Decision**: the image and digest of a history entry are computed in the fold from the event's
descriptor. FR-006, SC-002 and the scenario are amended to say what is true.

**Rationale**: the spec assumed an entry from a journal written before the feature cannot know
its image. It can: `ServiceApplied` has always carried the descriptor, and history is rebuilt by
folding. `EventSourcedEntity.snapshotEvery` defaults to `Some(100)`
(`modules/sdk/…/sdk/EventSourcedEntity.scala:107`) and `ServiceEntity` does not override it, so
after the upgrade a service is in one of two positions:

- **No snapshot yet** (fewer than a hundred events). Recovery replays every event through the new
  fold. Every apply's history entry gains its image and digest, and `kept` holds the last fifty
  applied descriptors. Such a service can be rolled back at once.
- **A snapshot from before the feature.** It decodes with `kept` empty and with history entries
  that have no image and no digest; events after it are folded by the new code. These entries are
  the ones the scenario is about.

Making old events show nothing would mean the fold telling old events from new ones, which it has
no way to do and no reason to.

**The gap this leaves, and its fix**: a service with an old snapshot has an empty `kept`, so after
the upgrade the first bad apply would have nothing to roll back to, which is the case the feature
exists for. The fold therefore **seeds**: wherever `kept` is read and is empty while `descriptor`
is defined, the current descriptor stands as kept at the generation of the newest `applied`
history entry (or the current generation when history has none). `onApplied` pushes onto the
seeded vector. It is a pure function of the state, so replay reproduces it. A scenario is added:
a service applied before the platform kept descriptors is rolled back to the descriptor it had.

**Held by tests**: `EventCompatibilitySuite` pins a pre-feature `Service` snapshot and a
pre-feature `ServiceApplied`, and folds an apply and a rollback over the old snapshot.

## R7. The digest is SHA-256 of the canonical wire form, with maps in key order

**Decision**: `ServiceDescriptor.digest: String` in `controlplane-api/…/api/descriptors.scala`:
lowercase hex SHA-256 of the UTF-8 bytes the descriptor's codec writes, after `labels` and
`annotations` are put in key order. Sixty-four characters; the CLI and the console show the first
twelve.

**Rationale**: the spec's assumption is "two descriptors that encode identically have one digest".
The codec writes fields in declaration order and omits defaults, so that holds for everything but
maps: a small immutable `Map` iterates in insertion order, so `{a, b}` and `{b, a}` are equal
values with different encodings. A member who reorders labels in a file has not changed the
service, and a rollback that treated the two as different would apply a descriptor the service
already has. `env` is a `Vector` and its order is kept: Kubernetes expands `$(VAR)` in order.

It lives in `controlplane-api` because the CLI depends on that module and can then print the
digest of a file on disk without the control plane, the same reason `Compatibility` is there. It
needs only the JDK's `MessageDigest`.

"The same descriptor" is always decided by comparing digests computed now from the kept
descriptors, never by comparing a digest stored in a history entry, so a change to the codec in a
later version cannot make two stored digests disagree about a descriptor.

**Alternatives considered**: case class equality. It gives the same answer and was the simpler
check, but the clarification says "by digest" and one definition of "same" shown to the member and
used by the platform is worth more than the shortcut.

## R8. Refusals: five, each with its own words

**Decision**: `RollbackRefusal` in `domain/model.scala`, each case rendering its message and its
`ErrorCode`:

| Case | When | Code | Words |
|---|---|---|---|
| `NoSuchGeneration(n)` | `n < 1` or above the current generation | `NotFound` | `service 'cart' has no generation 9` |
| `NotKept(n, oldest)` | below the oldest kept generation | `Conflict` | `the descriptor of generation 3 is no longer kept; the oldest kept is generation 11` |
| `NoDescriptor(n, ran)` | between the oldest kept and the current, with no descriptor of its own | `Conflict` | `generation 3 was a restart and ran the descriptor of generation 2` |
| `SameDescriptor(n)` | the target's digest is the current descriptor's | `Conflict` | `service 'cart' already has the descriptor of generation 2` |
| `NothingToRollBackTo` | no generation named and no kept descriptor differs | `Conflict` | `service 'search' has no earlier generation with a different descriptor` |

A descriptor the platform no longer accepts is `BadRequest` with
`invalid descriptor at generation 1: …` and every problem, as an apply's is. A quota refusal and a
disabled organization are the organization's own messages, unchanged.

**Rationale**: only apply and restart move the generation, so a generation inside the kept range
with no kept descriptor is a restart's; that is why `NoDescriptor` can name what it ran (the
newest kept generation below it). Below the oldest kept generation the entity cannot tell an
apply from a restart, and says only that it is no longer kept. `descriptor-at` answers the first
three; `SameDescriptor` and `NothingToRollBackTo` are a rollback's alone.

A service that does not exist, a deleted one included, is the entity's existing `NotFound` for a
rollback. `descriptor-at` answers for a deleted service, as `history` does.

## R9. The CLI

**Decision**: `ankka services rollback <name> [--to-generation N]` and
`ankka services history <name> [--generation N]`, in `cli/…/cli/Main.scala`, over two new
`ControlPlaneClient` methods. The history table becomes `WHEN KIND GEN IMAGE DIGEST BY`
(`Output.history`). `rollback` prints the status it produced and, in table form, a first line
`rolled back to generation N`.

**Rationale**: the reply of the rollback route carries the generation it rolled back to (R10), so
the CLI can say which generation a rollback with none named chose without a second request.
`--generation` on `history` prints the descriptor as indented JSON in both output formats: it is a
document to redirect into a file, not a table.

`cli/…/cli/mcp/AnkkaTools.scala` gains no tool: `service_history` serialises the entries, so the
new fields appear in it without a change, and the spec asks for no tool. Its description is
updated, since it lists what the history holds.

**Docs**: `CliReferenceSuite` fails until `just docs-reference` has rewritten `docs/reference/cli.md`.

## R10. Wire types

**Decision**, all in `controlplane-api/…/api/descriptors.scala`:

- `HistoryEntry` gains `image: Option[String] = None`, `digest: Option[String] = None`,
  `rolledBackTo: Option[Long] = None`. The kind of a rollback is `rolled-back`.
- `RollbackRequest(generation: Option[Long] = None)`, the body of the rollback route.
- `RolledBack(rolledBackTo: Long, status: ServiceStatus)`, its reply.

**Rationale**: the body rather than a query parameter because the route changes state, and
`postBody` with two path parameters exists. An `Option` defaulting to `None` is the one case the
codec's null-is-absent rule is harmless for (CLAUDE.md's jsoniter trap is about a default that is
not `None`). The reply is its own type because every other operation returns a bare
`ServiceStatus`, which has no place for the generation chosen.

`ControlPlaneFixturesSuite` writes a fixture for each new type and the changed one under
`-Dankka.docs.update=true`, and fails until the console's schemas match.

## R11. The console

**Decision**: in `console/package`: three optional fields on `historyEntrySchema`, schemas for the
two new types, `ControlPlaneClient.rollback(projectId, name, generation)` and `.descriptor(…)`,
an Image and a Digest column in the service page's history, and a rollback control on each row
that can be rolled back to. A new `service.rollback` operation in `extensions/types.ts`, so a host
can hide it as it can hide the others. The fake control plane gains both routes and keeps
descriptors per generation.

Which rows offer it is decided from the history alone: an entry offers a rollback when it has a
digest and that digest is not the newest digest in the history. The control is a `<details>`
whose body names the generation and its image beside the submit, so the confirmation FR-013 asks
for needs no script. A refusal is shown as the control plane gave it, by the page's existing
`Refused`.

**Rationale**: the console adds no route to the control plane and decides nothing the control
plane does not (CLAUDE.md, "the control plane is the only authority"). One generation that can be
rolled back to is not offered: the seeded one of R6, whose history entry was recorded before the
feature and has no digest to compare. The CLI rolls back to it, and FR-013 states the exception.
Filling that entry's digest on read was rejected: history would then be computed in two places.
The history's fifty entries
are of every kind and the kept descriptors are the last fifty applies, so every entry on the page
that has a digest is still kept (R3); an entry from an old snapshot has no digest and offers
nothing. The newest digest is the current descriptor's, since only an apply or a rollback changes
it. If the page is stale the control plane refuses, and that refusal is shown.

**Sequencing**: feature 035 (`035-console-treatment`, an open pull request) rewrites
`console/package/src/routes/service.tsx` (219 lines changed) and moves the page into the shell.
The console slice of this feature is written last, on top of whichever of the two is on `main` by
then. The client, schemas and fake control plane are untouched by 035 apart from eleven lines of
the fake and can be done at any time.

**Tests**: `test/client.test.ts` and `test/fixtures.test.ts` for the client and schemas; a
Playwright case for the rollback and one for a refused one, run against the fake and, through
`just test-console-compose`, against a real control plane. The fake is a second implementation of
the rules, and the compose run is what has found it drifting before.

## R12. Where each scenario is tested

Forty scenarios in two feature files. Neither file can be run whole by one `GherkinSuite`:
they span the fold, the HTTP API, the console and a cluster. Each scenario is a test named after
it, at the lowest level that can see it fail.

| Level | Suite | Scenarios |
|---|---|---|
| The fold and the entity, no runtime | `ServiceEntitySuite` (`EventSourcedTestKit`) | default target and what it passes over; a named generation; each of the five refusals; paused and exposed untouched; deleted and applied again; digests equal and different; what applied no descriptor shows none; a rollback's own entry |
| Stored forms | `EventCompatibilitySuite` | history an older platform kept; the seeded descriptor; the event's pinned JSON with and without `rolledBackTo` |
| The digest | `DescriptorSuite` (`controlplane-api`) | same descriptor same digest, map order ignored, variable order kept, a different environment differs |
| The HTTP API with the fake cluster | `ControlPlaneHttpSuite` | rollback through the route; the descriptor route and its three refusals; invalid descriptor; a person who is not a member; a deploy token (`DeployTokensSuite`) |
| The organization | `QuotaSuite`, `SuspensionSuite` | over quota refused and the reservation put back; a disabled organization |
| Two at once | `ControlPlaneHttpSuite` | two rollbacks to one generation sent together: one status, one refusal, generation moved once |
| The CLI against the control plane | `CliEndToEndSuite` | `services rollback`, `services history`, `services history --generation` whose output `services apply -f` accepts |
| The CLI's output | `OutputSuite` | the table's columns, `rolled-back to N`, `-` where nothing was recorded |
| A real cluster | `EndToEndClusterSuite`, after "5. changing the image rolls the workload" | the pod runs the image rolled back to |
| The projection, with the fake cluster | `ControlPlaneHttpSuite` | a runtime version the platform no longer runs is recorded and not deployed |
| The console | Playwright (`e2e/tests/services.spec.ts`) | rolls back from the console; offers it only where it can be made; shows a refusal as given; shows the image per generation |

The k3s case needs no new image. The suite already applies a second image to roll the workload;
the rollback returns to the first, and the assertion is the pod's image, read from the cluster.

## R13. Documentation

- `docs/operate/service-lifecycle.md`: a section on rolling back, what it changes and what it
  leaves (paused, exposed, the database).
- `docs/operate/status-and-history.md`: the new history table, reading a past descriptor, and what
  an entry from before an upgrade may lack.
- `docs/reference/glossary.md`: "Generation" says every apply, restart and rollback; entries for
  history, roll back and digest, in the words of `GLOSSARY.md`.
- `docs/reference/control-plane-api.md`: two hand-written sections; the route table is rewritten
  by `ControlPlaneRoutesReferenceSuite`.
- `docs/reference/cli.md`: rewritten by `CliReferenceSuite`.
- `docs/operate/console.md`: the rollback control.
- `docs/reference/console-package.md`: the `service.rollback` operation, if the operations are
  listed there.

No new page, so nothing is added to `mkdocs.yml` or a skill's `pages:`. The skills are rendered
again by `just docs-sync` because pages they carry changed. One sentence the docs must have: a
past descriptor shows its variables' values as they were applied, to every member of the
project's organization and every platform administrator, so a value that should not be read back
belongs in a project secret.

## R14. What a past descriptor reveals

**Decision**: no new access rule. The descriptor route is authorized as `history` and `logs` are:
`authz.project(principal, projectId, write = false)`, and a person who is not a member is told
there is no such project.

**Rationale**: this is the first route that returns a descriptor at all; until now a member could
apply one and never read one back. Every member of a project's organization may already apply to
it, there is no read-only role, and the values were always in the journal. A platform
administrator passes the same check for any project, and may likewise already apply to it. So
nobody gains access to anything they could not already replace. What changes is that a variable's
literal `value` is now one request away for any member and any platform administrator, which the
documentation says (R13).

## To verify first, before building on it

1. That a `Service` snapshot written by `main` decodes with the new fields at their defaults,
   through `ServiceEntity`'s state serializer, not only through the codec.
2. That the shared codec skips `rolledBackTo` when an older build decodes a new `ServiceApplied`
   (R1's rolling-update claim): decode the new JSON with a copy of the old case class in a test.
3. That `query.required[Long]` has a `FromQuery[Long]`; if only `Int` exists, read an `Int`.
4. That `postBody` accepts the body `{}` and decodes it to `RollbackRequest(None)`.
5. The size table of R3, through the real serializer.
6. What a caller is told when a command names a handler the entity's node does not declare, which
   is what a rollback reaching a node on the previous version meets during a rolling update. It
   must be an answer, not a wait until the timeout. **Verified**: the host replies at once with
   `NotFound`, `no handler 'rollback' on component 'service'`.
