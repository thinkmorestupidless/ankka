---
description: "Task list for Kubernetes Service Reconciliation"
---

# Tasks: Kubernetes Service Reconciliation

**Input**: Design documents from `specs/001-k8s-reconciliation/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: Test tasks are **included and not optional**. The specification requires them directly —
FR-037 (offline-testable rendering and classification), FR-038 (verified against a real cluster in
the standard test run), SC-010 and SC-011 — and the repository's stated testing model is two
levels, both real.

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story the task serves (US1–US4)
- Paths are repository-relative

## Path Conventions

Two new sbt modules plus changes to three existing ones:

```
crd/src/main/scala/ankka/crd/                  NEW — the contract, no ankka dependencies
operator/src/main/scala/ankka/operator/        NEW — the in-cluster operator
controlplane/src/main/scala/ankka/controlplane/deploy/    NEW package
controlplane-api/src/main/scala/ankka/controlplane/api/   MODIFIED
cli/src/main/scala/ankka/cli/                  MODIFIED
```

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Build wiring for two new modules. Nothing compiles until this is done.

- [X] T001 Add `jackson` version and the `jacksonDatabind` / `jacksonScala` module ids to `project/Dependencies.scala`; fabric8 requires Jackson for custom resource serialisation and `jackson-module-scala` is needed for Scala case classes (see research R1)
- [X] T002 Define the `crd` and `operator` projects in `build.sbt`: `crd` depends on **no** ankka module, `operator` depends on `crd` only; add both to the root `aggregate`
- [X] T003 Wire `controlPlane` in `build.sbt` to depend on `crd`, and on `operator % Test` (same rationale the build already records for `cli % Test`: only a test running both ends catches a wire-contract disagreement)
- [X] T004 [P] Create `operator/src/main/resources/reference.conf` with the `ankka.operator` block from [contracts/configuration.md](./contracts/configuration.md)
- [X] T005 [P] Edit `controlplane/src/main/resources/reference.conf`: delete the `ankka.controlplane.namespace` key (it has no readers and contradicts per-project isolation) and add the `ankka.controlplane.kubernetes` block

**Checkpoint**: `sbt compile` succeeds with two empty modules; `sbt scalafmtCheckAll` passes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The custom resource contract, the shared type changes, and the scaffolding both sides
need. No business logic here — no projection, no rendering, no classification.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### The contract (`crd` module)

- [X] T006 Define `AnkkaServiceSpec`, `EnvEntry`, `AutoscalingSpec`, `AnkkaServiceStatus` and the `AnkkaService` custom resource class in `crd/src/main/scala/ankka/crd/AnkkaService.scala` per [contracts/custom-resource.md](./contracts/custom-resource.md); name the two generations distinctly (`spec.generation` is ankka's, `status.observedGeneration` echoes `metadata.generation`)
- [X] T007 Define group, version, kind, plural, short name and scope in `crd/src/main/scala/ankka/crd/AnkkaServiceDefinition.scala`, and register `DefaultScalaModule` into fabric8's `Serialization` mapper so Scala case classes round-trip
- [X] T008 [P] Write the CustomResourceDefinition manifest to `operator/src/main/resources/ankka/crd/ankkaservice.yaml` with the OpenAPI schema (`required` on `projectId`, `serviceName`, `generation`, `image`), the `status` subresource, and the printer columns from the contract — this is the single copy, and the cluster suites apply this exact file
- [X] T009 [P] Round-trip test for every resource field in `crd/src/test/scala/ankka/crd/AnkkaServiceCodecSuite.scala`, asserting unknown-field tolerance and that spec and status serialise independently

### Shared type changes

- [X] T010 [P] Add DNS-label validation for project ids to `controlplane-api/src/main/scala/ankka/controlplane/api/descriptors.scala`, bounded to `63 - (namespace-prefix + 1)` characters, beside the existing `ServiceDescriptor.ValidName`
- [X] T011 Add `confirmed: Boolean = true` to `ServiceStatus` in `controlplane-api/src/main/scala/ankka/controlplane/api/descriptors.scala` (same file as T010, so not parallel)
- [X] T012 Reject an invalid project id with `400` in `controlplane/src/main/scala/ankka/controlplane/api/ProjectEndpoint.scala`; note in the task that this is a behaviour change — the route previously accepted any path segment
- [X] T013 [P] Add `confirmed: Boolean = true` to `ServiceObservation` and `ServiceEvent.ServiceObserved` in `controlplane/src/main/scala/ankka/controlplane/domain/events.scala`, defaulted so journalled events decode
- [X] T014 Add `confirmed` to `Service`, fold it in `onObserved`, pass it through `toStatus`, and default it `true` in `Service.empty` in `controlplane/src/main/scala/ankka/controlplane/domain/model.scala`; keep the generation guard inside the fold
- [X] T015 Carry `confirmed` through `observe` in `controlplane/src/main/scala/ankka/controlplane/application/ServiceEntity.scala`, leaving the equality-based dedupe untouched so it covers the new field automatically
- [X] T016 [P] Fold `confirmed` in `controlplane/src/main/scala/ankka/controlplane/application/ServiceRows.scala`, resetting it to `true` on applied, restarted, paused and resumed
- [X] T017 [P] Render an unconfirmed status as `Ready (unconfirmed)` in the table and add a `confirmed` field to the single-service view in `cli/src/main/scala/ankka/cli/Output.scala`
- [X] T018 [P] Extend `controlplane/src/test/scala/ankka/controlplane/ServiceEntitySuite.scala`: `confirmed` folds, a repeated unconfirmed observation is refused, and a stale-generation observation is still dropped

### Operator scaffolding

- [X] T019 [P] Namespace and object naming with DNS-label bounds in `operator/src/main/scala/ankka/operator/Names.scala`
- [X] T020 [P] Identity labels and the owner reference (carrying the resource **uid**, not just its name) in `operator/src/main/scala/ankka/operator/Labels.scala`
- [X] T021 [P] The inert `Action` cases — `EnsureNamespace`, `ApplyDeployment`, `DeleteDeployment`, `SetStatus`, `NoAction` — in `operator/src/main/scala/ankka/operator/Action.scala`
- [X] T022 Interpret actions in `operator/src/main/scala/ankka/operator/Fabric8Executor.scala` using server-side apply with field manager `ankka-operator` and `forceConflicts`, writing status through the subresource only; this must be the only file in the module that touches cluster I/O
- [X] T023 [P] Operator configuration in `operator/src/main/scala/ankka/operator/Settings.scala`, overridable by a system property (the CLI's `Settings.path` precedent — an env var alone is untestable in-process)
- [X] T024 Informer wiring in `operator/src/main/scala/ankka/operator/Operator.scala`: watch `AnkkaService` and owned Deployments, feed a de-duplicating work queue keyed by resource, dispatch each reconcile on a virtual thread, with per-resource backoff and periodic resync
- [X] T025 One-line `main` wrapper over `Operator.run(args): Int` in `operator/src/main/scala/ankka/operator/Main.scala`, per the CLI precedent that `sys.exit` must not live inside the command logic
- [X] T026 [P] Install manifest at `operator/src/main/resources/ankka/install/operator.yaml` — ServiceAccount, ClusterRole with exactly the verbs in [contracts/configuration.md](./contracts/configuration.md) (**no verb on `secrets`**), ClusterRoleBinding and Deployment; applying twice must be a no-op

### Control plane seam

- [X] T027 [P] The `AnkkaServiceClient` trait and `AnkkaServiceResource` in `controlplane/src/main/scala/ankka/controlplane/deploy/AnkkaServiceClient.scala`, in terms of `crd` types only — no fabric8 in the signature
- [X] T028 Implement it in `controlplane/src/main/scala/ankka/controlplane/deploy/Fabric8AnkkaServiceClient.scala` with field manager `ankka-controlplane`, a reconnecting watch that reports its connection state, and no path that writes `status`
- [X] T029 [P] `controlplane/src/test/scala/ankka/controlplane/FakeAnkkaServiceClient.scala` satisfying obligations C1–C8 plus the controls `failNext`, `setStatus`, `clearStatus`, `driftEdit`, `driftDelete`, `disconnect`, `reconnect`
- [X] T030 [P] Narrow RBAC manifest for the control plane at `controlplane/src/main/resources/ankka/install/controlplane-rbac.yaml` — `ankkaservices` and `namespaces` only, no workload access

**Checkpoint**: The contract exists, both sides can reach the cluster, nothing decides anything yet.

---

## Phase 3: User Story 1 - An applied descriptor actually runs (Priority: P1) 🎯 MVP

**Goal**: `ankka services apply -f cart.json` results in a running workload that reports `Ready`,
with no further operator action.

**Independent Test**: Apply a descriptor against a cluster running the operator; poll
`ankka services get` until it reports `Ready 1/1`; confirm the Deployment carries the image,
environment and resource sizing from the descriptor.

### Tests for User Story 1

- [X] T031 [P] [US1] `operator/src/test/scala/ankka/operator/RenderingSuite.scala`: determinism (render twice, assert equal), the selector never contains the generation, `spec.replicas` is 1, **no HorizontalPodAutoscaler is emitted**, the owner reference carries the uid, and descriptor labels cannot override identity labels
- [X] T032 [P] [US1] `controlplane/src/test/scala/ankka/controlplane/ServiceProjectionSuite.scala`: a `Service` maps to a spec; an invalid project id is a reported problem rather than an exception; an unchanged service projects identically

### Implementation for User Story 1

- [X] T033 [US1] Pure `Service` → `AnkkaServiceSpec` projection in `controlplane/src/main/scala/ankka/controlplane/deploy/ServiceProjection.scala`, reporting every problem at once in the style of `ServiceDescriptor.problems`
- [X] T034 [US1] Pure status ingest in `controlplane/src/main/scala/ankka/controlplane/deploy/StatusIngest.scala` covering all three cases from [data-model.md](./data-model.md) §3, including the FR-031 "resource exists, nothing has reported" case; a below-current generation is passed through untouched for the fold to drop
- [X] T035 [P] [US1] `controlplane/src/test/scala/ankka/controlplane/StatusIngestSuite.scala` covering all three cases and the pass-through of a stale generation
- [X] T036 [US1] `controlplane/src/main/scala/ankka/controlplane/deploy/ServiceProjector.scala` as a `RuntimeExtension` starting a cluster singleton that projects on trigger and on sweep, consumes the status watch, and calls `ServiceEntity.observe` on virtual threads — capturing everything the async half needs while inside the actor, per the `TimerSweeper`/`Sweep` precedent
- [X] T037 [US1] `controlplane/src/main/scala/ankka/controlplane/application/ProjectionTrigger.scala`, a `Consumer` over `ServiceEntity` events that projects immediately on a desired-state change
- [X] T038 [US1] Register the trigger in `ControlPlane.components` and the projector in `ControlPlane.builder` in `controlplane/src/main/scala/ankka/controlplane/ControlPlane.scala`
- [X] T039 [US1] Pure `AnkkaServiceSpec` → `Vector[Action]` rendering in `operator/src/main/scala/ankka/operator/Rendering.scala`: namespace, Deployment with image, literal and secret-sourced env, labels, annotations, CPU and memory from the instance type, `progressDeadlineSeconds`, `replicas: 1`, the owner reference, and the generation annotation **on the pod template**
- [X] T040 [US1] Happy-path subset of the classification table in `operator/src/main/scala/ankka/operator/LifecycleRules.scala` — rules 3, 5, 6 and 7 of [contracts/observation-rules.md](./contracts/observation-rules.md); the remaining rules land in US2
- [X] T041 [US1] Wire reconcile in `operator/src/main/scala/ankka/operator/Operator.scala`: render → execute → derive status → `SetStatus`

### Integration and cluster tests for User Story 1

- [X] T042 [US1] `controlplane/src/test/scala/ankka/controlplane/ProjectorSuite.scala` against `AnkkaTestKit` and the fake: applying produces a resource; a steady-state service performs **zero** writes and records **zero** observations across many sweeps (SC-003); FR-031 reports unconfirmed when no status exists
- [X] T043 [US1] `operator/src/test/scala/ankka/operator/OperatorClusterSuite.scala` against k3s, tagged `cluster`: install the CRD **from the shipped manifest**, write a resource, assert the Deployment is created and accepted by the API server, then apply again at a new generation and assert it is accepted — the selector-immutability regression test
- [X] T044 [US1] `controlplane/src/test/scala/ankka/controlplane/EndToEndClusterSuite.scala` against Postgres and k3s, tagged `cluster`, driving the CLI's `Main.run`: apply → `Ready 1/1`; change the image tag → rolls → `Ready`

**Checkpoint**: A descriptor applied through the CLI runs in Kubernetes and reports ready. This is
the MVP and it is demonstrable on its own.

---

## Phase 4: User Story 2 - Reported status reflects reality (Priority: P2)

**Goal**: Lifecycle, counts and detail describe what the cluster is actually doing, and a failing
service says why rather than sitting in progress forever.

**Independent Test**: Drive cluster-side changes — kill the pod, point at a nonexistent image — and
assert the reported lifecycle, counts and detail follow within the stated latency, with no operator
command in between.

### Tests for User Story 2

- [X] T045 [P] [US2] `operator/src/test/scala/ankka/operator/LifecycleRulesSuite.scala`: one case per row of [contracts/observation-rules.md](./contracts/observation-rules.md), plus both ordering traps — pause beats progress-deadline failure, and `observedGeneration` beats `updatedReplicas` — plus the assertion that an unchanged status produces no write

### Implementation for User Story 2

- [X] T046 [US2] Complete the classification table in `operator/src/main/scala/ankka/operator/LifecycleRules.scala` — rules 1, 2, 4, 8, 9 and 10 — including `PartiallyReady`, which is unreachable at one replica but implemented now so multi-replica does not have to retrofit it
- [X] T047 [US2] Read pods **only** when the Deployment is not fully ready, and populate `podProblems`, in `operator/src/main/scala/ankka/operator/Operator.scala`
- [X] T048 [US2] Map container waiting reasons to `detail` in `operator/src/main/scala/ankka/operator/LifecycleRules.scala`: `ImagePullBackOff`, `ErrImagePull`, `CreateContainerConfigError` and `CrashLoopBackOff`
- [X] T049 [US2] Skip the status write when the computed status equals the recorded one, ignoring `lastTransitionTime`, in `operator/src/main/scala/ankka/operator/Operator.scala` (FR-022 — without this the resync rewrites every status every pass)
- [X] T050 [US2] Report an unsupported `apiVersion` in `status.detail` and take no action, in `operator/src/main/scala/ankka/operator/Operator.scala` (FR-004)
- [X] T051 [US2] Map all seven lifecycle names in `controlplane/src/main/scala/ankka/controlplane/deploy/StatusIngest.scala`

### Integration and cluster tests for User Story 2

- [X] T052 [P] [US2] Extend `controlplane/src/test/scala/ankka/controlplane/ProjectorSuite.scala`: SC-003 holds across a long run of sweeps with the fake reporting an unchanged status
- [X] T053 [US2] Extend `operator/src/test/scala/ankka/operator/OperatorClusterSuite.scala`: a nonexistent image tag reaches `Failed` with an `ImagePullBackOff` detail within a short test progress deadline
- [X] T054 [US2] Extend `controlplane/src/test/scala/ankka/controlplane/EndToEndClusterSuite.scala`: delete the pod, assert a not-ready lifecycle is reported, then assert the return to `Ready`
- [X] T055 [US2] Assert no secret value can reach `detail`: a unit case in `operator/src/test/scala/ankka/operator/LifecycleRulesSuite.scala` proving only a secret's name and key ever appear, and a case in `operator/src/test/scala/ankka/operator/OperatorClusterSuite.scala` proving the operator's ServiceAccount has no verb on `secrets`

**Checkpoint**: Every reported state is derived from the cluster by a tested rule, and no service
can sit silently in progress.

---

## Phase 5: User Story 3 - Lifecycle commands take effect (Priority: P2)

**Goal**: `pause`, `resume`, `restart` and `delete` change the cluster, not just the record.

**Independent Test**: Issue each command through the CLI and assert both the reported status and
the cluster-side outcome, including that a deleted service leaves nothing behind.

### Tests for User Story 3

- [X] T056 [P] [US3] Extend `operator/src/test/scala/ankka/operator/RenderingSuite.scala`: a paused spec renders `replicas: 0` and retains every other field

### Implementation for User Story 3

- [X] T057 [US3] Honour `spec.paused` in `operator/src/main/scala/ankka/operator/Rendering.scala` by rendering `replicas: 0` while leaving the rest of the Deployment intact
- [X] T058 [US3] Carry `Service.isPaused` into the spec in `controlplane/src/main/scala/ankka/controlplane/deploy/ServiceProjection.scala`
- [X] T059 [US3] Confirm restart needs **no** new mechanism: a bumped generation changes the pod-template annotation and triggers a rolling replacement. Add the assertion to `operator/src/test/scala/ankka/operator/RenderingSuite.scala` rather than adding code
- [X] T060 [US3] Delete the resource on `ServiceDeleted` in `controlplane/src/main/scala/ankka/controlplane/deploy/ServiceProjector.scala`, and keep retrying while the cluster is unreachable so the tombstone is not orphaned (FR-009)

### Integration and cluster tests for User Story 3

- [X] T061 [US3] Extend `controlplane/src/test/scala/ankka/controlplane/ProjectorSuite.scala`: pause, resume, restart and delete against the fake, including a delete issued while `failNext` is active that completes on recovery
- [X] T062 [US3] Extend `operator/src/test/scala/ankka/operator/OperatorClusterSuite.scala` with the owner-reference cascade test: delete the resource and assert the Deployment disappears **without the operator taking any action** — this proves the design's central simplification is real
- [X] T063 [US3] Extend `controlplane/src/test/scala/ankka/controlplane/EndToEndClusterSuite.scala`: pause → zero pods, resume → `Ready`, restart → pod replaced, delete → zero objects remain (SC-009)

**Checkpoint**: Every day-two operation takes effect in the cluster.

---

## Phase 6: User Story 4 - Drift and outages heal themselves (Priority: P3)

**Goal**: Out-of-band changes, unreachable clusters and component restarts all converge back to
desired state with no operator action and no duplicates.

**Independent Test**: Introduce each disruption — delete the Deployment, edit it, edit the custom
resource, stop the cluster, restart either component — and assert convergence with no duplicate or
orphaned objects.

### Tests for User Story 4

- [X] T064 [P] [US4] Assert a name collision with a foreign object is reported and never overwritten, in `operator/src/test/scala/ankka/operator/RenderingSuite.scala` and the executor's tests (FR-016, SC-008)

### Implementation for User Story 4

- [X] T065 [US4] Watch owned Deployments in `operator/src/main/scala/ankka/operator/Operator.scala` so an out-of-band deletion is noticed immediately — this is what makes SC-004's 10 seconds achievable rather than one polling cycle
- [X] T066 [US4] Enforce drift through server-side apply with `forceConflicts` in `operator/src/main/scala/ankka/operator/Fabric8Executor.scala`, reverting out-of-band edits to fields the operator owns (FR-017)
- [X] T067 [US4] Periodic resync as the backstop for anything no watch announced, in `operator/src/main/scala/ankka/operator/Operator.scala` (FR-018)
- [X] T068 [US4] Per-resource retry backoff in `operator/src/main/scala/ankka/operator/Operator.scala`, bounded and increasing, such that one permanently failing resource cannot delay any other (FR-024, FR-032)
- [X] T069 [US4] Restore the control plane's record when a custom resource is edited or deleted out of band, in the sweep in `controlplane/src/main/scala/ankka/controlplane/deploy/ServiceProjector.scala` (FR-007)
- [X] T070 [US4] Report `confirmed = false` with a reason when the cluster is unreachable or the watch is disconnected, in `controlplane/src/main/scala/ankka/controlplane/deploy/ServiceProjector.scala` (FR-030, obligation C8)
- [X] T071 [US4] Bounded increasing backoff on projection failure in `controlplane/src/main/scala/ankka/controlplane/deploy/ServiceProjector.scala`, discarding no desired state (FR-032)

### Integration and cluster tests for User Story 4

- [X] T072 [US4] Extend `controlplane/src/test/scala/ankka/controlplane/ProjectorSuite.scala`: `failNext` backoff spacing, **exactly one** unconfirmed observation regardless of how many sweeps failed, recovery; `driftEdit` restored; `disconnect`/`reconnect`; and `restartService()` mid-projection leaving exactly one resource per service
- [X] T073 [US4] Extend `operator/src/test/scala/ankka/operator/OperatorClusterSuite.scala`: delete the Deployment out of band → recreated within 10s; edit its image out of band → reverted; restart the operator mid-rollout → no duplicate objects and the service reaches `Ready`
- [X] T074 [US4] Extend `controlplane/src/test/scala/ankka/controlplane/EndToEndClusterSuite.scala`: stop the operator → every affected service reports "no operator has reported on this service" within one cycle (SC-012); restart the control plane → converges with nothing duplicated

**Checkpoint**: All four stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: The documentation this feature invalidates, and the checks that keep the design honest.

- [X] T075 [P] Rewrite the reconciliation entry under "Not implemented" in `README.md`: state what now works, and what still does not — multi-replica, platform-provisioned databases, multi-cluster — as plainly as the gap it replaces
- [X] T076 [P] Correct the `minInstances` row in the divergence table in `README.md`: it defaults to 1 because more than 1 does not currently work, not because of development-cluster ergonomics
- [X] T077 [P] Add the operator to the Layout section of `README.md`, document the two install manifests, and add a bring-your-own-database section carrying the **one database per service** rule and the reason — `ankka_timers` has no service column and `TimerSweeper` deletes rows it does not recognise
- [X] T078 [P] Correct the `controlPlane` project comment in `build.sbt`; "each deployment is a workflow" describes a design that was considered and rejected (research R2)
- [X] T079 [P] Update `CLAUDE.md`: the new module direction, the two seam `grep` checks, and the traps — selector immutability, the two generations, the shared-database timer collision, and the single-replica cap
- [X] T080 Tag both cluster suites so `--exclude-tags=cluster` excludes them, and document the tag alongside the existing commands in `CLAUDE.md`
- [X] T081 Run `sbt scalafmtAll scalafmtSbt` and confirm `sbt compile` is warning-free under `-Wunused`
- [X] T082 Confirm the seam holds: `grep -rn "io.fabric8" controlplane/src/main` hits only `Fabric8AnkkaServiceClient.scala`, and `grep -rn "io.fabric8" operator/src/main` hits only `Fabric8Executor.scala` and client construction
- [X] T083 Confirm the dependency rules hold: `cli` still depends on `controlplane-api` alone, and `crd` depends on no ankka module
- [X] T084 Work through the reviewer's checklist in [quickstart.md](./quickstart.md), including the Tier 5 manual walkthrough

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — **blocks every user story**
- **User Stories (Phases 3–6)**: all depend on Foundational; US1 should be first because it is the MVP
- **Polish (Phase 7)**: depends on the stories being delivered

### User Story Dependencies

- **US1 (P1)**: after Foundational. No dependency on another story.
- **US2 (P2)**: after Foundational. Technically independent, but it *completes* files US1 creates (`LifecycleRules.scala`, `StatusIngest.scala`), so running it concurrently with US1 means two people in one file. Sequence it after US1 unless staffing forces otherwise.
- **US3 (P2)**: after Foundational. Genuinely independent of US2 — different files apart from `Rendering.scala`.
- **US4 (P3)**: after Foundational. Touches `Operator.scala` and `ServiceProjector.scala`, both of which US1 creates.

**Honest summary**: these stories are independently *testable* and independently *demonstrable*,
but they are not cleanly independently *implementable* — they layer onto a shared set of files
rather than partitioning them. That is inherent to a reconciler, where one loop serves every
behaviour. Plan for sequential delivery in priority order and treat the checkpoints as the value
milestones.

### Within Each Story

- Pure unit tests alongside the pure functions they cover — these are cheap and catch the ordering traps
- Pure logic before the wiring that calls it
- Wiring before integration tests
- Integration tests (fake, Postgres) before cluster tests (k3s)

### Parallel Opportunities

- T004 and T005 in Setup
- In Foundational: T008/T009 (contract artifacts), T010/T013/T016/T017/T018 (type changes across different files), T019/T020/T021/T023/T026 (operator scaffolding), T027/T029/T030 (seam)
- In each story, the `[P]` pure test tasks run alongside each other
- **Across stories**: US3 can run alongside US2 with light coordination on `Rendering.scala`

---

## Parallel Example: Phase 2 Foundational

```bash
# Contract artifacts — independent files:
Task: "T008 CRD manifest in operator/src/main/resources/ankka/crd/ankkaservice.yaml"
Task: "T009 Round-trip test in crd/src/test/scala/ankka/crd/AnkkaServiceCodecSuite.scala"

