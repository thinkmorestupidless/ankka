# Tasks: Multi-Node Service Clusters

**Input**: Design documents from `specs/004-multi-node-clusters/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Included. The spec makes the automated proof a requirement (FR-036 to FR-039), and the
central property — *one cluster, never two* — is a race, which only a real cluster can test and
only repetition can test convincingly.

**Organization**: By user story, but **US2 (runs locally, unchanged) comes first**. It is the
runtime's configuration layering and formation step, and every other story stands on it. It is also
where the two unverified items closest to the ground (research R2, R3) get settled — before anything
is built on them.

## Format: `[ID] [P?] [Story] Description`

## Read before starting

Five things research.md marks **not verified**, each settled by a named task below:

| Risk | Task |
|---|---|
| the comma-separated seed list joined in code (R2) | T012 |
| the formation step inside nakka's own `host` (R3) | T011, T013 |
| the operator's new RBAC under its real identity (R10) | T034 |
| Postgres `CREATE TABLE IF NOT EXISTS` at concurrent cold start (R11) | T042, T043, T048 |
| the control plane at three instances (R12) | T053 to T056 |

And three measured facts to keep in mind while writing tests:

- `kubectl delete --force` still sends SIGTERM — it is a graceful leave, **not a crash**. A crash is
  `SIGKILL` from the k3s node; a partition is `iptables` on the k3s node.
- `Marking exiting node(s) as UNREACHABLE … This is expected` appears during every rollout. A test
  asserting "no unreachable member" across a rollout asserts something false.
- A pod whose management endpoint is up but which has not joined reports **zero members**. That is
  a node waiting, not a cluster; only *disjoint* member sets are a split.

---

## Phase 1: Setup

- [ ] T001 Confirm the baseline: `sbt -Dnakka.cluster.tests=off test` green and `sbt compile` warning-free; record the test count. Then confirm the working tree is committed or the user has decided not to — feature 003 is currently uncommitted, and this feature edits the same files.
- [ ] T002 Add to `project/Dependencies.scala`: `pekko-management`, `pekko-management-cluster-http`, `pekko-management-cluster-bootstrap`, `pekko-discovery-kubernetes-api` at **1.2.1** and `pekko-discovery` at the Pekko version, wired into `runtime`'s `libraryDependencies` in `build.sbt`. Compile. The 1.2.1 line was verified against Pekko 1.7.0 in research R13; a later line pulls a different Pekko.

---

## Phase 2: User Story 2 — The same service runs locally, unchanged (Priority: P1) 🎯 First

**Goal**: The base, two overlays and the loader exist; the formation step replaces
`joinSelfIfUnseeded`; **nothing observable changes** for anyone running locally or in a test.

**Independent Test**: every existing suite passes untouched; `sbt shoppingCart/run` with no
environment works and logs no bootstrap warning; two local JVMs form one cluster.

### Tests

- [ ] T003 [P] [US2] New `modules/runtime/src/test/scala/nakka/runtime/ClusterConfigSuite.scala`, precedence per [contracts/config-layering.md](./contracts/config-layering.md): a key set in the base, the overlay and a test `application.conf` resolves to the `application.conf` value; a `-D` system property beats all three; a key only in the overlay is present; a key only in the base is present. Load with `mode = "local"`.
- [ ] T004 [P] [US2] Same suite: `mode = "nope"` fails at load with a message naming `local` and `kubernetes`; `mode = "kubernetes"` with no `POD_IP` in the environment fails naming `POD_IP` (pass an explicit env map to the loader rather than mutating the JVM's).
- [ ] T005 [P] [US2] Same suite, **the rule that makes it a base, as a test**: read `reference.conf` as a resource and assert it contains none of `seed-nodes`, `canonical.hostname`, `bootstrap`, `discovery`, `join-self`. And assert `pekko.coordinated-shutdown.exit-jvm` resolves to `on` and `pekko.cluster.split-brain-resolver.active-strategy` to `keep-majority`.
- [ ] T006 [P] [US2] Same suite: with `mode = "local"` and no environment, `nakka.cluster.formation == "join-self-or-seeds"`, `pekko.remote.artery.canonical.port == 0`, `nakka.cluster.seed-nodes == ""`; with `NAKKA_CLUSTER_PORT=17355` and `NAKKA_CLUSTER_SEED_NODES=a,b` in the env map, the port is 17355 and the seed key is `"a,b"` — a **string**, so the comma form is what the code parses (research R2's type-error trap).

### Implementation

- [ ] T007 [US2] Edit `modules/runtime/src/main/resources/reference.conf`: **remove** `pekko.remote.artery.canonical.{hostname,port}`, `pekko.cluster.seed-nodes` and `nakka.join-self-if-no-seed-nodes`; **add** `pekko.coordinated-shutdown.exit-jvm = on` (comment: a node downed by the split-brain resolver must exit so Kubernetes restarts it — research R9) and `pekko.cluster.split-brain-resolver.active-strategy = keep-majority` (comment: stated because the instance-count guidance depends on it). Update the file's header comment: this file says nothing about how nodes find each other, on purpose.
- [ ] T008 [P] [US2] New `modules/runtime/src/main/resources/nakka-cluster-local.conf` exactly per [contracts/config-layering.md](./contracts/config-layering.md): `formation = join-self-or-seeds`, `join-self-if-no-seed-nodes = on`, loopback hostname, port `0` overridable by `NAKKA_CLUSTER_PORT`, `nakka.cluster.seed-nodes = ""` overridable by `NAKKA_CLUSTER_SEED_NODES`. Comment why the port stays random (several services on one machine) and why the seed list is nakka's own string key (an env var cannot be a HOCON list).
- [ ] T009 [P] [US2] New `modules/runtime/src/main/resources/nakka-cluster-kubernetes.conf` per the same contract: `formation = bootstrap`, `join-self-if-no-seed-nodes = off` (comment: here, joining self *is* the split), artery on `${POD_IP}`:17355, management on `${POD_IP}` bind `0.0.0.0` port 7626, bootstrap `kubernetes-api` with `service-name = ${NAKKA_CLUSTER_SERVICE}` and `required-contact-point-nr = ${NAKKA_CLUSTER_CONTACT_POINTS}`, `pod-label-selector = ${NAKKA_CLUSTER_POD_SELECTOR}`. All **required** substitutions — comment that a missing one must fail loudly, not bind loopback quietly.
- [ ] T010 [US2] New `modules/runtime/src/main/scala/nakka/runtime/ClusterConfig.scala`: `def load(env: Map[String, String] = sys.env): Config` — mode from `NAKKA_CLUSTER_MODE` defaulting to `local`; resource `nakka-cluster-<mode>.conf` (fail naming the modes that exist if absent — discover them by probing the known names, or list `local` and `kubernetes` explicitly); merge `defaultOverrides` > `defaultApplication` > overlay > `defaultReference`; **`resolve()` last**, with the env map supplied for substitution (comment: earlier and `${POD_IP}` resolves before the environment is consulted — research R1). Doc-comment why this is not `-Dconfig.resource`.
- [ ] T011 [US2] New `modules/runtime/src/main/scala/nakka/runtime/ClusterFormation.scala`: `def form(system: ActorSystem[?]): Unit` reading `nakka.cluster.formation`. `join-self-or-seeds`: if `nakka.cluster.seed-nodes` is non-empty, split on `,`, trim, parse each as an `Address`, and `cluster.manager ! JoinSeedNodes(...)`; else if `join-self-if-no-seed-nodes` then `Join(selfAddress)` — today's code, moved. `bootstrap`: `PekkoManagement(system).start()` then `ClusterBootstrap(system).start()`, and **never** join self. Any other value: throw naming the two. Doc-comment: one code path; configuration picks the mechanism; management is started only here because it binds a fixed port (research R2, R3).
- [ ] T012 [US2] Wire it in `modules/runtime/src/main/scala/nakka/runtime/Nakka.scala`: `start`'s default `config` becomes `ClusterConfig.load()`; `host` calls `ClusterFormation.form(system)` where `joinSelfIfUnseeded` was; delete `joinSelfIfUnseeded`, carrying its doc comment's sentence ("running the same code path in development and production is the point") onto `ClusterFormation`. **`NakkaTestKit` is not touched** — it passes its own `Config` and must keep bypassing the loader.
- [ ] T013 [US2] Prove the runtime story by running it (research R2, R3 — the two unverified items closest to the ground): `docker compose up -d`; `sbt shoppingCart/run` with **no** environment serves a request and its log contains no `bailing out of the bootstrap` warning; then `NAKKA_CLUSTER_PORT=17355 sbt shoppingCart/run` in one terminal and `NAKKA_CLUSTER_SEED_NODES=pekko://nakka@127.0.0.1:17355 NAKKA_HTTP_PORT=9001 sbt shoppingCart/run` in another form **one cluster of two** (add an item through `:9000`, read it through `:9001`). Record both outcomes here.
- [ ] T014 [US2] Add to `controlplane-api/src/main/scala/nakka/controlplane/api/descriptors.scala`: the five platform variables (`NAKKA_CLUSTER_MODE`, `POD_IP`, `NAKKA_CLUSTER_SERVICE`, `NAKKA_CLUSTER_POD_SELECTOR`, `NAKKA_CLUSTER_CONTACT_POINTS`) join `NAKKA_HTTP_PORT` as names a descriptor's `env` may not set — one set, one rule, one message shape. Extend `controlplane-api/src/test/scala/nakka/controlplane/api/DescriptorSuite.scala`: each refused by name, value irrelevant; a neighbour like `NAKKA_CLUSTER_MODE_X` accepted.

