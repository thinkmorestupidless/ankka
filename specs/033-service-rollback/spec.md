# Feature Specification: Service Rollback — Apply a Generation That Already Ran

**Feature Branch**: `033-service-rollback`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "Let a member roll a service back. Every apply already records the
full descriptor in the control plane's journal, so every generation's descriptor exists; nothing
reads one back. Add `ankka services rollback <service> [--to-generation N]`, which applies the
descriptor recorded at generation N as a new generation, never a rewind. Make history entries
carry the image and a digest of the descriptor, so a member can see what each generation ran and
pick one, and let `services history --generation N` print that generation's descriptor. Approvals
and promotion stay out of scope: a GitHub environment with required reviewers in front of the
deploy step is an approval gate that already exists, is audited, and needs no second permission
model in the control plane."

## Context

The control plane records a service as an event sourced entity. `ServiceEntity.scala` in
`controlplane/.../application/` handles `apply`, which checks the project and name, runs the
descriptor's own validation, and persists `ServiceApplied(projectId, descriptor, generation,
actor, at)` with the generation incremented. The events are in `controlplane/.../domain/events.scala`
and the state in `domain/model.scala`: a `Service` holds the current `descriptor`, `generation`,
`paused`, `restarts`, `exposed`, `suspended`, and a `history` of `HistoryEntry` values, newest
first, capped at `Service.HistoryLimit`, which is fifty. `HistoryEntry` in
`controlplane-api/.../api/descriptors.scala` carries a kind, a generation, an optional actor and
an optional time. The kinds are applied, restarted, paused, resumed, exposed, unexposed and the
rest; observations are not recorded. `GET /services/{project}/{name}/history` returns those
entries, and `ankka services history` prints when, kind, generation and by.

Two facts follow. Every generation's full descriptor is in the journal, because `ServiceApplied`
carries it. And nothing reads one back: the state keeps only the current descriptor, a history
entry carries neither image nor descriptor, and there is no command or route that names a past
generation. A member who deployed a bad image at generation twelve can see that generation twelve
was applied by whom and when, and nothing about what it was. To go back they find the old
descriptor in their own repository and apply it again. A deploy token in CI can do the same if
the repository holds it. Neither is a rollback the platform offers.

Re-applying an unchanged descriptor already bumps the generation on purpose, so the image is
pulled again. That is the shape a rollback should have: a new generation whose descriptor is an
old one. A rollback is never a rewind of the journal. The generation keeps counting, the history
shows the rollback as its own entry, and the reconciliation guard in `Service.onObserved`, which
drops observations describing a superseded generation, needs no change.

Spec 013 put deployment environments, approvals and promotion out of scope as a
workflow-authoring concern. That decision stands, for the same reason. A GitHub environment with
required reviewers sits in front of the deploy step, records who approved, and can gate on a
branch. A second approval model in the control plane would have to replicate that and would be
the place where the two disagreed. The domain plan's gate tiers map onto environments and need
nothing here.

The decisions this feature makes:

- **A rollback is a new apply.** A `rollback` command on the service entity, with its own wire
  name, takes a target generation, finds that generation's descriptor, and persists a
  `ServiceApplied` with the next generation and a `rolledBackTo` field naming the target. The
  descriptor's validation runs again, because the platform's rules may have changed since the
  target ran.
- **Descriptors are kept in state, bounded.** The entity keeps the last fifty applied descriptors
  by generation beside the history it already caps at fifty, so a rollback reads state and never
  replays the journal. A rollback to a generation older than the kept descriptors is refused with a
  message saying how far back they reach.
- **History entries learn what ran.** `HistoryEntry` gains an optional image and an optional
  digest of the descriptor, set on every entry that recorded a descriptor: an apply's and a
  rollback's. Both are optional with `None` defaults, so a snapshot written before this feature
  decodes and a history entry held in it shows neither. `EventCompatibilitySuite` pins the old
  JSON.
- **A past descriptor is readable.** `services history --generation N` prints the descriptor
  recorded at generation N, through a route that returns it, so a member can diff two
  generations before choosing one. It is the first route that returns a descriptor at all, so a
  variable's literal value is readable by every member of the project's organization and every
  platform administrator, as it is already replaceable by each of them.
- **Paused, exposed and the restart counter are not part of a descriptor** and are untouched by a
  rollback. A paused service rolled back stays paused.
- **A deploy token can roll back.** It is a member, and apply is a member's action.
- **The console can roll back.** Its history offers a rollback at each generation that can be
  rolled back to, through the same route the CLI calls, and shows a refusal as the control plane
  gave it.
- **Approvals stay out.** No gate, no environment, no promotion.

What this feature is not: a rewind, an approval workflow, a way to roll back a database, or a
way to pin a service to a generation. A rollback moves forward.

## Clarifications

### Session 2026-10-04

- Q: With no generation named, which generation does a rollback target, given that a restart also
  moves the generation? → A: The most recent generation whose descriptor differs from the current
  one, compared by digest. Restarts and applies of an identical descriptor are skipped, so a
  rollback always changes something, and rolling back twice returns to where it started.
- Q: What happens when a member names a generation that recorded no descriptor of its own (a
  restart's), or one whose descriptor is the same as the current one? → A: Both are refused, each
  saying why. A generation with no descriptor is refused naming the generation whose descriptor it
  ran; a generation whose descriptor equals the current one is refused as changing nothing.
  Reading a past descriptor follows the same rule for a generation that recorded none.
- Q: Is a rollback offered from the console in this feature, or only from the CLI? → A: From the
  console too. Its history offers a rollback at each generation that can be rolled back to.
- Q: Are the glossary's five proposed terms right (generation, history, roll back, digest,
  disabled), and what is the field naming a rollback's target called? → A: All five stand. An
  organization is "disabled" and its services show `Suspended`. The field is `rolledBackTo`. The
  descriptors a service keeps are "the kept descriptors" (formerly "the kept window"), because
  "window" already means the stretch of time a topology counts calls over.
- Q: What does `ankka services history` print by default, and does a rollback's own history entry
  carry an image and a digest? → A: The table gains IMAGE and DIGEST (its first twelve characters)
  by default, on every entry that recorded a descriptor, applied and rolled-back alike. A
  rolled-back entry's kind is printed as `rolled-back to N`. JSON carries the full digest and
  `rolledBackTo`.

### Amended in planning, 2026-10-04

- **The route that reads a past descriptor** is `GET /services/{project}/{name}/descriptor?generation=N`,
  not `…/history/{generation}`: an endpoint's route takes at most two path parameters
  (research R5). FR-008 says so.
- **Old history.** A history entry's image and digest are computed from the apply's event, which
  has always carried the descriptor. So an entry replayed from events shows both, whenever the
  event was written; only an entry held in a snapshot taken before this feature shows neither.
  A service with such a snapshot has its current descriptor stand as kept, so its first apply
  after the upgrade can be rolled back (research R6). FR-004, FR-006 and SC-002 say so.
- **Two rollbacks to one generation at the same moment**: one is made and the other is refused,
  because by then the service has that descriptor. This follows from refusing a target whose
  descriptor the service already has (research R4).
- **The kept descriptors stay at fifty.** The limit that mattered was not the journal's but the
  256 KiB frame a reply between nodes must fit in, and no reply carries the kept descriptors
  (research R2, R3). SC-004 says so.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A member rolls a service back to the generation that worked (Priority: P1)

A member applies a new image, sees the service fail to become ready, and runs
`ankka services rollback cart`. The platform applies the previous generation's descriptor as a
new generation. The pods roll to the old image. History shows three entries: the good apply, the
bad apply, and the rollback naming the generation it rolled back to.

**Why this priority**: This is the feature. Everything else is how a member chooses the target.

**Independent Test**: In `ControlPlaneHttpSuite`, apply a descriptor with image A, apply with
image B, roll back, and assert the current descriptor's image is A at generation three. In the
k3s suite, assert the pod runs image A after the rollback.

**Acceptance Scenarios**:

- added `features/control-plane/rollback.feature`: rolling back with no generation named applies the descriptor of the generation before
- added `features/control-plane/rollback.feature`: rolling back with no generation named passes over a restart
- added `features/control-plane/rollback.feature`: rolling back with no generation named passes over a generation applied with the same descriptor
- added `features/control-plane/rollback.feature`: rolling back twice with no generation named brings back the descriptor it started with
- added `features/control-plane/rollback.feature`: rolling back to a named generation applies the descriptor of that generation
- added `features/control-plane/rollback.feature`: a service rolled back runs the image of the generation it was rolled back to
- added `features/control-plane/rollback.feature`: the history shows a roll back, who made it and the generation it was rolled back to
- added `features/control-plane/rollback.feature`: a paused service that is rolled back stays paused
- added `features/control-plane/rollback.feature`: an exposed service that is rolled back stays exposed
- added `features/control-plane/rollback.feature`: a roll back to a descriptor the platform no longer accepts is refused
- added `features/control-plane/rollback.feature`: a roll back that would take the organization over its quota is refused
- added `features/control-plane/rollback.feature`: a machine holding a deploy token rolls a service back
- added `features/control-plane/rollback.feature`: a member rolls a service back from the console
- added `features/control-plane/rollback.feature`: the console offers a roll back only to a generation that can be rolled back to
- added `features/control-plane/rollback.feature`: the console shows a refused roll back as the control plane refused it
- added `features/control-plane/rollback.feature`: a service applied only once cannot be rolled back
- added `features/control-plane/rollback.feature`: a roll back to the generation a service is at is refused
- added `features/control-plane/rollback.feature`: a roll back to a generation with the descriptor the service already has is refused
- added `features/control-plane/rollback.feature`: a roll back to a generation that recorded no descriptor is refused
- added `features/control-plane/rollback.feature`: of two roll backs to one generation made at once, one is made and the other is refused
- added `features/control-plane/rollback.feature`: a service applied before the platform kept descriptors is rolled back to the descriptor it had
- added `features/control-plane/rollback.feature`: a roll back to a descriptor whose runtime version the platform no longer runs is recorded and not deployed
- added `features/control-plane/rollback.feature`: a service deleted and applied again is rolled back to a generation from before it was deleted
- added `features/control-plane/rollback.feature`: a service of a disabled organization cannot be rolled back
- added `features/control-plane/rollback.feature`: a person who is not a member cannot roll a service back

---

### User Story 2 - A member sees what each generation ran (Priority: P1)

Before rolling back, the member reads the history and sees the image each generation ran and a
digest of its descriptor, so two generations with the same image but different environment are
distinguishable. They print generation one's descriptor and compare it with the current one.

**Why this priority**: A rollback to a generation whose contents are invisible is a guess. This
is what makes the target choosable.

**Independent Test**: Apply three descriptors differing in image and in environment, read the
history, and assert each entry's image and digest; read generation one's descriptor and assert
it equals what was applied.

**Acceptance Scenarios**:

- added `features/control-plane/history.feature`: the history shows the image and a digest at every generation that was applied
- added `features/control-plane/history.feature`: two generations with the same image and a different environment have different digests
- added `features/control-plane/history.feature`: two generations applied with the same descriptor have the same digest
- added `features/control-plane/history.feature`: a roll back shows the image and the digest of the generation it was rolled back to
- added `features/control-plane/history.feature`: a member reads the descriptor that was applied at a generation
- added `features/control-plane/history.feature`: history an older platform kept is still read, and shows no image and no digest
- added `features/control-plane/history.feature`: what applied no descriptor shows no image and no digest in the history
- added `features/control-plane/history.feature`: the console shows the image of each generation that was applied
- added `features/control-plane/history.feature`: a person who is not a member cannot read the history of a service

---

### User Story 3 - A rollback beyond the kept descriptors is refused clearly (Priority: P2)

A service has been applied two hundred times. A member asks for generation three. The platform
refuses, saying it keeps the descriptors of generations 151 to 200, rather than replaying the journal or
failing opaquely.

**Why this priority**: The bound is what keeps the entity's snapshot small; the refusal is what
keeps the bound honest.

**Independent Test**: Apply more descriptors than are kept, roll back to one no longer kept, and
assert the refusal's message names the oldest generation whose descriptor is kept.

**Acceptance Scenarios**:

- added `features/control-plane/rollback.feature`: a roll back to a generation whose descriptor is no longer kept is refused
- added `features/control-plane/rollback.feature`: a roll back to the oldest generation whose descriptor is kept is made
- added `features/control-plane/rollback.feature`: a roll back to a generation the service never had is told there is no such generation
- added `features/control-plane/history.feature`: the descriptor of a generation that is no longer kept cannot be read
- added `features/control-plane/history.feature`: the descriptor of a generation the service never had cannot be read
- added `features/control-plane/history.feature`: the descriptor of a generation that recorded none cannot be read

---

### Edge Cases

- A rollback to a generation whose descriptor is the one the service already has, the current
  generation among them, is refused as changing nothing rather than applying the same descriptor
  again; a member who wants a re-pull uses `services restart` or `apply`. The comparison is by
  digest.
- A rollback to a generation that recorded no descriptor, which is a restart's, is refused with a
  message naming the generation whose descriptor it ran. `services history --generation N`
  answers the same for such a generation.
- Two members roll back at the same moment: the entity is single-writer. Where they name
  different generations, one is applied at generation N+1 and the other at N+2, each to what it
  named, and both show in history. Where they name the same generation, or neither names one, the
  first is applied and the second is refused, because the service by then has that descriptor.
- The target generation's descriptor declares a runtime version the platform no longer supports:
  the compatibility check that runs at projection refuses it as it would any apply, and the
  rollback's generation is recorded with that refusal in its status.
- A service deleted and recreated under the same name: its generation keeps counting and its
  kept descriptors span the deletion; a rollback across it is allowed and the descriptor is what
  it was.
- A disabled organization: a rollback is refused with a conflict, as every change is.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service entity MUST offer a `rollback` command with its own wire name that
  applies the descriptor recorded at a named generation as a new generation.
- **FR-002**: With no target named, a rollback MUST target the most recent generation whose
  descriptor differs from the current one, compared by digest. A restart, and an apply or a
  rollback that recorded the descriptor the service already has, are never the target. Where no
  kept descriptor differs, the rollback MUST be refused with a message saying so.
- **FR-003**: A rollback MUST run the descriptor's validation, and the checks of the
  organization's quota and of whether it is disabled, as an apply does.
- **FR-004**: The entity MUST keep the last fifty applied descriptors by generation in its state,
  and a rollback MUST read from that state, never from the journal. A state written before this
  feature holds none; its current descriptor MUST stand as kept, at the generation of its newest
  recorded apply (the current generation when its history records none), so the first apply after
  an upgrade can be rolled back. The kept descriptors MUST NOT be
  part of any reply but the two that return one descriptor.
- **FR-005**: A rollback MUST be refused, with a message saying which, when the named generation
  is older than the kept descriptors, never existed, recorded no descriptor of its own, or recorded the
  descriptor the service already has. The refusal for a generation that recorded no descriptor
  MUST name the generation whose descriptor it ran.
- **FR-006**: `HistoryEntry` MUST gain an optional image and an optional descriptor digest, with
  defaults such that a journal and a snapshot written before this feature are read. Both MUST be
  set on every entry that recorded a descriptor, an apply's and a rollback's, and on no other. An
  entry held in a snapshot taken before this feature has neither.
- **FR-007**: A history entry recording a rollback MUST carry the generation it rolled back to, as
  `rolledBackTo`.
- **FR-008**: `GET /services/{project}/{name}/descriptor?generation=N` MUST return the descriptor
  applied at that generation while it is held, and `ankka services history --generation N` MUST
  print it. For a generation that recorded no descriptor it MUST answer so, naming the generation
  whose descriptor it ran.
- **FR-009**: A rollback MUST NOT change `paused`, `exposed` or the restart counter.
- **FR-010**: A deploy token MUST be able to roll back, as a member.
- **FR-011**: The CLI reference and the control plane route reference MUST be regenerated, and
  the console's history page MUST show the image per applied generation.
- **FR-012**: No approval gate, environment or promotion MUST be added.
- **FR-013**: The console's history MUST offer a rollback at each generation that can be rolled
  back to and at no other: not at a generation that recorded no descriptor, not at one whose
  descriptor the service already has, and not at one older than the kept descriptors. The one
  exception is a generation whose history entry has no digest, which is one recorded before this
  feature: the console does not offer it, and the CLI rolls back to it. It MUST ask
  the member to confirm, naming the generation and its image, before it calls the control plane,
  and MUST show a refusal verbatim. It calls the route the CLI calls, as the member; no route is
  added for the console alone.
- **FR-014**: `ankka services history` MUST print, by default, an IMAGE column and a DIGEST column
  holding the digest's first twelve characters, each `-` on an entry that recorded no descriptor,
  and MUST print a rollback's kind as `rolled-back to N`. Its JSON output MUST carry the full
  digest and `rolledBackTo`.

### Key Entities

- **Service**: gains a bounded list of applied descriptors, each with its generation, newest
  first.
- **History entry**: kind, generation, actor, time, and now an optional image, an optional
  descriptor digest, and for a rollback the generation it rolled back to.
- **Rollback request**: the service, an optional target generation. The CLI may leave the target
  out; the console always names one.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service rolled back on a real cluster runs the image rolled back to within the
  k3s suite's wait of 180 seconds.
- **SC-002**: Every history entry for an apply made after this feature carries an image and a
  digest; an entry held in a snapshot taken before it carries neither, and
  `EventCompatibilitySuite` holds the old snapshot and the old event.
- **SC-003**: A rollback to a generation older than the kept descriptors, to a missing
  generation, to a generation that recorded no descriptor, or to a generation with the descriptor
  the service already has is refused with a distinct message for each, shown by four tests.
- **SC-004**: With fifty kept descriptors of typical size, no reply of the entity but the two that
  return one descriptor grows, and the snapshot's size is measured and recorded in the plan
  (research R3: 83 KiB typical, 214 KiB with forty variables each).

## Assumptions

- A descriptor's digest is a hash of its canonical JSON, the same wire form `Codecs` writes, so
  two descriptors that encode identically have one digest.
- Fifty descriptors are kept because that matches `HistoryLimit`, so every history entry that
  recorded a descriptor is still kept.
- The GitHub Action and the `ankka init` templates need no change; a rollback is a CLI command a
  workflow may run.
- The reference docs for the CLI and the routes are regenerated by their suites under the update
  flag, and the new route gets a hand-written section.

## Dependencies

- None on other specs. This touches `controlplane`, `controlplane-api`, the CLI, the console
  package (its history page, its control plane client and schemas, and the fake control plane its
  tests run against) and the generated reference pages.
- Gates nothing in the domain plan's stages; it is operator convenience the plan lists under
  stage 3 and later.

## Open Questions

None. The three this spec opened with were closed by clarification (the console, the history
table) and by planning (the kept descriptors stay at fifty).
