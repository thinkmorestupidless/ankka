# Implementation Plan: Kubernetes Service Reconciliation

**Branch**: `001-k8s-reconciliation` | **Date**: 2026-09-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-k8s-reconciliation/spec.md`

**Revision note**: This plan was rewritten after the architecture decision to split reconciliation
across a control plane projector and an in-cluster operator, with a custom resource between them.
The superseded design had the control plane writing Deployments directly. See
[research.md](./research.md) R0 for why it changed.

## Summary

Two components and one contract.

The **control plane** gains a narrow new job: project each service's desired state into a
`NakkaService` custom resource, and fold that resource's status back into the journal as the
observation `ServiceEntity` already knows how to accept. It writes one kind of object and reads one
kind of object. It never sees a Deployment.

The **operator** runs in the target cluster, watches `NakkaService` resources, and owns everything
below — namespace, Deployment, and the reported status. It is a small binary with a deliberately
tiny dependency surface: the custom resource model, a Kubernetes client, and nothing else.

Both halves are built the same way the rest of nakka is: rendering and classification are **total
functions over data**, and a thin interpreter performs the I/O. The operator's `Action` values are
inert descriptions of cluster mutations, and `Fabric8Executor` is the only thing that executes
them — the same shape as `EventSourcedEffect` and its materialiser.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21 (`--release 21`), virtual threads

**Primary Dependencies**: `io.fabric8:kubernetes-client:7.9.0` (already declared in `controlPlane`,
currently unused); Jackson, which fabric8 requires for custom resource serialisation;
`org.testcontainers:k3s:1.21.4` (already declared, currently unused). The operator takes **no Pekko
dependency** — see research R2.

**Storage**: No new tables and no schema change. Desired state stays in the existing journal;
observations remain `ServiceObserved` events. Cluster-side desired state lives in the custom
resource, which is the API server's problem, not Postgres'.

**Testing**: munit, four tiers — pure offline suites for rendering, projection and classification;
a control plane integration suite against Postgres with a fake resource client; an operator suite
against k3s; and one end-to-end suite running both halves against k3s.

**Target Platform**: Linux/macOS development cluster; k3s single node under testcontainers in CI

**Project Type**: JVM backend service (control plane) + JVM Kubernetes operator + existing CLI

**Performance Goals**: cluster-originated change visible within 15s (SC-002); out-of-band deletion
healed within 10s (SC-004) — achievable because the operator watches rather than polls; ready
within 60s of apply (SC-001)

**Constraints**: `sbt test` stays deterministic and offline apart from containers it starts itself;
tests stay serialized; compile stays warning-free under `-Wunused`; the CLI must not acquire a
Kubernetes client

**Scale/Scope**: Hundreds of services across tens of projects, one cluster, one operator

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is **an unmodified template** — every principle is still a
`[PRINCIPLE_N_NAME]` placeholder and there is no ratification date, so there is no ratified
constitution to check against. The project's real invariants are written down in `CLAUDE.md` and
`README.md`, and this plan is gated on those.

| # | Invariant (source) | Pre-design | Post-design |
|---|---|---|---|
| 1 | Effects are inert data; handlers do no I/O and folds are replayable (`CLAUDE.md`) | PASS | PASS — the operator's `Action` and the control plane's projection are inert values; `Fabric8Executor` is the sole interpreter. `ServiceEntity` gains one field and no I/O. |
| 2 | Module direction; the CLI carries no actor system, no DB driver, no Kubernetes client | PASS | PASS — `cli` still depends on `controlplane-api` only, and `controlplane-api` gains no dependency. The new `crd` and `operator` modules hang off nothing the CLI can see. |
| 3 | Anything needing the service to exist first plugs in as a `RuntimeExtension` | PASS | PASS — `ServiceProjector extends RuntimeExtension`, alongside `ProjectionRuntime` and `HttpServer`. |
| 4 | Registration is explicit; no classpath scanning | PASS | PASS — the trigger consumer joins `ControlPlane.components`; the projector joins `ControlPlane.builder`. |
| 5 | Wire names declared separately from Scala method names | PASS | PASS — no new wire names. The projector calls the existing `observe` and `desired` handles. The custom resource's field names are declared explicitly for the same versioning reason. |
| 6 | Never touch `ActorContext` from a `Future` callback | PASS | PASS — informer callbacks run on fabric8's threads and touch no `ActorContext`; everything they need is captured at construction, following the `TimerSweeper`/`Sweep` precedent. |
| 7 | Cross-entity checks at the endpoint, never in a handler | PASS | PASS — the projector is not a handler. |
| 8 | Both test levels real; default run deterministic and offline | **AT RISK** | **JUSTIFIED** — see Complexity Tracking. Rendering, projection and classification are all offline-testable; only two suites need a cluster. |
| 9 | Test serialization must not be undone | PASS | PASS — the cluster suites are two more serialized suites. |
| 10 | Compile warning-free under `-Wunused` | PASS | PASS — enforced at implementation. |
| 11 | DDL has a single copy | PASS | PASS — no schema change. The CRD manifest is a new single-copy artifact under the operator's resources, following the same rule. |
| 12 | "The control plane is itself a nakka application" (`README.md`) | **AT RISK** | **JUSTIFIED** — see Complexity Tracking. The control plane stays a nakka application; the operator deliberately is not. |

**Gate result**: pass, with three justified deviations recorded in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/001-k8s-reconciliation/
├── plan.md
├── spec.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/requirements.md
├── contracts/
│   ├── custom-resource.md        # The NakkaService CRD — the whole contract (FR-001..FR-004)
│   ├── operator-actions.md       # Resource → objects, and the Action/Executor split
│   ├── observation-rules.md      # Cluster state → status (FR-020)
│   ├── control-plane-seam.md     # Projection, status ingest, the fake (FR-005..FR-011)
│   ├── wire-changes.md           # controlplane-api additions (FR-030, FR-031)
│   └── configuration.md          # Config keys, RBAC, install manifests (FR-035, FR-036, FR-039)
└── tasks.md                      # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

Two new sbt modules. The dependency graph gains one leaf and one branch, and the CLI's is untouched.

```text
core → sdk → runtime → {http, agent} → testkit → samples
core → controlplane-api → cli
crd → operator                                        ← NEW, no nakka dependencies
controlplane-api + sdk + runtime + http + crd → controlplane
                                  (cli, operator, testkit are Test-only deps of controlplane)
