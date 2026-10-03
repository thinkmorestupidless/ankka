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
  `ServiceApplied` with the next generation and a `rolledBackFrom` field naming the target. The
  descriptor's validation runs again, because the platform's rules may have changed since the
  target ran.
- **Descriptors are kept in state, bounded.** The entity keeps the last fifty applied descriptors
  by generation beside the history it already caps at fifty, so a rollback reads state and never
  replays the journal. A rollback to a generation older than the kept window is refused with a
  message saying how far back the window reaches.
- **History entries learn what ran.** `HistoryEntry` gains an optional image and an optional
  digest of the descriptor. Both are optional with `None` defaults, so a journal written before
  this feature replays and a history entry from it shows neither. `EventCompatibilitySuite` pins
  the old JSON.
- **A past descriptor is readable.** `services history --generation N` prints the descriptor
  recorded at generation N, through a route that returns it, so a member can diff two
  generations before choosing one.
- **Paused, exposed and the restart counter are not part of a descriptor** and are untouched by a
  rollback. A paused service rolled back stays paused.
- **A deploy token can roll back.** It is a member, and apply is a member's action.
- **Approvals stay out.** No gate, no environment, no promotion.

What this feature is not: a rewind, an approval workflow, a way to roll back a database, or a
way to pin a service to a generation. A rollback moves forward.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A member rolls a service back to the generation that worked (Priority: P1)

A member applies a new image, sees the service fail to become ready, and runs
`ankka services rollback cart`. The platform applies the previous generation's descriptor as a
new generation. The pods roll to the old image. History shows three entries: the good apply, the
bad apply, and the rollback naming the generation it restored.

**Why this priority**: This is the feature. Everything else is how a member chooses the target.

**Independent Test**: In `ControlPlaneHttpSuite`, apply a descriptor with image A, apply with
image B, roll back, and assert the current descriptor's image is A at generation three. In the
k3s suite, assert the pod runs image A after the rollback.

**Acceptance Scenarios**:

1. **Given** a service applied at generation one with image A and at generation two with image
   B, **When** a member runs rollback with no target, **Then** generation three is applied with
   image A, and `services get` reports generation three and image A.
2. **Given** the same service, **When** a member runs rollback to generation one explicitly,
   **Then** the result is the same as scenario one.
3. **Given** a rollback at generation three, **When** the operator reconciles, **Then** the
   deployment's pods run image A, measured against a real cluster.
4. **Given** a rollback, **When** history is read, **Then** the newest entry has kind
   `rolled-back`, generation three, the actor, and the generation it restored.
5. **Given** a paused service, **When** it is rolled back, **Then** it stays paused and its
   replica count stays zero.
6. **Given** a service whose previous descriptor no longer passes the platform's validation,
   **When** a rollback is attempted, **Then** it is refused with the validation's problems and no
   generation is written.
7. **Given** a deploy token, **When** it runs a rollback, **Then** it succeeds and the history
   entry's actor is the token's subject.
8. **Given** a service that has been applied once, **When** a rollback is attempted, **Then** it
   is refused because there is no earlier generation.

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

1. **Given** three applies, **When** history is read, **Then** each `applied` entry carries the
   image that generation ran and a digest of its descriptor.
2. **Given** two generations with the same image and different environment, **When** history is
   read, **Then** their digests differ.
3. **Given** generation one, **When** `services history --generation 1` is run, **Then** it
   prints the descriptor that was applied at generation one, and the output decodes as a valid
   descriptor.
4. **Given** a journal written before this feature, **When** the service replays, **Then** its
   history entries show no image and no digest, and the service is otherwise unchanged.
5. **Given** a history entry of kind `paused`, **When** history is read, **Then** that entry
   carries no image and no digest, because nothing was applied.
6. **Given** the console's history page, **When** it reads a service with the new entries,
   **Then** it shows the image per applied generation.

---

### User Story 3 - A rollback beyond the kept window is refused clearly (Priority: P2)

A service has been applied two hundred times. A member asks for generation three. The platform
refuses, saying the window holds generations 151 to 200, rather than replaying the journal or
failing opaquely.

**Why this priority**: The bound is what keeps the entity's snapshot small; the refusal is what
keeps the bound honest.

**Independent Test**: Apply more descriptors than the window holds, roll back to one outside it,
and assert the refusal's message names the window's oldest generation.

**Acceptance Scenarios**:

1. **Given** a service with more applied generations than the window keeps, **When** a rollback
   names a generation older than the window, **Then** it is refused with a message naming the
   oldest generation available.
2. **Given** the same service, **When** a rollback names a generation inside the window, **Then**
   it succeeds.
3. **Given** a generation that never existed, **When** a rollback names it, **Then** the answer
   is a 404.
4. **Given** a generation inside the window, **When** `services history --generation N` is
   asked for one outside it, **Then** the answer says the descriptor is no longer held.

---

### Edge Cases

- A rollback to the current generation is refused as a no-op with a message, rather than
  applying the same descriptor again; a member who wants a re-pull uses `services restart` or
  `apply`.
- Two members roll back at the same moment: the entity is single-writer, so one succeeds at
  generation N+1 and the other is applied at N+2 to whatever it named, which is correct and
  visible in history.
- The target generation's descriptor declares a runtime version the platform no longer supports:
  the compatibility check that runs at projection refuses it as it would any apply, and the
  rollback's generation is recorded with that refusal in its status.
- A service deleted and recreated under the same name: its generation keeps counting and its
  kept descriptors span the deletion; a rollback across it is allowed and the descriptor is what
  it was.
- A suspended organization: a rollback is refused with a conflict, as every change is.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service entity MUST offer a `rollback` command with its own wire name that
  applies the descriptor recorded at a named generation as a new generation.
- **FR-002**: With no target named, a rollback MUST target the most recent applied generation
  before the current one.
- **FR-003**: A rollback MUST run the descriptor's validation and the organization's quota and
  suspension checks as an apply does.
- **FR-004**: The entity MUST keep the last fifty applied descriptors by generation in its state,
  and a rollback MUST read from that state, never from the journal.
- **FR-005**: A rollback to a generation outside the kept window, to the current generation, or to
  a generation that never existed MUST be refused with a message saying which.
- **FR-006**: `HistoryEntry` MUST gain an optional image and an optional descriptor digest, with
  defaults such that a journal written before this feature replays.
- **FR-007**: A history entry recording a rollback MUST carry the generation it restored.
- **FR-008**: `GET /services/{project}/{name}/history/{generation}` MUST return the descriptor
  applied at that generation while it is held, and `ankka services history --generation N` MUST
  print it.
- **FR-009**: A rollback MUST NOT change `paused`, `exposed` or the restart counter.
- **FR-010**: A deploy token MUST be able to roll back, as a member.
- **FR-011**: The CLI reference and the control plane route reference MUST be regenerated, and
  the console's history page MUST show the image per applied generation.
- **FR-012**: No approval gate, environment or promotion MUST be added.

### Key Entities

- **Service**: gains a bounded map of generation to applied descriptor.
- **History entry**: kind, generation, actor, time, and now an optional image, an optional
  descriptor digest, and for a rollback the restored generation.
- **Rollback request**: the service, an optional target generation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service rolled back on a real cluster runs the restored image within the rollout
  deadline, shown by the k3s suite.
- **SC-002**: Every history entry for an apply made after this feature carries an image and a
  digest; every entry replayed from an older journal carries neither, and `EventCompatibilitySuite`
  holds the old JSON.
- **SC-003**: A rollback outside the window, to the current generation, or to a missing
  generation is refused with a distinct message for each, shown by three tests.
- **SC-004**: The entity's snapshot size with fifty kept descriptors of typical size stays under
  the journal's payload limits, measured once and recorded in the plan.

## Assumptions

- A descriptor's digest is a hash of its canonical JSON, the same wire form `Codecs` writes, so
  two descriptors that encode identically have one digest.
- Fifty is the window because it matches `HistoryLimit`; it may be lowered if the snapshot
  measurement in SC-004 says so.
- The GitHub Action and the `ankka init` templates need no change; a rollback is a CLI command a
  workflow may run.
- The reference docs for the CLI and the routes are regenerated by their suites under the update
  flag, and the new route gets a hand-written section.

## Dependencies

- None on other specs. This touches `controlplane`, `controlplane-api`, the CLI, the console's
  history page and the generated reference pages.
- Gates nothing in the domain plan's stages; it is operator convenience the plan lists under
  stage 3 and later.

## Open Questions

- Whether the kept descriptors should be fewer than fifty to bound the snapshot's size; SC-004
  measures it.
- Whether a rollback should also be offered from the console's history page as a button, or
  stay a CLI command for this feature.
- Whether `services history` should show the image column by default or behind a flag, given
  the width of the current output.