# Type changes — five different files:
Task: "T013 confirmed on events in controlplane/.../domain/events.scala"
Task: "T016 confirmed in controlplane/.../application/ServiceRows.scala"
Task: "T017 unconfirmed rendering in cli/src/main/scala/ankka/cli/Output.scala"
Task: "T018 entity tests in controlplane/src/test/.../ServiceEntitySuite.scala"

# Operator scaffolding — five different files:
Task: "T019 Names.scala"
Task: "T020 Labels.scala"
Task: "T021 Action.scala"
Task: "T023 Settings.scala"
Task: "T026 install manifest operator.yaml"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup — two modules exist and compile
2. Phase 2 Foundational — the contract and both seams
3. Phase 3 US1 — projection, rendering, the happy-path status
4. **STOP and VALIDATE**: `sbt 'controlPlane/testOnly *EndToEndClusterSuite'`. An applied descriptor
   runs in Kubernetes and reports `Ready`. `README.md`'s "the deployment is not happening" stops
   being true at this point, which is the single largest change in the project's honesty.

### Incremental Delivery

1. Setup + Foundational → the contract exists and both ends can reach the cluster
2. + US1 → **MVP**: apply produces a running service
3. + US2 → the reported state can be trusted, and failures say why
4. + US3 → pause, resume, restart and delete take effect
5. + US4 → the system heals itself and is honest when it cannot see

