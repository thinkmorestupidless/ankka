# Contract: the operator

What the operator renders for a custom hostname, what it reads, what it reports and what it may
touch. Decisions are in [research.md](../research.md) (R1, R5, R6, R9, R10, R13).

## Settings

| Variable | Meaning | Absent |
|---|---|---|
| `ANKKA_HOSTNAME_ISSUER` | the `ClusterIssuer` every custom hostname's Certificate names | every custom hostname is `rejected: the operator names no authority for custom hostnames (ANKKA_HOSTNAME_ISSUER)`; nothing is rendered for it |

## What is rendered, per exposed service (after the route's actions)

| Spec | Actions |
|---|---|
| any hostname under the base domain, or a wildcard | never rendered, whatever the resource says; reported `rejected` (a set's listener for `api.<base>` is more specific than the Gateway's `*.<base>` and would win: spike S1) |
| `customHostnames` empty | `RemoveListenerSet(ns, <service>-hostnames, ownerUid)`, `PruneHostnameCertificates(ns, service, keep = [])` |
| non-empty, issuer named | `EnsureCertificate(<hostname>)` per hostname, `EnsureListenerSet(<service>-hostnames)`, `PruneHostnameCertificates(ns, service, keep = hostnames)`; the route carries the hostnames and the set as a second parent |
| non-empty, no issuer | as empty, and the status says why |
| not exposed | as today (`RemoveHttpRoute` …) and as empty |

Every action's `describe` names the hostname and never a key. Server-side apply with the
operator's field manager for the ensures; the removals read first and check the owner, and a 404
on the `listensersets` *type* reads as absent.

The `Certificate`:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: app.example.com
  namespace: ankka-checkout
  labels: { ankka.thinkmorestupidless.com/hostname-certificate: "true", <the service labels> }
  ownerReferences: [the AnkkaService]
spec:
  secretName: app.example.com
  dnsNames: [app.example.com]
  issuerRef: { name: <ANKKA_HOSTNAME_ISSUER>, kind: ClusterIssuer, group: cert-manager.io }
  privateKey: { algorithm: RSA, size: 2048, encoding: PKCS8, rotationPolicy: Always }
```

The `ListenerSet` is in [data-model.md](../data-model.md). The route's `hostnames` are the derived
hostname first, then the custom ones in the spec's order; `parentRefs` the Gateway
(`sectionName: https`) first, then `{group: gateway.networking.k8s.io, kind: ListenerSet, name:
<service>-hostnames}`.

## What is read, per custom hostname, every reconcile

| Object | Verb | Used for |
|---|---|---|
| `Certificate <hostname>` | get | `Ready`; `Issuing` with a failure message |
| `Challenge` (acme.cert-manager.io) in the namespace, `spec.dnsName == hostname`, newest | list | `status.state`, `status.reason` |
| `ListenerSet <service>-hostnames` | get | the listener's `Accepted`, `Programmed`, `ResolvedRefs`, `Conflicted` |
| `HTTPRoute <service>` (already read) | — | `status.parents` entry for the set: `Accepted` |

A cluster without the `challenges` type reads as "no challenge"; without `listensersets`, as "no
set" (`pending: the gateway is attaching the hostname` forever, which is a visible answer for an
installation whose Gateway API is too old).

## What is reported

`status.hostnames`, one entry per spec hostname in the spec's order, by the rules in
[data-model.md](../data-model.md). Written only when the report differs (`sameReport`).

## The grant (`kustomization/components/operator/operator.yaml`)

| Added | Verbs |
|---|---|
| `listensersets` (`gateway.networking.k8s.io`) | get list watch create patch delete |
| `challenges` (`acme.cert-manager.io`) | get list watch |

Unchanged and proved withheld by `OperatorClusterSuite` test 20: nothing on `gateways`,
`gatewayclasses`, `referencegrants`, `envoyproxies`, `clusterissuers`; `secrets` stays
`create, patch`.

## What does not change

The pod template of every hosting (no variable, no mount); the `BackendTLSPolicy`; the gRPC rule;
the redirect; `RenderingUnchangedSuite`'s objects (two action lines per fixture, no object);
`Hostnames.of` as the derived hostname's one derivation, never read from the resource.
