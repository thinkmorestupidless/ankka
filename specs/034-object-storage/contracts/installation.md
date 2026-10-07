# Contract: the object store in an installation

What `kustomization/components/garage/` installs and what an overlay must supply. Decisions are in
[research.md](../research.md) (R1 to R3, R15, R17, R22).

## The component

`kind: Component`, listed after `operator` in an overlay's `components`.

| Object | Namespace | What it is |
|---|---|---|
| Namespace `garage-system` | — | not labelled `app.kubernetes.io/managed-by: ankka`: no route and no trust bundle belongs in it |
| ConfigMap `garage-config` | `garage-system` | `garage.toml` |
| StatefulSet `garage` | `garage-system` | one replica, `dxflrs/garage:v2.3.0`, `server --single-node`, one `volumeClaimTemplate` |
| Service `garage` | `garage-system` | ports `s3` 3900 and `admin` 3903 |
| NetworkPolicy `garage` | `garage-system` | below |
| Role and RoleBinding `ankka-operator-grants` | `garage-system` | `referencegrants`: `get`, `create`, `patch`, for the operator's ServiceAccount |
| Secret `garage-secrets` | `garage-system` | `rpc-secret`, `admin-token` — development values |
| Secret `ankka-object-store-admin` | `ankka-operator` | `token`, the same admin token — a development value |
| a patch on Deployment `ankka-operator`, container `ankka-operator` | `ankka-operator` | the five `ANKKA_OBJECT_STORE_*` variables, the token by `secretKeyRef` |

`garage.toml`:

```toml
metadata_dir = "/var/lib/garage/meta"
data_dir = "/var/lib/garage/data"
db_engine = "lmdb"
replication_factor = 1
rpc_bind_addr = "[::]:3901"

[s3_api]
s3_region = "garage"
api_bind_addr = "[::]:3900"

[admin]
api_bind_addr = "[::]:3903"
```

There is no `[s3_web]` section and no `root_domain`. The RPC secret and the admin token are given
as environment variables from `garage-secrets` (`GARAGE_RPC_SECRET`, `GARAGE_ADMIN_TOKEN`): Garage
refuses a secret file anyone but its owner can read, and a variable has no mode to get wrong.
Readiness is an `exec` probe, `/garage status`, so no port is opened for
the kubelet.

## Who may connect

| Port | From |
|---|---|
| 3900 `s3` | pods labelled `app.kubernetes.io/managed-by: ankka` in namespaces with that label; the gateway's proxy pods in `envoy-gateway-system` |
| 3903 `admin` | the operator's pods |
| 3901 `rpc` | the store's own pods |

Connections are plain HTTP inside the cluster. From outside it, a request is TLS to the Gateway,
under the installation's wildcard certificate.

## Overlays

| | `overlays/local` | `overlays/cloud` |
|---|---|---|
| the component | listed | listed |
| `garage-secrets`, `ankka-object-store-admin` | as shipped | deleted with `$patch: delete`; the store and the operator do not start until both exist |
| the store's hostname | `storage.127.0.0.1.sslip.io:8443`, derived by the operator | `storage.<base>`, derived by the operator |
| replicas | 1 | 1; an installation's own overlay raises it and applies a layout |

No replacement is added: the component has no route and names no base domain. The operator derives
the hostname from `ANKKA_BASE_DOMAIN` and `ANKKA_HTTPS_PORT`, which it already has.

`RemoteOverlaySuite` asserts, for both overlays: each `ANKKA_OBJECT_STORE_*` variable is set
exactly once on the operator and the operator has one container; the two development Secrets are
absent from the cloud render and present in the local one; the store's image is the pinned tag; and
nothing of the local overlay's addresses appears in the cloud render.

## An installation without the component

The operator has no `ANKKA_OBJECT_STORE_ADMIN_URL`. A service that asks for a bucket is reported
`object storage provisioning failed` with `the installation has no object store`, and none of its
instances starts. A service with an object store of its own is unaffected.

## `deploy-local.sh`

One wait is added, for `statefulset/garage` in `garage-system`, beside the waits it has. It applies
nothing the overlay does not.

## The k3s suites

`ObjectStoreStack.install(k3s, k8s, repoRoot)` in the operator's test sources applies the
component's files with the node's `kubectl`, as `GatewayStack` and `KeycloakStack` apply theirs,
and waits for the StatefulSet. It returns the admin URL a test's own `GarageStore` uses to ask
whether a bucket exists.
