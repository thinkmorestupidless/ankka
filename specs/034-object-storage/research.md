# Research: Object Storage

Decisions for [plan.md](plan.md), each with what in the repository or in the store it rests on.
File references are to this branch's base (`64102d79`). "Verify first" marks a claim read from
code or documentation and not yet run; the task that touches it starts with a test or a spike that
would show it false. The words are the glossary's: a service is given a **bucket** in the
installation's **object store** and a **storage credential** that reaches it; a **signed URL** is
what the service gives a browser.

## R1. The store is Garage, not MinIO

**Decision**: the installation's object store is [Garage](https://garagehq.deuxfleurs.fr/), image
`dxflrs/garage:v2.3.0`, driven through its administration API v2. The spec named MinIO; FR-008 and
the assumptions are amended.

**Rationale**: MinIO's community edition no longer exists as something to install. Its admin
console was removed in May 2025, binaries and images stopped in October 2025, the repository was
marked unmaintained on 2026-02-12 and archived on 2026-04-25, and `minio/minio` and `minio/mc` were
deleted from Docker Hub on 2026-09-11. A component that names an image nobody publishes cannot be
applied.

Garage has the model the spec asks for and nothing else to map: an access key, a bucket, and a
per-key-per-bucket permission (`read`, `write`, `owner`). There are no policies or ACLs to write.
`POST /v2/CreateKey`, `POST /v2/CreateBucket` and `POST /v2/AllowBucketKey` are the whole of
provisioning; `GET /v2/GetBucketInfo` returns `created`, which is what `recovered` needs (R7). It
implements signature v4, presigned URLs, path-style addressing, multipart upload and bucket CORS.
It is one static binary; `garage server --single-node` configures a one-node cluster without the
layout commands, so the component needs no script step (R2).

**Alternatives considered**: SeaweedFS (Apache 2.0, mature) — rejected: its S3 credentials are a
JSON file mapping keys to permissions, so issuing a key per service means rewriting shared
configuration instead of calling an API. RustFS — rejected: a release candidate. Ceph through Rook
— rejected: a storage cluster of several daemons to give a kind node one bucket. Pinning the last
MinIO source release and building an image here — rejected: it makes this repository the
maintainer of an archived server.

**Licence**: Garage is AGPL-3.0. The platform runs it unmodified as a separate program reached
over HTTP and links none of it, so nothing of ankka's licence changes; an installation that
modifies Garage owes its users the source.

**Verify first**: `garage server --single-node` exists in the v2.3.0 image and leaves a node that
accepts `CreateBucket` with no layout step (verified; the secrets are given as variables, not files); `/garage status` exits 0 once the node serves and non-zero before. Spike S1.

## R2. The component: one node, in `garage-system`, and `kubectl apply -k` is the whole deploy

**Decision**: `kustomization/components/garage/` is a `Component` holding a Namespace
`garage-system`, a ConfigMap with `garage.toml`, a one-replica StatefulSet with one
`volumeClaimTemplate`, a Service `garage` with named ports `s3` (3900) and `admin` (3903), a
NetworkPolicy, a Role and RoleBinding (R15), and a patch that adds the operator's settings to the
`ankka-operator` Deployment (R3). The configuration has `replication_factor = 1`, `db_engine =
"lmdb"`, `s3_region = "garage"`, no `root_domain` and **no `[s3_web]` section**. Readiness is an
`exec` probe, `/garage status`, because the image has no shell and a probe over the network would
need the admin port opened to the kubelet, which a policy can only say as "anyone". Two development Secrets, the RPC secret
and the admin token, are in the component, and the admin token is there twice: in `garage-system`
for Garage and in `ankka-operator` for the operator. The example cloud overlay deletes all three
with `$patch: delete`, so the store and the operator do not start until real ones exist.

**Rationale**:

- **The namespace is outside the `ankka-<name>` pattern.** A project's namespace is
  `ankka-<projectId>` and only the id `platform` is reserved (`descriptors.scala:100`,
  `Names.scala:45`), so a platform namespace `ankka-storage` is also the namespace of a project
  called `storage`. `cnpg-system` and `envoy-gateway-system` are the precedent for a third-party
  component's namespace.
- **No script step.** An overlay that only works from `deploy-local.sh` is not an overlay; the
  schema ConfigMap and the realm were both that defect. `--single-node` is what removes Garage's
  one imperative step. The script gains one line, a wait for the rollout, as it waits for others.
- **No `[s3_web]`.** With no web listener, a bucket whose owner turns on website access (R16) is
  still served to nobody without a signature, which is FR-015.
- **No `root_domain`.** Only path-style addressing works, which is what one hostname with the
  bucket in the path needs (R15).
- **The admin secret follows Keycloak's.** `ankka-keycloak-admin` is public in the repository, so
  the cloud overlay deletes it and the identity provider refuses to start
  (`overlays/cloud/kustomization.yaml`, the `$patch: delete` entries; `RemoteOverlaySuite`'s
  "absent remotely and present locally"). The same test shape holds the three Secrets here.
- **The image is pulled by the node.** `dxflrs/garage` is on Docker Hub; `deploy-local.sh` loads
  only images this build makes.

One replica with one volume means the store's durability is its disk's. That is right for kind
and thin for a real installation; an installation's own overlay raises the replica count and the
replication factor, and Garage then needs a layout applied through its admin API. That is recorded
in the docs (R21) and is not built here.

**Alternatives considered**: Garage's Helm chart — rejected: it renders Kubernetes discovery
through a CRD and a ClusterRole the single node does not need, and a kustomize component cannot
apply a chart. A `Job` that assigns the layout — rejected for the default once `--single-node`
does it. A namespace `ankka-object-store` — rejected for the collision above.

**Verify first**: a strategic merge patch in a Component reaches a Deployment an earlier component
in the same overlay declared, and names the container `ankka-operator` (a patch naming a container
the target lacks adds one — the sidecar-image trap). `RemoteOverlaySuite` asserts each variable is
set exactly once and the operator still has one container.

## R3. What the operator is told, and what "no object store" means

**Decision**: `Settings` (`operator/…/Settings.scala:16-52`) gains
`objectStore: Option[ObjectStoreSettings]`:

