# Contract: what the operator renders for a service with `grpcPort`

Everything here is rendered **only when `spec.grpcPort` is set**. A resource without it renders
exactly what it rendered before this feature, object for object. Decisions are in
[research.md](../research.md) (R8, R11, R12).

`P` is `spec.port` (may be absent), `G` is `spec.grpcPort`.

## Deployment

On the node container, and nothing else changes:

- a container port `{name: grpc, containerPort: G, protocol: TCP}`;
- `ANKKA_GRPC_PORT=G`.

The port's name is load-bearing, as `http` and `management` are: the Service targets it and the
SRV record is named from it.

## Service `<service>`

Exists when `P` or `G` is set. Its ports, in this order:

```yaml
ports:
  - {name: http, protocol: TCP, port: P, targetPort: P}          # when P is set, as today
  - {name: grpc, protocol: TCP, port: G, targetPort: G, appProtocol: kubernetes.io/h2c}
```

## Service `<service>-grpc-peers` (headless)

```yaml
spec:
  clusterIP: None
  selector: <the identity labels, the same function as the Deployment's>
  ports:
    - {name: grpc, protocol: TCP, port: G, targetPort: G}
```

Owned by the resource. Only ready pods are published (the default), so a pod is in a caller's
rotation exactly while it can answer.

Applied by `EnsureGrpcPeers`, which reads first: an object of this name that the resource does
not own is another service's address, and is left alone.

## NetworkPolicy `<service>-grpc`

Ingress to port `G` on the service's pods from the same two peers as `<service>-http`: any pod
labelled as managed by ankka in any namespace so labelled, and the gateway's proxy pods in
`envoy-gateway-system`.

## HTTPRoute `<service>` (when exposed, and the operator knows the base domain)

```yaml
spec:
  parentRefs: [<the installation's gateway, section https>]      # as today
  hostnames: [<derived, as today>]
  rules:
    - matches:
        - headers:
            - {name: content-type, type: RegularExpression, value: 'application/grpc(\+.+)?'}
      timeouts: {request: "0s"}
      backendRefs: [{name: <service>, port: G}]
    - backendRefs: [{name: <service>, port: P}]                   # when P is set, exactly as today
```

Rendered when the service is exposed and has `P` or `G`.

## BackendTLSPolicy `<service>` (when exposed)

One `targetRef` per port the service has, `sectionName: http` and `sectionName: grpc`; validation
unchanged.

## Removal

When `G` is absent: `<service>-grpc-peers` (Service) and `<service>-grpc` (NetworkPolicy) are removed if
this resource owns them, by the read-first removal the route uses.

## Unchanged

Certificates, the cluster policy, the probe, the `preStop` sleep, the rollout strategy, the
operator's RBAC, and the gateway's own manifests.
