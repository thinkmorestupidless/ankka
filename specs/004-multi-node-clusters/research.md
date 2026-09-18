# Research: Multi-Node Service Clusters

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Everything marked **Verified** was run, not read. The vehicle was a standalone spike — a minimal
Pekko 1.7.0 application with `pekko-management` 1.2.1, cluster bootstrap and Kubernetes API
discovery, using the three-file configuration layering this plan proposes — deployed to the real
`kind-nakka` cluster in a scratch namespace, then removed. Standalone on purpose: feature 003 is
uncommitted in the working tree, and a plan should not leave code behind.

Two results changed the design rather than confirming it (R6, R9). Five things are recorded as **not
verified**, each with the reason and the task that will settle it: the seed-list environment variable
(R2), the formation step inside nakka itself (R3), the operator's new RBAC (R10), the Postgres
cold-start race (R11), and the control plane at several instances (R12).

---

## R1 — The layering, and why the selector cannot be the reference project's

**Decision**: three layers inside `nakka-runtime`, selected by one environment variable:

| Layer | File | Holds |
|---|---|---|
| base | `reference.conf` (as today) | everything common — and **nothing** about how nodes find each other |
| local overlay | `nakka-cluster-local.conf` | join self by default; named seed nodes when given |
| Kubernetes overlay | `nakka-cluster-kubernetes.conf` | pod-IP binding, management, bootstrap via the Kubernetes API |

Selected by `NAKKA_CLUSTER_MODE` (`local` when unset), resolved as the resource
`nakka-cluster-<mode>.conf` — so a further means of execution is a new file and no code (FR-012).
An unknown mode fails at startup, naming the modes that exist.

**Precedence, verified**:

```
system properties  >  the service's own application.conf  >  the platform's overlay  >  reference.conf
```

```scala
ConfigFactory.defaultOverrides()
  .withFallback(ConfigFactory.defaultApplication())
  .withFallback(ConfigFactory.parseResources(s"nakka-cluster-$mode.conf"))
  .withFallback(ConfigFactory.defaultReference())
  .resolve()
```

**Verified** in the spike: a value from the base, a value only in the user's `application.conf`, and
a value from the overlay were all present at once; and when the user's `application.conf` set a key
the overlay also set, **the user won**. `resolve()` must come last, after all four are merged, or the
overlay's `${POD_IP}` substitutions are resolved before the environment is consulted.

**Why not `-Dconfig.resource=application.k8s.conf`**, which is how the reference project
(`eitheror-platform/substrate`) selects its overlay: that flag *replaces* `application.conf`. Right for
an application that owns its configuration — its overlays `include "application.base"` — and wrong for
a platform running images whose `application.conf` belongs to someone else, which it would silently
discard (FR-011). The structure is adopted; the selector is not.

**What is adopted from it unchanged**: the startup code is identical in both modes. Which mechanism
runs is decided by configuration alone.

**Aside, reported to the user**: that project's local overlay sets a *top-level* `cluster { seed-nodes }`
rather than `akka.cluster.seed-nodes`, and nothing in its base references it — those seeds appear not
to be applied.

---

## R2 — The local overlay must not cost what nakka has today

**Decision**: locally the default stays exactly as now — a random remoting port and a programmatic
join-self — and named seed nodes are opt-in:

| Variable | Default | Effect |
|---|---|---|
| `NAKKA_CLUSTER_PORT` | `0` (random) | fix it on the node others will join |
| `NAKKA_CLUSTER_SEED_NODES` | unset → join self | comma-separated addresses of existing nodes to join instead |

**Rationale**: the spike's local overlay used the reference project's shape — a fixed port and
`seed-nodes = [self]` — and **verified** that a second JVM joins the first and leaves gracefully. But a
fixed port breaks something nakka relies on: several services, and several test suites, sharing one
machine. That works today *because* the port is random, and a seed-node list cannot name a port that
is not known until bind time — hence join-self being programmatic. So join-self stays the local
default, and the seed list is what you reach for to see a second node.

The seed list is nakka's own comma-separated key, joined programmatically — not
`pekko.cluster.seed-nodes = ${?ENV}`, which is a type error: an environment variable is a string and
that key is a list. (The spike proved two-node local clustering with `-D` system properties, which
*can* address list elements; the env-var form is **not verified** and is the runtime story's
second task.)

`NakkaTestKit` passes an explicit `Config` to `start`, bypassing the loader entirely, so every
existing suite runs exactly as before (SC-010). **Verified by reading** `NakkaTestKit.scala:109-126`.

