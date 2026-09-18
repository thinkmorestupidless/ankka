# Quickstart: Validating Multi-Node Service Clusters

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Fastest signal first.

## Prerequisites

Unchanged from feature 003: JDK 21, sbt, Docker; `kind create cluster --name nakka` for the manual
tier. The multi-instance suite runs several JVMs in one k3s container — allow a few GB of memory.

---

## Tier 1 — Pure logic (seconds, no Docker)

```bash
sbt 'runtime/testOnly nakka.runtime.ClusterConfigSuite'
sbt 'operator/testOnly nakka.operator.RenderingSuite nakka.operator.IdentityRenderingSuite'
sbt 'operator/testOnly nakka.operator.LifecycleRulesSuite'
sbt 'controlPlaneApi/testOnly nakka.controlplane.api.DescriptorSuite'
```

| Suite | Proves |
|---|---|
| `ClusterConfigSuite` | Precedence (system props > `application.conf` > overlay > base); an unknown mode fails naming the valid ones; Kubernetes mode without `POD_IP` fails naming it; **the base contains no peer-finding setting** (a grep, as a test); `exit-jvm` is on. |
| `RenderingSuite` | replicas = `minInstances`; `RollingUpdate` 1/0 at every count including one; the five env vars; `required-contact-point-nr = min(n, 2)`; a port literally **named** `management`; `httpGet /ready` always, HTTP or not; still no liveness probe. |
| `IdentityRenderingSuite` | ServiceAccount, Role, RoleBinding: owned, namespaced, three read verbs on `pods` and nothing else; the selector env equals the Deployment's selector — compared object to object. |
| `LifecycleRulesSuite` | old-template pods present → `UpdateInProgress`, never `Ready`; `PartiallyReady` reachable at 2-of-3. |
| `DescriptorSuite` | the five platform variables are refused in a descriptor's `env`. |

## Tier 2 — Locally, no Kubernetes

```bash
docker compose up -d
sbt shoppingCart/run                                   # exactly as today: no configuration, one node

# a second node, to see a real cluster without Kubernetes
NAKKA_CLUSTER_PORT=17355 sbt shoppingCart/run          # terminal 1
NAKKA_CLUSTER_SEED_NODES=pekko://nakka@127.0.0.1:17355 NAKKA_HTTP_PORT=9001 sbt shoppingCart/run     # terminal 2
```

Add an item through `:9000`, read it through `:9001`.

## Tier 3 — The operator against a real cluster

```bash
sbt 'operator/testOnly nakka.operator.OperatorClusterSuite'
```

Extended: the three identity objects exist, owned, and go with the service; **the operator's real
ServiceAccount can create them**; **a service's real ServiceAccount can list pods in its own namespace
and nothing else, anywhere**; an existing `Recreate` Deployment moves to `RollingUpdate` without
wedging.

## Tier 4 — A real multi-node nakka service

```bash
sbt 'controlPlane/testOnly nakka.controlplane.MultiNodeClusterSuite'
```

The shopping cart at three instances, through the real CLI. How it measures "one cluster" is in
[contracts/formation-and-rollout.md](./contracts/formation-and-rollout.md) — disjoint member sets,
read from every pod.

- one cluster of three; an item written through one pod is read through another;
- **twenty simultaneous cold starts, one cluster every time** (SC-002) — and **zero init-container
  restarts**, which is the Postgres `CREATE TABLE IF NOT EXISTS` race (research R11);
- a rolling update under continuous load: ≥ 99% of requests succeed, none stale, one cluster
  throughout;
- a JVM `SIGKILL`ed from the node: entities answer again, state intact;
- a partition by `iptables`: exactly one side survives, and heals to three;
- scaled 1 → 3 → 1: the pods that stay are not restarted;
- an image with no management endpoint, at three instances: never `Ready`, then `Failed`.

`kubectl delete --force` appears nowhere in it. It still sends SIGTERM; it is not a crash.

## Tier 5 — The control plane at three instances

```bash
sbt 'controlPlane/testOnly nakka.controlplane.ControlPlaneClusterSuite'
```

Apply services while one control-plane instance is replaced: every command accepted; each service
projected **once**; a status report seen by three nodes recorded as **one** journal event — counted in
the journal, not inferred.

## Tier 6 — Manual, on kind

```bash
./kustomization/deploy-local.sh
echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest",
       "resources":{"autoscaling":{"minInstances":3}}}}' > cart.json
nakka services apply -f cart.json
kubectl -n nakka-checkout get pods -l app.kubernetes.io/name=cart        # three, 1/1
kubectl -n nakka-checkout exec deploy/cart -- wget -qO- http://localhost:7626/cluster/members
```

| Action | Expected |
|---|---|
| `minInstances: 3` | three pods, **one** cluster |
| `nakka services restart cart` under `curl` in a loop | no failed requests; membership 3 → 4 → 3 |
| a one-instance service restarted | **also** no failed requests — feature 003's outage is gone |
| `kubectl delete pod` | back to three in seconds |
| `minInstances: 3` → `5` | two new pods; the first three keep their age |

## Reviewer's checklist

- [ ] `grep -nE "seed-nodes|canonical.hostname|bootstrap|discovery" modules/runtime/src/main/resources/reference.conf` finds nothing.
- [ ] `sbt shoppingCart/run` with no environment at all still just works, and logs **no** bootstrap warning.
- [ ] Every existing suite passes unchanged (SC-010); `NakkaTestKit` was not modified.
- [ ] A service's `application.conf` overrides the overlay — tested, not assumed.
- [ ] `Recreate` is gone from `Rendering`; feature 003's strategy migration is either removed or shown to be needed in reverse.
- [ ] `README.md`'s "Zero-downtime deploys" and "Multi-replica services" gaps are rewritten, and what is still missing is said plainly: no autoscaler, two instances cannot survive a partition, no network isolation.
- [ ] `CLAUDE.md`'s "`strategy: Recreate`, always" and "never more than one replica" traps are **replaced**, not left standing beside their opposites.
- [ ] The operator's new RBAC is proven under its real identity; a service's identity is proven unable to do anything but read pods in its own namespace.
- [ ] No test uses `--force` deletion as a crash.
