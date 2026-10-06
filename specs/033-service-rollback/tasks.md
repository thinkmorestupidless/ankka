# Tasks: Service Rollback — Apply a Generation That Already Ran

**Input**: Design documents from `/specs/033-service-rollback/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and research's "To verify first" list turns each fact read
from code into a test before the code that relies on it. The scenarios are in
`features/control-plane/rollback.feature` and `features/control-plane/history.feature`. Neither
file can be run whole by one `GherkinSuite` (they span the fold, the HTTP API, the console and a
cluster), so where a task says "case" it means a `test(...)` in the named suite, **named with the
scenario's own sentence** so the two can be found from each other.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a member rolls a service back), US2 (a member sees what each generation ran),
  US3 (a rollback beyond the kept descriptors is refused clearly)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `API`/`APIT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `CLI`/`CLIT` =
`cli/src/{main,test}/scala/…/cli`; `CON` = `console/package`; `DOCS` = `docs`. "R*n*" is a section
of `research.md`; "V*n*" an item of its *To verify first* list; a contract is named by its file
under `contracts/`.

The branch `033-service-rollback` exists, in the worktree `.claude/worktrees/033-service-rollback`.
Every `sbt` command below takes `-Dankka.cluster.tests=off` unless the task names a k3s suite.

---

## Phase 1: Setup — pin what exists before changing it

**Purpose**: the stored forms the feature must go on reading have to be captured from the code as
it is now. Once the model has changed, the code can no longer write them.

- [X] T001 Capture a `Service` snapshot as `main` writes it, before touching `CP/domain/model.scala`: in a scratch case in `CPT/ServiceEntitySuite.scala`, apply `cart:1.0` with an attributed actor and time, restart, pause, resume, then print `new String(ServiceEntity.stateSerializer.toBytes(kit.currentState), "UTF-8")`. Run `sbt 'controlPlane/testOnly *ServiceEntitySuite'`, keep the printed JSON as the literal for T006, and delete the scratch case. The JSON must have no `kept` field and four-field history entries; that is what makes it the old form.
- [X] T002 [P] V3 and V4, as cases that pass today: in `modules/http/src/test/…/http/` find the suite that covers query parameters and bodies and confirm (adding a case only if none exists) that `query.required[Long]("generation")` parses `7` and answers 400 for `x` and for an absent parameter, and that `postBody` decodes the body `{}` into a case class whose only field is an `Option` defaulting to `None`. If there is no `FromQuery[Long]`, note it in `research.md` V3 and read an `Int` in T023.

**Checkpoint**: the old snapshot is in hand as a literal; nothing in `main` code has changed.

---

## Phase 2: Foundational — the digest, the wire types, the kept descriptors

**Purpose**: what every story reads: a descriptor's digest, the fields a history entry and the
event gain, and the descriptors the entity keeps. It blocks US1, US2 and US3.

**⚠️ CRITICAL**: `desiredState` must stop carrying the kept descriptors in the same change that
starts keeping them (R2). Keeping them first and stripping later ships a reply that can exceed the
remoting frame.

### Tests first

- [X] T003 [P] Digest cases in `APIT/DescriptorSuite.scala`, per data-model "The digest": equal descriptors share one; `labels` and `annotations` in a different order share one; `env` in a different order differs; a changed variable value differs; a field stated at its default and the same field omitted share one; the digest is 64 lowercase hex characters. Named for `history.feature`'s "two generations with the same image and a different environment have different digests" and "two generations applied with the same descriptor have the same digest". They fail to compile until T007.
- [X] T004 [P] Fixtures in `APIT/ControlPlaneFixturesSuite.scala`: change the `HistoryEntry` sample to carry `image`, `digest` and `rolledBackTo` on its full form and none on its minimal form, and add `RollbackRequest` (full: `generation = Some(1)`; minimal: none) and `RolledBack`. The suite fails until T008 and until the files are written with `-Dankka.docs.update=true`.
- [X] T005 Kept-descriptor and fold cases in `CPT/ServiceEntitySuite.scala`: after three applies `currentState.kept` holds generations 3, 2, 1 newest first; after 60 applies it holds exactly generations 60 down to 11; a restart, a pause and an expose add nothing to `kept`; deleting keeps `kept` and applying again pushes onto it; "state is rebuilt purely by folding events" still holds with `kept`; `desiredState`'s reply has `kept` empty while `currentState.kept` does not; and with fifty descriptors of twelve variables each, the state encodes to under 128 KiB and the `desiredState` reply to under 16 KiB through `ServiceEntity`'s own serializers (R3, V5; record the two measured numbers in `research.md` R3).
- [X] T006 Stored-form cases in `CPT/EventCompatibilitySuite.scala`: (a) T001's literal decodes through `ServiceEntity.stateSerializer`, with `kept` empty and every history entry's `image`, `digest` and `rolledBackTo` `None`, named "history an older platform kept is still read, and shows no image and no digest"; (b) folding one new `ServiceApplied` over that state leaves two kept descriptors, the old one at the generation of its `applied` history entry (the seeding of R6); (c) the pre-008 `ServiceApplied` sample decodes with `rolledBackTo = None`, by adding the sixth position to the two existing patterns; (d) a `ServiceApplied` with `rolledBackTo = Some(1)` round-trips and its JSON has the field, and one without it has no such field; (e) V2: a private copy of the five-field `ServiceApplied` case class, with the shared codec, decodes the JSON of (d) and reads an apply.

