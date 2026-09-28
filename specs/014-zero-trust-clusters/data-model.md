# Data Model: Zero Trust in the Service Clusters

**Feature**: `014-zero-trust-clusters` | **Date**: 2026-09-28

Nothing here is persisted in a journal; the entities are certificates, identities, policies and
configuration. The one journal-visible change is a status observation's detail text (§7).

## 1. Workload identity

A certificate is the only credential a workload holds. One per purpose per service:

| Purpose | `Certificate` name | Secret | Issuer | Subject names | Usages |
|---|---|---|---|---|---|
| cluster (remoting + management) | `<service>-cluster` | `<service>-cluster-tls` | `ClusterIssuer ankka-cluster` | URI `ankka://<project>/<service>`; DNS `<service>.<ns>.svc` | server auth, client auth |
| service (HTTP, service client) | `<service>-service` | `<service>-service-tls` | `ClusterIssuer ankka-service` | URI `ankka://<project>/<service>`; DNS `<service>`, `<service>.<ns>.svc`, `<service>.<ns>.svc.cluster.local` | server auth, client auth |
| database (client to Postgres) | `<service>-database` | `<service>-database-tls` | `Issuer ankka-database` (project namespace) | CN `<service>` (the role name; CNPG's `cert` method matches CN to user) | client auth |

Common to all three: `privateKey {algorithm: RSA, size: 2048, encoding: PKCS8, rotationPolicy:
Always}`, `duration: 24h`, `renewBefore: 16h`, owned by the `AnkkaService` (owner reference), in
the project namespace. The database certificate exists only when `provisionDatabase` is true.

The gateway's identity is `Certificate ankka-gateway-client` in `ankka-gateway` (URI
`ankka://gateway`, issuer `ankka-service`, client auth). The control plane's are `ankka-controlplane-cluster`
and `ankka-controlplane-service` in `ankka-controlplane`. The identity provider's server certificate is
`ankka-keycloak-tls` in `ankka-auth` (DNS `ankka-keycloak-service.ankka-auth.svc`, issuer `ankka-service`).

**Identity string** (`Caller`, §2) is parsed from the URI SAN: scheme `ankka`, authority = project,
first path segment = service; `ankka://gateway` has authority `gateway` and no path. Anything else
is *unrecognised* and refused.

**Validation** (`Names`/`Hostnames` already enforce): project ids and service names are DNS labels,
so the URI is always well-formed and the DNS SANs are valid names.

## 2. Authorities

