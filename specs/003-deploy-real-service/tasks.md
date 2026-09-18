# Tasks: Deploy a Real nakka Service

**Input**: Design documents from `specs/003-deploy-real-service/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Included. The spec makes an automated proof a requirement in its own right (FR-017 to
FR-020), and this repository's standard is that anything a fake would simply agree with is tested
against a real cluster.

**Organization**: By user story. US1 is shippable without US2 — it is provable with any image that
listens — which is the point of the split: the platform change is de-risked before the awkward build
ordering is touched.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable — a different file, and no dependency on an incomplete task
- **[Story]**: the user story a task serves (US1, US2, US3)

## Read before starting

Three traps the plan found by running things. Each has cost, or would cost, a debugging session:

- **`"port": null` can never mean "no port"** — on the first design's `Option[Int]` it silently parsed as 9000 (research R4); on today's `Int` it is a decode error. "Serves no HTTP" is
  `"http": false`. Do not reintroduce an `Option[Int]` on the descriptor.
- **Kubernetes defaults `imagePullPolicy` to `Always` for a `:latest` tag** (research R2). A locally
  built image sitting in the node's containerd is ignored and the pod fails `ErrImagePull`.
- **The CRD's Scala model and its OpenAPI schema must change together** (feature 002 shipped one
  without the other; a real cluster rejected every write). T006 and T007 are one change.

---

## Phase 1: Setup

**Purpose**: A known-green starting point, so a failure later is attributable.

- [X] T001 Confirm the baseline: `sbt -Dnakka.cluster.tests=off test` passes and `sbt compile` is warning-free, before any change. Record the test count.
  - Baseline green: 531 tests across 12 modules. **But it found a pre-existing bug**: `-Dnakka.cluster.tests=off` was passed and the k3s suites ran anyway (417s). `Test / fork := true` means tests run in a forked JVM that never inherits sbt's `-D` properties, so the switch documented in `CLAUDE.md` and both earlier specs had been a silent no-op since feature 001. FR-020 depends on it, so fixed here: `Test / javaOptions` now forwards the property. Verified — `controlPlane/test` with the switch off takes 52s with `EndToEndClusterSuite` reported Ignored.

---

## Phase 2: Foundational — the port, through every layer it crosses

**Purpose**: One value, resolved once, carried from the descriptor to the custom resource. Nothing is
rendered from it yet, so this phase changes no deployed behaviour at all — which is what makes it
safe to land first.

**⚠️ CRITICAL**: US1 and US2 both depend on this phase.

### Tests

- [X] T002 [P] Extend `controlplane-api/src/test/scala/nakka/controlplane/api/DescriptorSuite.scala` with the resolution table from [contracts/port-resolution.md](./contracts/port-resolution.md): neither field → `resolvedPort == Some(9000)`; `"port": 8080` → `Some(8080)`; `"http": false` → `None`; `"http": false, "port": 8080` → `None` and **not** a problem. Decode from JSON strings, not constructed values, so the codec is what is under test.
- [X] T003 [P] In the same suite: **`{"port": null}` decodes to `resolvedPort == Some(9000)`**, with a comment saying why the assertion exists — it pins the jsoniter behaviour that made `Option[Int]` unrepresentable, so nobody reintroduces it. Also assert a `ServiceSpec(http = false)` survives a write-then-read round trip as `http = false`.
  - The test caught a difference from the plan on its first run. With `port` a plain `Int`, `{"port": null}` is not silently defaulted to 9000 (that was the `Option[Int]` behaviour that killed the first design) — it is a **loud `JsonReaderException`**. Strictly better, so the test now pins the refusal, with the history in its comment.
- [X] T004 [P] In the same suite, the two validation rules: a port of `0`, `-1` and `65536` each produce `service port <n> is outside the range 1-65535`; an `env` entry named exactly `NAKKA_HTTP_PORT` produces the conflict problem, **including when `http` is `false`**, and whether its value is literal or a `secretKeyRef`; an unrelated `NAKKA_HTTP_INTERFACE` does not. Assert both problems arrive together in one `problems` result.

### Implementation

- [X] T005 Add `http: Boolean = true` and `port: Int = 9000` to `ServiceSpec` in `controlplane-api/src/main/scala/nakka/controlplane/api/descriptors.scala`, plus `def resolvedPort: Option[Int] = Option.when(http)(port)`, and the two rules in `ServiceSpec.problems`. Doc-comment *why* the no-HTTP case is a boolean (research R4) and why `0` was rejected (`HttpServer.at` already gives it a meaning).
- [X] T006 Add `port: Option[Int] = None` to `NakkaServiceSpec` in `crd/src/main/scala/nakka/crd/NakkaService.scala`. `None` is the default deliberately — a resource written before this feature must keep behaving as it does today. Extend `crd/src/test/scala/nakka/crd/NakkaServiceCodecSuite.scala`: present round-trips; absent decodes as `None`; `None` is omitted from the JSON entirely.
  - Added `@JsonDeserialize(contentAs = classOf[java.lang.Integer])`: Scala's `Option[Int]` erases to `Option[Object]`, so without the hint Jackson chooses the boxed type from the JSON rather than the field. The round-trip test forces an unboxing (`port.map(_ + 1)`) so a `ClassCastException` cannot hide until the operator renders.
- [X] T007 Add `port` (`type: integer`, `minimum: 1`, `maximum: 65535`, not required) to the spec schema in `kustomization/components/crd/nakkaservice.yaml`. **Same change as T006 — do not split them.**
- [X] T008 Project the resolved port in `controlplane/src/main/scala/nakka/controlplane/deploy/ServiceProjection.scala` (`port = descriptor.service.resolvedPort`), and extend `controlplane/src/test/scala/nakka/controlplane/ServiceProjectionSuite.scala`: default → `Some(9000)`; explicit → kept; `http = false` → `None`.
- [X] T009 Give every descriptor that deploys a non-listening image `"http": false` — `descriptorJson` and `applySupplied` in `controlplane/src/test/scala/nakka/controlplane/EndToEndClusterSuite.scala`, with a comment that the workload is irrelevant to that suite's subject so this is the honest descriptor, not a workaround (research R12). Harmless now; essential the moment T017 lands.

**Checkpoint**: `sbt -Dnakka.cluster.tests=off test` green. The custom resource carries a port; nothing renders from it.

---

## Phase 3: User Story 1 — A deployed service can be reached (Priority: P1) 🎯 MVP

**Goal**: A service that declares a port gets a container port, the runtime's port variable, a
readiness probe and a `ClusterIP` Service — all from one value — and is not `Ready` until the port
is open. A service that serves no HTTP gets none of them and still reaches `Ready`.

**Independent Test**: Write a `NakkaServiceSpec` with a port and an image that listens on it
(`nginx` on 80); a Service appears with a live endpoint, and `Ready` follows the port. No sample
image needed.

### Tests (pure — write first, watch them fail)

- [X] T010 [P] [US1] New `operator/src/test/scala/nakka/operator/ServiceRenderingSuite.scala`: the rendered Service's `spec.selector` **equals** `deployment.getSpec.getSelector.getMatchLabels` from the same spec (compare the two rendered objects, not two calls to `Labels.identity`); name is `Names.service`; type `ClusterIP`; one port named `http` with `port == targetPort ==` the resolved value; owner reference carries the resource's uid; metadata labels are the merged labels.
- [X] T011 [P] [US1] In the same suite: `Rendering.render` emits `EnsureService` and no `RemoveService` when `port` is `Some`; emits `RemoveService(namespace, name, ownerUid)` and no `EnsureService` when it is `None`; rendering twice is identical.
- [X] T012 [P] [US1] Extend `operator/src/test/scala/nakka/operator/RenderingSuite.scala`: with `port = Some(8080)` the container has one `containerPort` 8080 named `http`, an env `NAKKA_HTTP_PORT=8080`, and a `tcpSocket` readiness probe on 8080 with `initialDelaySeconds` 10 / `periodSeconds` 5; with `None` all three are absent; **no liveness probe in either case**; and **`imagePullPolicy` is `IfNotPresent` in both cases**.

### Implementation

- [X] T013 [P] [US1] Add `def service(serviceName: String): String = serviceName` to `operator/src/main/scala/nakka/operator/Names.scala`, extending the existing comment: the Deployment, the container, the resource *and the Service* share the name.
- [X] T014 [P] [US1] Add `EnsureService(service: io.fabric8.kubernetes.api.model.Service)` and `RemoveService(namespace: String, name: String, ownerUid: String)` to `operator/src/main/scala/nakka/operator/Action.scala`, with `describe` cases. Doc-comment `RemoveService`: it carries the uid because the executor must never delete a Service the resource does not own.
- [X] T015 [US1] In `operator/src/main/scala/nakka/operator/Rendering.scala`, render `imagePullPolicy: IfNotPresent` on the workload container unconditionally, with a comment recording the `:latest` → `Always` default and that this matches what the platform's own manifests already do for themselves (research R2). Revisit note: becomes a descriptor field the day a registry exists.
- [X] T016 [US1] In the same file, add `def service(resource, spec, namespace, port: Int): Service` per [contracts/service-object.md](./contracts/service-object.md). The selector must be **the same `identity` value** the Deployment's selector is built from — hoist it so both read one `val`, rather than calling `Labels.identity` twice.
- [X] T017 [US1] In the same file, thread `spec.port` into `container`: `containerPort`, injected `NAKKA_HTTP_PORT`, and the `tcpSocket` readiness probe, all from the one `Option[Int]`; nothing when `None`. Then have `render` append `EnsureService` or `RemoveService`. Do **not** touch `LifecycleRules` — `readyReplicas` already counts only probe-passing pods, which is the whole mechanism (research R6).
- [X] T018 [US1] In `operator/src/main/scala/nakka/operator/Executor.scala`, implement both actions in `Fabric8Executor.execute`. `EnsureService`: server-side apply, same field manager and `forceConflicts` as every other ensure. `RemoveService`: **get first**; delete only if the Service exists *and* its `ownerReferences` contains `ownerUid`; otherwise do nothing. Reading first is what keeps steady state silent for a service that never served HTTP.
- [X] T019 [US1] Add the `services` rule — `get, list, watch, create, patch, delete` — to the `ClusterRole` in `kustomization/components/operator/operator.yaml`, and extend the header comment: `delete` is granted here, unlike on the CNPG kinds and `secrets`, because a Service holds no data, and the one action using it is guarded on ownership. `patch` is required, not optional (server-side apply is always a PATCH).

### Cluster tests — `operator/src/test/scala/nakka/operator/OperatorClusterSuite.scala`

Insert before the existing test "11", which closes the operator and must stay last. Use a new
service name per case so nothing collides with the database tests.

- [X] T020 [US1] A spec with `image = "nginx:1.27-alpine"`, `port = Some(80)`, `provisionDatabase = false` reaches `Ready`; a Service of that name exists, `ClusterIP`, port 80; its `Endpoints` object has one ready address (the selector really matches the pod); and a `wget` **from the k3s node to the Service's `clusterIP`** returns nginx's welcome page. Reach it by IP — the node does not resolve cluster DNS (research R11).
- [X] T021 [US1] **`Ready` follows the port, not the process** (SC-003): `pause` with `port = Some(9000)` and `progressDeadlineSeconds = 30`. Poll the status throughout: it must **never** report `Ready`, and must end `Failed` with a non-empty detail — via the Deployment's own deadline, not a new clock (FR-013).
- [X] T022 [US1] `pause` with `port = None` reaches `Ready`, has no container port and no probe, and **no Service exists** for it (FR-012, SC-004).
- [X] T023 [US1] Turning HTTP off removes the address: re-write T020's service with `port = None` at a new generation; its Service disappears. Then the guard: hand-create an unowned Service named after T022's service, reconcile again (re-write its spec), and assert it **survives**.
- [X] T024 [US1] Steady state is silent: record T020's Service `resourceVersion`, re-apply the identical spec ten times as the existing idempotence test does, and assert it is unchanged.
- [X] T025 [US1] **The grant is proven, not assumed.** Extend the existing real-ServiceAccount test (the one minting a token with `kubectl create token nakka-operator`): using that same restricted client, create a Service in the test namespace and assert it succeeds; then patch and delete it. This is the only test that would have caught the missing `services` rule — the rest of the suite runs the operator with admin credentials.
  - Made stronger than specified: rather than a bare `create`, the **real `Fabric8Executor`** runs under the operator's real ServiceAccount token, in a namespace the in-process operator does not watch. The executor writes by server-side apply — a PATCH — so this is what would catch "`create` granted but not `patch`", the mistake this ClusterRole has made before. Also asserts `RemoveService` leaves a Service alone for an owner it does not have.

**Checkpoint**: `sbt operator/test` green. A deployed service is reachable, and `Ready` means the port is open. US1 is complete and demonstrable without any sample image.

---

## Phase 4: User Story 2 — A real nakka application runs on the platform (Priority: P1)

**Goal**: The shopping cart, built as an image by the project's own build, deployed through the real
CLI with nothing in its descriptor but an image, serving requests from a platform-provisioned
database.

**Independent Test**: `SampleDeploymentClusterSuite` — POST an item, GET it back, delete the pod, GET
it again.

### Packaging

- [X] T026 [US2] In `build.sbt`, give `shoppingCart` `.enablePlugins(JavaAppPackaging, DockerPlugin)`, `.settings(dockerSettings)` and `Compile / mainClass := Some("runShoppingCart")` — the class `@main def runShoppingCart()` compiles to (verified). Explicit for the same reason the operator's is.
- [X] T027 [US2] **Verify the image really builds** (research R13, unverified during planning): run `sbt shoppingCart/Docker/publishLocal`, then `docker images sample-shopping-cart`. `shoppingCart` sets `publish / skip := true`, which `Docker / publishLocal / skip` delegates to, and some sbt-native-packager versions honour it — the task would then succeed and build nothing. If no image appears, add `Docker / publish / skip := false`. Record what you found in a comment either way.
  - **Resolved: it builds.** `publish / skip := true` does not suppress `Docker / publishLocal` on sbt-native-packager 1.11.7. Both tags produced, 649MB. No `Docker / publish / skip` override needed.
- [X] T028 [US2] Confirm `sbt docker:publishLocal` at the root now builds **three** images with nothing named (FR-015), then add `kind load docker-image sample-shopping-cart:latest --name "$CLUSTER_NAME"` to `kustomization/deploy-local.sh` and update its header comment, which still says two images (FR-016).

### Getting the image into k3s

- [X] T029 [US2] New helper in `controlplane/src/test/scala/nakka/controlplane/ClusterImages.scala`: `importInto(k3s: K3sContainer, image: String): Unit` — save the image to a temp tar (testcontainers' own Docker client, `saveImageCmd`, rather than shelling out), `copyFileToContainer`, then `execInContainer("ctr", "-a", "/run/k3s/containerd/containerd.sock", "-n", "k8s.io", "images", "import", <tar>)`, asserting exit code 0. All three of `ctr` (not `k3s ctr`), the socket address and `-n k8s.io` are load-bearing; comment each with its failure mode from [contracts/packaging-and-rbac.md](./contracts/packaging-and-rbac.md). No `--platform` flag.
- [X] T030 [US2] In the same helper: if the image is absent from the local Docker daemon, **fail with an actionable message** naming `sbt shoppingCart/Docker/publishLocal` — `testOnly` bypasses the build wiring in T031, and a missing image must not present as a three-minute timeout.
- [X] T031 [US2] In `build.sbt`, the build ordering from research R9: a helper `taskKey[Unit]` whose `taskDyn` does nothing when `sys.props.get("nakka.cluster.tests").contains("off")` and otherwise runs `shoppingCart / Docker / publishLocal`; then `controlPlane`'s `Test / test := (Test / test).dependsOn(thatTask).value`. The `taskDyn` goes on the **helper**, never on `Test / test` itself — a self-reference inside `taskDyn` is a cycle. Comment that this is a build-level task dependency, not a classpath one.

### The suite — `controlplane/src/test/scala/nakka/controlplane/SampleDeploymentClusterSuite.scala`

Modelled on `EndToEndClusterSuite`'s `beforeAll` (k3s, CRD, CNPG, in-process operator and control
plane, CLI via `Main.run`), same `munitIgnore` switch, plus `ClusterImages.importInto`. Set the
deploy config's progress deadline to 170s — a project's first service, as feature 002 established.

- [X] T032 [US2] Scaffold the suite and its first case: create an org and project through the CLI; apply `{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}` — **nothing about databases or ports**; wait for `Ready`, asserting throughout that it is never `Failed` on the way; assert `status.database.phase == "Provisioned"`.
- [X] T033 [US2] A `nodeHttp` helper in the suite: resolve the `cart` Service's `clusterIP` through fabric8, then `k3s.execInContainer("wget", "-qO-", …)` with `--header` and `--post-data` for writes. **Not a port-forward** — that goes API server → pod and bypasses the Service, so it could not catch a wrong selector (research R11). Comment that.
- [X] T034 [US2] Use it: `POST /carts/c1/items` with `{"productId":"p1","name":"Widget","quantity":2}`, then `GET /carts/c1` contains `Widget` and `GET /carts/c1/total` is `2`.
- [X] T035 [US2] Durability: delete the workload's pod, wait for the replacement to be `Ready`, `GET /carts/c1` — the same item. It was in Postgres, not in memory (SC-002).
- [X] T036 [US2] The events are where they should be: exec `psql` in `nakka-db-1` and assert `event_journal` in the **`cart`** database holds rows for the cart's persistence id, and that the tables are owned by `cart`.
  - Also added case 4, "a restart never runs two pods at once" — the cluster-level half of the Recreate fix below.
- [X] T037 [US2] Run the manual walkthrough (quickstart Tier 4) against `kind-nakka`: `deploy-local.sh`, apply the sample, `curl` it through a port-forward, delete the pod, `curl` again. First re-apply any existing `pause` services in that cluster with `"http": false`, or they go un-`Ready` (research R12).
  - Done against `kind-nakka`: `POST` → 204, `GET` → the cart with its Widget, pod deleted, same cart back. **It also found three real bugs no suite could**, all now fixed and tested:
    1. **Workloads were rendered with no rollout strategy**, so Kubernetes' default `RollingUpdate`/`maxSurge 25%` ran the old and new pod *side by side* on every deploy and restart — two single-node clusters over one journal, the exact thing "never more than one replica" forbids. Invisible with `pause` (no journal) and no probe (a one-second overlap). Now `strategy: Recreate`; pinned by `RenderingSuite` and by `SampleDeploymentClusterSuite` case 4.
    2. **That fix wedged every existing Deployment**: the API server rejects `Recreate` while the defaulted `rollingUpdate` block remains, and server-side apply cannot remove a field no manager owns — `invalid: spec.strategy`, retried forever. Only an *existing* object shows it. `Fabric8Executor` now merge-patches the strategy once, on that exact 422. Pinned by `OperatorClusterSuite` case 26; seen to work live (`migrating deployment nakka-checkout/cart from RollingUpdate to Recreate`).
    3. **Re-running `deploy-local.sh` never rolled pods onto rebuilt images** — unchanged manifest, same `:latest` tag, so nothing rolled and five-hour-old code kept running while every line reported success. The script now `rollout restart`s both.
  - Also: the false `Ready` mid-rollout (the ready replica being counted was the *old* pod) is closed by (1) — under `Recreate` the old pod is gone first, so `LifecycleRules` is still untouched. And the sample shipped no `logback.xml`, so a deployed pod logged the whole r2dbc wire protocol at DEBUG; it now has one. **The operator and control plane images have the same gap** — left alone as out of scope, flagged to the user.

**Checkpoint**: A real nakka application runs on the platform, serves HTTP, and survives a restart.

---

## Phase 5: User Story 3 — The proof survives future changes (Priority: P2)

**Goal**: The suite fails when the thing it guards breaks — shown by breaking it.

**Independent Test**: Sabotage the rendering; the suite goes red.

- [X] T038 [US3] **Mutation check, by hand** (SC-006): temporarily make the Service's `targetPort` `port + 1` in `Rendering.scala`; confirm `SampleDeploymentClusterSuite` **and** T020 fail. Revert. Then temporarily drop one key from the Service's selector; confirm both fail again. Revert. Record in this task what failed and how — a guard nobody has seen fail is a hope.
  - **Both sabotages caught at both levels.** `targetPort = port + 1`: `ServiceRenderingSuite` 2 failures in milliseconds; `SampleDeploymentClusterSuite` 3 of 4 failed — and tellingly case 1 still *passed*, the pod `Ready` behind a dead address, which is precisely what a port-forward-based test would have waved through. Wrong selector value: `ServiceRenderingSuite` 3 failures; `OperatorClusterSuite` case 21 failed on the Endpoints check. (Dropping a selector *key*, as first planned, is not a valid sabotage for the cluster suite — it broadens the selector and the service still works; only the pure object-to-object comparison catches that one, which is why it exists.) Reverted, byte-identical.
- [X] T039 [US3] Confirm the switch (FR-020): `sbt -Dnakka.cluster.tests=off controlPlane/test` builds **no** image (watch for the absence of native-packager's Docker output) and skips all cluster suites; plain `sbt controlPlane/test` builds it.
  - Off: no image built (verified with the image deliberately absent), 2 suites Ignored, 52s. On: the absent image was built by the run itself, then 135 passed. Depends on the fork fix recorded under T001.
- [X] T040 [US3] Confirm T030's message: remove the local image (`docker rmi sample-shopping-cart:latest sample-shopping-cart:0.1.0-SNAPSHOT`), run the suite via `testOnly`, and see the actionable failure rather than a timeout. Rebuild afterwards.
  - Confirmed: fails in 12s with the exact `sbt shoppingCart/Docker/publishLocal` instruction, rather than a three-minute timeout.

**Checkpoint**: The chain is guarded, and the guard has been seen to work.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T041 [P] `README.md`: document `http` and `port` where the descriptor is described; add the in-cluster address (`<service>.<namespace>.svc.cluster.local`); update "No image registry" to three images; and add to "Not implemented", plainly: no ingress/TLS/external reachability; readiness means "the port is open", not "healthy"; **no network isolation between projects** — a `ClusterIP` is reachable from every namespace.
- [X] T042 [P] `CLAUDE.md`, "Traps": the `imagePullPolicy` / `:latest` default; containerd's `-n k8s.io` namespace (imports fine, lists fine, kubelet blind); **jsoniter reads `null` as absent and applies the default**, so an `Option` with a non-`None` default cannot express "none"; port-forward bypasses a Service; the k3s node reaches `clusterIP`s but not cluster DNS. Also note under module dependencies that `controlPlane`'s *tests* build the sample image — a task dependency, not a classpath one.
- [X] T043 [P] `CLAUDE.md`, "Commands" and "Deploying locally": three images; the new suite and its ~700MB import; `sbt shoppingCart/Docker/publishLocal`.
- [X] T044 [P] `specs/002-cnpg-database-provisioning/quickstart.md`: its walkthrough descriptor deploys `pause` and now needs `"http": false`, with one line saying why.
- [X] T045 `sbt scalafmtAll scalafmtSbt`; `sbt compile` warning-free under `-Wunused`.
- [X] T046 Seams: `crd` still depends on no nakka module; `cli` on `controlplane-api` alone; `controlPlane` has gained **no** compile- or test-scope dependency on `shoppingCart` (`sbt 'show controlPlane/Test/internalDependencyClasspath'` should not list it).
- [X] T047 Full `sbt test`, then the reviewer's checklist in [quickstart.md](./quickstart.md), in full.
  - Full `sbt test`: **578 tests, 0 failed** (baseline 531, +47), 10m47s, building the sample image itself on the way. `scalafmtCheckAll`/`scalafmtSbtCheck` clean; `compile` and `Test/compile` warning-free. Reviewer's checklist walked: every item holds, with one wording change — the checklist's "grants `services` without `delete`" was corrected during plan review to "grants `services`, proven with the operator's real ServiceAccount, and `RemoveService` refuses a Service the resource does not own".

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (1)** → **Foundational (2)** → everything else
- **US1 (3)** after Foundational. No dependency on US2.
- **US2 (4)** after US1 — it needs an address to send a request to, and `IfNotPresent` to start at all
- **US3 (5)** after US2 — it sabotages what US1 and US2 built
- **Polish (6)** last

**Honest summary, as in features 001 and 002**: independently *testable and demonstrable*, not
independently implementable. US1 is the genuine exception — complete and shippable on its own.

### Within phases

- T006 and T007 are one change (model + schema). Never split.
- T009 must land **before T017**. After T017, a `pause` descriptor without `"http": false` never goes `Ready`.
- T015–T017 all edit `Rendering.scala` — sequential.
- T020–T025 all edit `OperatorClusterSuite.scala` — sequential, and T023 depends on T020 and T022.
- T027 before anything else in US2: if the image does not build, nothing after it means anything.
- T026/T031 both edit `build.sbt` — sequential.

### Parallel opportunities

- Foundational tests: T002, T003, T004 (one file, but independent cases — parallel only across people if coordinated; otherwise sequential)
- US1 pure tests: T010/T011 (new file) alongside T012 (existing file)
- US1: T013 and T014 (different files) alongside each other, before T015
- Polish: T041, T042/T043 (same file — one person), T044

---

## Implementation strategy

### MVP — US1 alone

Phases 1–3. Stop and validate: a service with a port is reachable from inside the cluster, and
`Ready` means the port is open. This is a real platform improvement even if the sample is never
packaged — and it is where the two blocking findings (RBAC, `imagePullPolicy`) get fixed.

### Then US2

The first real workload. Expect this to be where surprises live: it is the first time the runtime's
database connection, the applied schema, self-join clustering and HTTP serving meet in a pod. If it
fails, the init container's logs and the workload's own logs are the first two places to look — the
sample now has a logging backend in-cluster precisely so that is possible.

### Then US3 and Polish

Short, and not optional: T038 is the difference between a suite that guards the chain and one that
merely passes.
