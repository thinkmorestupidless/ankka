# Implementation Plan: Deploy a Real ankka Service

**Branch**: `003-deploy-real-service` | **Date**: 2026-09-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-deploy-real-service/spec.md`

## Summary

Three pieces, and the smallest of them is the one that blocks everything.

**One value decides five things.** A descriptor gains `http` (default `true`) and `port` (default
9000), resolved once in `controlplane-api` into a single `Option[Int]`, carried through the custom
resource, and used
by the operator to render the container port, the injected `ANKKA_HTTP_PORT`, the readiness probe and
the Service's target. They cannot disagree because there is one value. A descriptor that also sets
`ANKKA_HTTP_PORT` by hand is refused.

**A Service, and a probe that makes `Ready` mean something.** A `ClusterIP` Service named after the
service, selecting the *same* identity labels the Deployment selects, owned by the `AnkkaService` so
deletion cascades. Plus a `tcpSocket` readiness probe — which upgrades `lifecycle: Ready` from "the
JVM launched" to "the port is open" **without touching `LifecycleRules` at all**, because
`readyReplicas` already counts only pods that pass their probe.

**The shopping cart becomes an image, and finally runs.** `JavaAppPackaging, DockerPlugin` on the
sample, and a new cluster suite that builds it, imports it into a throwaway k3s cluster, deploys it
through the real CLI against a platform-provisioned database, and adds an item to a cart over HTTP.
The sample's own code needs no change — which is the strongest evidence the platform's contract was
already right.

This plan was reviewed after its first draft, and **the review found four errors in it** — each by
running something rather than re-reading. They are listed under "Corrections" below because a plan
that quietly absorbed them would hide exactly the reasoning a reviewer needs.

Two findings from Phase 0 are worth reading before anything else, because both were **verified by
running them** and either would otherwise cost a session:

- **The operator has no RBAC to create a Service at all.** Same bug shape as feature 002's missing
  CNPG rules, and the k3s suites cannot catch it because they run the operator with admin
  credentials.
- **Kubernetes defaults `imagePullPolicy` to `Always` for a `:latest` tag**, so a locally built image
  sitting in the node's containerd is ignored and the pod fails `ErrImagePull`. Without rendering
  `IfNotPresent`, this feature cannot work at all. It has never mattered because every test to date
  used `registry.k8s.io/pause:3.9` — pullable, and not `:latest`.

## Corrections made on review

| # | The first draft said | What is actually true | How it was found |
|---|---|---|---|
| 1 | "No HTTP" is `"port": null` on an `Option[Int]` | **Unrepresentable.** jsoniter reads `null` as absent and applies the default, so it parses as port 9000 — and the descriptor crosses that codec twice. Now `http: Boolean` + `port: Int`. | Ran the codec: `{"port":null}` → `Some(9000)` |
| 2 | (silent) | **Default-on probing breaks all 11 `EndToEndClusterSuite` cases**, which deploy `pause` through descriptors naming only an image. They gain `"http": false`. | Grepped every descriptor that deploys a non-listening image |
| 3 | The suite "exercises its endpoints over HTTP" (mechanism unstated) | A test JVM cannot reach a `ClusterIP`, and the obvious fix — port-forward — **bypasses the Service**, so it could never catch a wrong selector (SC-006). Requests go from the k3s node to the `clusterIP`. | Ran it: node → `clusterIP` works; node → cluster DNS name does not |
| 4 | Turning HTTP off leaves the Service behind, because the operator should hold no `delete` | Misapplied feature 002's rule, which protects *data*. The operator already has `delete` on `deployments`. It also **contradicted the spec's own edge case**. Now a guarded `RemoveService`. | Read `Action.scala`; re-read the spec |

Also fixed: the build-ordering snippet put `taskDyn` on `Test / test` itself, which is a cycle, not
an override (research R9); and one new risk is recorded as **unverified** rather than assumed
(`publish / skip` on the sample, research R13).

## Found during implementation

The plan said the sample's own code needed no change, and that `LifecycleRules` needed none. Both
held. What did not hold was the unstated assumption that the platform was otherwise ready for a
real workload — deploying one found four bugs that predate this feature:

| Bug | Since | Why nothing caught it |
|---|---|---|
| Workloads rendered with no rollout strategy → default `RollingUpdate` surges a second pod on every deploy: two writers, one journal | 001 | `pause` has no journal; with no probe the overlap lasted a second |
| Fixing that wedges every existing Deployment (`invalid: spec.strategy`) — SSA cannot remove a defaulted field nobody owns | — | every test starts from an empty cluster |
| `-Dankka.cluster.tests=off` was a no-op: tests fork, and the fork never saw the property | 001 | the "skipped" suites still passed — they just took seven minutes |
| Re-running `deploy-local.sh` never rolled pods onto rebuilt images | 001 | every line of its output reports success |

All four are fixed and, where a test can hold them, pinned. See tasks.md T001 and T037.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21 — unchanged

**Primary Dependencies**: **none new.** The Service is a fabric8 model already on the classpath;
sbt-native-packager and `DockerPlugin` are already configured and merely get enabled on a third
project.

**Storage**: No change. The sample uses the per-service database feature 002 already provisions, via
the same `envFrom` credential secret, with the same platform-applied schema.

**Testing**: munit, four tiers — pure rendering and validation suites (offline); the existing
`OperatorClusterSuite` extended for the Service and a positive RBAC check; a new
`SampleDeploymentClusterSuite` that runs a real ankka application in k3s; and a manual walkthrough.
See Complexity Tracking for what the new suite costs.

**Target Platform**: Local kind cluster; k3s `v1.31.2-k3s1` in the suites — both unchanged

**Performance Goals**: The sample reaches `Ready` within the existing 3-minute budget a project's
first service already has (feature 002's SC-002), with no new allowance for the readiness probe
beyond its `initialDelaySeconds: 10`

**Constraints**: `sbt compile` stays warning-free; `sbt -Dankka.cluster.tests=off test` must build
**no** image and skip the new suite (FR-020); the CLI gains no new module dependency; the module
graph is unchanged in every compilation scope; in-cluster reachability only — ingress, TLS and
anything from outside the cluster stay out of scope

**Scale/Scope**: Two new descriptor fields resolving to one value, one new rendered object, two new
`Action` cases, one new RBAC rule, one newly packaged sample, one new test suite

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled Spec Kit template, as it was for features 001
and 002. The governing document is `CLAUDE.md`, and the gates below are taken from it.

| Principle (`CLAUDE.md`) | Assessment |
|---|---|
| **Effects are inert data; the runtime interprets them** | PASS — the Service is rendered as one more `Action.EnsureService` value by the same pure function that renders the Deployment; `Fabric8Executor` stays the only thing that performs it. |
| **Module dependency direction** | PASS — no compilation-scope change anywhere. `controlPlane`'s *tests* gain a build-level task dependency on the sample's image, which is not a classpath dependency; recorded in `CLAUDE.md` because it is surprising. |
| **`crd` depends on nothing** | PASS — the new field is a plain `Option[Int]`. |
| **Explicit registration, no scanning** | PASS — unaffected. |
| **Never more than one replica; never an autoscaler** | PASS — a Service in front of one pod changes nothing about replica count. |
| **Schema and manifest move together** | PASS by construction, and called out explicitly: the `port` field lands in the Scala model and in `kustomization/components/crd/ankkaservice.yaml` in the same task. Feature 002 got this wrong and a real cluster caught it. |
| **Single-copy DDL / manifests** | PASS — no DDL change; the operator's install manifest stays the single copy behind its symlink. |
| **Withhold verbs rather than promise restraint** | PASS — that rule protects *data* (feature 002). A Service holds none, so `delete` is granted, and the one action using it refuses any Service the resource does not own. |
| **Tests are real, at two levels** | PASS — pure suites for rendering and resolution, real-cluster suites for everything a fake would simply agree with. |

**Post-design re-check**: PASS, with one violation carried deliberately — see Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/003-deploy-real-service/
├── plan.md                        # This file
├── research.md                    # Phase 0 — thirteen findings; two blocking, one unverified
├── data-model.md                  # Phase 1
├── quickstart.md                  # Phase 1 — four tiers + reviewer's checklist
├── contracts/
│   ├── port-resolution.md         # one value → five things; the two validation rules
│   ├── service-object.md          # the Service, the probe, and what Ready now means
│   └── packaging-and-rbac.md      # RBAC, image packaging, k3s image import, build ordering
├── checklists/
│   └── requirements.md            # spec quality checklist (complete)
└── tasks.md                       # Phase 2 — NOT created by /speckit-plan
```

