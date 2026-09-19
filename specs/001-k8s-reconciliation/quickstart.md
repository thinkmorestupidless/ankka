# Quickstart: Validating Kubernetes Service Reconciliation

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

How to prove this feature works, fastest signal first. These are the runs a reviewer performs;
implementation detail belongs in `tasks.md`.

---

## Prerequisites

| Need | For | Notes |
|---|---|---|
| JDK 21, sbt | everything | already required |
| Docker | integration and cluster suites | already required by the Postgres and Kafka suites |
| A Kubernetes cluster | the manual walkthrough only | Docker Desktop, k3d, kind or minikube; the automated suites start their own |
| `kubectl` | the manual walkthrough only | `kubectl get asvc` is a first-class debugging surface here |

No API key. No cloud account.

---

## Tier 1 — Pure logic (seconds, no Docker)

Rendering, projection and classification are total functions over data, so every rule about drift,
staleness, ordering and failure is provable with no cluster and no database.

```bash
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.RenderingSuite'
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.LifecycleRulesSuite'
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.ServiceProjectionSuite'
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.StatusIngestSuite'
```

**Expected**: all pass in well under a second each; no container starts.

| Suite | Proves |
|---|---|
| `RenderingSuite` | FR-012 to FR-017. Determinism (render twice, assert equal); the selector never contains the generation; `spec.replicas` is 1 and **no HPA is emitted**; owner reference carries the resource's uid; identity labels cannot be overridden. See [contracts/operator-actions.md](./contracts/operator-actions.md). |
| `LifecycleRulesSuite` | FR-019 to FR-023. One case per row of [contracts/observation-rules.md](./contracts/observation-rules.md), including both ordering traps — pause beats failure, `observedGeneration` beats `updatedReplicas` — and that an unchanged status produces no write. |
| `ServiceProjectionSuite` | FR-005. A `Service` maps to a spec; an invalid project id is a reported problem, not an exception; an unchanged service projects identically. |
| `StatusIngestSuite` | FR-008, FR-030, FR-031. All three cases: unreachable, resource-with-no-status, and a real status; and that a stale generation is passed through for the fold to drop rather than second-guessed here. |

---

## Tier 2 — The control plane, with Postgres and a fake cluster (minutes, Docker)

```bash
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.ProjectorSuite'
```

**Expected**: one Postgres container, the real control plane through `AnkkaTestKit` against
`FakeAnkkaServiceClient`.

- **FR-006** — applying projects immediately via the journal consumer, *and* the periodic sweep
  alone converges a service the consumer never saw.
- **FR-005 / SC-003** — a steady-state service over many sweeps performs **zero** writes to the
  fake and records **zero** observations. Assert on the journal and the fake's write log.
- **FR-007** — `driftEdit` the resource; assert the control plane restores its own record.
- **FR-032 / FR-030** — `failNext` makes the client throw; assert backoff spacing, then exactly
  *one* `confirmed = false` observation no matter how many sweeps failed, then recovery.
- **FR-031** — `clearStatus`; assert the service reports unconfirmed with "no operator has
  reported", distinct from a rollout.
- **FR-009** — delete while `failNext` is active; assert the resource is deleted once the client
  recovers.
- **SC-007** — `testKit.restartService()` mid-projection; assert convergence and exactly one
  resource per service.

---

## Tier 3 — The operator against a real cluster (slower, Docker)

```bash
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.OperatorClusterSuite'
```

Starts k3s, installs the CRD **from the shipped manifest** (so FR-039's install path is exercised,
not bypassed), runs the operator, and writes `AnkkaService` resources directly.

What only a real API server can prove:

- The rendered objects are **accepted** — a fake would happily agree with a spec Kubernetes rejects.
- **Selector immutability**: apply, then apply again at a new generation, and assert the second is
  *accepted*. This is the regression test for putting a changing value in the selector.
- **Owner-reference cascade**: delete the resource, assert the Deployment disappears **without the
  operator doing anything**. This is the test that the design's central simplification is real.
- **Drift enforcement** (FR-017): edit the Deployment's image out of band; assert it is reverted.
- **Pause** scales to zero and retains configuration; **resume** restores.
- **A bad image tag** reaches `Failed` with an `ImagePullBackOff` detail within a short progress
  deadline (FR-021).
- **Status is written to the subresource only** — assert the operator's service account cannot
  update `ankkaservices` itself (FR-003).
- **An unknown `apiVersion`** is reported and not acted on (FR-004).

---

## Tier 4 — Both halves, end to end (slowest)

```bash
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.EndToEndClusterSuite'
```

Postgres **and** k3s, the real control plane and the real operator, driven through the CLI's
`Main.run`. This is the only test that catches the two sides disagreeing about the custom resource
— the same argument the repository already makes for `controlplane` taking `cli % Test`.