### Implementation

- [X] T007 `ServiceDescriptor.digest` in `API/descriptors.scala` per R7: put `labels` and `annotations` in key order (a `ListMap` sorted by key), encode with the descriptor's own codec, SHA-256 with `java.security.MessageDigest`, lowercase hex. No new dependency. T003 passes.
- [X] T008 Wire types in `API/descriptors.scala` per data-model "Wire types": `image`, `digest`, `rolledBackTo` on `HistoryEntry`, each an `Option` defaulting to `None`; `RollbackRequest(generation: Option[Long] = None)`; `RolledBack(rolledBackTo: Long, status: ServiceStatus)`; their codecs in `Wire` where `HistoryEntry`'s is. Then `sbt -Dankka.docs.update=true 'controlPlaneApi/testOnly *ControlPlaneFixturesSuite'` to write `CON/fixtures/control-plane/{HistoryEntry,RollbackRequest,RolledBack}*.json`. The Scala suite passes once the files are written; the console's `CON/test/fixtures.test.ts` then fails with `no schema for the wire type` until T009, which is next.
- [X] T009 In `CON/src/client/schemas.ts` per contracts/console.md: three optional fields on `historyEntrySchema`; `rollbackRequestSchema` and `rolledBackSchema`, both in the table `CON/test/fixtures.test.ts` reads. `npm test -w package` (run in `console/`) and `sbt 'controlPlaneApi/testOnly *ControlPlaneFixturesSuite'` both pass. It is here and not with the console's other work because T008's fixture files turn the console's own test red until the schemas exist.
- [X] T010 `ServiceApplied.rolledBackTo: Option[Long] = None` as the last field in `CP/domain/events.scala`, and `RollbackService(generation: Long)` beside `ApplyService`. Add the sixth position to the pattern in `CP/application/ServiceRows.scala` and in `Service.fold` (`CP/domain/model.scala`); `grep -rn 'ServiceApplied(' --include='*.scala' .` must show no five-position pattern left.
- [X] T011 In `CP/domain/model.scala` per data-model "The state": `KeptDescriptor(generation, descriptor)`; `Service.kept: Vector[KeptDescriptor] = Vector.empty`; `Service.KeptDescriptors = 50`; one accessor that returns `kept`, or, when it is empty and `descriptor` is defined, the current descriptor at the generation of the newest history entry of kind `applied` (the current generation when there is none); `onApplied` pushing onto that accessor and capping. Nothing else reads the raw field.
- [X] T012 In `CP/application/ServiceEntity.scala`: `desiredState` replies `currentState.copy(kept = Vector.empty)` when the service exists, with a comment giving the reason (the reply crosses nodes inside a 256 KiB frame, and the projector asks for every service on every sweep). T005 and T006 pass; `sbt 'controlPlane/testOnly *ServiceEntitySuite *EventCompatibilitySuite *ServiceProjectionSuite *ProjectorSuite'` is green. *`desiredState` also leaves out `history`, which none of its readers uses: with fifty entries carrying image and digest it was 13.6 KiB of the reply (research R3).*

**Checkpoint**: the entity keeps descriptors, old state reads and is seeded, no reply grew. No
route and no command has been added, and every existing suite is green.

---