### Sequencing Risks

- **Phase 2 is large** (25 tasks) and nothing is demonstrable until Phase 3 lands. That is the price
  of a contract-first design. It is heavily parallelisable, which is the mitigation.
- **The k3s suites arrive late in each story.** Get T043 working early in US1 even if thin —
  discovering that the CRD manifest or RBAC is wrong during US4 is expensive.
- **T049 (skip unchanged status writes) is easy to defer and painful to skip.** Without it the
  resync writes every status on every pass and SC-003 fails quietly, as continuous API-server load
  rather than a broken test.

### Deferred by design

Two follow-on features are named in the spec's Out of Scope and are **not** in this task list:
multi-replica support with Pekko cluster formation, and platform-provisioned databases. The
operator built here is the intended home for both, and `cloudflow`'s `AkkaRunner.scala` and
`TopicActions.scala` are worked references for them respectively.

---

## Notes

- `[P]` means different files with no incomplete dependencies
- Every task names its file path; no task should require reading another to know where the code goes
- Commit after each task or logical group
- Stop at any checkpoint to validate the story independently
- The two cluster suites need Docker and a privileged container; everything else is offline

---

## Implementation Notes

What the plan got wrong, discovered while building it. Recorded because each was found by a test
rather than by review.

- **The control plane must create project namespaces, not the operator** (changed T027–T030, and
  `controlplane-rbac.yaml`). An `AnkkaService` is namespaced, and owner references only work within
  a namespace — so the resource has to live beside the workload it owns. That makes the namespace a
  precondition of writing the resource, and the operator cannot create it first because it only
  learns a project exists by *seeing* the resource. Found by `EndToEndClusterSuite`, which could
  not create a single resource. The control plane's RBAC gained `namespaces: create`; it still has
  no workload access and no verb on secrets.

