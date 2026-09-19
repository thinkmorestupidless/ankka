# Contract: The Rendered Route

**Satisfies**: FR-006 to FR-008, FR-012 to FR-015, FR-023 to FR-025

## HTTPRoute, per exposed service

Rendered by the operator iff `spec.exposed && resolvedPort.isDefined`:

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: cart
  namespace: ankka-checkout
  labels:
    app.kubernetes.io/name: cart
    app.kubernetes.io/part-of: checkout
    app.kubernetes.io/managed-by: ankka
  ownerReferences:
    - apiVersion: ankka.thinkmorestupidless.com/v1alpha1
      kind: AnkkaService
      name: cart
      uid: …
      controller: true
spec:
  parentRefs:
    - group: gateway.networking.k8s.io
      kind: Gateway
      name: ankka
      namespace: ankka-gateway
      sectionName: https
  hostnames:
    - cart-checkout.127.0.0.1.sslip.io
  rules:
    - backendRefs:
        - name: cart
          port: 9000
```

| Property | Rule | Why |
|---|---|---|
| hostname | `Hostnames.of(name, projectId, baseDomain)` — derived by the operator, never read from the resource | a resource cannot claim a name it does not own (FR-025) |
| backend | the service's own Service, same namespace, resolved port | Gateway API forbids cross-namespace backends without a `ReferenceGrant`; none is rendered |
| owner | the `AnkkaService` | route dies with the service and the project (FR-014) |
| absent when | not exposed, or `http: false` | there is nothing to route to; the operator deletes a leftover |
| readiness | inherited: backend is the Service, whose endpoints are the ready pods | FR-012 by construction |

## Status copied to the resource

From the route's `status.parents[]` entry whose `parentRef` is the ankka Gateway, the `Accepted`
condition:

| Condition | `status.route` | `services get` |
|---|---|---|
| `Accepted=True` | `accepted` | hostname shown, no detail |
| `Accepted=False`, reason R | `rejected: R` | hostname shown, `detail: route rejected: R` |
| no status yet | `pending` | hostname shown, `detail: route pending` |

## Gateway, installer-owned (never touched by the operator)

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata: { name: ankka, namespace: ankka-gateway }
spec:
  gatewayClassName: ankka
  infrastructure:
    parametersRef: { group: gateway.envoyproxy.io, kind: EnvoyProxy, name: ankka }
  listeners:
    - name: http
      port: 80
      protocol: HTTP
      allowedRoutes: { namespaces: { from: Same } }        # only the redirect route
    - name: https
      port: 443
      protocol: HTTPS
      hostname: "*.127.0.0.1.sslip.io"                     # replaced from ankka-platform.baseDomain
      tls:
        mode: Terminate
        certificateRefs: [{ kind: Secret, name: ankka-wildcard-tls }]
      allowedRoutes:
        namespaces:
          from: Selector
          selector:
            matchLabels: { app.kubernetes.io/managed-by: ankka }
```

A route from a namespace without that label is not admitted — and the operator is the only thing
that labels namespaces.

## RBAC

Operator ClusterRole, added:

```yaml
- apiGroups: ["gateway.networking.k8s.io"]
  resources: ["httproutes"]
  verbs: ["get", "list", "watch", "create", "patch", "delete"]
```

Nothing on `gateways`, `gatewayclasses`, `referencegrants`, `envoyproxies`, `certificates`,
`issuers`, `secrets`. A deployed service's Role (feature 004) is unchanged and is proven unable to
list `httproutes` under its real token.
