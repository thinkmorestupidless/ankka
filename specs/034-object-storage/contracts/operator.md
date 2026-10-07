# Contract: what the operator renders and does

Decisions are in [research.md](../research.md) (R3 to R9, R13, R15, R16). Data is in
[data-model.md](../data-model.md).

## Settings

| Variable | Required when the admin URL is set | Meaning |
|---|---|---|
| `ANKKA_OBJECT_STORE_ADMIN_URL` | — | the store's administration API. Unset: the installation has no object store |
| `ANKKA_OBJECT_STORE_ADMIN_TOKEN` | yes | its bearer token |
| `ANKKA_OBJECT_STORE_ENDPOINT` | yes | given to workloads as `ANKKA_S3_ENDPOINT` |
| `ANKKA_OBJECT_STORE_REGION` | yes | given to workloads as `ANKKA_S3_REGION` |
| `ANKKA_OBJECT_STORE_SERVICE` | yes | `<namespace>/<name>:<port>`, the backend of a bucket's route |

Each has a system property, as every operator setting has. A set admin URL with another missing is
a startup failure naming the missing variable.

## The store's interface

```scala
trait ObjectStore:
  def bucket(name: String): Option[BucketInfo]
  def createBucket(name: String): BucketInfo
  def keysNamed(name: String): Vector[String]
  def createKey(name: String): IssuedKey
  def deleteKey(accessKeyId: String): Unit
  def allow(bucketId: String, accessKeyId: String): Unit

final case class BucketInfo(id: String, created: Instant, allowedKeys: Set[String])
final case class IssuedKey(accessKeyId: String, secretAccessKey: String)   // toString prints the id only
final class ObjectStoreUnavailable(reason: String) extends RuntimeException(reason)
```

`GarageStore` maps them to `GET /v2/GetBucketInfo?globalAlias=`, `POST /v2/CreateBucket`,
`GET /v2/ListKeys`, `POST /v2/CreateKey`, `POST /v2/DeleteKey?id=` and `POST /v2/AllowBucketKey`
with `read`, `write` and `owner` true. It sends no request with `showSecretKey`.

## The plan

`ObjectStorage.decide(spec, settings, observed)`, first match wins:

| # | When | Plan | Reported phase |
|---|---|---|---|
| 1 | does not ask, gives no `ANKKA_S3_` variable | `NotAsked` | no status |
| 2 | does not ask | `Supplied` | `Supplied` |
| 3 | the bucket's name is over the limit | `Failed` | `Failed` |
| 4 | no object store is configured | `Failed("the installation has no object store")` | `Failed` |
| 5 | the store could not be reached | `Waiting(Some(reason))` | `Waiting` |
| 6 | the bucket is absent, or no key named for the service is allowed on it | `Waiting(None)` | `Waiting` |
| 7 | otherwise, the bucket older than the resource | `Ready(recovered = true)` | `Recovered` |
| 8 | otherwise | `Ready(recovered = false)` | `Provisioned` |

The store is observed only for a service that asks, and only when one is configured.

## Actions

| Action | Describes | Performed as |
|---|---|---|
| `EnsureBucket(bucket)` | a bucket's name | read; create when absent; allow every key named for the service when none is allowed |
| `EnsureStorageCredential(namespace, secretName, labels, bucket)` | where the Secret goes; holds no key | `StorageCredential.ensure`, once per process per Secret |
| `EnsureReferenceGrant(grant)` | the grant | server-side apply |
| `EnsureHttpRoute(route)`, `RemoveHttpRoute(namespace, name, ownerUid)` | as today | as today |

`describe` prints namespaces and names. No action, log line or status holds a secret key.

Order within `render`: after the secret key's action and before the zero-trust actions, so the
Secret is asked for before the Deployment that names it.

| Plan | Rendered |
|---|---|
| `NotAsked`, `Supplied`, `Failed` | `RemoveHttpRoute` for `<service>-storage` |
| `Waiting(Some(_))` | the exposure actions |
| `Waiting(None)`, `Ready` | `EnsureBucket`, `EnsureStorageCredential`, the exposure actions |