| Variable | Meaning | In the component |
|---|---|---|
| `ANKKA_OBJECT_STORE_ADMIN_URL` | the administration API | `http://garage.garage-system.svc.cluster.local:3903` |
| `ANKKA_OBJECT_STORE_ADMIN_TOKEN` | its bearer token | from the Secret `ankka-object-store-admin`, `secretKeyRef` |
| `ANKKA_OBJECT_STORE_ENDPOINT` | what a workload is given as `ANKKA_S3_ENDPOINT` | `http://garage.garage-system.svc.cluster.local:3900` |
| `ANKKA_OBJECT_STORE_REGION` | what a workload is given as `ANKKA_S3_REGION` | `garage` |
| `ANKKA_OBJECT_STORE_SERVICE` | the backend of a bucket's route, `<namespace>/<name>:<port>` | `garage-system/garage:3900` |

`objectStore` is `Some` when the admin URL is set, and `Settings.fromEnvironment` fails naming the
variable when the URL is set and any of the others is missing. With no admin URL the installation
has no object store: a service that asks for a bucket is reported `Failed` with the detail "the
installation has no object store" (R7), with no call made to anything.

**Rationale**: `Settings` already reads the base domain and the two images this way, system
property first (`Settings.scala:88-128`), which is what lets `SettingsSuite` set them in-process.
Putting the variables in the component's patch rather than in `components/operator/operator.yaml`
is what makes "the store is not installed" a fact about the overlay's component list: an overlay
that omits the component renders an operator with no admin URL. The base domain and HTTPS port the
public address needs are already settings (`baseDomain`, `httpsPort`).

**Alternatives considered**: discovering the store by looking for its Service — rejected: a read
the operator has no grant for, and a store that is down would read as a store that is absent,
which are two different reports (R7).

## R4. The seam: an `ObjectStore` trait, and Garage behind the JDK's HTTP client

**Decision**: the operator gains

```scala
trait ObjectStore:
  def bucket(name: String): Option[BucketInfo]              // id, created, the keys allowed on it
  def createBucket(name: String): BucketInfo
  def keysNamed(name: String): Vector[String]               // access key ids, exact name match
  def createKey(name: String): IssuedKey                    // id and secret; the secret is returned once
  def deleteKey(accessKeyId: String): Unit
  def allow(bucketId: String, accessKeyId: String): Unit    // read, write and owner
```

and `GarageStore(adminUrl, token)` implements it with `java.net.http.HttpClient` and the Jackson
that `crd` already brings. A call that cannot connect, times out or is answered 5xx throws
`ObjectStoreUnavailable`; any other failure throws with the store's message. `Fabric8Executor`
takes an `Option[ObjectStore]`.

**Rationale**: the operator's library dependencies are `fabric8` and `logback`
(`build.sbt:390-424`), and a process whose job is to keep working while other things are broken
should depend on as little as possible. Garage's admin API is JSON over HTTP with a bearer token,
so a client library would add only weight. The trait is FR-008's "one interface": a cloud
provider's buckets are a second implementation, and the credential logic (R6) is written against
the trait so it does not move.

`createKey` never asks for the secret again. The admin API can return a key's secret
(`GetKeyInfo?showSecretKey=true`); `GarageStore` has no method that sends it, and `GarageStoreSuite`
asserts no request it makes carries that parameter.

**Alternatives considered**: MinIO's or AWS's Java SDK in the operator — rejected: neither speaks
Garage's admin API, and the AWS SDK alone is larger than the operator. Rendering a `Job` that runs
a client image — rejected: the Job would need the admin token in every project's namespace, where
a descriptor's `secretKeyRef` could name it.

**Verify first**: `ListKeys` returns names, so `keysNamed` can match exactly; `AllowBucketKey` is
idempotent; `CreateBucket` with a `globalAlias` that exists answers a status `GarageStore` can tell
from a fault. `GarageStoreSuite` runs against the real image in a container, not a fake (spike S2
becomes that suite).

## R5. Names

**Decision**: a new object `Buckets` in `crd`, beside `Hostnames`:

```scala
object Buckets:
  val MaxName: Int = 63
  val SecretSuffix: String = "-storage"
  def name(projectId: String, serviceName: String): String = s"$projectId.$serviceName"
  def secret(serviceName: String): String = s"$serviceName$SecretSuffix"
  def problems(projectId: String, serviceName: String): Vector[String]   // over MaxName, naming the limit
  def publicEndpoint(baseDomain: String, httpsPort: Int): String         // https://storage.<base>[:port]
  def publicAddress(projectId: String, serviceName: String, baseDomain: String, httpsPort: Int): String
```

and `Hostnames` gains `StorageLabel = "storage"`. The access key's name in the store is the
bucket's name. The Secret is `<service>-storage`. A bucket's route is `<service>-storage` (R15).

**Rationale**: the bucket's name is global to the store, so it carries the project, where a
database's name does not (`CnpgRendering.database`: name and owner are the service name, inside a
per-project cluster). The separator is a dot because a project id and a service name are DNS
labels and cannot contain one (`descriptors.scala:61`, `Names.isLabel`), so the name is
unambiguous. A hyphen would repeat the hostname's collision — `a-b` in `c` and `a` in `b-c` — which
the control plane has to refuse at expose time by looking at every other service
(`ServiceEndpoint.scala:192-196`). A collision here would hand one service another's objects, so
it is made impossible instead of refused. The rule lives in `crd` for the reason `Hostnames` does:
the control plane refuses it and shows it, the operator renders it, and the resource carries only
two booleans, so no writer of the resource can point a service at a bucket that is not its own.

A project id is at most 57 characters and a service name at most 63, so the name can pass the 63
an S3 bucket name allows; the edge case in the spec is real.

**Alternatives considered**: `<project>-<service>` with a collision check — rejected above. A
hash suffix — rejected: a member reads this name in a status and types it into a tool. The
namespace prefix in the name — rejected: one store serves one installation.

**Verify first**: Garage accepts a dot in a global alias and serves path-style requests and
presigned URLs for it. Spike S2.

## R6. A credential is issued once, and nothing is read back from anywhere

**Decision**: `Action.EnsureStorageCredential(namespace, secretName, labels, bucket)` holds no key.
`describe` prints the namespace and the name. Performing it is one function,
`StorageCredential.ensure(store, secrets, …)`, written against `ObjectStore` and a two-method
`SecretWriter` (`create` answering `Created` or `Exists`; `patch`), so it is tested with doubles:

```text
if this process has already ensured (namespace, secretName): stop
existing = store.keysNamed(bucket)
key      = store.createKey(bucket);  store.allow(bucketId, key.id)
create the Secret holding key
  Created → delete every key in `existing`          # their secrets are in no Secret
  Exists  → if existing is empty: patch the Secret with key   # the store knows no key: the old one is dead
            else: store.deleteKey(key.id)            # the Secret holds one of `existing`; discard ours
remember (namespace, secretName)
```

