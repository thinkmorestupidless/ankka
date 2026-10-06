# Implementation Plan: Service Rollback — Apply a Generation That Already Ran

**Branch**: `033-service-rollback` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/033-service-rollback/spec.md`

## Summary

A member rolls a service back with `ankka services rollback <name> [--to-generation N]`, or from
the console's history. The control plane applies the descriptor recorded at an earlier generation
as a new generation: nothing is rewound, the generation keeps counting, and the history shows the
rollback and what it rolled back to. To make the target choosable, each history entry that
recorded a descriptor now shows its image and a digest, and a past descriptor can be read back.

Technically: the service entity keeps its last fifty applied descriptors in state (R2), and a
rollback is a `ServiceApplied` event with one new defaulted field, so everything that reacts to
an apply reacts to a rollback with no new case (R1). One pure function on the state resolves a
target or names one of five refusals (R8); the endpoint asks it first so it can check the
descriptor and reserve quota as an apply does, and the entity asks it again before it writes
(R4). The digest is SHA-256 of the descriptor's wire form with maps in key order (R7).

Planning found four things the spec did not have, and the spec is amended for each:

- **The kept descriptors must never travel with the state.** The projector reads every service's
  whole state on every sweep, between nodes, inside a 256 KiB frame. Fifty large descriptors
  exceed it. That reply now carries none, and with that the count stays at fifty (R2, R3).
- **Old history is not what the spec assumed.** An apply's event has always carried its
  descriptor, so history replayed from events gains images and digests whenever it was written;
  only entries held in an old snapshot lack them. A service with such a snapshot would have had
  nothing to roll back to after its first bad deploy, so its current descriptor stands as kept
  (R6).
- **The route `…/history/{generation}` cannot be declared**: a route takes two path parameters.
  It is `…/descriptor?generation=N` (R5).
- **Two rollbacks to one generation at once** end as one rollback and one refusal, not two
  generations, which follows from the clarification that refuses a target the service already
  has (R4).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`controlplane-api`, `controlplane`, `cli`); TypeScript on
Node ≥ 22 (`console/package`, `console/e2e`)

**Primary Dependencies**: none added. The digest is the JDK's `MessageDigest`.

**Storage**: the control plane's own journal and snapshots. `ServiceApplied` gains one defaulted
field and `Service` one defaulted field. No DDL, no change to `AnkkaServiceSpec` or its schema,
nothing new in the cluster.

**Testing**: munit in `controlplane-api` and `cli`; `EventSourcedTestKit` for the entity;
`AnkkaTestKit` with Postgres and the fake cluster for the HTTP suites; one k3s suite extended
(`EndToEndClusterSuite`); Node's test runner and Playwright for the console, against the fake and
against compose; the docs build and the two reference suites.

**Target Platform**: the control plane in a cluster or run locally; the CLI; the console.

**Project Type**: a control plane (an ankka application), its CLI and its console.

**Performance Goals**: a rollback costs one entity read more than an apply. `desiredState`, read
per service per sweep, does not grow.

**Constraints**: a journal and a snapshot written before the feature are read unchanged; a node
on the previous version reads a rollback as an apply; no reply but the two that return one
descriptor carries a kept descriptor; no route is added for the console alone; the operator, the
CRD and the runtime are untouched; warning-free; no suite binds a fixed port; `Test /
parallelExecution := false` stays.

**Scale/Scope**: about 9 Scala source files changed and none added, across three modules, with
about 10 suites changed and none added; 6 console source files and 3 test files changed, 4 fixture
files added and 2 changed; 6 documentation pages changed, 2 of them generated; no new page.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | `rollback` returns `effects.persist(…).thenReply`, and the two queries are `ReadOnlyEffect`s, so "cannot persist" is the compiler's (R4) |
| Where two readers reduce the same thing, they share one function | pass | the endpoint's pre-check and the entity's command both call `Service.rollbackTarget`; the fold is still `Service.fold`, shared by the entity and the replay tests (R4) |
| Module dependency direction | pass | wire types and the digest in `controlplane-api`, which still depends on `core` alone; no module gains a dependency |
| Wire names are a versioning boundary | pass | `rollback`, `rollback-target` and `descriptor-at` are declared strings; `apply` is untouched, so an in-flight apply survives the update |
| Stored forms stay readable both ways | pass | two defaulted fields; an old snapshot and an old event are pinned; an old build reads the new event as an apply (R1, R6) |
| Cross-entity checks live in the endpoint, never a handler | pass | quota and the disabled organization are checked in `ServiceEndpoint`, in the order an apply checks them (R4) |
| Quotas are reserve-first | pass | the target's instances are reserved before the command and put back if it fails, as `putBody` does |
| `generation` is guarded in the fold | pass | a rollback is folded by `onApplied`, so `onObserved`'s guard is unchanged |
| No secret value in the control plane's journal | pass | nothing new is journaled: a rollback's event holds a descriptor the journal already held. What becomes readable is said in R14 |
| A fieldless enum needs an explicit string codec | pass | `RollbackRefusal` never crosses a wire; it renders a message and an `ErrorCode` |
| jsoniter reads `null` on an `Option` as absent | pass | every new `Option` defaults to `None`, the one case where that is harmless (R10) |
| An extension looked up in a constructor breaks suites without a system | pass | the endpoint gains no field |
| Tests are serialised; no fixed port; no literal image tag | pass | no new suite; the k3s case reuses the images its suite already applies (R12) |
| An `eventually` waits for the thing it asserts | pass | every new assertion reads the entity (`get`, `history`, the descriptor route), not a listing |
| Could this check pass while the thing it checks is false? | pass | the k3s case reads the pod's image from the cluster, not the status; the two-at-once case asserts the generation moved once, not only that one call failed; quickstart names the break that turns each step red |
| Each acceptance scenario ends as a test that fails without the feature | pass | 40 scenario references in the spec, each mapped to a level in R12 |
| The console: the control plane is the only authority; the fake drifts | pass | the console decides only what to offer, and the control plane refuses; the Playwright cases run against compose too (R11) |
| Docs: pages stand alone, reference facts generated, a new route has a hand-written section | pass | R13; no new page |
| Every tracked file claimed by a CI path filter | pass | every changed file is under a directory a filter already claims |

**Violations to justify**: none. Where the plan departs from the spec's wording is under
*Complexity Tracking*.

**Post-design re-check**: unchanged. The contracts add no dependency, no grant, no field on the
resource and no route for the console alone.

## Project Structure

### Documentation (this feature)

```text
specs/033-service-rollback/
├── plan.md              # this file
├── research.md          # R1–R14: decisions with file-level evidence; six things to verify first
├── data-model.md        # the event, the state, the fold, resolving a target, the digest, stored forms
├── quickstart.md        # the validation runs: pure → entity → HTTP and CLI → console → k3s → docs → by hand
├── contracts/
│   ├── control-plane.md     # the two routes, every refusal, the changed history reply, the entity
│   ├── cli.md               # the command, the option, the table
│   └── console.md           # the client, the history's columns and control, the fake
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/control-plane/rollback.feature` and
`features/control-plane/history.feature`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
controlplane-api/…/api/descriptors.scala          # HistoryEntry's fields, RollbackRequest, RolledBack,
                                                   # ServiceDescriptor.digest
controlplane-api/src/test/…/DescriptorSuite.scala, ControlPlaneFixturesSuite.scala

controlplane/…/controlplane/domain/events.scala   # ServiceApplied.rolledBackTo, RollbackService
controlplane/…/controlplane/domain/model.scala    # Service.kept, KeptDescriptor, the seeding accessor,
                                                   # descriptorAt, rollbackTarget, RollbackRefusal, the fold
controlplane/…/controlplane/application/ServiceEntity.scala   # rollback, rollback-target, descriptor-at;
                                                               # desiredState empties kept
controlplane/…/controlplane/application/ServiceRows.scala     # one more position in a pattern
controlplane/…/controlplane/api/ServiceEndpoint.scala         # the two routes
controlplane/src/test/…/ServiceEntitySuite.scala, EventCompatibilitySuite.scala,
                        ControlPlaneHttpSuite.scala, QuotaSuite.scala, SuspensionSuite.scala,
                        DeployTokensSuite.scala, CliEndToEndSuite.scala, EndToEndClusterSuite.scala,
                        ControlPlaneRoutesReferenceSuite.scala

cli/…/cli/Main.scala                              # services rollback; history --generation
cli/…/cli/ControlPlaneClient.scala                # rollbackService, serviceDescriptor
cli/…/cli/Output.scala                            # the history table; the rollback's line
cli/…/cli/mcp/AnkkaTools.scala                    # service_history's description; no tool added
cli/src/test/…/OutputSuite.scala, CliReferenceSuite.scala

console/package/src/client/schemas.ts, control-plane.ts
console/package/src/routes/service.tsx            # the columns and the control (after feature 035)
console/package/src/extensions/types.ts           # service.rollback
console/package/src/testing/fake-control-plane.ts, scenarios.ts
console/package/fixtures/control-plane/            # RollbackRequest*, RolledBack* (new); HistoryEntry* (changed)
console/package/test/client.test.ts, fixtures.test.ts
console/e2e/tests/services.spec.ts

docs/operate/service-lifecycle.md, status-and-history.md, console.md
docs/reference/glossary.md, control-plane-api.md (part generated), cli.md (generated),
               console-package.md
marketplace/, ankka.g8/…/.claude/skills/           # rendered again by `just docs-sync`
```