### Source code (repository root)

```text
controlplane-api/src/main/scala/ankka/controlplane/api/
└── descriptors.scala              # + ServiceSpec.port, + two validation rules

crd/src/main/scala/ankka/crd/
└── AnkkaService.scala             # + AnkkaServiceSpec.port

kustomization/components/crd/
└── ankkaservice.yaml              # + port in the OpenAPI schema (same change, always)

kustomization/components/operator/
└── operator.yaml                  # + services RBAC (no delete)

kustomization/
└── deploy-local.sh                # + kind load for the sample image

controlplane/src/main/scala/ankka/controlplane/deploy/
└── ServiceProjection.scala        # projects the resolved port

operator/src/main/scala/ankka/operator/
├── Names.scala                    # + Names.service
├── Action.scala                   # + EnsureService, + RemoveService (guarded on owner uid)
├── Executor.scala                 # + the case that applies it
└── Rendering.scala                # + the Service, containerPort, probe, ANKKA_HTTP_PORT,
                                   #   imagePullPolicy

samples/shopping-cart/             # no source change — packaging only
build.sbt                          # DockerPlugin on shoppingCart; the test/image ordering

controlplane/src/test/scala/ankka/controlplane/
├── SampleDeploymentClusterSuite.scala   # new — the point of the feature
└── EndToEndClusterSuite.scala           # its pause descriptors gain "http": false

operator/src/test/scala/ankka/operator/
├── ServiceRenderingSuite.scala     # new — pure
├── RenderingSuite.scala            # extended — port, probe, pull policy
└── OperatorClusterSuite.scala      # extended — Service + positive RBAC check

controlplane-api/src/test/scala/ankka/controlplane/api/
└── DescriptorSuite.scala           # extended — resolution + the two rules
```