The Secret is `Opaque`, has the service's identity labels and **no owner reference**, and holds
two entries, `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`. Every pass also allows every key
named for the service on its bucket when the bucket shows none allowed, which repairs a bucket the
service deleted and the operator made again (R16).

**Rationale**: this is `EnsureSecretKey`'s shape (`Executor.scala:276-304`): an action with no
secret in it, bytes that exist only in the executor, a `create` whose 409 is success, and a
per-process set so the API server is asked once per process and not once per reconcile. What is
new is that the secret comes from the store, and the store returns it once. So the Secret's
existence is learned from the `create` itself, with a key issued speculatively and discarded on a
conflict. Allowing the key *before* the `create` gives the invariant that a Secret which exists
holds a key that is already allowed on the bucket.

What each interruption leaves:

| Interrupted after | Next pass | Result |
|---|---|---|
| `createKey` | a second key is issued, the Secret is created, the first key is deleted | one key, in the Secret |
| the Secret's `create` | a speculative key meets `Exists` and is deleted | unchanged |
| deleting stale keys part way | a speculative key meets `Exists` and is deleted | a dead key remains whose secret no one holds |
| the store lost its keys | `existing` is empty and the `create` meets `Exists`, so the Secret is patched | a new credential; the detail says the service must be restarted to read it |
| the service was deleted and applied again | the Secret was kept, so `Exists`, and the speculative key is deleted | the same credential, which is "made once and never replaced" |

No owner reference is the database's rule (`CnpgRendering.scala:15-19`, `Rendering.scala:645-648`)
and the secret key's: the garbage collector runs with cluster privilege, not the operator's, so an
owned Secret would be deleted by the platform. The grant is `create` and `patch`, which the
operator's ClusterRole already has for Secrets; `get` is not used, so this code is correct before
and after the prerequisite removes the verb (R19).

`deleteKey` removes a key whose secret is in no Secret. It is not a deletion of anything a service
has: the rule is that the platform deletes no bucket and no object.

**What "never reads it back" covers**. The operator cannot read the Secret from the cluster once
the prerequisite lands, and never asks the store for a secret. It does hold the store's
administrator token, as it administers each project's Postgres cluster; whoever holds that token
can grant themselves any bucket. The docs say this in those words (R21).

**Alternatives considered**: asking the store for the secret (`showSecretKey`) to fill a missing
Secret — rejected: it makes "never read back" a statement about one API and not the other, for the
sake of a crash window a second key closes. `ImportKey` with an id the operator derives — rejected
by Garage's own warning ("do not use it to generate custom key identifiers or you will break your
Garage cluster"). Recording the key id in the resource's status and rotating when it is absent —
rejected: the status is written at the end of a pass, after the Deployment, so a crash between
would rotate a credential under pods that had started with it.

**Verify first**: fabric8 answers a `create` of a Secret that exists with a
`KubernetesClientException` whose code is 409 under the operator's real ServiceAccount (it does
for `EnsureSecretKey`); a JSON merge patch of `stringData` under `patch` alone is admitted.

## R7. The plan, the phases, and what "recovered" means

**Decision**: a second plan beside the database's, decided by a pure function:

```scala
enum ObjectStoragePlan:
  case NotAsked                         // no status
  case Supplied                         // the descriptor gives an ANKKA_S3_ variable
  case Waiting(detail: Option[String])
  case Ready(recovered: Boolean)
  case Failed(problems: Vector[String])

def decide(spec: AnkkaServiceSpec, settings: Settings, observed: ObjectStorageObservation): ObjectStoragePlan
```

First match wins:

1. `!spec.provisionObjectStorage` and no variable of the declared prefix in `spec.env` → `NotAsked`.
2. `!spec.provisionObjectStorage` → `Supplied`.
3. `Buckets.problems` non-empty → `Failed` (the control plane refuses this first; the operator
   trusts no writer of the resource).
4. `settings.objectStore` is `None` → `Failed("the installation has no object store")`.
5. `observed.unreachable` → `Waiting(Some(reason))`.
6. the bucket is absent, or no key named for the service is allowed on it → `Waiting(None)`.
7. otherwise → `Ready(recovered = observed.bucketCreated is before the resource's creation)`.

The reported phases are the database's: `Waiting`, `Provisioned`, `Recovered`, `Supplied`,
`Failed`. `ObjectStorageObservation` is `(unreachable: Option[String], bucketCreated:
Option[Instant], keyAllowed: Boolean, resourceCreatedAt: Option[Instant])`, read by a new
`Executor.observeObjectStorage` only for a service that asks and only when a store is configured.

**Rationale**: `Provisioning.decide` is this shape and `ProvisioningSuite` is its rule table
(`Provisioning.scala:86-136`); the database's `recovered` compares the credential Secret's
creation time with the resource's (`DatabaseObservation.scala:51`), which needs a read of the
Secret. The store reports when a bucket was created, so the same comparison needs no read from the
cluster. `Executor.resourceCreatedAt` exists already (`Executor.scala:567-569`).

Unreachable is decided at observation so that it is *reported*. An action that throws aborts the
pass before `SetStatus` (`WorkQueue.scala:103-113`), which is why a missing CNPG is never reported
today; here an unreachable store renders no store action, so the pass completes and the status
says `Waiting` with the reason. A store that is not installed is a different report from one that
is down, and rule 4 needs no call to tell them apart (R3).

The operator infers `Supplied` from `spec.env`, where the database's flag alone says it. The
flag's default here is `false`, so `false` alone cannot tell "its own" from "none"; the env is on
the resource, and the prefix is read from the one declaration the operator already compiles (R10).

**Alternatives considered**: a three-valued field on the resource (`none`, `provision`,
`supplied`) — rejected: a second statement of something the env already says, which the two could
disagree about. Folding storage into `ProvisioningPlan` — rejected: every combination of two
independent plans would be a case.

## R8. What is rendered, in what order, and nothing for a service that does not ask

**Decision**: `Rendering.render` gains `objectStorageActions(resource, spec, namespace, settings,
plan)` after `secretKeyAction` and before `zeroTrustActions`:

| Plan | Actions |
|---|---|
| `NotAsked`, `Supplied` | `RemoveHttpRoute` for the bucket's route, and nothing else |
| `Failed` | `RemoveHttpRoute` |
| `Waiting(Some(_))` (the store is unreachable) | the exposure actions of R15, which do not reach the store |
| `Waiting(None)`, `Ready` | `EnsureBucket(bucket)`, `EnsureStorageCredential(…)`, then the exposure actions of R15 |