- **`Service.isPaused` was derived from `lifecycle`, which is observed state** (extra work in
  T014). A status report already in flight when a pause happened set `lifecycle = Ready`, which
  erased the pause — and pause does not bump the generation, so the staleness guard could not catch
  it. `paused` is now a field of its own: desired state and observed state no longer share one.
  Found by `EndToEndClusterSuite` test 6; two unit cases in `ServiceEntitySuite` now pin it.

- **The custom resource carries resolved CPU and memory, not an instance-type name** (changed
  T006). `InstanceType` lives in `controlplane-api`, which the operator cannot depend on — and
  should not need to. The control plane resolves the named size; `instanceType` is carried as
  informational only, so `kubectl describe` still reads in an operator's vocabulary. Adding a size
  later is now a control plane change alone.

- **`ServiceEntity.desired` now replies with the whole `Service`** (changed T036), renamed to
  `desiredState` in Scala while keeping the wire name `desired`. Projecting needs the generation and
  the pause flag as well as the descriptor, and two round trips per service per sweep bought
  nothing. A worked example of why the wire name is declared separately from the method name.

- **`T046`, `T048`, `T049` and `T050` landed with `T040`.** Writing half an ordered `match` and
  completing it in a later phase would have been churn, not increments. The US2 *tests* stayed in
  US2 and are what actually pin the behaviour.