**Structure Decision**: no module, file, image or published artifact is added, and nothing below
the control plane changes: not the CRD, the operator or the runtime. Each piece goes where its
kind already is: the wire types and the digest beside the descriptor in `controlplane-api`, the
state and its rules in `domain/model.scala` beside the fold, the routes on `ServiceEndpoint` so
they are under the same ACL and project scoping as every service route.

## Order of work

Cut by user story, tests before the code they hold. Each slice stands on its own and leaves the
build green.

1. **What every story reads.** The old snapshot captured first; the digest; the wire types and
   the console's schemas for them; `kept` with its cap and its seeding; `desiredState` emptied;
   the stored-form pins. Verify items 1 to 5 of research's list here.
2. **The rollback** (User Story 1). `rollbackTarget` and its refusals; the command and its query;
   the route with quota, the disabled organization, the deploy token and two at once; the CLI
   command; the one k3s case. Verify item 6 here.
3. **What each generation ran** (User Story 2). The image and digest in history; the descriptor
   route; the CLI's table and `--generation`.
4. **Beyond the kept descriptors** (User Story 3). Cases, over code slice 2 wrote.
5. **The console** (the third clarification). Client and fake first; the page last, on whichever
   of this feature and feature 035 reaches `main` second (R11).
6. **Documentation**, then `sbt buildAll` and `just features`.