`EnsureBucket` is rendered on every such pass and performs a read before a create; it and the
credential action are the only ones that reach the store. The Deployment is applied whatever the
plan is, as it is for the database.

**Rationale**: the Deployment is never withheld for a database either; what stops a pod running
without one is that its `envFrom` names a Secret that does not exist yet, and the container cannot
be created until it does (`Rendering.scala:1172-1183`). The same holds here: a service that asks
is rendered with `envFrom` on `<service>-storage` whatever the plan, so with no store there is no
Secret and no instance starts, which is the scenario, and the kubelet's own reason reaches the
status through `podProblems`.

The route's removal is rendered for every service, asked or not, as an unexposed service's own
route is removed on every pass (`Rendering.scala:445-459`). It has to be: a member can drop both
fields in one apply, and a removal rendered only for a service that still asks would leave that
bucket's route in place. It is owner-checked and, for a service that never had one, a read that
finds nothing.

So a service that does not ask and gives no `ANKKA_S3_` variable is rendered with **no object
changed**: no variable, no annotation, no volume. `RenderingUnchangedSuite` pins four such
services with every action in order, removals included, so its fixtures gain exactly one line
each, `# RemoveHttpRoute`, and are repinned once, on purpose, with `-Dankka.rendering.pin=true`.
The repin's diff is the proof that upgrading the operator rolls no pod: it must contain that line
and nothing else. (Feature 023 added a variable to every pod template and every service rolled
once; this one must not.)

## R9. Where the variables go, and which are in the Secret

**Decision**: for a service that asks, the developer's container is given

| Variable | From |
|---|---|
| `ANKKA_S3_ACCESS_KEY`, `ANKKA_S3_SECRET_KEY` | `envFrom` the Secret `<service>-storage` |
| `ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION` | literals, from the operator's settings |
| `ANKKA_S3_BUCKET` | a literal, `Buckets.name` |
| `ANKKA_S3_PUBLIC_ENDPOINT` | a literal, `Buckets.publicEndpoint`, only when the bucket is reachable from the internet |

| Hosting | Containers | The variables are on |
|---|---|---|
| embedded | one | that one |
| wasm | one, the platform's | that one; the module's `config` import answers them |
| process | the platform's and `<service>-app` | `<service>-app` only |
| web | the proxy and `<service>-app` | `<service>-app` only |

**Rationale**: the Secret is made once and never rewritten, so only what never changes belongs in
it. The store's address is the installation's and the public endpoint follows a flag a member can
turn off; as literals they change with the pod template, which rolls the pods that must read them.
The database's Secret holds host, port, name and user because it predates that reasoning and holds
nothing secret (`CnpgRendering.scala:262-290`).

`ANKKA_S3_REGION` is the fifth variable. A client signs for a region; Garage answers a signature
made for another region with `AuthorizationHeaderMalformed`. Most clients default to `us-east-1`,
so without the variable the first request of "any S3 client" fails with an error that names
neither the region nor the fix.

The placement is new rendering, not a copy of the database's: a process-hosted service's database
`envFrom` is on the platform's container only (`ProcessHostingRenderingSuite`, "the credential
reaches the sidecar only"), because the sidecar opens the database. Nothing of the platform's
opens a bucket. The shared `container` builder (`Rendering.scala:1154-1340`) takes a new
`storageEnv` argument that the embedded and wasm cases pass and the process and web cases pass to
`<service>-app` instead. In a module, `HostImports.lookup` answers any variable that is not
withheld (`HostImports.scala:144-149`), and these are not (R10).

A descriptor's own `ANKKA_S3_` variables already reach the right container with no change: in
process hosting everything that is not `runtimeOnly` goes to the process (`Rendering.scala:995`),
and in web hosting the process gets all of `spec.env`.

**What the docs must say** (R21): the client is configured for path-style addressing, since the
store has no hostname per bucket.

## R10. `ANKKA_S3_` is declared once, and is not a platform setting

**Decision**: `PlatformVariables` gains

```scala
/** The developer's program's own: where its bucket is and how to reach it. Never the platform's. */
val ObjectStoragePrefix: String = "ANKKA_S3_"
def objectStorage(name: String): Boolean = name.startsWith(ObjectStoragePrefix)
```

It joins none of `PlatformOnly`, `RuntimeOnlyPrefixes`, `SharedPrefixes` or `RuntimeReadPrefixes`.
`ServiceSpec.problems` and `ObjectStorage.decide` read it; no file writes the literal a second
time.

**Rationale**: the spec said the prefix joins "the shared reserved-variable declaration", written
before feature 023 landed. What landed is a declaration of variables kept *from* the developer's
program: `runtimeOnly` routes a variable to the sidecar and `withheldFromModule` hides it from a
module. Adding this prefix to either would do the opposite of FR-004. What the spec wanted is that
the prefix is said once, which this is; `PlatformDeclarationSuite` and `PlatformVariablesSuite`
hold it. The glossary agrees: a platform setting is "for the platform's program and never for the
developer's", and these are not that. (`"ANKKA_DB_"` is still a bare literal at
`descriptors.scala:364` and `ServiceProjection.scala:125`; that is not this feature's to change.)

## R11. The descriptor: two fields and three refusals

**Decision**: `ServiceSpec` (`descriptors.scala:116-198`) gains
`provisionObjectStorage: Boolean = false` and `exposeObjectStorage: Boolean = false`, and
`problems` gains `objectStorageProblems`:

| The descriptor | Refusal |
|---|---|
| sets `provisionObjectStorage` and gives `ANKKA_S3_X` | `provisionObjectStorage cannot be combined with env var 'ANKKA_S3_X', which supplies an object store of the service's own` |
| sets `exposeObjectStorage` without `provisionObjectStorage` | `exposeObjectStorage needs provisionObjectStorage: only a bucket the platform made can be reached from outside the cluster` |

The second covers both rows of the scenario outline: a descriptor that gives its own store cannot
also set `provisionObjectStorage`. The bucket's name is checked where the project id is known:
`ServiceEndpoint`'s apply, beside `descriptor.problems` (`ServiceEndpoint.scala:81-85`), adds
`Buckets.problems(projectId, name)` when the descriptor asks, and `ServiceProjection.project` adds
it to its own list, so an apply is refused with a 400 naming the 63-character limit and a
projection can never write a resource the operator would fail.

All hostings may set both fields, web hosting included; `webProblems` gains nothing.