- **The cluster suites are gated by `-Dankka.cluster.tests=off`, not a munit tag** (changed T080).
  `--exclude-tags` filters tests but still runs `beforeAll`, which is where the container starts —
  so it would have skipped the assertions and paid the whole cost.

- **The seam check in the reviewer's checklist was wrong as written** (corrected in T082). Rendering
  legitimately builds fabric8 *model* objects — a POJO is not a call — so `io.fabric8` appears
  across the operator's pure layer. The checkable invariant is that `KubernetesClient` appears in
  none of those eight files, and it does not.

- **`jackson-module-scala` must match the databind fabric8 resolves** (T001). Pinned at 2.18.2
  first, which fabric8 7.9.0 rejected at runtime — it resolves 2.21.4. `AnkkaServiceCodecSuite`
  caught it on the first run, which is the intended way to find out.

Two fixture mistakes worth remembering, both only visible against a real API server: server-side
apply rejects an object carrying `metadata.managedFields`, so a test must never re-apply one read
back from the server; and `busybox` exits immediately, so a Deployment built from it crash-loops and
never reports Ready.

---

## Post-Implementation: Getting This Onto a Real Cluster

The tasks above delivered reconciliation logic verified against k3s via testcontainers, which
proved the operator and control plane worked — but nothing produced a container image or a way to
install either one, so none of it could actually run outside a test JVM. Closing that gap surfaced
three more real bugs, none of which any existing test caught.

