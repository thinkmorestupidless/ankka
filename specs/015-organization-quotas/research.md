# Research: Organization Quotas

Every decision below was taken against the code as it is on `main` at 3578944.

## R1. Where the count lives: the organization entity, told by the endpoints

**Decision**: the organization entity keeps an exact record — the ids of its projects and, per
service key, the minimum instance count — and the endpoints tell it *before* they create or apply
(`reserve`) and *after* they delete or when the second step fails (`release` / `record`).

**Rationale**: the spec requires exact counts, and a view lags (the existing delete guards say so
in their own comments: "a project created moments ago might not be counted yet"). The entity is
also where the refusal has to happen for two concurrent creates against one free slot to admit at
most one — an entity serializes its commands; a view read cannot. Reserve-first is the only order
that never has to undo a creation: a refusal after `ProjectEntity.createProject` would need a
delete, and a deleted project id is a tombstone.

**Alternatives considered**: counting from the views at request time — inexact, and the
concurrent case admits both. A consumer folding project and service events into the organization —
exact eventually but not at the moment of the request, which is when the refusal has to hold.
Making `ProjectEntity`/`ServiceEntity` ask the organization from inside a handler — forbidden by
the codebase's rule that an entity never calls out.

## R2. What a service counts: `minInstances`, whatever its lifecycle

**Decision**: `descriptor.service.minInstances`. Pause, suspension, restart, expose change
nothing; only apply and delete do.

**Rationale**: `minInstances` is what the operator renders as the replica count (`maxInstances` is
not enforced, and `CLAUDE.md` says never to render an autoscaler), so it is what an installation
runs for the service. A paused service is still owed its capacity when it resumes, and a plan that
counted paused services as free would let a customer hold ten and run any five.

## R3. Rollback when the second step fails

**Decision**: `reserveProject` replies whether it *newly* reserved; the endpoint releases only
then. `reserveService` replies the *previous* instance count; the endpoint restores it with
`recordService(key, previous)` — `None` when the service was new. Both are unchecked writes: they
restore what existed, whatever the quota. The original failure is what the caller sees; a failure
of the rollback itself is swallowed, since a stale count is repairable (R5) and the caller's error
is not.

**Rationale**: a re-apply of an existing service is idempotent on the organization — it may be a
retry of one that already landed — so a blind release after a failed create would erase a real
project. The reply is the one bit that tells the endpoint whether there is anything to undo.

Cheap refusals that the service entity would make (`descriptor.problems`, a descriptor naming a
different service) are made in the endpoint first, so a typo does not write two events to the
organization. The entity keeps its own checks; the endpoint's are the same functions.

## R4. Lowering a quota, and re-applies

**Decision**: `setQuota` never checks usage. `reserveService` for a key already recorded accepts any
count at or below the previous one without consulting the quota, and checks only the *increase*
against the instance quota; the service quota is consulted only for a key not yet recorded.

**Rationale**: FR-004 and FR-006. A member over quota after a downgrade must be able to re-apply
unchanged (an image tag) and to work their way down.

## R5. Organizations that predate the feature: `quota set` brings the record up to date

**Decision**: `PUT /organizations/{id}/quota` carries, from the endpoint, a snapshot of what exists
— project ids from `ProjectRows`, and for every service row in those projects the descriptor's
`minInstances` read from `ServiceEntity.desiredState` — and the entity **merges** it into its
record (one `UsageReconciled` event, only when it changes something) before recording the quota:
projects are the union, a service the snapshot names takes the snapshot's count, a service only
the record knows is kept. Reads never compute; `quota clear` reconciles nothing.

**Rationale**: an organization created before this feature has never been told anything, and its
usage would read zero under a fresh quota — exactly when a wrong count matters. Setting a quota is
the administrator's act, rare, and the natural moment to make the record true. Merging rather than
replacing is forced by the snapshot's source: a listing lags, so a project created a moment before
the set is in the record and not yet in the snapshot, and a replace would forget it and admit one
too many — `QuotaSuite` hit exactly that on its first run, with a quota set milliseconds after a
create. The cost of merging is that a phantom entry (a reservation whose apply *and* whose undo
both failed) is never swept; it costs one slot until that key is next applied or deleted, and the
undo's failure is logged when it happens.

**Alternatives considered**: replacing the record with the snapshot — see above. Counting services
only from their next apply (the spec's original assumption) — leaves an old organization's usage
wrong for as long as its services go untouched, which for a stable service is forever. Reconciling
on every read — the entity would have to call out. A one-off migration — there is no migration
machinery and this is the same work on demand.

## R6. The listing carries quota and usage too

**Decision**: `OrganizationRow` gains `quota` and the same `UsageRecord` the entity holds, folded
from the same events by one function (`UsageRecord.fold`) so the two cannot drift.
`OrganizationSummary.of(detail, projects, role)` carries both through from the detail.

**Rationale**: FR-007 says "wherever the organization is read", and `GET /organizations` builds
its summaries from the row without opening an entity — that is the point of the row. The
`projects` field of the summary stays the view's count (it is a listing's number and is what the
table has always shown); `usage.projects` is the entity's. They differ only for an organization
that predates quotas and has not had one set.

## R7. Routes, roles and the CLI

**Decision**: `PUT /organizations/{id}/quota` with a `Quota` body replaces the whole quota;
`DELETE /organizations/{id}/quota` clears it. Both `authz.requireAdmin` — the same gate as
`disable`/`enable`, allowed on a disabled organization. A quota naming no limit is a bad request
("names no limit; clear it instead"), a negative limit is a bad request, zero is a limit. Refusals
are `409 Conflict` with a message naming the quota and the count in use, the code the platform
already uses for "the state does not allow this". CLI: `ankka organizations quota set <id>
[--projects N] [--services N] [--instances N]` and `quota clear <id>`; the table gains a `QUOTA`
column (`2/3/4`, `-` per unlimited slot) and `SERVICES` / `INSTANCES` from usage.

**Rationale**: replace-not-patch is the spec's assumption; a plan is a whole quota. `PUT`/`DELETE`
on a sub-resource is the shape `/projects/{id}/registry` already uses.