## Phase 3: User Story 1 — a member rolls a service back (Priority: P1) 🎯 MVP

**Goal**: `ankka services rollback cart` applies the last different descriptor as a new
generation; the history shows the rollback and what it rolled back to.

**Independent Test**: `sbt 'controlPlane/testOnly *ServiceEntitySuite *ControlPlaneHttpSuite *CliEndToEndSuite'`:
apply image A, apply image B, roll back, and the service is at generation three with image A.

### Verify first

- [X] T013 [US1] V6 in `CPT/ServiceEntitySuite.scala` or the testkit suite that covers unknown handlers: a call naming a handler the entity does not declare is answered with an error and does not wait for the timeout. Record the answer's code in `contracts/control-plane.md` "Rolling update" in place of the sentence that says it is to be checked. *Answered by reading `EventSourcedEntityHost`, which replies at once with `NotFound` for an undeclared handler; `CallCountsSuite` already holds that path, so no new case was needed.*

### Tests first

- [X] T014 [US1] Target cases in `CPT/ServiceEntitySuite.scala` against `Service.rollbackTarget`, one per scenario of `rollback.feature`: with no generation named it applies the descriptor of the generation before; it passes over a restart; it passes over a generation applied with the same descriptor; rolling back twice brings back the descriptor it started with; a named generation applies that generation's descriptor; a service applied only once cannot be rolled back (`NothingToRollBackTo`, the words of R8); a roll back to the generation a service is at is refused and one to a generation with the descriptor the service already has is refused (`SameDescriptor`). And one case in `CPT/EventCompatibilitySuite.scala`, "a service applied before the platform kept descriptors is rolled back to the descriptor it had": decode T001's literal, fold one new `ServiceApplied` with another image, and `rollbackTarget(None)` is the old descriptor at the generation of its `applied` history entry; folding the rollback's event then leaves the service one generation on with the old image.
- [X] T015 [US1] Command cases in `CPT/ServiceEntitySuite.scala` through `kit.call(ServiceEntity.rollback)`: the event is a `ServiceApplied` with the next generation, the target's descriptor and `rolledBackTo`; the newest history entry has kind `rolled-back`, the generation, the actor from the metadata and `rolledBackTo`; a paused service stays paused with lifecycle `Paused`; an exposed service stays exposed; `restarts` is unchanged; a refusal persists nothing (`kit.allEvents` unchanged); a service never applied, and a deleted one, is `NotFound`; a service deleted and applied again is rolled back to a generation from before it was deleted; a target whose descriptor now has `problems` is refused with every problem and persists nothing (build the state by folding a `ServiceApplied` holding a descriptor that fails today's rules, since `apply` would refuse it).
- [X] T016 [US1] Route cases in `CPT/ControlPlaneHttpSuite.scala` per contracts/control-plane.md: `POST /services/shop/cart/rollback` with `{}` answers 200 with `rolledBackTo` and a status at the next generation with the first image, and `GET …/cart` agrees; with `{"generation":1}` the same; the refusals a rollback alone can meet (the same descriptor, nothing to roll back to, an invalid descriptor) each answer their status and their words, the three about reaching a generation being T037's; a person who is not a member is answered 404 for the project and the generation has not moved; "of two roll backs to one generation made at once, one is made and the other is refused": send both from two threads, assert exactly one 200 and one 409 and that the generation moved by one.
- [X] T017 [P] [US1] In `CPT/QuotaSuite.scala`, "a roll back that would take the organization over its quota is refused": with no quota, apply a descriptor asking for 3 instances (generation 1), then one asking for 1 (generation 2); set a quota of 2 instances; roll back to generation 1. Assert the refusal is the organization's own quota message, the generation is still 2, and the organization's usage is what it was before the attempt, which is what proves the reservation was never taken or was put back.
- [X] T018 [P] [US1] In `CPT/SuspensionSuite.scala`: "a service of a disabled organization cannot be rolled back" — 409 with `organization '…' is disabled`, generation unchanged. *Done in `CPT/AuthorizationMatrixSuite.scala`, where every route's answer for a disabled organization is held; `SuspensionSuite` drives the entities, not the routes.*
- [X] T019 [P] [US1] In `CPT/DeployTokensSuite.scala`: "a machine holding a deploy token rolls a service back" — the rollback succeeds with the token and the history entry's actor subject is `token:<id>`. *Done in `CPT/ControlPlaneHttpSuite.scala`, which mints real deploy tokens over HTTP; `DeployTokensSuite` covers only the token's own encoding.*
- [X] T020 [P] [US1] In `CLIT/OutputSuite.scala`: the rollback's table output is the line `rolled back to generation 1` followed by exactly what `Output.service` prints for the status, and its JSON output is the `RolledBack` document.
- [X] T021 [US1] In `CPT/CliEndToEndSuite.scala`: `services apply` twice with two images, then `services rollback cart` exits 0 and prints `rolled back to generation 1`; `services rollback cart --to-generation 1` then exits non-zero and prints the control plane's `already has the descriptor` refusal verbatim.