- **`sbt-native-packager` was a declared plugin, never enabled.** Wired `JavaAppPackaging` +
  `DockerPlugin` onto `operator` and `controlPlane` only; `dockerRepository` is unset by default
  so images build unqualified (`ankka-operator:0.1.0-SNAPSHOT`) straight into the local Docker
  daemon — exactly what a registry-free local cluster needs.

- **Missing `patch` on `namespaces` in both RBAC ClusterRoles.** `ensureNamespace` writes via
  server-side apply, which is always a PATCH request even for an object that does not exist yet.
  Granting only `create` 403s the very first time — which is every time a project's namespace is
  new, the one case the rule exists for. **No test caught this**: the k3s suites hand fabric8 the
  testcontainer's own admin kubeconfig directly and never construct a client scoped to the shipped
  ClusterRole, so the RBAC manifests were never actually exercised end to end until a real deploy.
  This is a real coverage gap, recorded rather than fixed, since closing it properly means standing
  up a client bound to the ServiceAccount + RBAC inside the test itself.

- **A startup-order race between the control plane and Postgres.** `kubectl apply -k` creates all
  three Deployments together; nothing serializes their actual scheduling. The control plane's one
  r2dbc connection attempt at boot failed against a Postgres that had not opened its port yet, and
  the failure happened deep enough inside actor-system startup that the pod was left half-alive —
  `Running`, 0 restarts, never ready — rather than crash-looping into a fix. Fixed with a
  `wait-for-postgres` init container (`pg_isready` loop), which also protects the same path on any
  future reschedule, not just first boot. Worth carrying forward: the control plane does not retry
  its own initial connection, which is a real robustness gap independent of Kubernetes.

**A near-miss worth recording precisely because it almost wasn't:** attempting to symlink the
kustomize component directories to the existing `src/main/resources` files used `ln -sf` directly
on the destination path — which force-removes whatever is there before creating the symlink. Since
the `git mv` meant to relocate the content first had already failed silently (the files were never
committed, so `git mv` had nothing tracked to move), the original file content was deleted with
nothing yet in its place. Recovered only because the compiled `target/*/classes/` still held the
pre-deletion content from the last build. The fix — canonical file inside `kustomization/`, symlink
pointing into it from `src/main/resources` — was also the only direction that works at all, since
kustomize's load restrictor rejects any reference (including a symlink target) resolving outside a
component's own directory, checked per-component with no override for `kubectl apply -k`.

**Proven on a real cluster, not just asserted:** `kind create cluster --name ankka`, full cold
teardown and redeploy, then the actual CLI — `organizations create`, `projects create`,
`services apply` — against a port-forwarded control plane. `ankka services list` reported
`Ready 1/1` for a pod the operator created with no manual step in between, and `services delete`
removed the resource, the Deployment and the pod together, by owner reference. That loop is the
thing this whole feature was for.