| Name | Kind | Namespace | Root | Consumers of `ca.crt` |
|---|---|---|---|---|
| `ankka-selfsigned` | `ClusterIssuer` (selfSigned) | — | — | issues the roots below |
| `ankka-cluster` | `ClusterIssuer` (ca) | secret `ankka-cluster-ca` in `cert-manager` | `Certificate ankka-cluster-ca`, isCA, RSA 4096, 10y | every workload (from its own cluster Secret's `ca.crt`) |
| `ankka-service` | `ClusterIssuer` (ca) | secret `ankka-service-ca` in `cert-manager` | `Certificate ankka-service-ca`, isCA, RSA 4096, 10y | every workload; the gateway via ConfigMap `ankka-service-ca` (trust-manager `Bundle`); the control plane's JWKS fetch |
| `ankka-database` | `Issuer` (ca) | per project namespace, secret `<cluster>-client-ca` | `Certificate <cluster>-client-ca`, isCA, self-signed via `ankka-selfsigned`, 10y | the CNPG `Cluster` (`certificates.clientCASecret`) |

The CNPG-generated **server** CA (`<cluster>-ca`, keys `ca.crt` + `ca.key`) stays CNPG's; workloads
mount only its `ca.crt` item.

Root rotation is out of scope; ten years is stated so nobody reads it as "forever".

## 3. Caller and caller-naming ACL (`modules/http`)

```
Caller
  = Gateway                          // arrived through the installation's gateway: "the internet"
  | Service(project: String, name: String)
  | Local                            // no cluster: a developer's machine, the testkit

Acl (existing enum) + AllowCallers(matchers: Vector[CallerMatcher])

CallerMatcher
  = Internet                         // Caller.Gateway
  | NamedService(project: Option[String], name: String)   // None: this project
  | AnyInProject                     // any Service with this service's own project
  | Self                             // this service's own identity
```

Semantics: a list admits a caller matched by any element; `Local` is admitted by every
`AllowCallers` (FR-019). `AllowCallers` refuses with 403 and a body that names no matcher.
`RequestContext.caller: Caller` is total. `Principal` is unchanged and independent.

**Self identity**: the server's own `(project, name)` comes from its own service certificate's
URI SAN in Kubernetes mode; locally it is `("local", <service name from the builder>)` and only
`Local` callers arrive, so `Self` and `AnyInProject` never match a real remote caller there.

**Python** (`ankka.endpoint`): `Acl` stops being a plain enum and becomes a class with the four
existing constants plus `Acl.allow_callers(Callers.internet, Callers.service("orders"),
Callers.service("orders", project="checkout"), Callers.any_in_project, Callers.self_)`.
`request.caller` is one of `Gateway`, `ServiceCaller(project, name)`, `LocalCaller`.

**TypeScript** (`endpoint.ts`): `Acl.allowCallers(Callers.internet, Callers.service("orders"),
Callers.service("orders", { project: "checkout" }), Callers.anyInProject, Callers.self)`;
`request.caller` is a discriminated union `{ kind: "gateway" } | { kind: "service", project, name }
| { kind: "local" }`.

## 4. Sidecar protocol (`protocol/…/v1`)

`endpoint.proto`:

```
message HttpRequest { …existing…; Caller caller = 9; }
message Caller { oneof kind { Empty gateway = 1; ServiceCaller service = 2; Empty local = 3; } }
message ServiceCaller { string project = 1; string name = 2; }
```

`discovery.proto`:

```
message Endpoint { …; Acl acl = 3; repeated CallerMatcher allow_callers = N; }
enum Endpoint.Acl { ALLOW_ALL = 0; DENY_ALL = 1; AUTHENTICATED = 2; CALLERS = 3; }
message CallerMatcher { oneof kind { Empty internet = 1; NamedService service = 2; Empty any_in_project = 3; Empty self = 4; } }
message NamedService { optional string project = 1; string name = 2; }
// RouteSpec: optional Endpoint.Acl acl = 6 (existing); repeated CallerMatcher allow_callers = M;
```

`allow_callers` is read only when `acl == CALLERS`. Protocol version minor +1; an SDK declaring the
older minor is still served (a minor only adds), and it simply never sees `caller`.

## 5. Operator-rendered objects per service (additions)

| Object | Name | Owned | When |
|---|---|---|---|
| `Certificate` ×2 (cluster, service) | §1 | yes | always |
| `Certificate` (database) | §1 | yes | `provisionDatabase` |
| `NetworkPolicy` | `<service>-cluster` | yes | always |
| `NetworkPolicy` | `<service>-http` | yes | `port` declared |
| `BackendTLSPolicy` | `<service>` | yes | exposed (with the `HTTPRoute`) |
| pod template | label `ankka.thinkmorestupidless.com/transport: tls`; volumes `ankka-cluster-tls`, `ankka-service-tls`, `ankka-database-tls`, `ankka-database-ca` (Secret `<cluster>-ca`, `items: [ca.crt]`); container port `probe` 7627; readiness probe on `probe` | — | always |
| env (platform-set, refused in descriptors) | `ANKKA_NAMESPACE_PREFIX` | — | always |
| credential Secret (`<service>-db`) | keys `ANKKA_DB_HOST/PORT/NAME/USER` (existing), `ANKKA_DB_SSL_MODE=verify-full`, `ANKKA_DB_SSL_ROOT_CERT`, `ANKKA_DB_SSL_CERT`, `ANKKA_DB_SSL_KEY`; **no** `ANKKA_DB_PASSWORD`, **no** `password` (basic-auth) | no (data) | `provisionDatabase` |
| `DatabaseRole` | `disablePassword: true`, no `passwordSecret`, `inRoles: [ankka_tls]` | no | `provisionDatabase` |

Per project (rendered beside the `Cluster`, not owned by any service):

| Object | Name | Notes |
|---|---|---|
| `Certificate` (CA) + `Issuer` | `<cluster>-client-ca` / `ankka-database` | §2 |
| `Certificate` | `<cluster>-replication` | CN `streaming_replica`, from `ankka-database` |
| `Cluster` (CNPG) fields | `certificates.clientCASecret: <cluster>-client-ca`, `certificates.replicationTLSSecret: <cluster>-replication`, `postgresql.pg_hba: ["hostssl all +ankka_tls all cert clientcert=verify-full"]`, `managed.roles: [{name: ankka_tls, login: false}]` | the group role is what lets a pre-feature role keep its password until its next deploy |
| `NetworkPolicy` | `<cluster>-database` | selector `cnpg.io/cluster: <cluster>`; 5432 from same-namespace pods labelled `app.kubernetes.io/managed-by: ankka`; 8000 + 5432 from namespace `cnpg-system` and from pods with the same `cnpg.io/cluster` label |

Mount points (fixed, in the Kubernetes overlay's config, not variables):
`/var/run/secrets/ankka/cluster`, `/var/run/secrets/ankka/service`,
`/var/run/secrets/ankka/database`, `/var/run/secrets/ankka/database-ca`.

Operator ClusterRole additions (each with a comment saying why): `cert-manager.io/certificates`
(get, list, watch, create, patch, delete), `networking.k8s.io/networkpolicies` (same),
`gateway.networking.k8s.io/backendtlspolicies` (same), `cert-manager.io/issuers` (get, create,
patch), `events` (get, list). `secrets` unchanged.

## 6. Runtime configuration

`reference.conf` (defaults, local):

```
ankka.http.tls { enabled = off, directory = "" }
ankka.tls.cluster-directory = ""          # used by management + bootstrap client
ankka.tls.service-directory = ""          # used by the service client
ankka.probe { enabled = off, port = 7627 }
pekko.persistence.r2dbc.connection-factory {
  ssl.mode      = ${?ANKKA_DB_SSL_MODE}
  ssl.root-cert = ${?ANKKA_DB_SSL_ROOT_CERT}
  ssl.cert      = ${?ANKKA_DB_SSL_CERT}      # ankka's keys, read by ankka's factory
  ssl.key       = ${?ANKKA_DB_SSL_KEY}
  password      = ${?ANKKA_DB_PASSWORD}      # already optional; absent → no password
}
```

`ankka-cluster-kubernetes.conf` (overlay) adds the transport (R1), the four directories, probe
`on`, `tls-session-info-header = on`, and management HTTPS is programmatic in `ClusterFormation`.

Descriptor rule: `ServiceSpec.problems` refuses `ANKKA_NAMESPACE_PREFIX` as it refuses the cluster
variables. `ANKKA_DB_SSL_*` are **allowed** (the supplied-database escape hatch, FR-025).

## 7. Status and history

Operator-reported status during the transition (R9): `lifecycle = Deploying`, `detail = "moving to
mutual TLS: instances restart together, once"`. The control plane folds it as any observation;
the history shows one `Deploying` with that detail between the two `Ready`s.

`Failed` detail on a never-ready pod gains the newest readiness Event's message, e.g.
`readiness probe: Get "http://10.42.0.7:7627/ready": dial tcp: connection refused` — the shape a
pre-feature image produces.

## 8. Compatibility

`Compatibility.MinimumRuntime = Version(0, 8, 0)`. `supports(platform, declared)` is the existing
rule **and** `declared >= MinimumRuntime`. Refusal detail: `runtime <declared> predates mutual TLS;
this platform requires <minimum> or later`.