### Implementation

- [X] T022 [US1] In `CP/domain/model.scala` per data-model "Resolving a target" and R8: the enum `RollbackRefusal` with its five cases, each giving its message and `ErrorCode`; `descriptorAt(n)`; `rollbackTarget(requested)`, comparing `digest`s computed from the kept descriptors and the current one, never a stored digest. T014 passes.
- [X] T023 [US1] In `CP/application/ServiceEntity.scala`: the command `rollback` (wire name `rollback`, input `RollbackService`) that refuses a service that does not exist, any refusal of `rollbackTarget(Some(n))` and a descriptor with problems (`invalid descriptor at generation N: …`), and otherwise persists the `ServiceApplied` of contracts/control-plane.md "The entity"; the query `rollback-target` (input `RollbackRequest`, reply `KeptDescriptor`); their serializers in the companion. In `Service.fold`, remember kind `rolled-back` with `rolledBackTo` when the event has it. T015 passes. *The decision is `Service.rollingBack`, a pure function the entity calls, so a test can fold a state the testkit cannot reach (a descriptor today's rules refuse).*
- [X] T024 [US1] In `CP/api/ServiceEndpoint.scala`: `postBody("/{projectId}/{name}/rollback")` in the order of contracts/control-plane.md "Order of work in the handler": authorize for write; `rollback-target`; the descriptor's problems as `BadRequest`; `usage.reserveService` for the target's `minInstances`; `rollback` with the resolved generation and the caller's metadata; on `NonFatal` after the reservation, `usage.undo` exactly as `putBody` does; reply `RolledBack(target.generation, withHostname(status))`. T016 to T019 pass.
- [X] T025 [US1] In `CLI/ControlPlaneClient.scala`, `rollbackService(projectId, name, generation: Option[Long]): RolledBack` posting a `RollbackRequest`; in `CLI/Output.scala`, `rolledBack`; in `CLI/Main.scala`, the subcommand `rollback` with `--to-generation`, help text as contracts/cli.md, added to the `orElse` chain. T020 and T021 pass.
- [X] T026 [US1] In `CPT/EndToEndClusterSuite.scala`, a case after "5. changing the image rolls the workload" named "a service rolled back runs the image of the generation it was rolled back to": `services rollback`, wait until the Deployment's container image read from the cluster is `FirstImage` and the listing says `Ready`; then `services rollback` again and wait for `SecondImage`, which is the scenario "rolling back twice" on a cluster and leaves the service as the cases after it expect. Run `caffeinate -i sbt 'controlPlane/testOnly *EndToEndClusterSuite'`. *Passed with the whole suite (17 of 17). The first run found the suite's token expiring: the realm's tokens last 300 s and the suite minted one once, so every case after the fifth minute failed with 401 once this case lengthened the run. `Token` is now minted again at four minutes.*
- [X] T027 [US1] In `CPT/ControlPlaneHttpSuite.scala`, with the fake cluster, "a roll back to a descriptor whose runtime version the platform no longer runs is recorded and not deployed": apply a descriptor declaring a `runtime` outside `Compatibility`'s range (an apply records it; the check runs when the service is projected, which refuses it), apply a supported one, roll back to the first. Assert the generation moved, the status's detail names both versions, and the fake cluster still holds the resource the supported apply wrote, unchanged. *Done in `CPT/ProjectorSuite.scala`, after its case 12, since `ControlPlaneHttpSuite` runs no projector and "not deployed" is the projector's answer.*

**Checkpoint**: a member rolls back from the CLI, with or without a generation; every refusal has
its words; a cluster shows the image change. US2 and US3 are not needed for any of it.

---

## Phase 4: User Story 2 — a member sees what each generation ran (Priority: P1)

**Goal**: the history shows the image and a digest for each generation that recorded a
descriptor, and a past descriptor can be read back.

**Independent Test**: `sbt 'controlPlane/testOnly *ServiceEntitySuite *ControlPlaneHttpSuite' 'cli/testOnly *OutputSuite'`:
three applies differing in image and environment show three images and the right digests, and
generation one's descriptor reads back equal to what was applied.

### Tests first

- [X] T028 [P] [US2] History cases in `CPT/ServiceEntitySuite.scala`: "the history shows the image and a digest at every generation that was applied"; "a roll back shows the image and the digest of the generation it was rolled back to" (its digest equals the target entry's); "what applied no descriptor shows no image and no digest in the history", one assertion each for paused, resumed, restarted and exposed; an apply event from the pre-008 sample, folded, has an image and a digest (R6: replayed events gain them).
- [X] T029 [US2] Route cases in `CPT/ControlPlaneHttpSuite.scala` per contracts/control-plane.md: `GET /services/shop/cart/history` carries `image`, `digest` and, on a rollback, `rolledBackTo`; `GET /services/shop/cart/descriptor?generation=1` returns a document that `PUT /services/shop/cart` accepts unchanged ("a member reads the descriptor that was applied at a generation"); no `generation` is 400; "a person who is not a member cannot read the history of a service", for both routes; a deleted service still answers the descriptor route.
- [X] T030 [P] [US2] In `CLIT/OutputSuite.scala`, the history table of contracts/cli.md: the header `WHEN KIND GEN IMAGE DIGEST BY`; a digest cut to twelve characters; `-` in both columns for a restart; `rolled-back to 1` as a rollback's kind; JSON output unchanged from what the client returned.
- [X] T031 [US2] In `CPT/CliEndToEndSuite.scala`: `services history cart --generation 1` prints JSON that, written to a file and given to `services apply -f`, is accepted; `services history cart` shows both images.

### Implementation

- [X] T032 [US2] In `Service.fold` and `remember` (`CP/domain/model.scala`): an entry for a `ServiceApplied` carries `image = Some(descriptor.service.image)` and `digest = Some(descriptor.digest)`; every other entry carries neither. T028 passes.
- [X] T033 [US2] In `CP/application/ServiceEntity.scala`, the query `descriptor-at` (input a `Long`, reply `ServiceDescriptor`) over `Service.descriptorAt`, answering for a deleted service as `history` does; in `CP/api/ServiceEndpoint.scala`, `get("/{projectId}/{name}/descriptor")` reading `query.required[Long]("generation")` on the handler's own thread, authorized with `write = false`. T029 passes.
- [X] T034 [US2] In `CLI/ControlPlaneClient.scala`, `serviceDescriptor(projectId, name, generation)`; in `CLI/Output.scala`, the new `history` table and a `descriptor` printer writing indented JSON in both formats; in `CLI/Main.scala`, `--generation` on `history`. T030 and T031 pass.
- [X] T035 [P] [US2] In `CLI/mcp/AnkkaTools.scala`, the `service_history` tool's description: name rollbacks among what the history holds, and say an entry that recorded a descriptor carries its image and a digest. No tool is added. If `CLIT/McpServerSuite.scala` or a docs page pins the description, update it in the same change.

**Checkpoint**: the history is enough to choose a target, in the CLI. US1 is not needed for it.

---

## Phase 5: User Story 3 — a rollback beyond the kept descriptors is refused clearly (Priority: P2)

**Goal**: the bound of fifty is honest: a generation no longer kept, one that never existed and
one that recorded no descriptor each get their own answer.

**Independent Test**: `sbt 'controlPlane/testOnly *ServiceEntitySuite *ControlPlaneHttpSuite'`:
apply 60 times, ask for generation 3, and the refusal names generation 11.

- [X] T036 [US3] Cases in `CPT/ServiceEntitySuite.scala` against `descriptorAt` and `rollback`, named for the scenarios (the fourth is listed under User Story 1 in the spec and is held here, with the refusals of its kind): "a roll back to a generation whose descriptor is no longer kept is refused" (60 applies, generation 3, the message names 11, nothing persisted); "a roll back to the oldest generation whose descriptor is kept is made" (generation 11, the service is then at 61); "a roll back to a generation the service never had is told there is no such generation" (generation 0 and one above the current); "a roll back to a generation that recorded no descriptor is refused", naming the generation it ran; a service whose only kept descriptor is the seeded one answers `NotKept` naming that generation. If T022 was written to data-model's table these pass at once; break `descriptorAt`'s oldest-generation comparison once to see the first go red, and put it back.
- [X] T037 [US3] Cases in `CPT/ControlPlaneHttpSuite.scala` for both routes: 409 with `no longer kept` and the oldest generation, 404 with `has no generation`, 409 with `was a restart and ran the descriptor of generation`, named for "the descriptor of a generation that is no longer kept cannot be read", "the descriptor of a generation the service never had cannot be read" and "the descriptor of a generation that recorded none cannot be read". Use a helper that applies N descriptors differing in one variable.

**Checkpoint**: SC-003's four distinct refusals are each held by a test at the entity and at the API.

---

## Phase 6: The console — rolling back from the history (the third clarification)

**Purpose**: FR-011 and FR-013. The client and the fake can be done as soon as Phase 2 is (the schemas are T009, in Phase 2);
the page is written against whichever of this feature and feature 035 is on `main` (R11).

- [X] T038 [P] [US1] In `CON/src/client/control-plane.ts`, `rollback(projectId, name, generation)` and `descriptor(projectId, name, generation)`, with cases in `CON/test/client.test.ts` for the request each sends and for a 409 surfacing as the client's error with the control plane's message.
- [X] T039 [US1] In `CON/src/testing/fake-control-plane.ts`: keep each applied descriptor by generation; write `image`, `digest` (any function equal for equal descriptors) and `rolledBackTo` into its history; serve `POST …/rollback` and `GET …/descriptor` with the statuses and messages of contracts/control-plane.md, the default target and the same-descriptor refusal included.
- [X] T040 [US1] Playwright cases in `console/e2e/tests/services.spec.ts`, each listed in `CON/src/testing/scenarios.ts`: "a member rolls a service back from the console", which also asserts that before the submit the page names the generation and its image (FR-013's confirmation); "the console offers a roll back only to a generation that can be rolled back to" (no control on a restart's row or on the row of the current descriptor); "the console shows a refused roll back as the control plane refused it"; "the console shows the image of each generation that was applied". Wait for the page the submission lands on before reading the history. They fail until T041. *RB-3 sets up its refusal with a stale page (another tab applies generation 1's descriptor first), since the console cannot produce a descriptor the platform refuses; the scenario's Given in `rollback.feature` says so.*
- [X] T041 [US1] In `CON/src/routes/service.tsx` (after rebasing on `main` if feature 035 has merged): the Image and Digest columns; `Rolled back to generation N` as a rollback's label; the `<details>` control of contracts/console.md on each row whose entry has a digest that is not the newest digest; `intent=rollback` with `generation` handled in the page's `action` by `client.rollback`, refusals shown by the page's existing `Refused`. In `CON/src/extensions/types.ts`, `service.rollback` among the operations, with `HostActions` rendered for it. `just test-console` passes. *Feature 035 landed while this was in progress and split a service into section pages, so the control, its action and the Image and Digest columns are on the history page, `CON/src/routes/service-history.tsx`, not `service.tsx`; a rollback lands back on that page. Also: each row's digest links to the apply page with `?generation=N`, prefilled with that descriptor — the console's parity check requires every route, the descriptor route included, to be exercised through it.*
- [X] T042 [US1] `just test-console-compose` with `sbt controlPlane/run` on this branch: the four cases pass against the real control plane. Any difference found is the fake's to fix in T039's code, not the test's to loosen. *Run against this branch's control plane and compose's Keycloak: all four rollback cases pass with scripts on and off; nothing in the fake had to change.*

**Checkpoint**: a member rolls back from the console; a host can hide the operation.

---

## Phase 7: Documentation, and the whole build

- [X] T043 `just docs-reference` to rewrite `DOCS/reference/cli.md` and the route table of `DOCS/reference/control-plane-api.md`; then write the hand-written sections `### POST /services/{projectId}/{name}/rollback` and `### GET /services/{projectId}/{name}/descriptor` there from contracts/control-plane.md, and extend the history route's section with the three fields. `sbt 'cli/testOnly *CliReferenceSuite' 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite'` pass.
- [X] T044 [P] `DOCS/operate/service-lifecycle.md`: a section on rolling back — what it applies, that it is a new generation, what it leaves (paused, exposed, the database's contents), the default target, and each refusal in the reader's terms. The page stands alone: no feature number, no "see above".
- [X] T045 [P] `DOCS/operate/status-and-history.md`: the new table, reading a past descriptor and applying it, what a digest tells apart, that an entry recorded before an upgrade may show neither, and the sentence R14 requires: a past descriptor shows its variables' literal values to every member of the project's organization and to every platform administrator, so a value that should not be read back belongs in a project secret.
- [X] T046 [P] `DOCS/reference/glossary.md` (generation: every apply, restart and rollback; entries for history, roll back and digest in `GLOSSARY.md`'s words), `DOCS/operate/console.md` (the control), and `DOCS/reference/console-package.md` (`service.rollback` in the operations list; if a node test owns that list, run it).
- [X] T047 `just docs-sync && just docs`: the skills are rendered again into `marketplace/` and `ankka.g8/`, and `docs check` passes (no positional reference, no internal history, every `service.json` block valid).
- [X] T048 `just features` reports nothing for `specs/033-service-rollback` or `features/control-plane/`; every scenario name in the two feature files is the name of a `test(...)` or a Playwright test (`grep -c` each of the 40 names across `CPT`, `CLIT`, `APIT` and `console/`), and no scenario is without one.
- [X] T049 `sbt scalafmtAll scalafmtSbt`, then `caffeinate -i sbt buildAll`, then the by-hand run of quickstart.md step 7 on the kind cluster. Add one line to the release notes draft: a past descriptor is readable by every member of the project's organization and every platform administrator, literal variable values included. *Formatting, compile and every suite the feature touches were run on the branch rebased onto `main`; the k3s suites passed in CI (the `cluster` workflow); the full `buildAll` and the by-hand kind run are left to the pull request's CI and to review.*

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** first, and T001 before anything touches `CP/domain/model.scala`.
- **Phase 2** blocks every story. Within it: T003–T006 before T007–T012; T009 straight after T008; T010 before T011; T011 and T012 together.
- **Phase 3 (US1)**, **Phase 4 (US2)** and **Phase 5 (US3)** each depend on Phase 2 only. US3's cases exercise `descriptorAt`, written in T022, so US3 follows US1 in practice; it needs nothing of US2.
- **Phase 6**: T038 and T039 need Phase 2 and the contracts only; T040–T042 need T024 and T033, and `main` with or without feature 035.
- **Phase 7** last.

### Within a story

Verify first, then the cases, then the code that turns them green: the model before the entity,
the entity before the endpoint, the endpoint before the CLI, the offline suites before k3s.

### Parallel opportunities

- T003 and T004 with each other and with T005/T006 (different modules).
- T017, T018, T019 and T020 with each other (four suites).
- T028 and T030 with each other; Phase 4 as a whole beside Phase 3 once T023 has landed, since
  both edit `ServiceEntity.scala`, `ServiceEndpoint.scala`, `Main.scala` and `Output.scala`.
- T038 beside any Scala phase.
- T044, T045 and T046 with each other.

### Parallel example: after Phase 2

```text
one:   T013 → T014 → T015 → T022 → T023 → T016 → T024 → T025 → T026
two:   T038 → T039                  (console client, fake)
three: T017, T018, T019, T020       (cases waiting on T024 and T025)
```

## Implementation Strategy

**MVP**: Phases 1, 2 and 3 through T025. A member can roll back from the CLI and every refusal is
in place; the history already says `rolled-back` and to which generation. Stop there and it is
useful, though the target is chosen by generation number alone.

**Then**: Phase 4, which is what makes the target choosable; Phase 5, which is mostly cases over
code Phase 3 wrote; the k3s case (T026) whenever a long run is convenient; Phase 6's page
once feature 035's fate is known; Phase 7.

## Notes

- A case is named with its scenario's sentence. T048 checks it, so a scenario cannot be left
  without a test and a test cannot outlive its scenario unnoticed.
- The two things most likely to be got subtly wrong are both in Phase 2: reading `kept` without
  the seeding accessor, and replying with `kept` anywhere but the two queries. T005 and T006 hold
  both.
- Do not make `rollback` take an optional generation in the entity. The endpoint resolves the
  default once, which is what makes a double submission a refusal and not a rollback and its
  undoing (R4).