**Management does not start locally.** It binds a fixed HTTP port (7626), which would collide between
two local services precisely as a fixed remoting port would. It starts only when the overlay asks for
bootstrap.

---

## R3 — One formation step, chosen by configuration

**Decision**: replace `joinSelfIfUnseeded` with one step driven by `nakka.cluster.formation`:

| Value | Set by | Behaviour |
|---|---|---|
| `join-self-or-seeds` | local overlay | seed nodes if configured, otherwise join self — today's behaviour |
| `bootstrap` | Kubernetes overlay | start Pekko Management, then Cluster Bootstrap; **never** join self |

**Rationale**: the reference project calls `ClusterBootstrap(system).start()` unconditionally and
relies on bootstrap standing down when seed nodes exist. **Verified** that it does — and that it says
so with a `WARN` on every start (`…configured with specific pekko.cluster.seed-nodes … bailing out of
the bootstrap process!`). A warning on every local run trains people to ignore warnings, so nakka
decides from configuration which to start. Still one code path, still chosen by the overlay.

The Kubernetes overlay also sets `nakka.join-self-if-no-seed-nodes = off`. It must: in that mode a
self-join *is* the split this feature exists to prevent.

**Not verified**: this step inside nakka's own `Nakka.host` — the spike is standalone. First task of
the runtime story.

---

## R4 — Simultaneous cold start forms one cluster

**Decision**: `required-contact-point-nr = min(instances, 2)`, injected by the operator.

**Verified**, three pods started at the same instant, membership read from every pod's own management
endpoint and compared:

| `required-contact-point-nr` | Rounds | Clusters formed | Time to form |
|---|---|---|---|
| 2 | 10 | **1, every round** | 10–15 s |
| 1 (what the documentation warns against) | 10 | **1, every round** | 10–14 s |

**The `nr = 1` result is not evidence that `1` is safe.** `kind-nakka` is one node with a fast,
unloaded API server, and the split the documentation describes needs two pods that each fail to see
the other in discovery for the whole stable margin — hard to provoke here, entirely possible across
nodes under scheduling pressure. Twenty clean rounds on the friendliest possible cluster is an
absence of failure, not a proof. So the conservative value stands, and **SC-002's twenty-round test
belongs in the automated suite**, not only in this document.

`min(instances, 2)` rather than `instances`: one instance must be able to form alone, and at
`nr = instances` a single unschedulable pod would hold the entire service down.

---

## R5 — Scaling keeps the pods that stay (FR-016)

**Verified**: a settled one-pod cluster scaled to three **with the pod template unchanged** became
one cluster of three, and the original pod was untouched — 0 restarts, its age still counting.

**Consequence for R4**: the contact-point count is an environment variable, so changing it changes the
pod template and rolls every pod. With `min(instances, 2)` that happens only when the count crosses
between 1 and 2-or-more; scaling 3→5 or 5→2 touches nothing that stays. And crossing that boundary
costs a roll that R6 shows is free. FR-016 is honoured except at that one boundary, where the
exception is harmless — recorded in plan.md's Complexity Tracking rather than glossed.

---

## R6 — A rolling update keeps one cluster, even at one instance — this changes feature 003

**Decision**: render `RollingUpdate` with `maxSurge: 1`, `maxUnavailable: 0`, **at every instance
count including one**. `Recreate` goes.

**Verified**, reading every pod's membership throughout a rollout and counting *disjoint* member sets
(views that share a member are one cluster sampled at different instants; only disjoint views are a
split):

| Case | Disjoint clusters | Membership | Fewest ready pods |
|---|---|---|---|
| 3 instances | **1 throughout** | 3 → 4 → 5 → … → 3 | — |
| **1 instance** | **1 throughout** | 1 → 2 → 1 | **1 — never zero** |

The surge pod *joins the existing cluster*, becomes ready, and only then does an old pod leave. At
one instance that means **no outage at all**.

This is a better result than the spec asked for. FR-020 required only that a single-instance update
stay *correct*; it turns out to be uninterrupted too. Feature 003 rendered `Recreate` — and documented
"a deploy is a brief outage" in `README.md` — because a surge pod then joined *itself* and became a
second writer. That reason disappears the moment nodes can find each other, and so does the outage,
for every service, not just multi-instance ones.