The exposure actions are `EnsureReferenceGrant` then `EnsureHttpRoute` when
`spec.exposeObjectStorage` and a base domain is set, and `RemoveHttpRoute` otherwise.

## The storage credential's Secret

| | |
|---|---|
| Name | `<service>-storage`, in the project's namespace |
| Type | `Opaque` |
| Data | `ANKKA_S3_ACCESS_KEY`, `ANKKA_S3_SECRET_KEY` |
| Labels | the service's identity labels |
| Owner reference | none |
| Made | by `create`; `409` means it exists. Patched only when the store knows no key for the service |
| Never | read, listed or deleted by the operator |

`StorageCredential.ensure`, in full:

| The store has keys named for the service | The Secret's `create` | Then |
|---|---|---|
| no | `Created` | done |
| yes | `Created` | the earlier keys are deleted |
| yes | `Exists` | the key just issued is deleted; nothing else changes |
| no | `Exists` | the Secret is patched with the key just issued, and the status's detail says the service must be restarted to read it |

The key is allowed on the bucket before the `create`, so a Secret that exists holds a key that is
already allowed.

## The developer's container

For a service that asks, whatever the plan:

```yaml
envFrom:
  - secretRef: { name: reports-storage }
env:
  - { name: ANKKA_S3_ENDPOINT, value: "http://garage.garage-system.svc.cluster.local:3900" }
  - { name: ANKKA_S3_REGION,   value: "garage" }
  - { name: ANKKA_S3_BUCKET,   value: "shop.reports" }
  - { name: ANKKA_S3_PUBLIC_ENDPOINT, value: "https://storage.example.com" }   # only when reachable from the internet
```

| Hosting | On |
|---|---|
| embedded | the one container |
| wasm | the one container; `config` answers each to the module |
| process | `<service>-app`; the platform's container has none of them |
| web | `<service>-app`; the proxy has none of them |

A service that does not ask has none of this, and no object rendered for it differs from before
the feature.

## The bucket's route

```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: HTTPRoute
metadata:
  name: reports-storage
  namespace: ankka-shop
  ownerReferences: [ the AnkkaService ]
spec:
  parentRefs: [{ group: gateway.networking.k8s.io, kind: Gateway, name: ankka, namespace: ankka-gateway, sectionName: https }]
  hostnames: [ storage.example.com ]
  rules:
    - matches: [{ path: { type: PathPrefix, value: /shop.reports } }]
      timeouts: { request: "0s" }
      backendRefs: [{ name: garage, namespace: garage-system, port: 3900 }]
```

```yaml
apiVersion: gateway.networking.k8s.io/v1beta1
kind: ReferenceGrant
metadata: { name: ankka-shop, namespace: garage-system }
spec:
  from: [{ group: gateway.networking.k8s.io, kind: HTTPRoute, namespace: ankka-shop }]
  to:   [{ group: "", kind: Service, name: garage }]
```

No `BackendTLSPolicy`: the Gateway ends TLS and the store speaks none.

## `status.objectStorage`

| Plan | `phase` | `bucket` | `publicAddress` | `recovered` | `detail` |
|---|---|---|---|---|---|
| `NotAsked` | — the block is absent | | | | |
| `Supplied` | `Supplied` | empty | absent | `false` | absent |
| `Waiting(d)` | `Waiting` | the name | when exposed | `false` | `d` |
| `Ready(false)` | `Provisioned` | the name | when exposed | `false` | absent |
| `Ready(true)` | `Recovered` | the name | when exposed | `true` | absent |
| `Failed(p)` | `Failed` | the name | absent | `false` | the problems |

## Grants

The operator's ClusterRole is unchanged by this feature. Secrets need `create` and `patch`, which
it has; routes need what it has. The one new grant is the component's: a Role in `garage-system`
with `get`, `create` and `patch` on `referencegrants`, bound to the operator's ServiceAccount.

The prerequisite change removes `get` on Secrets from the ClusterRole (R19). With it, a token
minted for the operator's ServiceAccount is refused a `get` of `<service>-storage` by the API
server.
