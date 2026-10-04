# Contract: Port 7628 `observe`

What a workload pod exposes so that the control plane, and only the control plane, can read the
topology on a member's behalf (research R7).

## Listener

| Property | Value |
|---|---|
| port | 7628, container port name `observe` (the name is load-bearing, like `management` and `probe`) |
| mode | Kubernetes overlay only (`ankka-cluster-kubernetes.conf`); nothing binds it locally |
| TLS | the service certificate (`/var/run/secrets/ankka/service`), served by `RotatingTls` |
| client auth | required; the peer's chain must verify against the `ankka-service` authority and carry exactly the URI `ankka://platform/controlplane` (`RotatingTls.Peers.Exactly`) |
| routes | `GET /observability/service` and `GET /observability/topology`, nothing else |
| server | the JDK HTTP server (`HttpsServer`) the local endpoint already uses, so `runtime` gains no dependency |
| readiness | not part of `/ready`; a broken observe listener must never take a service out of rotation |

A connection whose certificate carries any other identity, including the service's own, is refused
during the TLS handshake. It never reaches a route.

The peer check relies on `platform` being a reserved project id: a workload's identity is
`ankka://<projectId>/<serviceName>`, so no tenant workload may be issued an `ankka://platform/…`
identity. The control plane refuses to create a project with that id, and the operator refuses to
issue a certificate for one.

## Network policy (rendered by the operator, `ZeroTrust.scala`)

A new ingress rule on every workload's policy:

```yaml
- ports: [{ port: 7628, protocol: TCP }]
  from:
    - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: ankka-controlplane } }
      podSelector:       { matchLabels: { app.kubernetes.io/name: ankka-controlplane } }
```

The namespace and label are the control plane's own, as `kustomization/components/controlplane/`
declares them (namespace `ankka-controlplane`, pods labelled `app.kubernetes.io/name: ankka-controlplane`).
No other source is admitted. That includes the service's own pods, the gateway and every other
project.

## Rendering (`Rendering.scala`)

- The container port `observe: 7628` is added to every workload. It is **not** added to the
  Deployment's `spec.selector`, nor to anything else immutable.
- No new environment variable is needed: the overlay enables the listener, as it enables
  management.

## Old images

A pod whose runtime predates this listener refuses the TCP connection. The control plane reports
that instance as `unsupported`, with its declared runtime when known, and continues with the others.