Pekko's log during these rollouts includes `Marking exiting node(s) as UNREACHABLE … This is
expected`. It is: a transient of graceful exit, not a fault. A test asserting "never any unreachable
member" during a rollout would be asserting something false.

**Carried forward**: feature 003's executor migrates a live Deployment *from* `RollingUpdate` to
`Recreate`, because the API server rejected the change while a defaulted field remained — a bug that
only an existing object could show. The reverse move must be tested against an existing `Recreate`
Deployment in the same way, not assumed to be symmetric.

---

## R7 — Readiness is cluster membership

**Decision**: one `httpGet /ready` probe on the management port, replacing feature 003's `tcpSocket`
on the HTTP port. nakka registers a second readiness check of its own — "the HTTP server is bound" —
for services that serve HTTP, so one probe answers both questions.

**Verified**:

| State | `/ready` | Pod |
|---|---|---|
| management up, not yet a member | `500` | `0/1` — receives no traffic |
| a member, `Up` | `200` | `1/1` |

Cluster Bootstrap registers the membership check itself; nothing has to be written for it.

This generalises feature 003's probe rather than discarding it. `tcpSocket` could not exist for a
service that serves no HTTP, so such a service was `Ready` as soon as its container ran. Every service
is a cluster member, so now every service has a meaningful `Ready` (FR-024).

**It is also the safety net for old images** (FR-022): an image whose runtime predates this feature
has no management endpoint, the probe gets connection refused, the pod is never ready and the
Deployment's own deadline reports it `Failed`. That is the path feature 003's cluster test 23 already
proves for a port that never opens.

Still no liveness probe, for feature 003's reason, which multi-node makes stronger: restarting a
member for a slow GC now also costs a shard rebalance.

---

## R8 — Discovery that cannot work waits; it never goes alone (FR-002)

**Verified** by deleting the RoleBinding and cold-starting three pods:

- each pod logged `Forbidden to communicate with Kubernetes API server; check RBAC settings`;
- all three stayed at **0 members**, `/ready` → `500`, `0/1` — **none joined itself**;
- restoring the binding produced **one cluster of three with zero restarts**.

The last point matters operationally: the operator may create the Role a moment after the Deployment,
and that ordering is self-healing rather than something to sequence carefully.

---

## R9 — Losing a node: two different recoveries, and one setting nakka lacks

**Verified**, three separate ways of losing a pod:

| How | What happened | Recovery |
|---|---|---|
| `kubectl delete pod`, even `--grace-period=0 --force` | SIGTERM still arrives; the node leaves deliberately: `Leaving → Exiting → Removed` | **4–6 s**, nothing unreachable |
| `SIGKILL` to the JVM, from the node | kubelet restarts the container **in place, same IP**; Pekko sees a new incarnation at a known address and evicts the old one | **~4 s** — the split-brain resolver never involved |
| network partition (`iptables` dropping the pod's remoting traffic) | majority: `SBR took decision DownUnreachable`; the isolated node downed itself, its JVM exited, the container restarted and rejoined | **~25 s** to removal; one cluster of three after healing |

Two things the plan takes from this:

- `--force` deletion is **not** a crash test, and a suite that uses it to test failure is testing
  graceful leave twice. A real crash is a `SIGKILL` from the node; a real partition is `iptables`.
  Both are scriptable from a test through the k3s container.
- The isolated node exiting — which is what lets Kubernetes restart it into a clean rejoin — depends
  on `pekko.coordinated-shutdown.exit-jvm = on`. The spike set it. **nakka's `reference.conf` does
  not.** Without it a downed node's actor system stops and its JVM lives on: a pod that is `Running`,
  never ready, and never restarted. ~~It goes in the base.~~ **Implementation correction (T007): it goes
  in the Kubernetes overlay.** In the base it exits the JVM on *every* stopped actor system — the
  offline test suite, which stops one per case, died within seconds.

The partition strategy is `keep-majority`, so two instances cannot survive a partition with both
sides up. Odd counts are the documented recommendation (spec Assumptions).

---

## R10 — Identity and permissions

**Decision**: per service, in its project's namespace, all owned by the `NakkaService` so they go with
it (FR-030):

| Object | Name | Content |
|---|---|---|
| `ServiceAccount` | the service name | — |
| `Role` | `<service>-peers` | `pods`: `get`, `list`, `watch` — nothing else |
| `RoleBinding` | `<service>-peers` | that Role → that ServiceAccount |

A `Role`, never a `ClusterRole`: the grant cannot reach beyond the project (FR-028). Peers are
selected by `Labels.identity` — the same four labels the Deployment and Service already select on,
hoisted to one value in feature 003 — so a node cannot mistake another service's pods for its own
even in a shared namespace (FR-003).

**The operator's ClusterRole gains** `serviceaccounts`, `roles` and `rolebindings`:
`get, list, watch, create, patch`, plus `delete` on none (owner references remove them).

**Kubernetes refuses to let a principal grant a permission it does not hold.** Checked: the operator
**already has** `pods: get, list, watch` — feature 001 added it to explain why a Deployment is not
ready — which is exactly the set the Role grants, so no `escalate` or `bind` verb is needed.

**Not verified on a cluster**: that reasoning. It is standard Kubernetes behaviour, but this
ClusterRole has been wrong in the same way twice (features 002 and 003), and both times the k3s suites
— which run the operator on admin credentials — could not see it. It is to be proven the way feature
003's T025 proved `services`: the real `Fabric8Executor` under the operator's real ServiceAccount
token. SC-007 adds the other half: a *service's* identity attempting to list pods in another namespace
and to modify anything at all, both refused.

**The control plane** gets the same three objects as static kustomize manifests, as a `Role` in its
own namespace. Its RBAC comment says "still no workload access"; that stays true — the grant does not
reach a project namespace.

---

## R11 — Cold start races in Postgres (NOT VERIFIED — a risk to test, not a finding)

Several pods of a new service now start at once, and each does two things that one pod used to do
alone:

- the schema-init container runs the DDL and `REVOKE CONNECT`;
- the runtime creates view row tables with `CREATE TABLE IF NOT EXISTS` (`ViewStore.scala:20`).

`CREATE TABLE IF NOT EXISTS` is **not** safe under concurrency in Postgres: two sessions can both pass
the existence check, and the loser fails with a unique violation on `pg_type`. For the init container
that means a failed init and a restart — self-healing, but it would trip "never `Failed` on the way
up", which both cluster suites assert. For the runtime it means a node failing at startup.

**Decision**: serialise both with a Postgres advisory lock held for the session — for the init
container, one `psql` invocation (so one session) that takes the lock before its `-f` files.

Not verified because it needs nakka's real schema and runtime, not a spike. It gets its own task and
its own cluster test: several instances, cold, repeatedly, asserting zero init-container restarts.

---

## R12 — The control plane

**Verified by reading**, not by running:

- `ServiceProjector`'s sweeper is **already** a cluster singleton
  (`ServiceProjector.scala:69`) — FR-033 is met by existing code.
- The status **watch** is started by every node (`ServiceProjector.scala:61`), so N nodes produce N
  `observe` commands per status change. `ServiceEntity.observe` already refuses an observation
  identical to the recorded state — feature 001's guard against the sweep growing the journal — so
  the duplicates cost commands, not events (FR-034).
- `projectNow`, called by the apply endpoint, runs on whichever node served the request. It is a
  server-side apply and idempotent.

So US5 is manifests, the config overlay, and **proving** the above at three instances — counting
journal events, not trusting this paragraph. **Not verified**: that nothing else in the control plane
assumes it is alone. Views are Pekko Projections over a sharded daemon process, and timers are a
singleton, so none is expected; the test is what decides.

---

## R13 — Dependencies and ports

| Artifact | Version | Note |
|---|---|---|
| `pekko-management` | 1.2.1 | latest 1.x; resolved and ran cleanly against Pekko 1.7.0 |
| `pekko-management-cluster-http` | 1.2.1 | `/cluster/members` — what the tests read membership from |
| `pekko-management-cluster-bootstrap` | 1.2.1 | |
| `pekko-discovery-kubernetes-api` | 1.2.1 | |
| `pekko-discovery` | 1.7.0 | matches the rest of Pekko |

They go in `nakka-runtime`, so the same build runs locally and deployed (FR-006, SC-006) — a local
run pays some jar weight for libraries it never starts.

| Port | Number | Name in the pod spec |
|---|---|---|
| remoting | 17355 | `remoting` |
| management | 7626 | **`management`** |

The management port's *name* is load-bearing: Kubernetes API discovery finds a pod's contact point by
looking for a container port with that name. Get it wrong and discovery finds the pods and cannot
reach any of them.

`sbt-native-packager`'s start script `exec`s the JVM, so it is PID 1 and receives `SIGTERM` directly
(**verified** in the pod). Had it not, every shutdown would have been a 30-second wait and a
`SIGKILL`, and every graceful-leave result above would have been wrong.