Slices 2 and 3 depend on 1 and not on each other; 4 follows 2; 5 needs 2 and 3.

## Complexity Tracking

No principle is violated. These are the places the plan departs from the spec's wording, each
with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| A past descriptor is read at `…/descriptor?generation=N`, not `…/history/{generation}` (FR-008, amended) | a route takes at most two path parameters | three-parameter overloads widen a published library's API and its documentation for one control plane route (R5) |
| `desiredState` no longer returns the state whole | fifty large descriptors exceed the 256 KiB frame a reply between nodes must fit in, and the projector reads this for every service on every sweep | keeping fewer descriptors only moves the size at which reconciliation silently stops (R2, R3) |
| A state written before the feature has its current descriptor stand as kept (FR-004, amended) | otherwise the first bad deploy after the upgrade cannot be rolled back, for every service with a snapshot | "apply twice first" is a rule nobody would know until the moment they needed a rollback (R6) |
| An old apply replayed from events shows its image and digest (FR-006 and SC-002, amended) | the event always carried the descriptor, and the fold cannot tell an old event from a new one | hiding them would need a marker on new events for no gain to anyone (R6) |
| Two rollbacks to one generation at once: one refused (the edge case, amended) | the second clarification refuses a target the service already has | applying both, as the spec first said, contradicts that clarification (R4) |
| The rollback's reply is its own type | a rollback with no generation named must say which it chose | a bare `ServiceStatus` leaves the CLI to make a second request that can race (R10) |
| The endpoint resolves the default target, not the entity | the endpoint needs the descriptor to check it and reserve quota; and resolving once makes a double submission a refusal instead of an undo | resolving in the entity turns "two people clicked roll back" into a rollback and its reversal (R4) |

**One thing to say in the release notes**: a past descriptor shows its variables' literal values
to every member of the project's organization and every platform administrator. Nothing new is stored and no one gains access they
did not have, but a value that should not be read back belongs in a project secret (R14).

**One dependency on other work**: the console's page is rewritten by feature 035, an open pull
request. Slice 5's page change is written against whichever lands first.