**Structure Decision**: No new modules. Every change lands in a file that already exists, except the
two new test suites and `ServiceRenderingSuite`. The new cluster suite lives beside
`EndToEndClusterSuite` in `controlplane`'s test scope — the module that already drives the CLI, the
control plane and the operator against one k3s cluster — rather than inside it, because its subject
is the workload and `EndToEndClusterSuite`'s subject is the resource the two halves agree about.

## Phased delivery

The spec's two P1 stories are genuinely separable, and the split de-risks the larger half:

1. **US1 — reachability** (port, Service, probe, `imagePullPolicy`, RBAC). Provable with any image
   that listens, needing no sample packaging at all. This is the platform change.
2. **US2 — a real service** (packaging, image import, the end-to-end suite). Depends on US1 existing,
   and is where the awkward build ordering lives.
3. **US3 — guarding it** (the suite's assertions, the reviewer's checklist, the documentation
   corrections).

## Complexity Tracking

> Three costs accepted deliberately. The design gap the first draft left open is now closed.

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| **A test builds a Docker image** — `controlPlane`'s `Test / test` depends on `shoppingCart / Docker / publishLocal`, gated on the cluster-tests switch | FR-017 demands the *real* sample run in a *real* cluster, and something has to build it before the suite starts; `sbt test` must keep working with no preparatory step | Requiring a manual `docker:publishLocal` first makes `sbt test` fail for anyone who does not know; an unconditional dependency taxes every `controlPlane/test` run and breaks FR-020's switch in spirit; moving the suite into `shoppingCart`'s test scope would invert the sample→platform module direction for the sake of a task ordering |
| **A ~700MB image import per suite run** — `docker save`, copy, `ctr import` into the k3s container | It is the only verified way to get a locally built image into a testcontainers k3s node; `kind load docker-image` has no equivalent there | A `registry:2` container on the same network needs k3s registry configuration written before the container starts, for the same end result with more moving parts |
| **The descriptor default now has teeth** — a descriptor naming only an image asserts "serves HTTP on 9000" and is not `Ready` until it does | It is the user's chosen default, and the right one for an ankka service: the common case needs no configuration | Defaulting to *no* HTTP would keep every existing descriptor working and make every real service declare a port to be reachable — optimising for `pause`, which is a test fixture, over the thing the platform exists to run. Cost: every `pause` descriptor gains `"http": false` (research R12) |

## What this feature deliberately does not do

- **No ingress, no `LoadBalancer`, no TLS, no external reachability.** A service is reachable from
  inside the cluster. Getting traffic in from outside stays the operator's own concern, as it already
  is for the control plane.
- **No health endpoint.** Readiness means the port is open, not that the application considers itself
  healthy. What a deeper check should assert, and what a service must implement to satisfy it, is a
  larger design question left alone.
- **No multi-port services, no non-HTTP protocols.**
- **No isolation between projects at the network layer.** A `ClusterIP` is reachable from every
  namespace, so any project's pods can call any other project's service. Projects are the tenancy
  boundary for naming and databases; they are not one for traffic, and making them one means
  `NetworkPolicy` — worth its own feature, and worth saying out loud rather than discovering.
- **No `imagePullPolicy` field on the descriptor.** One right answer exists while the platform has no
  registry; the field is what to add on the day one does.
- **The multi-agent planner is not packaged.** It needs a model API key to do anything, which
  disqualifies it as an automated in-cluster proof.