**Rationale**: both are positive booleans with a `false` default, so a JSON `null` reads as
absent and an old journal's `ServiceApplied` decodes as before (the shared codec omits a field at
its default; `EventCompatibilitySuite` gets one "decodes as before" assertion and one round trip,
as gRPC's fields did). The wording follows the neighbours' ("…conflicts with the service port;
declare the port instead"): what was said, and why it cannot stand. `ServiceDescriptor.problems`
has no project id, which is why the hostname's limit is checked in the endpoint too.

**Alternatives considered**: one field with three values — rejected: exposure without a bucket
would then be unrepresentable, but so would the refusal's message, and two booleans mirror
`provisionDatabase` and `exposed` on the resource.

## R12. A storage credential's Secret is one the platform issues

**Decision**: `"-storage"` is added to `ServiceSpec.PlatformSecretSuffixes`
(`descriptors.scala:427`) and to `ProjectSecrets.ReservedSuffixes` (`descriptors.scala:1180`), and
`ReservedSecretNamesSuite` adds `Buckets.secret` to the names it derives from the operator, so the
two lists cannot fall behind a third. The console's fake control plane mirrors the suffix
(`fake-control-plane.ts:636`) and `docs/platform/secrets.md` lists it.

**Rationale**: a storage credential is the first per-service Secret since certificates that holds
something worth stealing and sits where a sibling's descriptor can name it. `<service>-db` is
deliberately not refused (`docs/reference/limitations.md:52-55`) because it has held no password
since certificate authentication; this one holds a secret key. Without the rule, "service A's
credential is refused by service B's bucket" passes while a member hands A the credential of B in
one line of a descriptor. A service's own is refused too: the variables already arrive, and one
suffix rule is simpler to state than "any but your own".

**What it costs**: a project secret a member already named `…-storage` can no longer be set, and a
descriptor that takes a variable from one is refused at its next apply. Project secrets shipped
one feature ago, so there are few; the upgrade notes say it (R21). If a service is given a bucket
while a project secret of its Secret's name exists, the operator's `create` meets `Exists` with no
key in the store and patches two entries into it (R6); the member's own entries stay.

`ProjectSecrets.ReservedSuffixes` also lacks `-mount-tls`, which `PlatformSecretSuffixes` has. It
is the same omission this decision avoids and is fixed in the same edit, with its row in
`ProjectSecretsSuite`.

## R13. The resource: two fields and a status block, declared in the schema

**Decision**: `AnkkaServiceSpec` gains `provisionObjectStorage: Boolean = false` and
`exposeObjectStorage: Boolean = false`; `AnkkaServiceStatus` gains
`objectStorage: Option[ObjectStorageStatus] = None`:

```scala
final case class ObjectStorageStatus(
    phase: String = "",                  // Waiting, Provisioned, Recovered, Supplied, Failed
    bucket: String = "",
    publicAddress: Option[String] = None,
    recovered: Boolean = false,
    detail: Option[String] = None
)
```

`ankkaservice.yaml` declares all three, the phase as the database's enum. `CrdSchemaSuite` gains a
case that compares the *nested* properties of `status.objectStorage` with the case class's fields
in both directions.

**Rationale**: a structural schema is closed, so a field the case class has and the schema lacks
is refused by server-side apply on every projection, forever, and no offline test sees it;
`CrdSchemaSuite` exists for that. Today it compares top-level fields only, and status in one
direction (`CrdSchemaSuite.scala:52-80`), so a property missing inside the new block would pass.
SC-005 says a field added to either side alone must fail, which for a status block means its
insides. `AnkkaServiceCodecSuite` gains the "an older resource decodes with the default" and
"absent is omitted, not null" cases the database's block has (L138-150, L173-198).

The comments that say `status.database` is "absent on the escape-hatch path"
(`AnkkaService.scala:237`, `ankkaservice.yaml:253`) are wrong — the operator reports `Supplied` —
and the new block's comments say what is true: absent only when the service neither asks nor
supplies.

## R14. The status a member reads

**Decision**: the control plane carries only the phase through its journal, as it does for the
database, and derives the rest:

- `ServiceObserved` and `ServiceObservation` gain `objectStorage: Option[String] = None`
  (`events.scala:247-290`); `StatusIngest` sets it from `status.objectStorage.map(_.phase)` and
  folds a `Waiting` or `Failed` block's `detail` into the observation's detail as
  `object storage: <detail>`, the way `withRoute` folds the route (`StatusIngest.scala:97-102`).
- `Service` gains `objectStorage: Option[String] = None` and one function,
  `Service.objectStoragePhrase`, used by `toStatus` **and** by `ServiceRows`:

  | Phase | Phrase |
  |---|---|
  | `Waiting` | `waiting for object storage` |
  | `Provisioned` | `provisioned` |
  | `Recovered` | `recovered existing bucket` |
  | `Supplied` | `supplied` |
  | `Failed` | `object storage provisioning failed` |

- The wire's `ServiceStatus` gains `objectStorage: Option[String]`, `bucket: Option[String]` and
  `bucketAddress: Option[String]`. `bucket` is `Buckets.name` when the descriptor asks;
  `bucketAddress` is `Buckets.publicAddress` when it also exposes and the base domain is known,
  filled where `withHostname` fills the hostname.
- `ankka services get` prints `object storage`, `bucket` and `bucket address` after `database`,
  each only when present.

**Rationale**: `ServiceStatus.database` is "deliberately a phrase, not the operator's full
DatabaseStatus" (`descriptors.scala:656-662`) and `StatusIngest` drops everything but the phase.
The bucket's name and address are functions of the project, the service, the base domain and the
port, all of which the control plane has, so journaling them would store something derivable and
make a rename of the rule a migration. The detail has to travel, since "the installation has no
object store" is the whole of what a member needs to read; the route's reason already travels this
way.

The listing returns the database's raw phase where `services get` returns the phrase
(`ServiceRows.scala:95-125` against `model.scala:551-575`), because the phrase function is called
in one place. Using one function in both for object storage does not repeat that; the database's
is left as it is.

`ServiceRows` destructures `ServiceObserved` positionally, so the new field is a compile error
there until it is carried, and `StatusIngest`'s three non-reporting branches must carry the last
value forward as they do the database's. `OutputSuite` pins `database` as the last line for an
embedded service (L198-202); the new lines are present only for a service with object storage, so
that case is unchanged.

## R15. A bucket reachable from the internet

**Decision**: when `spec.exposeObjectStorage` is true, the plan is `Waiting(None)` or `Ready`, and
the operator has a base domain, the operator renders two objects and one variable:

- **`EnsureReferenceGrant`**: a `ReferenceGrant` in the store's namespace, named for the project's
  namespace, from `HTTPRoute`s of that namespace to the Service `garage`. It has no owner and is
  never removed.
- **`EnsureHttpRoute`**: an `HTTPRoute` named `<service>-storage` in the project's namespace,
  owned by the resource. `parentRefs` is the installation's Gateway, section `https`, as a
  service's route has; `hostnames` is `storage.<base>`; one rule matches `PathPrefix /<bucket>`,
  with `timeouts.request: "0s"`, and its `backendRef` names the store's Service, namespace and
  port from the settings.
- `ANKKA_S3_PUBLIC_ENDPOINT` on the developer's container (R9), and `publicAddress` in the status.

Otherwise `RemoveHttpRoute(namespace, "<service>-storage", ownerUid)`. The component's Role and
RoleBinding grant the operator's ServiceAccount `get`, `create` and `patch` on `referencegrants`
in `garage-system` only; the operator's ClusterRole does not change. The component's NetworkPolicy
admits the gateway's proxy pods to port 3900, named as the control plane's policy names them
(`components/controlplane/zero-trust.yaml:58-81`).

**Rationale**:

- **One hostname, the bucket in the path.** The Gateway's listener is `*.<base>` with a wildcard
  certificate, and a wildcard is one label deep, so `storage.<base>` is covered and
  `<bucket>.storage.<base>` is not. A platform label with no hyphen cannot collide with a
  service's hostname, which is `<service>-<project>` (`api`, `auth` and `console` rest on the
  same fact).
