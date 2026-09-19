# Implementation Plan: Multi-Node Service Clusters

**Branch**: `004-multi-node-clusters` | **Date**: 2026-09-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-multi-node-clusters/spec.md`

## Summary

An ankka node does not find its peers; it joins itself. That one fact is why services run as a single
pod, why `autoscaling` is ignored, and why feature 003 had to make every deploy a brief outage. The
fix is to teach a node how to find its peers — differently depending on where it runs, with the
service's code identical in both places.

**Configuration in three layers**, following the user's reference project: a base that says nothing
about peer-finding, a local overlay (join self, or named seed nodes), and a Kubernetes overlay
(Pekko Management and Cluster Bootstrap over the Kubernetes API). Selected by `ANKKA_CLUSTER_MODE`,
which the operator sets — **not** by `-Dconfig.resource` as the reference does, because that replaces
`application.conf`, and on a platform that file belongs to someone else.

**The operator honours `autoscaling.minInstances`**, renders each service an identity that can read
the pods in its own project and nothing else, brings back `RollingUpdate`, and makes readiness mean
"a cluster member".

**The control plane runs on it too.** It turns out to be mostly ready: its sweeper is already a
cluster singleton, and duplicate status observations are already refused by the entity.

Almost everything asserted here was **measured on the real `kind-ankka` cluster** with a standalone
spike, since removed. Two results changed the design:

- **A rolling update keeps one cluster even at a single instance** — the surge pod joins the old pod's
  cluster and is ready before the old one leaves, so there was never fewer than one ready pod. Feature
  003's "a deploy is a brief outage" goes away for **every** service, not only multi-instance ones.
  The spec asked only that single-instance updates stay correct (FR-020).
- **ankka lacks `coordinated-shutdown.exit-jvm = on`.** The partition test recovered cleanly only
  because the spike set it: the isolated node downed itself, its JVM exited, Kubernetes restarted it
  and it rejoined. Without it a downed node lingers — `Running`, never ready, never restarted.

And one honest non-result: `required-contact-point-nr = 1`, which the documentation warns against,
also formed one cluster in 10 of 10 cold starts. That is one node and an idle API server, not
evidence of safety. The conservative value stands and SC-002's twenty rounds go in the suite.

## Technical Context

**Language/Version**: Scala 3.9.0, JDK 21 — unchanged

**Primary Dependencies**: **new**, in `ankka-runtime`: `pekko-management`,
`pekko-management-cluster-http`, `pekko-management-cluster-bootstrap`,
`pekko-discovery-kubernetes-api` — all 1.2.1, verified against Pekko 1.7.0 — and `pekko-discovery`
1.7.0. In the runtime rather than a separate module so the same build runs locally and deployed
(FR-006); a local run carries libraries it never starts.

**Storage**: No schema change. One new hazard: several pods now cold-start at once, and
`CREATE TABLE IF NOT EXISTS` is not concurrency-safe in Postgres (research R11, unverified).

**Testing**: munit. Pure suites for config precedence, rendering and status rules; the operator
cluster suite extended for identity and RBAC under real tokens; **two new cluster suites** —
`MultiNodeClusterSuite` (a real multi-instance service) and `ControlPlaneClusterSuite`. Crash and
partition are driven from the k3s node (`SIGKILL`, `iptables`), never `kubectl delete --force`, which
still sends SIGTERM.

**Target Platform**: local kind; k3s `v1.31.2-k3s1` in the suites — unchanged

**Performance Goals**: three instances, one cluster, ready within 3 minutes (SC-001) — cluster
formation itself measured at 10–15 s; ≥ 99% of requests succeed through a rolling update (SC-003);
entities from a killed instance answering within 60 s (SC-004) — measured ~4 s for a crash, ~25 s for
a partition

**Constraints**: every existing suite passes **unchanged** and `AnkkaTestKit` is not modified
(SC-010); `sbt shoppingCart/run` with no environment still just works; the base contains no
peer-finding setting; no autoscaler; compile stays warning-free

**Scale/Scope**: five new dependencies, two overlay files, one loader, one formation step, three new
rendered kinds, one new status rule, two new cluster suites, the control plane's manifests

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template; the gates are `CLAUDE.md`'s.

| Principle (`CLAUDE.md`) | Assessment |
|---|---|
| **Effects are inert data** | PASS — ServiceAccount, Role and RoleBinding are three more `Action` values rendered by the same pure function. |
| **Module dependency direction** | PASS — new libraries land in `runtime`; nothing new depends on anything. `crd` still depends on nothing. |
| **"Never more than one replica" / "`strategy: Recreate`, always"** | **SUPERSEDED, deliberately.** Both traps exist *because* a node joins itself. This feature removes that cause, and research R6 measured the consequence — one cluster throughout a rollout, at every count. The traps are to be **replaced** in `CLAUDE.md`, not left standing beside their opposites. |
| **The same code path in development and production** | PASS, and strengthened — that sentence is `joinSelfIfUnseeded`'s own doc comment. The formation step stays one code path; configuration picks the mechanism. |
| **Withhold verbs rather than promise restraint** | PASS — a service's identity gets three read verbs on one resource, in one namespace. The operator gains no `delete`. |
| **Prove RBAC under real identities** | PASS by plan — both the operator's new grants and a service's identity are tested with real tokens (feature 003's T025 pattern). |
| **Tests that start from an empty cluster cannot see migration bugs** | PASS by plan — the `Recreate` → `RollingUpdate` move is tested against an *existing* Deployment. |
| **Verify, do not read** | PASS — see research.md; five items explicitly marked unverified, each with a task. |

**Post-design re-check**: PASS, with the costs in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/004-multi-node-clusters/
├── plan.md
├── research.md                       # 13 findings; 2 changed the design, 5 marked unverified
├── data-model.md
├── quickstart.md                     # six tiers + reviewer's checklist
├── contracts/
│   ├── config-layering.md            # base + two overlays, selection, precedence
│   ├── formation-and-rollout.md      # the invariant, and every measured number
│   └── identity-and-rbac.md          # per-service identity; what the operator gains
├── checklists/requirements.md
└── tasks.md                          # /speckit-tasks — not created here
```