```

```text
crd/                                          # NEW MODULE: nakka-crd
└── src/main/scala/nakka/crd/
    ├── NakkaService.scala                    # the custom resource: spec, status, metadata
    └── NakkaServiceDefinition.scala          # group, version, kind, plural, printer columns

operator/                                     # NEW MODULE: nakka-operator
├── src/main/scala/nakka/operator/
│   ├── Main.scala                            # one-line wrapper over Operator.run, per CLI precedent
│   ├── Operator.scala                        # informer wiring + work queue on virtual threads
│   ├── Rendering.scala                       # PURE: NakkaServiceSpec → Vector[Action]
│   ├── LifecycleRules.scala                  # PURE: observed → NakkaServiceStatus
│   ├── Names.scala                           # namespace and object naming, DNS-label rules
│   ├── Labels.scala                          # identity labels + owner references
│   ├── Action.scala                          # inert cluster mutations
│   ├── Fabric8Executor.scala                 # the only thing that performs I/O
│   └── Settings.scala                        # config, with -D override per CLI precedent
├── src/main/resources/nakka/crd/
│   └── nakkaservice.yaml                     # the CRD manifest — single copy (FR-039)
├── src/main/resources/nakka/install/
│   └── operator.yaml                         # ServiceAccount, ClusterRole, Deployment
└── src/test/scala/nakka/operator/
    ├── RenderingSuite.scala                  # pure, offline
    ├── LifecycleRulesSuite.scala             # pure, offline
    └── OperatorClusterSuite.scala            # k3s