- **The route is in the project's namespace** so it is owned by the service's resource: deleting
  the service removes the route by garbage collection, and there is no sweep. A route in the
  store's namespace could not be owned across namespaces and would outlive its service, leaving a
  deleted service's bucket reachable.
- **A reference across namespaces needs the grant.** Without it the route is `ResolvedRefs: False
  / RefNotPermitted` and Envoy answers 500. A `ReferenceGrant` names its `from` namespace exactly
  and has no selector, so there is one per project that has ever exposed a bucket. It permits
  nothing by itself; a project id cannot be created again after deletion, so a leftover grant
  names a namespace that will not return. Granting the verbs by a Role in the store's namespace
  keeps the operator unable to write a grant anywhere else.
- **`timeouts.request: "0s"`**: Envoy's default route timeout is fifteen seconds, which ends an
  upload or a download of any size worth presigning.
- **Private until asked is a route that does not exist.** With no route for a bucket's path, the
  Gateway answers 404 and the request never reaches the store; that is the scenario's "does not
  reach the object store". Turning the flag off removes the route, so a signed URL made before
  stops working whatever its expiry.
- **The signature covers the host.** A presigned URL is signed for `storage.<base>[:port]`, and
  Envoy passes the request's authority to the backend unchanged, so the store verifies what the
  service signed. The service signs against `ANKKA_S3_PUBLIC_ENDPOINT` and reads and writes
  through `ANKKA_S3_ENDPOINT`.

The Gateway terminates TLS and the store speaks none (R17), so the route has no
`BackendTLSPolicy`.

**Alternatives considered**: one static route for the whole store — refused by clarification. An
`ExternalName` Service per project, to avoid the grant — rejected: support is the
implementation's own and it is the shape of a known redirect weakness. Envoy Gateway's
`SecurityPolicy` to add CORS at the Gateway — rejected in R16.

**Verify first**: Envoy Gateway v1.9.1 merges `HTTPRoute`s from several namespaces on one
hostname by path; it honours the grant; and it forwards the authority with its port, so a URL
signed for `storage.<base>:<mapped port>` verifies on k3s. Spike S3, in a k3s suite, before the
rendering is written.

## R16. A service owns its bucket's settings

**Decision**: `allow` grants `read`, `write` and `owner`. The platform sets no CORS rule and no
lifecycle rule; a service sets its own with its S3 client. FR-017.

**Rationale**: a browser page that sends a file to a presigned URL with `fetch` makes a
cross-origin request, since the page is at the interface's hostname and the store at
`storage.<base>`. The browser sends a preflight, and without a CORS rule on the bucket the upload
is refused by the browser — while `curl` succeeds. A test that proves "a browser keeps an object"
with `curl` alone would pass while the thing it checks is false. Bucket CORS is an S3 call
(`PutBucketCors`) and Garage admits it only from a key that owns the bucket; its admin API cannot
set it.

What `owner` also allows: a lifecycle rule (the service's own data), website access (inert,
because the store has no web listener, R2), and deleting the bucket when it is empty. That last is
the service deleting its own empty bucket, not the platform deleting anything; the next pass makes
the bucket again and allows the service's keys on it (R6).

`ObjectStorageReachableFeatures`' upload scenario sets a CORS rule with the service's credential, sends the preflight
through the Gateway with an `Origin`, and requires the allow headers before it sends the object.

**Alternatives considered**: the operator sets a permissive CORS rule on every exposed bucket —
rejected: it needs an S3 client and an owner key in the operator, and decides for every service
which origins may upload. CORS at the Gateway — rejected: an Envoy-specific resource and a grant
for it, to say less than the bucket's own rule can.

## R17. Traffic to the store inside the cluster is not encrypted

**Decision**: `ANKKA_S3_ENDPOINT` is `http://`. The store's NetworkPolicy decides who may connect:
port 3900 from pods labelled `app.kubernetes.io/managed-by: ankka` in namespaces with that label
and from the gateway's proxies; port 3903 from the operator's pods only; port 3901 from the
store's own pods. `docs/reference/limitations.md` says a workload's requests to the store cross
the cluster's network in the clear. FR-018.

**Rationale**: Garage's S3 endpoint "does not support TLS: a reverse proxy should be used". A
proxy in the store's pod with a certificate from the `ankka-service` authority is buildable, but
then every S3 client in a developer's program has to be told to trust the platform's authority,
and the developer's container of a process-hosted or web-hosted service holds no certificate
files at all (`isolation.feature`: "the process is given no certificate"). That is a sixth
variable and a mounted file to make "any S3 client works" true, and it is a change that can be
made later without changing anything decided here. What is on the wire: a request is signed, so
the secret key is never sent; object bodies are. The operator's admin token crosses the same
network, between two pods the policy names.

This is the second plain connection to a platform component; the control plane reads Keycloak's
keys the same way. Nothing restricts egress anywhere in the platform
(`docs/platform/networking.md:274-276`), so the store's ingress policy is the whole of who can
connect.