**Checkpoint**: `sbt -Dnakka.cluster.tests=off test` green with **every pre-existing test unchanged**
(diff the test directories against T001's baseline: only additions). This is SC-010, and it is the
story's whole point.

---

## Phase 3: User Story 1 — A service runs as one cluster of several nodes (Priority: P1)

**Goal**: The operator honours the instance count, gives each service an identity, tells its pods
how to find each other, and reports readiness as membership.

**Independent Test**: write a `NakkaServiceSpec` with `minInstances = 3` for the sample image; three
pods, one cluster, an item written through one pod and read through another.

### Tests (pure — first)

- [ ] T015 [P] [US1] Extend `operator/src/test/scala/nakka/operator/RenderingSuite.scala`: `replicas == spec.autoscaling.minInstances` (1 by default, 3 when set, **0 when paused regardless**); `Rendering.Replicas` no longer exists (delete its test); the strategy is `RollingUpdate` with `maxSurge 1` / `maxUnavailable 0` **at `minInstances = 1` too** (replace feature 003's Recreate test, keeping its history in the comment: right for a node that joined itself, wrong now — research R6).
- [ ] T016 [P] [US1] Same suite, the Kubernetes environment: the container carries `NAKKA_CLUSTER_MODE=kubernetes`, `POD_IP` from the downward API `status.podIP`, `NAKKA_CLUSTER_SERVICE = <name>`, `NAKKA_CLUSTER_POD_SELECTOR` **equal to the Deployment's selector rendered as `k=v,k=v`** (compare against `deployment.getSpec.getSelector.getMatchLabels`, not against `Labels.identity`), `NAKKA_CLUSTER_CONTACT_POINTS = min(minInstances, 2)` (assert `1`, `2`, `2` for 1, 2, 5). Beside the descriptor's own env, not instead of it.
- [ ] T017 [P] [US1] Same suite, ports and probe: container ports `management` 7626 and `remoting` 17355 always, `http` when a port is resolved; **the port named exactly `management`** (research R13 — discovery finds contact points by that name); `readinessProbe.httpGet` path `/ready` on port name `management`, **present with and without an HTTP port** (replace feature 003's tcpSocket tests); `serviceAccountName == <name>`; still no liveness probe.
- [ ] T018 [P] [US1] New `operator/src/test/scala/nakka/operator/IdentityRenderingSuite.scala` per [contracts/identity-and-rbac.md](./contracts/identity-and-rbac.md): `EnsureServiceAccount`, `EnsureRole`, `EnsureRoleBinding` all rendered; names `<name>`, `<name>-peers`, `<name>-peers`; all in the project namespace; all carry the owner reference with the resource's uid; the Role has **exactly one rule** — `""`/`pods`/`get,list,watch` — and nothing else; the RoleBinding's subject is that ServiceAccount; all three come **before** `ApplyDeployment` in the action list; rendering twice is identical.
- [ ] T019 [P] [US1] Extend `operator/src/test/scala/nakka/operator/LifecycleRulesSuite.scala`: a snapshot with `specReplicas 3, updatedReplicas 3, readyReplicas 3, totalReplicas 4` (an old-template pod still present) reports `UpdateInProgress`, not `Ready`; `readyReplicas 2 of specReplicas 3` with no rollout pending reports **`PartiallyReady` with counts 2/3** — the case feature 001 wrote as "unreachable at one replica" is now reachable, update that comment.

### Implementation

- [ ] T020 [US1] `operator/src/main/scala/nakka/operator/ClusterSnapshot.scala`: add `totalReplicas: Int` from `status.replicas`; `absent` sets 0.
- [ ] T021 [US1] `operator/src/main/scala/nakka/operator/LifecycleRules.scala`: after the `updatedReplicas < specReplicas` rule, add `totalReplicas > updatedReplicas` → `UpdateInProgress` with a comment: without it a rollout reports `Ready` while the ready pod being counted is an old one — seen live in feature 003 and hidden by `Recreate` rather than fixed. Update the `PartiallyReady` comment (T019).
- [ ] T022 [P] [US1] `operator/src/main/scala/nakka/operator/Action.scala`: add `EnsureServiceAccount(sa)`, `EnsureRole(role)`, `EnsureRoleBinding(binding)` with `describe` cases.
- [ ] T023 [P] [US1] `operator/src/main/scala/nakka/operator/Names.scala`: `serviceAccount(name) = name`, `peersRole(name) = s"$name-peers"`.
- [ ] T024 [US1] `operator/src/main/scala/nakka/operator/Executor.scala`: the three ensures by server-side apply with the usual field manager and `forceConflicts`. **Remove** feature 003's `strategyRejected` migration and its `PatchContext` imports — but only after T036 shows the reverse move needs no equivalent; if it does, replace rather than remove.
- [ ] T025 [US1] `operator/src/main/scala/nakka/operator/Rendering.scala`, in this order and in one change: delete `Replicas`; replicas from `spec.autoscaling.minInstances` (0 when paused); strategy `RollingUpdate` 1/0 with the comment from T015; `serviceAccountName`; the three identity objects rendered before the Deployment; `management` and `remoting` container ports; the five env vars (selector rendered from the **same hoisted identity value** the Deployment's selector uses); `httpGet /ready` on `management` always, replacing the tcpSocket probe. Keep the "no liveness probe" comment and strengthen it: a restart now also costs a rebalance.
- [ ] T026 [US1] `modules/http/src/main/scala/nakka/http/HttpServer.scala`: register a Pekko Management readiness check — "the HTTP server is bound" — so `/ready` is 200 only once a service that serves HTTP is actually listening. Registered via `pekko.management.health-checks.readiness-checks` in `modules/http`'s `reference.conf`, pointing at a class that reads the bound state; a service with no `HttpServer` extension never registers it, so its readiness is membership alone (FR-024).
- [ ] T027 [US1] `kustomization/components/operator/operator.yaml`: add `serviceaccounts` (core) and `roles`, `rolebindings` (`rbac.authorization.k8s.io`), each `get, list, watch, create, patch`, no `delete`. Comment: the operator already holds `pods: get, list, watch` (feature 001), which is exactly what the Role grants, so no `escalate`/`bind` — and that this is proven under its real identity by T034, because this ClusterRole has been wrong this way twice.
- [ ] T028 [US1] Modify `SchemaInit.script` in `operator/src/main/scala/nakka/operator/SchemaInit.scala` so the DDL and `REVOKE` run inside **one** `psql` session holding `pg_advisory_lock(<constant>)` first (`psql -v ON_ERROR_STOP=1 -c "select pg_advisory_lock(…)" -f a.sql -f b.sql …` is one session). Comment: `CREATE TABLE IF NOT EXISTS` races under concurrent cold start and the loser fails on `pg_type` (research R11). Extend `operator/src/test/scala/nakka/operator/SchemaInitSuite.scala`: the lock statement precedes the first `-f`, in the same command.
- [ ] T029 [US1] `modules/runtime/src/main/scala/nakka/runtime/ViewStore.scala`: take `pg_advisory_xact_lock` in the same transaction as `CREATE TABLE IF NOT EXISTS`, same reason, same comment.

### Cluster tests — `operator/src/test/scala/nakka/operator/OperatorClusterSuite.scala`

Before the existing final test that closes the operator. New service names per case.

- [ ] T030 [US1] Add a `membership(pod)` helper: `execInContainer`-free — fabric8 `exec` in the pod running `wget -qO- http://$POD_IP:7626/cluster/members`, parsed to a `Set[String]` of member addresses with status `Up`. And `disjointClusters(pods): Int` per [contracts/formation-and-rollout.md](./contracts/formation-and-rollout.md) — union overlapping sets, **ignore empty sets**, count components. Unit-test the counting on hand-made sets in a pure suite (`operator/src/test/scala/nakka/operator/MembershipSuite.scala`): `{a,b},{b,c}` → 1; `{a},{b}` → 2; `{a},{}` → 1.
- [ ] T031 [US1] `minInstances = 3`, sample image, `provisionDatabase = false`: three pods `Ready`; `disjointClusters == 1`; every pod's set has size 3; `status.readyInstances == 3`.
- [ ] T032 [US1] The identity objects exist, owned: ServiceAccount, Role, RoleBinding present with the owner reference; delete the `NakkaService`; all three are gone by owner reference within 60s.
- [ ] T033 [US1] **A service's own identity can read pods in its namespace and nothing else** (SC-007): mint a token with `kubectl create token <service> -n <ns>` in the k3s container (feature 003's pattern), build a restricted client; `list pods` in its namespace succeeds; `list pods` in `nakka-operator` → 403; `create`, `patch`, `delete` a pod in its own namespace → 403 each; `list services` in its own namespace → 403.
- [ ] T034 [US1] **The operator's real identity can create the identity objects** (research R10): extend the existing real-ServiceAccount test — with the restricted `Fabric8Executor`, execute `EnsureServiceAccount`, `EnsureRole`, `EnsureRoleBinding` in the probe namespace; all three exist; executing them again succeeds (PATCH of existing). This is the only test that can catch a missing verb — the suite otherwise runs the operator as admin.
- [ ] T035 [US1] Readiness is membership: `pause` with `minInstances = 1` and `progressDeadlineSeconds = 30` — an image with no management endpoint — is **never** `Ready` and ends `Failed` with a non-empty detail (FR-022, replacing feature 003's "port never opens" case, which no longer exists as such).
- [ ] T036 [US1] **The existing-object migration, in reverse**: as feature 003's test does, create a Deployment by hand in the unwatched probe namespace with `strategy: Recreate` (as 003 rendered it), then `Fabric8Executor.execute(ApplyDeployment(<now rendered RollingUpdate>))`; assert the live strategy is `RollingUpdate` with `maxSurge 1`. If the API server rejects it, that is a real finding — add a migration to the executor and keep T024's removal from happening.
- [ ] T037 [US1] Scaling keeps what stays (FR-016, research R5): `minInstances 3 → 5` at a new generation with everything else unchanged; two new pods; the original three keep their names and 0 restarts; `disjointClusters == 1` with sets of size 5. Then `5 → 2`; the survivors are two of the original set.

**Checkpoint**: `sbt operator/test` green. A multi-instance service is one cluster, with an
identity that can do nothing but see its neighbours.

---

## Phase 4: User Story 3 — Deploys and scaling happen without an outage (Priority: P2)

**Goal**: The real sample at three instances, through the real CLI, rolled under load — and at one
instance too.

**Independent Test**: `MultiNodeClusterSuite`.

### The suite — `controlplane/src/test/scala/nakka/controlplane/MultiNodeClusterSuite.scala`

Modelled on `SampleDeploymentClusterSuite` (k3s, CRD, CNPG, image import, in-process operator and
control plane, CLI). Give it its own `munitTimeout` of 15 minutes and note the memory it needs.

- [ ] T038 [US3] Scaffold: org, project, apply `{"name":"cart","service":{"image":"sample-shopping-cart:latest","resources":{"autoscaling":{"minInstances":3}}}}`; `Ready` within 300s, **never `Failed` on the way**; three pods; `disjointClusters == 1` (reuse T030's helper, or move it to a shared test file both suites see).
- [ ] T039 [US3] One entity, wherever the request lands: `POST /carts/c1/items` via the Service's `clusterIP` from the node (feature 003's `nodeHttp`), then read `GET /carts/c1` **directly from each pod's IP** in turn (`wget http://<podIP>:9000/carts/c1` from the node) — all three return the Widget. Then assert `event_journal` has one unbroken `seq_nr` sequence for that persistence id (FR-004: one writer).
- [ ] T040 [US3] Rolling update under load (SC-003): start a background thread issuing `GET /carts/c1` through the Service every 200ms, counting successes, failures and any response without the Widget; `nakka services restart cart`; poll `disjointClusters` every second until the rollout completes and 10s after; assert `disjointClusters` was **never > 1**, success rate ≥ 99%, stale responses == 0, and the pod set is entirely new.
- [ ] T041 [US3] The same at one instance: apply `minInstances = 1` (this crosses the boundary and rolls — expected), settle, then `restart` under the same load; assert the ready-pod count as sampled **never reached 0** and success rate ≥ 99% — feature 003's outage is gone (research R6).
- [ ] T042 [US3] **Twenty simultaneous cold starts** (SC-002, research R4, R11): loop 20×: scale the Deployment to 0 via the k8s client, wait for the pods to be gone, scale to 3, wait `Ready`; each round assert `disjointClusters == 1` **and the sum of init-container restarts across the three pods is 0** (the Postgres race). Report per-round formation time. Budget: ~40s a round.
- [ ] T043 [US3] Confirm T028/T029 by sabotage, by hand, once: temporarily remove the advisory lock from `SchemaInit`, run T042, and see whether init-container restarts appear. Record the answer either way — if the race does not reproduce here, say so and keep the lock anyway with that note.

**Checkpoint**: a real service rolls under load with one cluster throughout, at three instances and
at one.

---

## Phase 5: User Story 4 — The platform survives losing a node (Priority: P2)

**Goal**: crash and partition, done the real way.

- [ ] T044 [US4] Helpers in `MultiNodeClusterSuite`: `sigkill(pod)` — `containerID` → `crictl inspect` on the k3s node for the host pid → `kill -9` via `k3s.execInContainer`; `partition(podIP)` / `heal(podIP)` — `iptables -I FORWARD 1 -s <ip> -p tcp --dport 17355 -j DROP` and the `-d` twin, removed by `-D` (research R9). **Neither uses `kubectl delete`.**
- [ ] T045 [US4] Crash: write to `c2`, find the pod hosting it (the one whose `GET /carts/c2` from its own IP is fastest is not reliable — instead read `/cluster/shards` if available, or simply kill the pod that answered a direct `GET` with the entity's own log line; document the method), `sigkill` it; within 60s `GET /carts/c2` through the Service returns the Widget (SC-004); the killed pod's `restartCount` incremented; `disjointClusters == 1` with size 3 again.
- [ ] T046 [US4] Partition: `partition(one pod)`; within 60s the other two report it `Down`/gone and **still serve** `GET /carts/c1`; the isolated pod's `restartCount` increments (it exited — `exit-jvm`); `heal`; within 60s `disjointClusters == 1` with size 3 (FR-026, FR-027).
- [ ] T047 [US4] The graceful case, for contrast and FR-018: `kubectl delete pod` one pod (via the client, default grace); its member is `Removed` within 10s with **zero** unreachable members reported at any sample; the replacement joins to restore three.
- [ ] T048 [US4] Both suites' `beforeAll` unchanged in spirit: confirm `MultiNodeClusterSuite` respects `munitIgnore` and the image-build gating from feature 003 (FR-038).

**Checkpoint**: a node can be lost three different ways and the service keeps answering.

---

## Phase 6: User Story 5 — The control plane runs as several nodes too (Priority: P3)

**Goal**: the platform on itself.

- [ ] T049 [US5] `kustomization/components/controlplane/controlplane-rbac.yaml`: add a **`Role`** `nakka-controlplane-peers` in `nakka-controlplane` (`pods`: `get, list, watch`) and a `RoleBinding` to the existing ServiceAccount. Update the "still no workload access" comment: still true — a Role does not reach a project namespace.
- [ ] T050 [US5] `kustomization/components/controlplane/deployment.yaml`: `replicas: 3`; strategy `RollingUpdate` 1/0 (replace `Recreate` and its comment); env `NAKKA_CLUSTER_MODE=kubernetes`, `POD_IP` downward, `NAKKA_CLUSTER_SERVICE=nakka-controlplane`, `NAKKA_CLUSTER_POD_SELECTOR=app.kubernetes.io/name=nakka-controlplane`, `NAKKA_CLUSTER_CONTACT_POINTS=2`; ports `management` 7626 and `remoting` 17355; readiness `httpGet /ready` on `management` replacing the tcpSocket. Note the memory: three × 768Mi on a laptop's kind.
- [ ] T051 [US5] `kustomization/deploy-local.sh`: the wait for the control plane already uses `rollout status`; confirm it copes with three replicas and the `RollingUpdate` restart the script itself triggers.
- [ ] T052 [US5] Read `controlplane/src/main/scala/nakka/controlplane/deploy/ServiceProjector.scala` and `ProjectionTrigger.scala` for anything that assumes a single node beyond what research R12 found (the per-node status watch; `projectNow` on the serving node). Record the reading in this task. Change nothing unless something is found.

### The suite — `controlplane/src/test/scala/nakka/controlplane/ControlPlaneClusterSuite.scala`

The control plane itself is deployed into k3s here — image imported like the sample's, manifests
from `kustomization/components/controlplane/` applied — rather than run in-process, because
"three instances" has no meaning in-process. The CLI talks to it through a node-side port-forward
or the Service's `clusterIP`.

- [ ] T053 [US5] Scaffold: import `nakka-controlplane:latest` (add it to the image-build gating beside the sample), apply CRD, CNPG, the control plane's manifests and its schema ConfigMap; three pods `Ready`; `disjointClusters == 1`.
- [ ] T054 [US5] Apply a service through the CLI; **exactly one** `NakkaService` results (trivially) and — the real assertion — the operator-side reconcile count for it, read from the operator's log or from the resource's `managedFields` update count, does not scale with control-plane instances: apply five services, assert the projector wrote each **once**.
- [ ] T055 [US5] A status report seen by three nodes is recorded once (FR-034, SC-009): with the operator writing status, `psql` the control plane's `event_journal` for `ServiceObserved` events of one service; take a count, wait 30s of steady state, count again — **unchanged**; then force a status change and assert the count rises by **exactly one**.
- [ ] T056 [US5] Replace a control-plane pod under load (FR-035): a background loop of `nakka services list` and one `apply` every 2s; `kubectl delete` one control-plane pod; assert every command succeeded and the applied services all exist.

**Checkpoint**: the platform runs three-wide on itself, doing each thing once.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T057 [P] `CLAUDE.md`: **replace** the "`strategy: Recreate`, always" trap and the "never more than one replica" trap with what is now true — one cluster per service; `RollingUpdate` at every count; the two-pod overlap is now *the mechanism*, not a bug — keeping each one's history in a sentence. Add traps: an env var cannot be a HOCON list; `kubectl delete --force` is not a crash; the management port's *name* is load-bearing; "exiting node marked UNREACHABLE" is expected; `exit-jvm` is required for a downed node to restart.
- [ ] T058 [P] `CLAUDE.md`: describe the three-file layering under Architecture (base says nothing about peers; overlays; `NAKKA_CLUSTER_MODE`; why not `-Dconfig.resource`), the new dependencies, and the local two-node recipe under Commands.
- [ ] T059 [P] `README.md`: rewrite "Zero-downtime deploys" and "Multi-replica services" in Not-implemented into what exists; document `minInstances`, the odd-count recommendation, `NAKKA_CLUSTER_PORT`/`NAKKA_CLUSTER_SEED_NODES` for local multi-node; state plainly what is still missing: no autoscaler, two instances cannot survive a partition, no network isolation or encryption of cluster traffic.
- [ ] T060 [P] `specs/003-deploy-real-service/`: add a note at the top of `contracts/service-object.md` and `research.md` R6 that `Recreate` and "a deploy is a brief outage" were superseded by feature 004, with a link — so a reader of 003 is not misled.
- [ ] T061 `sbt scalafmtAll scalafmtSbt`; `sbt compile` and `Test/compile` warning-free under `-Wunused`.
- [ ] T062 Seams: `crd` still depends on nothing; `cli` on `controlplane-api` alone; no new Kubernetes client dependency anywhere but where fabric8 already was.
- [ ] T063 Full `sbt test`, then the reviewer's checklist in [quickstart.md](./quickstart.md) in full, including Tier 6 on `kind-nakka` — and re-apply that cluster's existing services, which will roll once onto the new template.

---

## Dependencies & Execution Order

- **Setup (1)** → **US2 (2)** → everything. US2 *is* the foundation; naming it a story is honest,
  because it has a user-visible guarantee (nothing changes locally) and its own independent test.
- **US1 (3)** after US2 — the operator's env vars mean nothing to a runtime that ignores them.
- **US3 (4)** after US1; **US4 (5)** after US3 (its helpers live in US3's suite).
- **US5 (6)** after US1 and US2; independent of US3/US4 and could run beside them by a second person.
- **Polish (7)** last.

### Within phases

- T007, T008, T009 are one change — the base loses what the overlays gain. Never land T007 alone.
- T024's removal waits for T036's answer.
- T025 is one large edit to `Rendering.scala`; T015–T017 are its tests and precede it.
- T030's helper precedes every cluster test that counts clusters (T031, T037, T038, T040–T046, T053).
- T042 before T043; T043 is manual and recorded, not automated.

### Parallel opportunities

- US2 tests T003–T006 (one new file — one person); T008 ∥ T009.
- US1 pure tests T015–T019 across three files; T022 ∥ T023.
- Polish T057/T058 (one file — one person) ∥ T059 ∥ T060.

---

## Implementation strategy

**Stop after US2** and run everything. If any existing suite changed behaviour, the layering is
wrong, and nothing built on it is worth building yet. T013 is the moment the two ground-level
unknowns become known.

**US1 is the MVP.** A multi-instance service that is one cluster, with a least-privilege identity
proven under real tokens. Shippable on its own; `RollingUpdate` in it is already safe by research R6.

**US3 and US4 are where surprises live** — a rolling update under real load, a JVM `SIGKILL`ed, an
`iptables` partition. Budget time for the suite to be slow and for one of these to find something,
as feature 003's walkthrough did.

**US5 last**, and mostly measurement: the control plane was found nearly ready; the suite exists to
turn "nearly" into a number.
