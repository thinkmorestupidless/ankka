# Quickstart: Validating a Real Deployed Service

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Fastest signal first. These are the runs a reviewer performs; implementation detail belongs in
`tasks.md`.

---

## Prerequisites

| Need | For | Notes |
|---|---|---|
| JDK 21, sbt | everything | unchanged |
| Docker | the cluster suites and every image build | unchanged |
| A local cluster | the manual walkthrough | `kind create cluster --name nakka`; the suites start their own |
| Disk headroom | the new suite | it imports a ~700MB image into a throwaway k3s container |

---

## Tier 1 — Pure logic (seconds, no Docker)

Port resolution, validation and rendering are total functions over data.

```bash
sbt 'controlPlaneApi/testOnly nakka.controlplane.api.DescriptorSuite'
sbt 'controlPlane/testOnly nakka.controlplane.ServiceProjectionSuite'
sbt 'operator/testOnly nakka.operator.RenderingSuite'
sbt 'operator/testOnly nakka.operator.ServiceRenderingSuite'
```

| Suite | Proves |
|---|---|
| `DescriptorSuite` (extended) | The resolution table in [contracts/port-resolution.md](./contracts/port-resolution.md): neither field → 9000, explicit port kept, `"http": false` → none; **`"port": null` parses as 9000, asserted so nobody reintroduces it**; a port outside 1–65535 is a problem; `NAKKA_HTTP_PORT` in `env` is a problem **even with `"http": false`**. |
| `ServiceProjectionSuite` | The resolved port reaches the custom resource, and `"http": false` projects as an absent field. |
| `ServiceRenderingSuite` | The Service's selector is the *same* value as the Deployment's selector; owner reference present; `port` and `targetPort` equal; `RemoveService` (not `EnsureService`) when there is no port. |
| `RenderingSuite` | `containerPort`, `NAKKA_HTTP_PORT` and the readiness probe all follow the one resolved port; all three absent with no port; **`imagePullPolicy: IfNotPresent` always**. |

The highest-value assertion in this tier is the selector one: a Service whose selector drifts from the
Deployment's is a service that reports `Ready` and accepts no connections.

---

## Tier 2 — The operator against a real cluster (minutes, Docker)

```bash
sbt 'operator/testOnly nakka.operator.OperatorClusterSuite'
```

Existing suite, extended:

- A service with a port gets a Service, with endpoints that actually resolve to its pod.
- A service with `"http": false` gets none, and still reaches `Ready`.
- A service changed from serving HTTP to not loses its Service — and a **hand-created** Service of the
  same name, not owned by the resource, is left alone.
- **The operator's own ServiceAccount can create a Service** — the positive half of the RBAC check
  whose negative half (the withheld `delete` on databases) feature 002 already built. Without this, the missing
  `services` grant fails only on a real deploy (see [contracts/packaging-and-rbac.md](./contracts/packaging-and-rbac.md)).

---

## Tier 3 — A real nakka application, end to end (slowest)

```bash
sbt 'controlPlane/testOnly nakka.controlplane.SampleDeploymentClusterSuite'
```

The point of the feature. Builds the shopping cart image, imports it into a throwaway k3s cluster,
deploys it through the real control plane and CLI against a platform-provisioned database, and uses
it over HTTP:

- A descriptor naming **only an image** reaches `Ready` — database provisioned, schema applied, port
  open.
- `POST /carts/c1/items` then `GET /carts/c1` returns the item — issued **from the k3s node to the
  Service's `clusterIP`**, not through a port-forward, which would bypass the Service and could not
  catch a wrong selector.
- The pod is deleted; after it returns, `GET /carts/c1` returns the same item — it was in Postgres,
  not in memory.
- The events are in that service's **own** database and nowhere else.

To skip everything needing a cluster — and, with it, the sample image build:

```bash
sbt -Dnakka.cluster.tests=off test
```

---

## Tier 4 — Manual walkthrough