**Alternatives considered**: a TLS sidecar now — deferred, as above. Leaving the limitation
unwritten — rejected: the platform says of itself that every port a workload has is mutual TLS,
and this is a port that is not.

## R18. The console

**Decision**: `serviceStatusSchema` (`schemas.ts:128-149`) gains three optional strings.
`service.tsx` gains an `Object storage` fact after `Database` (L119-120):

| The service | Shown |
|---|---|
| has `bucket` | the bucket's name, and its address when it has one |
| `objectStorage` is `supplied` | `Its own` |
| neither | `None` |
| `objectStorage` is a waiting or failed phrase | the phrase |

The delete text (L206) says the bucket is kept as the database is. The fake control plane
(`fake-control-plane.ts:256-280`) derives the three fields from the descriptor it was given, and
its reserved suffixes gain `-storage` and `-mount-tls` (R12). `ControlPlaneFixturesSuite`'s full
`ServiceStatus` and `ServiceSpec` samples set the new fields and the fixtures are written again
with `-Dankka.docs.update=true`. The e2e label list (`services.spec.ts:55`) gains the label, and
three cases cover the outline's rows.

**Rationale**: the spec's question named the local console, but the page that shows a service's
database is the installation's (`service.tsx:119`); the local console shows no database, and a
service run on a developer's machine is given no bucket. `ControlPlaneFixturesSuite` holds the
client's schemas to the codecs, and its convention is that the full sample sets every optional
field.

## R19. The prerequisite: the operator without `get` on Secrets

**Decision**: a separate change, before this feature's k3s work, does three things:
`EnsureCredentials` creates and treats 409 as success (`Executor.scala:253-274`);
`observeDatabase` stops reading `<service>-db` (`Executor.scala:529`) and takes "exists" and
"created at" from the `Database` object it already reads; and `operator.yaml`'s rule 10 loses
`get`, with its comment, which still speaks of a password, rewritten.

**Rationale**: those are the only two reads of a Secret in the operator. The database's
`recovered` compares the Secret's creation time with the resource's; a `Database` with reclaim
`retain` outlives the service in the same way, so its creation time answers the same question.
`OperatorClusterSuite`'s case 19 (delete, apply again, `Recovered`) and case 14 (ten applies leave
the Secret's `resourceVersion` unchanged, read by the test's own admin client) are what hold the
change.

This feature's code does not depend on it (R6 uses no `get`). One test does: SC-003's minted
token, which is written with this feature and fails until the prerequisite is merged. It is in
`OperatorClusterSuite`'s case 20, which already mints a token for the operator's ServiceAccount
(`OperatorClusterSuite.scala:724-768`).

## R20. Where each scenario is tested

Every scenario is run by a suite that fails without the feature. A feature file one suite can
run whole is run by a `GherkinSuite`; the files are cut so that most can be.

The k3s suites follow `WebHostingClusterSteps`: an abstract `ObjectStorageClusterSteps(feature)`
in `controlplane`'s tests, a `GherkinSuite` with the control plane and the operator in the test's
JVM, `GatewayStack`, CNPG, a new `ObjectStoreStack.install`, the sample image and a browser that
is `curl` on the host through the mapped port. Because the operator is built by the test, a step
can start it with no object store, or with one at an address nothing listens on.

| Feature file | Run by | Notes |
|---|---|---|
| `object-storage/provisioning.feature` | `ObjectStorageProvisioningFeatures` (k3s) | whole |
| `object-storage/isolation.feature` | `ObjectStorageIsolationFeatures` (k3s) | whole; the descriptor refusal also offline in `ObjectStorageDescriptorSuite` |
| `object-storage/kept.feature` | `ObjectStorageKeptFeatures` (k3s) | whole |
| `object-storage/own-object-store.feature` | `ObjectStorageOwnStoreFeatures` (k3s) | whole; the outline also offline |
| `object-storage/reachable.feature` | `ObjectStorageReachableFeatures` (k3s) | whole; signs with the AWS SDK for Java, a test dependency, so "any S3 client" is a client nobody here wrote |
| `web-hosting/object-storage.feature` | `WebHostingObjectStorageFeatures`, a `WebHostingClusterSteps` with the store installed | whole |
| `object-storage/hostings.feature` | two tests named for its scenarios in `SidecarClusterSuite` | the only suite with the Python example's and the Rust cart's images; the module's is also `HostImports.lookup` offline |
| `object-storage/console.feature` | Playwright tests named for the outline's rows, in `console/e2e/tests/services.spec.ts` | |
| `secrets/project-secrets.feature`, the changed outline | `ProjectSecretsSuite`, where its other rows are | |

Inside a pod, an object is kept and read with the image's own `curl --aws-sigv4`, with the
variables the pod was given, so the test uses exactly what the service has.

Beneath them, offline and in milliseconds: `BucketsSuite` and `AnkkaServiceCodecSuite` (`crd`);
`PlatformVariablesSuite`; `ObjectStorageDescriptorSuite`, `ProjectSecretsSuite` and
`ControlPlaneFixturesSuite` (`controlplane-api`); `ObjectStorageSuite` (the `decide` rule table),
`StorageCredentialSuite` (R6's table, with doubles), `ObjectStorageRenderingSuite` (the actions,
the four hostings' containers, the route and the grant), a new golden file in
`RenderingGoldenSuite`, `CrdSchemaSuite` and `SettingsSuite` (`operator`); `GarageStoreSuite`
against the real image in a container; `ServiceProjectionSuite`, `StatusIngestSuite`,
`ServiceEntitySuite`, `EventCompatibilitySuite`, `ReservedSecretNamesSuite` and
`RemoteOverlaySuite` (`controlplane`); `OutputSuite` (`cli`).

**Could each pass while the thing is false?** The isolation scenario asserts the store's own
refusal status, not a failed `curl`. "No bucket exists" asks the store's admin API, not the
status. "Does not reach the object store" asserts the Gateway's 404 and that the store's access
log has no line for the request. The upload asserts the preflight (R16). The unchanged-rendering
proof is a repin whose diff is one removal line per fixture (R8).

**Verify first**: the sample image's `curl` has `--aws-sigv4` (it needs 7.75; the base is
`eclipse-temurin`), and it signs a path-style request for a bucket with a dot in its name.

## R21. Documentation

**Decision**: one new page, `docs/platform/object-storage.md`, beside `databases.md`, in
`mkdocs.yml`'s "Run the platform" nav and in the `ankka-platform` skill's `pages:`; FR-010. It
says what is provisioned, the six variables, that the client uses path-style addressing and the
given region, what is never deleted, that a credential is made once and not rotated, what the
operator can and cannot reach (R6), bringing your own store, making a bucket reachable and setting
CORS, and that the default store is one replica. Its S3 example is a marked region of
`ObjectStorageReachableFeatures`, included with `<!-- include: … -->`.