Walks US1 through US4: apply → `Ready`; change the image → rolls; pause, resume, restart; delete
out of band → healed; delete → nothing left.

To run everything except the cluster suites:

```bash
sbt -Dankka.cluster.tests=off test
```

A system property rather than a munit tag: `--exclude-tags` filters tests but still runs
`beforeAll`, which is where the k3s container starts — so it would skip the assertions and pay
the whole cost anyway.

---

## Tier 5 — Manual walkthrough

```bash
# install into whatever cluster KUBECONFIG points at
kubectl apply -f operator/src/main/resources/ankka/crd/ankkaservice.yaml
kubectl apply -f operator/src/main/resources/ankka/install/operator.yaml

docker compose up -d
ANKKA_CONTROLPLANE_TOKEN=$(openssl rand -hex 16) sbt controlPlane/run

ankka config set url http://localhost:9000
ankka config set token "$ANKKA_CONTROLPLANE_TOKEN"
ankka organizations create acme --name "Acme Corp"
ankka projects create checkout --name Checkout -O acme
ankka config set project checkout
ankka services apply -f cart.json
```

Watch both sides — and note the third view the resource gives you for free:

```bash
ankka services list
kubectl -n ankka-checkout get asvc,deploy,pods
kubectl -n ankka-checkout describe asvc cart      # spec and status side by side
```

| Action | Expected |
|---|---|
| apply | `Ready` `1/1` within about a minute |
| `kubectl -n ankka-checkout delete deploy cart` | recreated within ~10s (the operator watches owned objects) |
| `kubectl -n ankka-checkout edit asvc cart` (change the image) | the control plane restores its own record (FR-007) |
| `ankka services pause cart` | `Paused` `0/0`, no pods, configuration retained |
| `ankka services restart cart` | generation bumps, pod replaced, back to `Ready` |
| apply with a nonexistent image tag | `Failed` with an `ImagePullBackOff` detail |
| `kubectl delete deploy -n ankka-operator ankka-operator` | services become `Ready (unconfirmed)` with "no operator has reported" (FR-031) |
| stop the cluster, then `ankka services list` | `Ready (unconfirmed)` — not a silent stale `Ready` |
| stop the cluster, then `ankka services apply` | **succeeds**; intent durable; deploys when the cluster returns |
| `ankka services delete cart` | resource gone, Deployment and pods gone with it |

**A deployed service needs a database.** An ankka service is event-sourced and will not start
without Postgres carrying the journal, projection and timer tables. Supply it through the
descriptor's `env`, one database *per service*:

```json
{ "name": "ANKKA_DB_HOST",     "value": "postgres.default.svc" },
{ "name": "ANKKA_DB_NAME",     "value": "cart" },
{ "name": "ANKKA_DB_PASSWORD", "secretKeyRef": { "name": "cart-db", "key": "password" } }
```

Two services sharing one database **delete each other's timers** — `ankka_timers` has no service
column and `TimerSweeper` drops rows it does not recognise. See spec Assumptions.

---

## Reviewer's checklist

- [ ] Tier 1 runs with no Docker daemon at all.
- [ ] `sbt compile` is warning-free — `-Wunused` is on.
- [ ] `sbt scalafmtCheckAll` passes.
- [ ] `grep -rl "io.fabric8" controlplane/src/main` hits **only** `Fabric8AnkkaServiceClient.scala`
      (the `AnkkaServiceClient` trait names it in prose, never in a signature).
- [ ] `grep -rl "KubernetesClient" operator/src/main` hits **only** `Executor.scala`,
      `Operator.scala`, `ServiceReconciler.scala` and `Main.scala`.

  The rule is about **I/O, not imports**. `Rendering`, `LifecycleRules`, `Names`, `Labels`,
  `Action`, `ClusterSnapshot`, `Settings` and `WorkQueue` do reference fabric8 *model* types —
  building a `Deployment` is constructing a POJO, not calling a cluster — and all eight contain
  zero references to `KubernetesClient`. A parallel model of `Deployment` would have bought a
  cleaner grep and an untested conversion between what the tests assert on and what the API
  server sees.
- [ ] `crd` depends on no ankka module; `operator` depends only on `crd`.
- [ ] `cli`'s dependencies are unchanged — `controlplane-api` only.
- [ ] No new file under `modules/runtime/src/main/resources/ankka/ddl/`; no schema change.
- [ ] The CRD and install manifests exist in exactly one place each, and the k3s suites apply those files rather than an inline copy.
- [ ] `README.md`'s "Not implemented" entry for reconciliation is rewritten, not deleted — the single-replica cap and bring-your-own database belong there, stated as plainly as the gap they replace.
- [ ] `README.md`'s divergence table entry for `minInstances` defaulting to 1 is corrected: it is 1 because more than 1 does not work, not because of dev-cluster ergonomics.
- [ ] `build.sbt`'s comment on `controlPlane` — "each deployment is a workflow" — is corrected.
