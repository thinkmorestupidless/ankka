# Contract: Cluster Formation, Readiness and Rollout

**Satisfies**: FR-001 to FR-005, FR-013 to FR-027

Every number here was measured on a real cluster — see [research.md](../research.md).

## The invariant

**The instances of one service never constitute more than one cluster.** Not at cold start, not
during a rollout, not when discovery is broken, not after a partition heals. Everything below is in
service of that sentence, and every cluster test is ultimately a test of it.

How a test decides: read `/cluster/members` from **every** pod and compare member sets. Views that
share a member are one cluster seen at different instants; only **disjoint** views are a split. A pod
whose management endpoint is up but which has not joined reports an empty set — that is a node
waiting, not a cluster, and must not be counted as one.

## Cold start

| Setting | Value |
|---|---|
| `required-contact-point-nr` | `min(instances, 2)` |

Measured: one cluster in 10 of 10 simultaneous three-pod cold starts, forming in 10–15 s.

`nr = 1` also gave 10 of 10 — and is **not** thereby shown safe: one node, an idle API server. The
documentation's warning stands, and SC-002's twenty rounds run in the automated suite.

## Discovery that cannot work

A node that cannot discover its peers **waits**. Measured with the RoleBinding removed: 0 members,
`/ready` → 500, `0/1`, a log line naming RBAC — and no self-join. Restoring it: one cluster, zero
restarts. So Role-after-Deployment ordering heals itself.

## Readiness

```yaml
readinessProbe:
  httpGet: { path: /ready, port: management }
  periodSeconds: 5
```

| State | `/ready` | Receives traffic |
|---|---|---|
| started, not a member | 500 | no |
| a member, HTTP not yet bound (services that serve HTTP) | 500 | no |
| a member, and HTTP bound if it serves any | 200 | yes |

Cluster Bootstrap registers the membership check. ankka adds one — "the HTTP server is bound" — only
when a service declares a port, so one probe covers both, and a service with no HTTP is ready on
membership alone (FR-024).

An image whose runtime predates this feature has no management endpoint → connection refused → never
ready → `Failed` by the Deployment's own deadline (FR-022). No new clock.

No liveness probe. Restarting a member for a slow GC now also costs a rebalance.

## Rollout

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate: { maxSurge: 1, maxUnavailable: 0 }
```

At **every** instance count. Measured:

| Instances | Disjoint clusters | Membership | Fewest ready |
|---|---|---|---|
| 3 | 1 throughout | 3 → 4 → 5 → 3 | — |
| 1 | 1 throughout | 1 → 2 → 1 | **1 — never zero** |

The surge pod joins the existing cluster and is ready before an old pod leaves, so a one-instance
service deploys with no outage. `Recreate`, and README's "a deploy is a brief outage", both go.

`Marking exiting node(s) as UNREACHABLE … This is expected` appears in Pekko's log during every
rollout, and is. Do not assert "no unreachable members" across one.

**To test, not assume**: feature 003 found that moving a *live* Deployment to `Recreate` was rejected
over a leftover defaulted field — a bug only an existing object shows. The move back to
`RollingUpdate` is to be tested against an existing `Recreate` Deployment.

## Scaling

Instances change; the pod template does not; pods that stay are untouched (measured: 1 → 3, original
pod 0 restarts). The one exception is crossing between one instance and several, where
`required-contact-point-nr` changes and the pods roll — without an outage, per the table above.

## Losing a node

| Cause | Recovery | Time |
|---|---|---|
| pod deleted (any grace period — SIGTERM still arrives) | deliberate leave | 4–6 s |
| JVM `SIGKILL`ed | container restarts in place, same IP; the new incarnation evicts the old | ~4 s |
| network partition | `keep-majority`: the majority downs the rest; the isolated node exits and rejoins | ~25 s |

`kubectl delete --force` is **not** a crash. A crash test `SIGKILL`s the JVM from the node; a
partition test drops remoting traffic with `iptables` on the node. Both are scriptable through the
k3s container.

The third row depends on `coordinated-shutdown.exit-jvm = on`, which ankka does not set today.

## Status

`PartiallyReady` becomes reachable. `LifecycleRules` gains: pods of the old template still present
(`status.replicas > updatedReplicas`) → `UpdateInProgress`. Without it, a rollout reports `Ready`
while the pod being counted is an old one.