Changed pages: `reference/service-descriptor.md` (two fields, the refusals' table, a
`service.json` block that `DocumentationDescriptorsSuite` decodes); `reference/limitations.md`
(R17, one replica, no provider's buckets); `platform/secrets.md` (the reserved suffix);
`platform/networking.md` (the store's policy and the bucket route); `operate/status-and-history.md`
(the phrases); `deploy/upgrading.md` (R12's cost); `platform/install-cloud.md` (the three Secrets
to create); the CLI and route references through `just docs-reference`; the skills rendered again.

**Rationale**: a new page not in the nav and a skill fails `docs check`; samples come from tested
code; every `service.json` block is decoded by the platform's own rules.

## R22. The cloud overlay runs Garage too

**Decision**: `overlays/cloud` lists the component and deletes its three development Secrets. No
cloud provider's buckets are rendered in this feature.

**Rationale**: the spec left the choice to the plan. A provider's bucket is reachable at the
provider's own address, so "private until the descriptor asks" would be a statement about the
provider's policy and not about a route the platform renders, and the credential model differs
per cloud (an AWS secret is returned once and cannot be set; a GCS HMAC key belongs to a service
account). Each is a second `ObjectStore` plus a second answer to R15, which is a feature. The seam
(R4) is what this one owes it.

## Verify first, gathered

| | Claim | Where it is checked |
|---|---|---|
| S1 | `garage server --single-node` in `v2.3.0` needs no layout step; the `_FILE` variables are read | a spike against the image, then `ObjectStoreStack` |
| S2 | a bucket named `<project>.<service>` works path-style, signed and presigned; `ListKeys` matches by name; `AllowBucketKey` is idempotent | `GarageStoreSuite` against the image |
| S3 | Envoy Gateway merges routes across namespaces on one hostname, honours the grant, and forwards the authority with its port | a k3s spike before R15's rendering |
| S4 | a Component's patch reaches the operator's Deployment and adds no container | `RemoteOverlaySuite` |
| S5 | a `create` of an existing Secret is a 409, and a merge patch needs only `patch` | `OperatorClusterSuite`, under the minted token |
| S6 | the sample image's `curl` has `--aws-sigv4` | the first k3s case |
| S7 | repinning `RenderingUnchangedSuite` adds one `# RemoveHttpRoute` line to each fixture and changes no object | the repin's diff |

## Verified during implementation

- **S1** (2026-10-04, against `dxflrs/garage:v2.3.0`). `garage server --single-node` lays out a
  one-node cluster by itself, with the node's disk as its capacity, and accepts `CreateBucket` with
  no layout step. `/garage status` exits 1 before the node serves and 0 after, so it is the
  readiness probe. The admin API refuses a request with no token (403) and `/health` needs none.
  With no `[s3_web]` section nothing listens on 3902. **Garage refuses a secret *file* that anyone
  but its owner can read** ("expected 0600"), and a bind mount on macOS reports 0640 whatever the
  file's mode, so the component gives the RPC secret and the admin token as environment variables
  from a Secret (`GARAGE_RPC_SECRET`, `GARAGE_ADMIN_TOKEN`) and the `_FILE` forms are not used.
- **S2** (same image; `GarageStoreSuite` holds it). A bucket named `shop.reports` is made, found,
  and served path-style and presigned. `CreateBucket` for a name that exists is `409
  BucketAlreadyExists`; `GetBucketInfo` for one that does not is `404 NoSuchBucket`. `ListKeys`
  returns names and the match is made by the client; a name is not unique to the store.
  `AllowBucketKey` a second time changes nothing. `GetKeyInfo` without `showSecretKey` returns no
  secret. A signature for `us-east-1` is `AuthorizationHeaderMalformed`. An unsigned request is
  `403 AccessDenied` ("Garage does not support anonymous access yet"). An owner key's
  `PutBucketCors` takes effect and the preflight then answers the allowed origin only.
  **The AWS SDK for Java since 2.30 sends a trailing checksum in an `aws-chunked` body by default,
  which Garage refuses as `Invalid payload signature`**; a client configured with
  `requestChecksumCalculation(WHEN_REQUIRED)` and `responseChecksumValidation(WHEN_REQUIRED)` works.
  Other SDKs' newest releases changed the same default; the docs page says so for every client.
- **S3** (2026-10-04, k3s v1.35.1 with Envoy Gateway v1.9.1; `BucketRouteSpike`, 5 of 5). Two
  `HTTPRoute`s in two namespaces on `storage.<base>`, each matching its bucket's path and naming the
  store's Service in `garage-system` through a `ReferenceGrant`, are merged by the Gateway: a URL
  presigned for `storage.<base>:<mapped port>` with the AWS SDK's presigner verifies at the store
  for a PUT and a GET, so Envoy passes the authority with its port. An unsigned request on a routed
  path is the store's `403 AccessDenied`; a path no route matches is the Gateway's `404`; with a
  route's grant removed its `ResolvedRefs` is `RefNotPermitted` and it answers `500`.
  `timeouts.request: "0s"` is accepted. The component's NetworkPolicy admits the gateway's proxies
  to the S3 port, under k3s's policy enforcement.
- **Departures from the plan, made while building it.**
  - One k3s suite, `ObjectStorageClusterFeatures`, runs the whole of `features/object-storage/`, not one
    suite per file: one cluster for every scenario, with `hostings.feature` and `console.feature` named in
    `ranElsewhere` (`SidecarClusterSuite`, the console's Playwright suite). Each scenario's services are
    named `<name>-<n>`, so a bucket an earlier scenario made — and none is ever deleted — is never taken
    for this one's.
  - "Does not reach the object store" is asserted as the gateway's `404` with no S3 error document in the
    body; Garage logs no line per request at its default level, so the store's log is not read.
  - A credential the store lost and the operator replaced (`StorageCredential.Result.Replaced`) is logged
    by the operator, not put in the status: the status is decided before the pass's actions run.
  - The bucket's address reaches a member as a path recorded by the entity and the listing, completed by
    the endpoint with the store's hostname, as a service's hostname is: the base domain is configuration,
    which neither has.
  - A JSON `null` for `provisionObjectStorage` is a decode error, as for every plain boolean of the
    descriptor, not a silent `false`.
  - `GherkinSuite` takes a step of six values: the variables' step names five and the service.