```bash
kind create cluster --name nakka
./kustomization/deploy-local.sh          # now also loads the sample image
kubectl -n nakka-controlplane port-forward svc/nakka-controlplane 9000:9000 &

nakka config set url http://localhost:9000
nakka config set token dev-local-token
nakka organizations create acme --name "Acme Corp"
nakka projects create checkout --name Checkout -O acme
nakka config set project checkout
```

A descriptor with **nothing but an image** — no database, no port:

```json
{ "name": "cart", "service": { "image": "sample-shopping-cart:latest" } }
```

```bash
nakka services apply -f cart.json
nakka services list
# NAME  STATUS  INSTANCES  GEN  IMAGE
# cart  Ready   1/1        1    sample-shopping-cart:latest

kubectl -n nakka-checkout get svc,deploy,pods
kubectl -n nakka-checkout port-forward svc/cart 8080:9000 &
```

Use it:

```bash
curl -XPOST localhost:8080/carts/c1/items \
  -H 'content-type: application/json' \
  -d '{"productId":"p1","name":"Widget","quantity":2}'

curl localhost:8080/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Widget","quantity":2}],"checkedOut":false}
```

Prove it is really persisted:

```bash
kubectl -n nakka-checkout delete pod -l app.kubernetes.io/name=cart
kubectl -n nakka-checkout rollout status deployment/cart
curl localhost:8080/carts/c1        # the same item, from the journal
```

| Action | Expected |
|---|---|
| first `apply` | a database is provisioned, the schema applied, and the pod goes `Ready` — **never `Failed`** on the way |
| `Ready` appears | only once the port is open, not when the container starts |
| `kubectl get svc` | one `ClusterIP` Service named `cart`, port 9000, with a live endpoint |
| pod deleted | the cart survives; the address keeps working once the replacement is ready |
| a descriptor with `"http": false` | deploys, reaches `Ready`, and gets **no** Service |
| a descriptor with `NAKKA_HTTP_PORT` in `env` | refused at apply time, naming the conflict |

**The schema actually landed** — the same check feature 002 ends on:

```bash
kubectl -n nakka-checkout exec nakka-db-1 -c postgres -- psql -U postgres -d cart -c '\dt'
kubectl -n nakka-checkout exec nakka-db-1 -c postgres -- \
  psql -U postgres -d cart -c 'select persistence_id, seq_nr from event_journal limit 5;'
# the cart's own events, owned by the cart role
```

---

## Reviewer's checklist

- [ ] Tier 1 runs with no Docker daemon at all.
- [ ] `sbt compile` warning-free; `sbt scalafmtCheckAll scalafmtSbtCheck` clean.
- [ ] `sbt -Dnakka.cluster.tests=off test` builds **no** sample image and skips all three cluster suites.
- [ ] `sbt docker:publishLocal` builds three images with nothing named on the command line.
- [ ] The Service's selector is literally the same value as the Deployment's, not a copy that matches.
- [ ] `imagePullPolicy: IfNotPresent` is rendered on every workload, whether or not it serves HTTP.
- [ ] The operator's `ClusterRole` grants `services`, a test proves the grant with the operator's real ServiceAccount, and `RemoveService` refuses a Service the resource does not own.
- [ ] `crd`'s OpenAPI schema in `kustomization/components/crd/nakkaservice.yaml` carries `port` — the model and the schema moved together, which is the mistake feature 002 shipped.
- [ ] A `NakkaService` written before this feature still deploys, serving no HTTP, unchanged.
- [ ] Every `pause` descriptor in the repository carries `"http": false`, and `EndToEndClusterSuite` still passes.
- [ ] `sbt shoppingCart/Docker/publishLocal` really produces an image — `publish / skip := true` did not silently suppress it.
- [ ] The sample suite talks to the Service's `clusterIP` from the node, not through a port-forward.
- [ ] `README.md`'s "Not implemented" list no longer claims services are unreachable, and says plainly what is still missing (ingress, TLS, anything from outside the cluster).
- [ ] `CLAUDE.md` records the `imagePullPolicy` trap, the containerd `-n k8s.io` namespace trap, and that `controlPlane`'s tests build a sample image.
