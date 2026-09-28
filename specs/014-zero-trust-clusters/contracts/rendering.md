# Contract: what the operator renders, and what an installation provides

## Installation (kustomize components)

New component `kustomization/components/pki/`:

```yaml
ClusterIssuer ankka-selfsigned                     # selfSigned
Certificate  ankka-cluster-ca   (ns cert-manager)  # isCA, RSA 4096, 87600h, issuer ankka-selfsigned
Certificate  ankka-service-ca   (ns cert-manager)  # same
ClusterIssuer ankka-cluster                        # ca.secretName: ankka-cluster-ca
ClusterIssuer ankka-service                        # ca.secretName: ankka-service-ca
Bundle       ankka-service-ca   (trust.cert-manager.io/v1alpha1)
  sources: [{secret: {name: ankka-service-ca, key: ca.crt}}]
  target:  {configMap: {key: ca.crt}, namespaceSelector: {matchLabels: {app.kubernetes.io/managed-by: ankka}}}
```

New component `kustomization/components/trust-manager/` (the rendered upstream chart at a pinned
version; `kubectl apply -k` cannot run Helm). Both overlays include both components; a cloud
installation may replace the two `ClusterIssuer`s with its own PKI — that is the one place the
choice lives, and `install-cloud.md` says so.

Gateway component additions: `Certificate ankka-gateway-client` (ns `ankka-gateway`, issuer
`ankka-service`, `uris: [ankka://gateway]`, client auth) and
`EnvoyProxy.spec.backendTLS.clientCertificateRef: {kind: Secret, name: ankka-gateway-client-tls,
namespace: ankka-gateway}`.

Control plane component additions: two `Certificate`s, two `NetworkPolicy`s, one
`BackendTLSPolicy`, the transport label, the mounts, `ANKKA_AUTH_JWKS_URL` on 8443 and
`ANKKA_AUTH_JWKS_CA`. Keycloak component: `Certificate ankka-keycloak-tls`,
`Keycloak.spec.http.tlsSecret`, `httpEnabled: false`, a `BackendTLSPolicy` for its route. The
`ankka-gateway`, `ankka-controlplane` and `ankka-auth` namespaces carry the
`app.kubernetes.io/managed-by: ankka` label so the `Bundle` reaches them.

`deploy-local.sh`: after the CRD-bearing controllers, prove policy enforcement:

```
kubectl create ns ankka-netpol-probe && (busybox pod A, busybox pod B, deny-all NetworkPolicy on B)
kubectl exec A -- wget -T 3 -qO- http://<B ip>:8080  → MUST fail; a success aborts the deploy with:
  "this cluster accepted a NetworkPolicy and did not enforce it; ankka needs a network that does
   (kind ≥ 0.24 does; see docs/platform/install-local.md)"
kubectl delete ns ankka-netpol-probe
```

## Per service (operator; `Rendering`, `CnpgRendering`, `Action`, `Fabric8Executor`)

New `Action`s, all server-side apply or owner-checked delete, each with a `Fabric8Executor` case:

```
EnsureCertificate(certificate)             // cert-manager.io/v1 Certificate, generic resource
EnsureIssuer(issuer)                       // per project, with the database CA Certificate
EnsureNetworkPolicy(policy)                // networking.k8s.io/v1
RemoveNetworkPolicy(namespace, name, ownerUid)
EnsureBackendTlsPolicy(policy)             // gateway.networking.k8s.io/v1alpha3, generic resource
RemoveBackendTlsPolicy(namespace, name, ownerUid)
Transition(namespace, name)                // delete the Deployment, wait for its pods to be gone (≤ 90s)
```

Rendered objects and names: see `data-model.md` §5. Rendering rules the suites assert:

1. The Deployment's `spec.selector` is byte-identical to today's (`ServiceRenderingSuite`).
2. The pod template carries `ankka.thinkmorestupidless.com/transport: tls`, and
   `ANKKA_CLUSTER_POD_SELECTOR` includes it beside the formation label.
3. Every volume is a whole-Secret projection (no `subPath`); `ankka-database-ca` projects only the
   `ca.crt` item of `<cluster>-ca`.
4. The readiness probe is `httpGet /ready` on the port **named** `probe`; ports `http`,
   `management`, `remoting`, `probe` are all declared.
5. `ANKKA_NAMESPACE_PREFIX` is set; `ServiceSpec.problems` refuses it in a descriptor.
6. A `"http": false` service gets no service certificate volume, no `<service>-http` policy, no
   `BackendTLSPolicy`.
7. The credential Secret has no `password` and no `ANKKA_DB_PASSWORD`; the `DatabaseRole` has
   `disablePassword: true`, no `passwordSecret`, `inRoles: [ankka_tls]`. `EnsureCredentials` no
   longer generates anything; `Passwords` is deleted.
8. `NetworkPolicy <service>-cluster` ingress: `{ports: [17355, 7626], from: [{podSelector:
   identity labels}]}` and `{ports: [7627]}` (no `from`). `<service>-http` ingress: `{ports:
   [<port>], from: [{namespaceSelector: managed-by ankka, podSelector: managed-by ankka},
   {namespaceSelector: kubernetes.io/metadata.name = envoy-gateway-system, podSelector: gateway.envoyproxy.io/owning-gateway-name = ankka, owning-gateway-namespace = ankka-gateway}]}` — the proxy pods, which Envoy Gateway runs in its own namespace.
9. `BackendTLSPolicy <service>`: `targetRefs: [{group: "", kind: Service, name: <service>,
   sectionName: http}]`, `validation: {caCertificateRefs: [{group: "", kind: ConfigMap, name:
   ankka-service-ca}], hostname: <service>.<ns>.svc.cluster.local}`.
10. Transition: when the existing Deployment is ankka's and its template lacks the transport
    label, the action list is `Transition` first, then the usual apply; status during the wait is
    `Deploying` with the detail in `data-model.md` §7.

## Per project (`CnpgRendering`)

`Certificate <cluster>-client-ca` (isCA), `Issuer ankka-database`, `Certificate
<cluster>-replication` (CN `streaming_replica`), the `Cluster` fields (`certificates.clientCASecret`,
`certificates.replicationTLSSecret`, `postgresql.pg_hba`, `managed.roles`), and `NetworkPolicy
<cluster>-database` — all applied on every reconcile of any service in the project, as the
`Cluster` is today, and none owned by a service.

The `ClusterSpec` model gains `certificates`, `postgresql.pg_hba` and `managed.roles`; `CrdSchemaSuite`'s
equivalent for CNPG (`CnpgRenderingSuite` against the installed CRD in `OperatorClusterSuite`)
must see the fields accepted by a real API server.

## Operator RBAC (`operator.yaml`, with a comment per addition)

```
cert-manager.io          certificates, issuers    get list watch create patch delete
networking.k8s.io        networkpolicies          get list watch create patch delete
gateway.networking.k8s.io backendtlspolicies      get list watch create patch delete
""                       events                   get list
```

`secrets` verbs unchanged; `OperatorClusterSuite`'s real-token case additionally proves the
operator's ServiceAccount cannot `list` secrets and cannot `get` a `Certificate`'s Secret it was not
told the name of — the existing negative case, restated for the new kinds.