### Source code (repository root)

```text
project/Dependencies.scala                       # + management, bootstrap, discovery
modules/runtime/src/main/resources/
├── reference.conf                               # loses every peer-finding setting; gains exit-jvm
├── ankka-cluster-local.conf                     # new
└── ankka-cluster-kubernetes.conf                # new
modules/runtime/src/main/scala/ankka/runtime/
├── ClusterConfig.scala                          # new — the layered loader
├── ClusterFormation.scala                       # new — replaces joinSelfIfUnseeded
└── Ankka.scala                                  # default config → the loader; host → formation
modules/http/                                    # registers the "HTTP is bound" readiness check
modules/runtime/.../ViewStore.scala              # advisory lock around CREATE TABLE IF NOT EXISTS

operator/src/main/scala/ankka/operator/
├── Rendering.scala                              # replicas, RollingUpdate, identity, ports, /ready, env
├── Action.scala / Executor.scala                # + EnsureServiceAccount, EnsureRole, EnsureRoleBinding
├── ClusterSnapshot.scala / LifecycleRules.scala # + totalReplicas; old-template pods → UpdateInProgress
└── SchemaInit.scala                             # advisory lock around the DDL
controlplane-api/.../descriptors.scala           # the five platform env names are refused

kustomization/components/operator/operator.yaml        # + serviceaccounts, roles, rolebindings
kustomization/components/controlplane/                 # replicas, RollingUpdate, Role+Binding,
                                                       # env, ports, /ready

controlplane/src/test/.../MultiNodeClusterSuite.scala      # new
controlplane/src/test/.../ControlPlaneClusterSuite.scala   # new
modules/runtime/src/test/.../ClusterConfigSuite.scala      # new
operator/src/test/.../IdentityRenderingSuite.scala         # new
```

**Structure Decision**: no new modules. The loader and formation step are small and live in
`runtime` beside the code they replace. A separate `ankka-cluster-kubernetes` module was considered —
it would keep the Kubernetes libraries off a local classpath — and rejected: it makes "the same
build" (FR-006, SC-006) false, and turns choosing a means of execution into a dependency decision
made by whoever builds the image, which is exactly who FR-010 says should not have to know.

## Phased delivery

1. **The runtime** (US2, then US1's precondition) — dependencies, the three files, the loader, the
   formation step. Done when every existing suite passes untouched and two local JVMs form a cluster.
   Settles the unverified R2 and R3 first, because everything else stands on them.
2. **The operator** (US1) — instances, identity, ports, env, `/ready`, the status rule.
3. **Rollout and failure** (US3, US4) — `RollingUpdate`, the migration from `Recreate`, the Postgres
   race, the multi-node suite with its load, crash and partition cases.
4. **The control plane** (US5).
5. **Documentation** — including *replacing* two `CLAUDE.md` traps and two README gaps that this
   feature makes false.

## Complexity Tracking

| Cost | Why accepted | Alternative rejected because |
|---|---|---|
| **Every service can list every pod in its project** | Kubernetes API discovery, chosen by the user over DNS for faster, more deterministic convergence | DNS needs no credential at all, and was offered; it is slower to converge and the less-trodden path. Bounded to read-only, one namespace, one ServiceAccount per service |
| **Kubernetes libraries on every local classpath** | the same build must run in both places (FR-006) | a separate module makes the means of execution a build-time choice |
| **Crossing between one instance and several rolls every pod** — a deviation from FR-016 | `required-contact-point-nr` must be `1` for one instance and should be `2` otherwise, and it is an environment variable | a constant `1` avoids the roll and is what the documentation warns against; research R6 shows the roll costs no outage, so the safe value is nearly free |
| **Two instances cannot survive a partition** | `keep-majority` has no majority of two | `keep-oldest` or a lease would; both trade a clear rule for a subtler one. Documented; odd counts recommended |
| **A multi-JVM cluster suite** — three sample pods plus Postgres plus CNPG in one k3s container | the invariant is only testable with real nodes | a fake proves nothing about a race |
| **Feature 003's `Recreate` and its migration code are undone one feature later** | 003 was right for a node that joins itself; this removes the cause | leaving `Recreate` would keep an outage the measurements show is unnecessary |

## What this feature deliberately does not do

- **No autoscaler.** `maxInstances` and `targetCpuPercent` stay carried and unhonoured. Every
  scale-in is a member leaving under load; that needs draining proven first.
- **No network isolation.** Remoting and management ports are as reachable across namespaces as the
  HTTP port already is. `NetworkPolicy` remains its own feature — and matters more now that there is
  a cluster protocol to reach.
- **No encryption or authentication of cluster traffic.** Artery runs plain, as it does locally.
- **No multi-cluster, multi-region, or cross-namespace clusters.**
- **No liveness probe**, still — and the reason is stronger: restarting a member costs a rebalance.