controlplane/src/main/scala/nakka/controlplane/
├── ControlPlane.scala                        # MODIFIED: register trigger consumer + ServiceProjector
├── domain/{model,events}.scala               # MODIFIED: `confirmed` on observation/state
├── application/
│   ├── ServiceEntity.scala                   # MODIFIED: observe() carries `confirmed`
│   ├── ServiceRows.scala                     # MODIFIED: row folds `confirmed`
│   └── ProjectionTrigger.scala               # NEW: Consumer over ServiceEntity events
└── deploy/
    ├── ServiceProjection.scala               # PURE: Service → NakkaServiceSpec
    ├── StatusIngest.scala                    # PURE: NakkaServiceStatus → ServiceObservation
    ├── NakkaServiceClient.scala              # the seam: put, delete, list, watch
    ├── Fabric8NakkaServiceClient.scala       # the implementation
    └── ServiceProjector.scala                # RuntimeExtension + cluster singleton

controlplane/src/test/scala/nakka/controlplane/
├── ServiceProjectionSuite.scala              # pure, offline
├── StatusIngestSuite.scala                   # pure, offline
├── FakeNakkaServiceClient.scala              # in-memory seam
├── ProjectorSuite.scala                      # NakkaTestKit + fake (Postgres, no cluster)
└── EndToEndClusterSuite.scala                # k3s + control plane + operator

controlplane-api/…/descriptors.scala          # MODIFIED: ServiceStatus + `confirmed`; project id validation
cli/…/Output.scala                            # MODIFIED: render an unconfirmed status
build.sbt, project/Dependencies.scala         # MODIFIED: two modules, jackson, move fabric8
```

**Structure Decision**: `crd` is its own module and depends on nothing from nakka. That is the
point — it is a wire contract, and the same reasoning that keeps `controlplane-api` free of Pekko
so the CLI stays thin keeps `crd` free of everything so both a control plane and an operator can
hold it without inheriting the other's world. `operator` depends only on `crd` and a Kubernetes
client, which makes "the operator cannot reach into the control plane" a build-level fact rather
than a convention.

`controlplane` takes `operator % Test` for the same reason it already takes `cli % Test`: the
repository's own note says that suite "is the only test that can catch the two ends disagreeing
about the wire format". A custom resource is a wire format with two ends, so it gets the same
treatment.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **Two new modules and a second deployable** where the superseded plan had none | The custom resource is a versioned contract between two independently deployable processes, and FR-003 requires either to be restarted and upgraded without the other. That is only enforceable if the contract is a module neither side can reach around. | A single process writing Deployments directly was the original plan and was rejected on review: it reimplements cascade deletion, change notification and desired/observed staleness, all of which Kubernetes already provides — and it puts cluster credentials in the control plane. The operator also gives the two deferred features (multi-replica clustering, database provisioning) a home that the single-process design lacked. |
| **A real cluster in `sbt test`**, against the project's "everything else is deterministic and offline" stance | FR-038, chosen explicitly by the operator during `/speckit-specify`. Rendering is the one part a fake models wrongly and then agrees with itself about: API server validation, selector immutability, and owner-reference cascade are exactly what must be proven against the real thing. | Fake-only leaves "the deployment is not happening" true. Manual verification catches no regression. Contained: two suites need a cluster, both tagged so `--exclude-tags=cluster` stays fast, and Docker is already required by the Postgres and Kafka suites. |
| **The operator is not a nakka application**, unlike the control plane, which `README.md` makes a point of | An operator is a watch loop over foreign resources. It has no entities, no journal, no sharding and no views, so hosting it on nakka would add an actor system, a Postgres dependency and cluster formation to a process that needs none of them — and would make the operator unable to start when the database it does not use is down. | Building it as a nakka application was considered for dogfooding symmetry and rejected on those grounds. The dogfooding argument is preserved where it is true: the control plane, which does have tenancy state, remains a nakka application. |
| **One new field (`confirmed`) on three types** rather than reusing `detail` | FR-030 requires distinguishing a live `Ready` from an unconfirmed one *in the listing operators scan*. `cli/…/Output.scala:54` prints `NAME STATUS INSTANCES GEN IMAGE` and drops `detail`, so a detail-only signal is invisible exactly there. | Encoding it in `detail` was cheaper and was rejected on that evidence. An eighth `ServiceLifecycle` case was rejected because staleness is orthogonal — any lifecycle can be unconfirmed. |
