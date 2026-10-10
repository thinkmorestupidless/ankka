---
paths:
  - "operator/**"
  - "crd/**"
  - "kustomization/**"
  - "controlplane/src/test/**"
  - "controlplane/src/main/scala/**/deploy/**"
  - "sidecar/src/test/**"
  - "proxy/src/test/**"
---

# Kubernetes: the operator, the resource, CNPG, cert-manager, the Gateway and k3s suites

## Reconciliation is split across two processes

The control plane projects a service's desired state into an `AnkkaService` custom resource
and folds the status back; an in-cluster **operator** watches those resources and owns
everything below — namespace, Deployment, reported status. The resource is the only thing
either side knows about the other.

The split is what buys cascade deletion (owner references, so there is no orphan sweep
anywhere in this design), sub-second change notification (watches, not polling), and a
control plane that holds no credential able to create a workload. Doing it in one process
was the original plan and was rejected on review — it reimplemented all three.

The operator is deliberately **not** an ankka application. It has no entities, no journal
and no sharding, so hosting it on ankka would give it a cluster to form and a database not
to use — and a process whose entire job is to keep working while other things are broken
should depend on as little as possible.

`Action` values are inert descriptions of cluster mutations and `Fabric8Executor` is the
only thing that performs them, which is the same organising idea as the component effects.

## The installation's broker is a component the operator provisions into

An installation has one Kafka for every project (feature 027), installed by the `broker` component
(`kustomization/components/broker`: Strimzi at a pinned release, one KRaft `Kafka`, a long-lived
certificate `ankka://platform/broker` from the service authority, and the operator's three settings
`ANKKA_BROKER_{BOOTSTRAP,NAMESPACE,CLUSTER}` — all or none, `BrokerSettings.read`). An installation without
the component has no broker and renders exactly what it rendered before (`RenderingGoldenSuite`).

Services are known to the broker the way they are known to their database: by the certificate they hold.
With a broker, the service certificate gains `commonName: <project>.<service>`, and the operator writes a
`KafkaUser` of that name (`tls-external`, topics `<project>.` prefix Read/Write/Describe, groups
`ankka.<project>.<service>.` prefix Read — feature 024's group ids) in `ankka-broker`, with no owner
reference and no action that removes it. It gives the service `ANKKA_KAFKA_BOOTSTRAP_SERVERS`,
`ANKKA_KAFKA_TLS_DIRECTORY` and `ANKKA_KAFKA_TOPIC_PREFIX` (shared with a process). `BrokerProvisioning.decide`
is the credential's status, in the database's shape. A descriptor with any `ANKKA_KAFKA_` variable supplies
its own broker and gets nothing.

Topics are the **project's**, declared once on it (`PUT /projects/{id}/topics/{name}`, `ankka projects topics
set`): the `Project` entity holds one declaration per name, so "never fewer partitions" is its own rule and
there is no cross-service check. Descriptors declared topics until a k3s run showed two declaring services
could never grow one. `ProjectTopicsTrigger` writes the declarations as an `AnkkaProject` in the project's
namespace (crd `AnkkaProject`, schema `ankkaproject.yaml`); the operator's `ProjectReconciler`, on a queue of
its own, renders a `KafkaTopic` per declaration (`TopicProvisioning.topicsToRender`: never fewer partitions
than one has) and writes each topic's phase onto the resource's status, which the topics route reads. A
service's status names the topics its components use that the project has not declared
(`undeclaredTopics`, read from its topology when an instance is ready). `docs/platform/broker.md` is the
contract. A cluster without the `AnkkaProject` type still runs the operator, which only logs that no
project's topics are made, so a k3s suite that declares topics must apply `ankkaproject.yaml` beside
`ankkaservice.yaml`, or every declaration simply never becomes a `KafkaTopic`.

A declaration also carries `compacted` (rendered as the `KafkaTopic`'s `config.cleanup.policy`, observed
back from `spec.config`, and `None` rather than an empty map so an uncompacted topic's applied object does
not change) and a contract's name and fingerprint; the schema document goes to the `ankka-project-schemas`
ConfigMap, written by the control plane (merge patch, one key per fingerprint; it has `configmaps`
get/create/patch) before the entity records the declaration, as a secret is written first. `ProjectReconciler`
renders the whole declaration — topics and declared brokers — as the `ankka-project` ConfigMap
(`ProjectConfig`, `Action.EnsureProjectConfig`), which every platform container mounts `optional: true`
at `/var/run/ankka/project` with `ANKKA_PROJECT_DECLARATIONS`; a changed declaration updates in place and
is checked at the service's next start. A project's declared brokers (`AnkkaProject.spec.brokers`) are an
input to `Rendering.render` (`declaredBrokers`, read by `ServiceReconciler` through
`Executor.projectBrokers`), attached by `BrokerMounts` as a Secret volume per broker on the platform
container only; the project informer requeues every service of a changed project, so a broker declared
rolls them once. `database: "none"` on the resource makes `Provisioning.decide` answer `NotNeeded` (as
web hosting does) and sets `ANKKA_DATABASE=none`; the process container is sized by `processCpuMillis`
and `processMemoryMiB`. Every platform container carries `terminationMessagePolicy: FallbackToLogsOnError`,
so a start refusal written by `StartRefusal` reaches `services get` as the detail.

## A service can ask for a bucket, provisioned like a database

`provisionObjectStorage` (feature 034) gives a service one bucket in the installation's object store,
Garage, and a storage credential that reaches it and no other. The store is the `garage` component, one
node in `garage-system` — outside the `ankka-<name>` pattern, so no project id can name it — started with
`server --single-node` so `kubectl apply -k` stays the whole deploy; the component also patches the
operator's Deployment with the five `ANKKA_OBJECT_STORE_*` settings, so an overlay without it renders an
operator with no store, and a service that asks is reported failed with that reason. The operator reaches
the store through `ObjectStore` (`GarageStore`, the JDK's HTTP client; no dependency added), decides with
`ObjectStorage.decide` as `Provisioning.decide` decides about a database, and issues the credential with
`StorageCredential.ensure`: a key issued speculatively and offered as a `create` of `<service>-storage`,
whose 409 is the only thing it ever learns about the Secret — nothing is read back from the cluster or the
store. The bucket's name, `<project>.<service>`, is derived in `crd`'s `Buckets` beside `Hostnames`, so the
resource carries two booleans. The variables go to the developer's program in every hosting, never the
sidecar or the proxy. `exposeObjectStorage` adds one `HTTPRoute` per bucket in the project's namespace,
owned by the service, at `storage.<base>` with the bucket in the path, naming the store's Service through a
`ReferenceGrant` the operator writes in `garage-system` under a Role of the component's. The store speaks
plain HTTP inside the cluster — the one departure from "every port is mutual TLS", written into the
limitations. `docs/platform/object-storage.md` is the contract with a service.

## A cloud request is rendered like a `KafkaTopic` and answered by a provider

What needs power over a cloud account (a bucket, a cloud identity, a key) is never the operator's to
make (feature 044). The operator writes a `CloudResource` (crd, schema `cloudresource.yaml`) by
server-side apply, owned by the `AnkkaService` or `AnkkaProject` it serves, and reads its status; a
**cloud provider**, a process in `ankka-cloud-provider` under the `cloud-provider` component's
ServiceAccount, writes the status and, for a credential, `create`s the Secret once and `patch`es it on a
raised generation. The two grants are halves: the operator has `create`/`patch` on `cloudresources` and
only `get` on their status; the provider has `get`/`list`/`watch` on them and `update`/`patch` on the
status; both have `create`/`patch` on Secrets, and neither has `get` or `delete`. `OperatorClusterSuite`
cases 40-43 prove each half under minted tokens. Six request kinds, named in `CloudRequests.Keys`; a
service's request is `<service>-<suffix>`, a project's `<project>.<suffix>` so the two cannot collide.
`ANKKA_CLOUD_*` (six names, `PlatformOnly`) come from `ankka-platform` to the operator, the control plane
and the `ankka-cloud` ConfigMap a provider reads; `CloudProviders` in `PlatformVariables` is the one list
of known names.

A bucket takes the cloud path when the installation names a provider and the bucket's store is the
cloud's (`ObjectStorage.takesCloudPath`): where the operator last reported a bucket made, so a bucket stays
put until a move switches it, else the installation's backend (`Settings.bucketBackend`: the one named,
else Garage when installed, else the cloud). A status written before stores were named (no `store`) is a
Garage bucket. `identity` and `bucket` first, then `bucket-credential` naming what they answered, into
`<service>-cloud-storage` (never Garage's `-storage`: a moving service holds both). One rotation grace,
`ANKKA_CLOUD_ROTATION_GRACE`, ends old credentials in both stores. `CloudProvisioning.decide` acts on no status whose `observedGeneration` is behind; while any is
unanswered `ObjectStorage.withheld` keeps the Deployment back (the endpoint is the provider's to say) and
the service reports `UpdateInProgress` with why. A bucket the provider refused holds it back too
(`ObjectStorage.refused`) and the service is `Failed`: a service that asked for a bucket cannot be relied on
to run without one; instances already running are left alone. A third informer on `CloudResource` wakes the owner, and a
pass with an unacknowledged request requeues at the bound, so "no provider for gcp has answered" lands on
time and the status recovers within seconds of a provider starting. The control plane shows the bucket's
name as the operator reported it (`Service.bucketNamed`), empty meaning not yet known.
`storageCredentialGeneration` is feature 039's count of credentials issued again, from 0, which the
control plane owns (`ankka services storage reissue`); a cloud request asks for that count plus one,
since a provider's generations start at 1. The scripted provider
(`operator/src/test/.../cloud/`) is what `CloudProviderClusterFeatures` runs `features/cloud-provider/`
against; `-Dankka.cloud.external=<kubeconfig>` points the same suite at a real provider.

A member moves one service's bucket from Garage to the cloud (feature 039). `StorageMove.next` is a pure
state machine, one transition per reconcile pass, its state kept in `status.objectStorage.move` because a
Job its time-to-live removed says nothing afterwards. While it runs, the reconciler asks for the target's
three cloud requests as the cloud path does, and `Rendering.moveActions` runs the mover (`storage-mover`,
its own image, `ANKKA_STORAGE_MOVER_IMAGE`) as a copy and then a verify Job into the target as its provider
answered it, holding the two storage credentials by `secretKeyRef` and no ServiceAccount token; the write
pause is `DenyBucketKey` on the Garage key in place. `Switched` in the status is what moves the bucket:
the next pass finds it in the cloud. A bucket the provider refuses holds the Deployment back and the
service is `Failed`. `ObjectStorageGcsClusterFeatures` runs `features/object-storage-gcs/` with the
scripted provider in its Garage-backed mode (`ScriptedStore.InGarage`), so a pod keeps real objects; a
move caught part way finishes in a pass or two with a few objects, so those scenarios are held by
`MoverSuite` and `StorageMoveSuite` and named in `ranElsewhere`.

## Deploying locally

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh
```

Installs [CloudNativePG](https://cloudnative-pg.io/), [cert-manager](https://cert-manager.io/)
and [Envoy Gateway](https://gateway.envoyproxy.io/) (all server-side apply — CNPG's CRDs are too
large for client-side apply's own annotation-size limit), builds all three images — the operator,
the control plane and the shopping cart sample — loads them into the cluster with `kind load
docker-image`, and applies the CRD, the operator, the platform's Gateway and local CA, the
control plane's own CNPG-managed database and the control plane itself via `kubectl apply -k`. No
registry — `DOCKER_REPOSITORY` in `build.sbt`'s `dockerSettings` is the one setting that changes
once one exists. The script checks `kubectl config current-context` and refuses to run against
anything other than the `kind-*` cluster it targets, and refuses a cluster whose node does not
publish the gateway's NodePorts (30080/30443 → the host's 8080/8443, from `kind.yaml`) — kind
decides that at creation and it cannot be added later.

It ends by exporting the local CA's root to `~/.ankka/local-ca.crt` and printing the control
plane's address, `https://api.127.0.0.1.sslip.io:8443`, with the `ankka config set url` /
`config set ca` lines to use it. No port-forward anywhere. The base domain and the HTTPS host port
are written once, in `kustomization/overlays/local/platform-configmap.yaml`, and kustomize
`replacements` copy them into the wildcard `Certificate`, the `Gateway` listener, both
Deployments' `ANKKA_BASE_DOMAIN` and the control plane's own `HTTPRoute`; `ANKKA_BASE_DOMAIN` on the
script overrides the domain for a machine whose resolver blocks sslip.io.

It ends by `rollout restart`ing the operator and control plane. Without that a *re*-run changes
nothing that is running: the manifests are unchanged and the tag is still `:latest`, so `kubectl
apply` sees no difference, nothing rolls, and the pods keep executing the image they started with
while every line of output reports success.

`kustomization/components/{crd,operator,controlplane}/` hold the canonical CRD, install manifest
and RBAC — see the kustomize load-restrictor trap in this file for why the direction is `kustomization/` → `src/main
/resources/` symlink, not the other way round. `kustomization/components/postgres/` is a CNPG
`Cluster` for the control plane's own database, the one case in this codebase using
`bootstrap.initdb` rather than the operator's per-service `Database`/`DatabaseRole` machinery; its
schema ConfigMap comes from the component's own `configMapGenerator` over `postgres/ddl/`, the
DDL's canonical copy, plus one `99-grants.sql` literal — see the `postInitApplicationSQLRefs` trap in this file about CNPG running
`postInitApplicationSQLRefs` as its own superuser, not as the role that owns the database.

## Deploying anywhere else

`kustomization/overlays/cloud/` is an **example** production overlay, with every
installation-specific value a placeholder marked `SET`. It is the same components with only what
must differ: a
`LoadBalancer` instead of the kind node ports, an ACME issuer over **DNS-01** instead of a
self-signed root (a wildcard certificate cannot be had from HTTP-01), a real base domain on 443,
and Keycloak's development admin secret **deleted** rather than overridden — `admin`/`admin` is
public in this repository, so the identity provider is made to refuse to start until a real Secret
exists out of band, and an `images:` block naming ghcr.io at a release (every Deployment names an
unqualified image with `imagePullPolicy: IfNotPresent`, which is right for `kind load` and useless
for a cluster that must pull).

**No real installation's overlay lives here.** The production clusters' overlays — their domains,
addresses, account IDs and the release each runs — live in the private `ankka-deployments`
repository beside the Terraform and Flux that create those clusters, and consume this repository's
`kustomization/components/` at a pinned tag. Keeping them here coupled every cluster change to an
ankka release: the tag Flux fetched had to name its own version inside it. A change a cluster
needs is a component change here, released, then a tag bump there.

`deploy-local.sh` will not apply it: that script refuses any context that is not the local kind
cluster, on purpose, and that guard is worth more than the convenience. Apply it by hand, after
the three CRD-bearing controllers. `RemoteOverlaySuite` renders both overlays and asserts they
differ in exactly the intended ways — it skips when `kubectl` is not on the host's PATH, which is
the only thing that can render kustomize.

Every service's own database is provisioned separately, by the operator, per `AnkkaServiceSpec` —
see `README.md`'s "Databases are provisioned automatically" for the model, and
`kustomization/components/cnpg/` for the install component itself.

## Schema

DDL lives in `kustomization/components/postgres/ddl/` and is the single copy — four files since feature
023 added `40-secrets-postgres.sql`. A new file is **named in seven lists**, each of which a missed one
fails only somewhere else: `SharedPostgres.DdlResources` (testkit), `CnpgRendering.SchemaFiles` (the per-project
`ankka-schema` ConfigMap), `SchemaResourceSuite`, `CnpgRenderingSuite`, `SidecarClusterSuite`, and the
postgres component's `kustomization.yaml` and `cluster.yaml`. Everything that globs the directory
(compose, the sidecar image, the template) picks it up unchanged. A local database created before a file
existed does not have it — Postgres runs `initdb.d` once.

`modules/runtime/src/main/resources/ankka/ddl` and `operator/src/main/resources/ankka/ddl` are
directory symlinks into it, so the runtime jar carries it, `docker-compose.yml` mounts it through
the runtime path, and the testkit (`SharedPostgres`) copies the same files into its container. A test can never
pass against a schema local development does not have. It is canonical under `kustomization/`
for the load-restrictor reason in the traps: that is the one place kustomize can read it from,
and everything else follows a symlink.
The journal and projection scripts are taken verbatim from the Pekko projects.

## Traps

- **Kafka knows a TLS client by its certificate's subject and nothing else.** The default principal builder
  reads the distinguished name; a subject alternative name is invisible to it. A service certificate with
  only an `ankka://` URI authenticated as `User:` and was denied everything, which is why the service
  certificate carries a common name on an installation with a broker — and only there, so nothing else's
  rendering changed.
- **Strimzi replaces the broker's pod whenever its listener certificate changes**, measured within 12s. The
  broker's certificate therefore lives a year, not the day every workload's does.
- **A custom listener trusts an authority by its certificate; Strimzi's own client authority wants the key.**
  Handing Strimzi the service authority's key would let it mint any service's identity. `authentication:
  custom` with `ssl.client.auth: required` and a PEM truststore of the authority's `ca.crt` needs only the
  public half.
- **Strimzi writes its listener's network policy, and policies only add.** A stricter policy of ankka's
  beside it narrowed nothing; who may connect is the listener's `networkPolicyPeers`.
- **A Strimzi `Ready` condition can describe an earlier generation.** A topic asked for more partitions reads
  `Ready=True` from before until the topic operator catches up; `StrimziObjectState.found` disregards a
  condition whose `observedGeneration` is behind the resource's, which is waiting.
- **Jackson reads an `Option[Long]` field of a Scala case class as an `Integer`.** Erasure hides the element
  type, so a status's `observedGeneration` held a boxed `Integer`, `==` with `Some(3L)` still passed (Scala's
  boxed equality crosses numeric types), and the operator's first comparison with a generation threw
  `ClassCastException` on every reconcile — found only by the k3s suite. Such a field carries
  `@JsonDeserialize(contentAs = classOf[java.lang.Long])`, and a test uses the value as a `Long`.

- **The operator has no `get` on Secrets, and must never need one.** It writes a Secret with `create`
  and learns from a 409 that one is already there (`EnsureCredentials`, `EnsureSecretKey`), remembering
  it per process; whether a database was recovered is read from its CNPG `Database`, not from its
  credential Secret. `OperatorClusterSuite` mints the operator's own token and asserts a `get` is
  refused. Code that reads a Secret fails there, on a real API server, and nowhere offline.
- **Every k3s suite that runs the operator needs `PkiStack.install`**: the operator asks cert-manager
  for every workload's certificates and a pod starts only once they exist. And a plain `wget` from
  the node reaches no service any more — `InPod.curl` runs `curl` inside a service pod (the images
  are `eclipse-temurin`, which has it) with that pod's certificates.
- **A Deployment's `spec.selector` is immutable.** It must never contain ankka's
  generation, or the second apply is rejected permanently and the service is bricked at
  generation 2. The generation lives on the Deployment's own annotations.
- **The generation must not be on the pod template either.** Feature 001 put it there so a
  restart would roll the pods — but the generation increments on *every* apply, so every apply
  rolled every pod, including one that only changed the instance count. Found by the test
  that scales 3→4 and asserts the three existing pods survive. What rolls the pods is a
  separate `restarts` counter on the pod template, incremented only by `services restart`;
  an apply that changes nothing Kubernetes cares about changes nothing Kubernetes sees.
- **`RollingUpdate` with `maxSurge: 1, maxUnavailable: 0` — feature 003's `Recreate` was
  reversed, on purpose.** `Recreate` was correct while every pod joined itself: a rolling update
  put two single-node clusters on one journal. Once nodes find each other (feature 004) the
  overlap is the *point* — the new pod joins the existing cluster, takes its shards by handoff
  and only then is an old one stopped — and `Recreate` would be the outage. This holds at one
  instance too, measured: the surge pod bootstraps into the old pod's cluster, so a single-instance
  service deploys with no downtime either. Do not put `Recreate` back for "safety"; the guard
  against the split it once prevented is now `join-self-if-no-seed-nodes = off` in the Kubernetes
  overlay, where joining self *is* the split.
- **Changing a field Kubernetes defaulted can wedge every existing object.** Moving a live
  Deployment from `RollingUpdate` to `Recreate` was rejected — `invalid: spec.strategy` — while
  its defaulted `rollingUpdate` block was still there, and server-side apply cannot remove a
  field no manager owns; every reconcile then failed forever, until a one-off JSON merge patch
  (`"rollingUpdate": null`). The reverse migration needs no such thing (an apply that *sets*
  `rollingUpdate` owns it), so that code is gone — but the class of bug stays: **tests that
  start from an empty cluster cannot see it**. It took a real cluster with a real leftover
  object, and the rolling-update migration test exists to keep it in view.
- **Never render a HorizontalPodAutoscaler.** `minInstances` is honoured as a fixed count, and
  that is the whole story until ankka has a reason to scale on load. An autoscaler that
  scaled to zero would also be a cold start nobody asked for.
- **Scaling a Deployment directly is undone within one resync.** The operator's reconcile
  loop restores the replica count from the resource, so `kubectl scale --replicas=0` is not how
  a test takes a service down: it is back before the assertion runs. `ankka services pause` /
  `resume` is — the count is rendered from the spec, and pause is the spec saying zero.
- **A wildcard is one label deep — for X.509 certificates and for Gateway API listeners alike.**
  `*.example.test` covers `cart-checkout.example.test` and not `cart.checkout.example.test`; two
  implementations that got the listener rule wrong filed it as a bug. With TLS on the
  installation's single Gateway, that is *why* an exposed service's hostname is
  `<service>-<project>.<base>` (one label; `com.thinkmorestupidless.ankka.crd.Hostnames`) and not the two-level form
  that reads better. Two costs, both refused at expose time: a label over 63 characters, and a
  collision between hyphenated names (`a-b` in `c`, `a` in `b-c`).
- **A cert-manager `ClusterIssuer` looks up its `ca.secretName` in cert-manager's own namespace,
  not the Certificate's.** `secrets "ankka-root-ca" not found` with the secret sitting right there
  in `ankka-gateway`. A namespaced `Issuer` beside the secret is the honest shape for a local CA.
- **The ClusterIssuer trap runs both ways, and the remote overlay needs the other direction.**
  A cluster-scoped issuer resolves its secrets in *cert-manager's* namespace rather than the
  Certificate's, which is why a `ClusterIssuer` was wrong for the local CA (above) — its secret
  sits beside the Certificate. `overlays/cloud` is the mirror: DNSimple is not one of
  cert-manager's built-in DNS-01 solvers, so it needs the out-of-tree webhook, and that webhook
  reads its API token with *its own* ServiceAccount in the namespace of the challenge. The chart
  grants that with a Role in its release namespace, pinned by `resourceNames` to its own secret.
  A namespaced `Issuer` in `ankka-gateway` therefore sends the webhook after a secret it cannot
  read, and issuance fails `forbidden` on the *token* — which reads nothing like the wildcard
  certificate being the problem. Same property, opposite answer, and `RemoteOverlaySuite` pins
  both directions so neither gets "tidied" into the other.
- **A Gateway API `RequestRedirect` without `port` keeps the *request's* port in the Location.**
  `http://…:8080/x` → `https://…:8080/x`, which goes nowhere on kind, where HTTPS is on 8443. The
  redirect route names its port (443 in the component, the kind host port in the overlay).
- **Envoy Gateway runs a Gateway's proxy in its own namespace, `envoy-gateway-system`, not the
  Gateway's.** A network policy admitting `ankka-gateway` admits no pod that routes anything: the
  route and its `BackendTLSPolicy` were both `Accepted` and `ResolvedRefs`, and every request was a
  503 `remote_connection_failure … Connection_refused` from Envoy — k3s's policy enforcement
  *rejects*, so a dropped connection reads as a closed port. The HTTP policies (operator-rendered
  and the control plane's) name the proxy pods by `gateway.envoyproxy.io/owning-gateway-{name,
  namespace}` in `envoy-gateway-system`.
- **A route can be `Accepted` and still not serve.** A backend in another namespace is
  `ResolvedRefs: False / RefNotPermitted` and Envoy answers 500 for it; an unlabelled namespace is
  `Accepted: False / NotAllowedByListeners` and gets a 404. The resource's `status.route` folds
  both conditions, and `services get` shows it as `route rejected: <reason>`.
- **Every reconcile reads an `HTTPRoute`, so a cluster without the Gateway API must read as "no
  route", not fail.** The read-first removal and the status read both run for every service on
  every pass; `Fabric8Executor` treats a 404 on the *type* as absent. Only an exposed service's
  `EnsureHttpRoute` is allowed to fail on a missing CRD, loudly.
- **Envoy Gateway's CRDs need Kubernetes ≥ 1.32.** Its experimental `xbackends` CRD carries a
  CEL rule using `format.dns1123Label()`, which a 1.31 API server rejects
  (`CustomResourceDefinition … is invalid: … x-kubernetes-validations[0].rule`) — with `kubectl`
  as much as with fabric8, so it looked like a client bug first. The k3s test image moved from
  v1.31.2 to v1.35.1 for this; and `kubectl apply` of a multi-document manifest applies everything
  *else* and exits 1, so a `grep -c applied` after it hides exactly this — check the exit code.
- **`curl -o /dev/null` without checking the status accepts a 404.** `deploy-local.sh` ended by
  curling `$API_URL/health` and testing only curl's exit code — and there is no `/health` endpoint
  on the control plane, which serves `/organizations`, `/projects` and `/services`. A 404 is a
  *successful* HTTP exchange, so curl exited 0 and the smoke test passed on every deploy it ever
  ran, including ones where every route was broken. It now asks for the organizations listing with
  the token read from the cluster and requires a 200, which exercises DNS, TLS, the gateway route,
  a control plane pod, the ACL and a database query. Note also that `curl -w '%{http_code}'`
  prints `000` of its own accord when it never got a response, so `|| echo 000` yields `000000`
  and the "could not connect" branch never matches — `|| true` is the guard `set -e` needs.
- **An overlay that only works from `deploy-local.sh` is not an overlay.** The control plane's
  schema ConfigMap was `kubectl create configmap --from-file` in the script, because the DDL lived
  under `modules/runtime` where a kustomize generator cannot reach. Every kind deploy passed. The
  first cluster reconciled by Flux applied the overlay as written, and the database's initdb pod
  waited on a ConfigMap mount that nothing would ever create. The DDL is now canonical in the
  postgres component with symlinks pointing in (the CRD's direction), and the script generates
  nothing: `kubectl apply -k` must be the whole deploy, or a second deployer finds the difference.
- **A `waitFor` that swallows exceptions turns a broken check into "it never happened".** Two
  runs were spent on a certificate that was `Ready` in 20s by hand, because the fabric8
  generic-resource status parsing in the check threw and the loop reported a timeout. For
  objects from CRDs the suites do not model (cert-manager, Gateway API status), the checks now
  ask `kubectl … -o jsonpath` on the node — the same tool `deploy-local.sh` waits with — and the
  k3s suites apply those manifests with the node's `kubectl` too.
- **LibreSSL's `openssl req -newkey ec` writes explicit EC parameters, which the JDK refuses**
  (`Only named ECParameters supported`). Test certificate fixtures are RSA.
- **The k3s node's `kubectl exec` works; its `wget` is BusyBox** (no PUT, no `--cacert`). Drive
  the gateway from the *host* with `curl --cacert --resolve` against the mapped NodePort — which is
  also the only proof that matches what a developer's machine does.
- **A rolling replacement still refuses requests without a `preStop` sleep.** A pod leaves its
  Service's endpoints the moment its deletion starts, but kube-proxy on each node learns that up
  to a second later — and the runtime unbinds its HTTP port the instant SIGTERM arrives. In that
  second a request routed to the old pod is refused: 2 of 33 control-plane commands during one
  replacement, measured. `lifecycle.preStop.sleep: 5s` (Kubernetes' own sleep action, so a
  workload image owes the platform no shell) runs *before* SIGTERM and the pod serves through it.
  Rendered on every workload and in the control plane's manifest; do not remove it as "unused".
- **A k3s test node running five sample JVMs answers in seconds, not milliseconds.** A suite that
  deploys several real ankka services into one k3s container starves it: the control plane's GET
  latency went to a median of 5.2s and a throughput assertion failed for the wrong reason. Deploy
  the real image only for the service a case actually needs to be `Ready`; the rest can be
  `pause` with `"http": false`.
- **Two things are called "generation".** ankka's lives in the resource's `spec` and is
  what `Service.onObserved` compares; Kubernetes' is `metadata.generation` and is only
  meaningful against `status.observedGeneration`. Conflating them reports the right answer
  about the wrong generation.
- **Two ankka services must never share a Postgres database.** `ankka_timers` has no
  service column, and `TimerSweeper` *deletes* rows whose component id it does not
  recognise — so they silently delete each other's timers. View row tables, named from the
  component id alone, collide the same way.
- **Server-side apply rejects an object carrying `metadata.managedFields`.** Always build
  a fresh object to apply; never re-apply one read back from the server. This only shows
  up against a real API server, which is what the k3s suites are for.
- **A field on `AnkkaServiceSpec` is not a field on the resource until `ankkaservice.yaml` declares
  it.** A structural schema is *closed*: server-side apply of an object carrying an undeclared field
  is refused with `failed to create typed patch object … .spec.x: field not declared in schema` — a
  500, on every projection of every service that sets it, forever. Every offline test passes, because
  nothing but a real API server validates against the schema; `imagePullSecret` was added to the case
  class, the projection, the rendering, the codec suite and three test suites before a k3s run found
  it. `CrdSchemaSuite` now compares the case class's fields against the declared properties in both
  directions, so the same mistake fails in milliseconds and names the field.
- **A project's namespace is the control plane's to create, not the operator's.** Owner
  references are namespace-scoped, so the resource must live beside the workload it owns —
  which makes the namespace a precondition of writing the resource, and the operator only
  learns a project exists by seeing that resource.
- **Server-side apply on an object that does not exist yet is still a PATCH, not a POST.**
  RBAC granting only `create` on a resource 403s the first time `ensureNamespace` runs,
  which is every time a project's namespace is new — the one case that rule exists for. Any
  resource written via `serverSideApply()` needs `patch` in its ClusterRole. Caught only by
  deploying against a real cluster: the k3s test suites mostly use kind's/testcontainers'
  admin credentials directly rather than exercising the shipped RBAC — the exceptions mint a
  real token for the operator's own ServiceAccount, and for a deployed service's, to prove a
  withheld verb is refused by the API server itself, not just unused (`OperatorClusterSuite`,
  features 002 US5 and 004 US1).
- **A `Database` or `DatabaseRole` referencing a `Cluster` in another namespace gets no
  status and no events at all**, not an error — the CNPG reconciler simply never touches
  it. This is why per-project Postgres capacity lives in the project's own namespace rather
  than a shared one: a cross-namespace reference cannot be detected from the referencing
  object's own status, only inferred from it never changing.
- **CNPG does not generate passwords.** Given a `DatabaseRole` with no `passwordSecret`, it
  creates a role nobody can log in as, silently. The platform must generate one itself —
  and only when the credential secret is absent, since regenerating on every reconcile
  rotates the password under a running service on a timer.
- **A `Database` can lose the race against its own `DatabaseRole`.** Both are applied in one
  pass and reconciled independently; a `Database` whose owner role does not exist yet fails
  with `role "x" does not exist` and self-heals once the role lands. Report this as *in
  progress*, never `Failed` — a transient ordering artifact that looks exactly like a
  permanent one.
- **A CNPG `forbidden` error on a role's password secret is transient, not an RBAC bug.**
  CNPG maintains a per-`Cluster` secrets allowlist and adds a newly-referenced
  `passwordSecret` to it only on its own next reconcile — 20-40 seconds after the role and
  secret are created together, not immediately. A status check during that window looks
  identical to a real permission failure; only the message's specific shape
  (`secrets "x" is forbidden`, naming the *secret*, not the role or database) and the fact
  that it clears on its own distinguish the two.
- **`bootstrap.initdb.postInitApplicationSQLRefs` runs as the `postgres` superuser, not as
  `bootstrap.initdb.owner`.** Every table the DDL creates ends up owned by `postgres`, so
  the role the application actually connects as has no privileges on any of them —
  discovered by deploying the control plane's own CNPG-managed database for real: it
  started cleanly, then every write timed out with `permission denied for table
  projection_management`. Fixed with a trailing `GRANT ALL ... TO <owner>` SQL fragment
  applied after the DDL, in the same `configMapRefs` list. This is the one case in this
  codebase that uses `bootstrap.initdb` at all — every per-service database goes through the
  operator's `Database`/`DatabaseRole`, whose owner is the role connecting to it from the
  start, so this trap cannot recur there.
- **Kubernetes defaults `imagePullPolicy` to `Always` for a `:latest` tag.** An image loaded
  straight onto a node (`kind load`, `ctr import`) is then ignored and the pod fails
  `ErrImagePull` with the image sitting right there. The operator renders `IfNotPresent` on every
  workload for this reason, as the platform's own manifests always have for themselves. It went
  unnoticed for two features because every test deployed `registry.k8s.io/pause:3.9` — pullable,
  *and* not `:latest`, which removes both halves of the trap at once.
- **containerd namespaces are hard isolation, and kubelet reads only `k8s.io`.** An image imported
  with plain `ctr images import` lands in the default namespace: the import succeeds, `ctr images
  ls` shows it, and kubelet still cannot see it. In a k3s container it is
  `ctr -a /run/k3s/containerd/containerd.sock -n k8s.io images import` — and `ctr`, not `k3s ctr`,
  which that image answers with "No help topic".
- **`ctr` does not expand a short image name; the kubelet does.** An image imported from a `docker
  save` tar is stored as `docker.io/library/sample-shopping-cart:latest`, and a pod naming
  `sample-shopping-cart:latest` finds it because the kubelet qualifies the name first. `ctr images
  tag sample-shopping-cart:latest …` answers `image "…": not found` for an image sitting right
  there. Ask containerd what it holds (`ctr images ls -q`) and match, rather than assuming the
  prefix — and fail with the listing attached, since the bare exit code reads like a missing image.
- **A port-forward does not go through the Service.** It is API server → pod, so a test using one
  passes with a broken selector or the wrong `targetPort` — the pod is `Ready`, the address is
  dead, and nothing notices. To test that a Service routes, make the request from the k3s *node*
  to its `clusterIP` (`k3s.execInContainer("wget", …)`). By IP: the node does not resolve cluster
  DNS names, only pods do.
- **A service's default descriptor now asserts something.** Saying nothing means "serves HTTP on
  9000" and the pod is not `Ready` until that port opens. Right for an ankka service; an image that
  listens on nothing (`pause`) needs `"http": false` or it is `Failed` when the rollout deadline
  passes. Every `pause` descriptor in the test suites carries it.
- **Kustomize's load restrictor is checked per component directory, not against the
  top-level build root.** A component cannot reference a file outside its own directory —
  not via a relative path, not via a symlink resolving there — even when a common ancestor
  contains both. There is no override for `kubectl apply -k`. The CRD, the operator's
  install manifest and the control plane's RBAC are therefore canonical *inside*
  `kustomization/components/`, with `operator/src/main/resources/ankka/{crd,install}/` and
  `controlplane/src/main/resources/ankka/install/` holding symlinks *into* them — the
  reverse of the direction that seems obvious, and the only direction that works, since sbt
  and the JVM follow symlinks transparently but kustomize does not. Never `ln -sf` onto a
  path that might already hold the real content; copy it out first. This one cost real file
  content, recovered only because the compiled classpath still had it.
- **A strategic merge patch that names a container the target lacks adds a container.** Containers
  merge by `name`, so the production overlay's sidecar patch, written for `operator` when the container is
  `ankka-operator`, rendered a second container holding only `ANKKA_SIDECAR_IMAGE` and no image.
  kustomize accepted it and `RemoteOverlaySuite` passed, because it asked whether the registry's
  sidecar appeared *somewhere* in the document. The API server refused the Deployment
  (`containers[0].image: Required value`) on the first production reconcile after v0.2.2, three minor
  versions after the patch was written. Assert the shape a patch must produce (the variable set
  once, the default gone), not the presence of a string.
- **Changing a cert-manager issuer's `server` does not replace the certificate it issued.**
  cert-manager reissues on a spec change or when the secret's issuer annotations disagree with
  `issuerRef`; a new server under the same issuer name is neither, so the old CA's certificate
  stays until renewal. Moving a production cluster from Let's Encrypt staging to production renamed the
  ClusterIssuer (`letsencrypt-production`) for exactly this, and `RemoteOverlaySuite` checks that
  the Certificate names an issuer that exists.
- **The operator re-applies a project namespace's `managed-by` label on every reconcile.** A test
  that removes it to make the gateway refuse a route is racing a platform that heals it — the k3s
  route-rejection case passed or failed on timing. Refuse the route from the gateway's side (its
  listener's selector, which the operator does not own) and put it back in a `finally`.
- **A kustomize Component's `namespace:` transformer runs over everything the overlay accumulated
  before it.** Setting it on the Keycloak operator component renamed CNPG's namespace and the render
  failed with an ID conflict. The operator's manifests sit in a nested plain Kustomization
  (`components/keycloak-operator/manifests`) whose transformer sees only them — and the
  ClusterRoleBinding's subject, which no namespace transformer reaches, is patched by hand there
  and in `KeycloakStack`.
- **A suite that fills a manifest placeholder with a plain `replace` also rewrites variable
  *names* that contain it.** `ControlPlaneClusterSuite` turned `ANKKA_BASE_DOMAIN` into
  `ANKKA_test.local`, so the deployed control plane had no base domain — harmless for two
  features, and a crash-loop at startup once the issuer was derived from it. Replace the
  placeholder with a lookbehind (`(?<!ANKKA_)BASE_DOMAIN`), and read a deployed pod's `env` before
  blaming its image.
- **A pull secret cannot be proved by restarting.** Every workload renders
  `imagePullPolicy: IfNotPresent`, so once an image is on a node a restart succeeds with no credential
  at all — "clear the registry and restart" passes whether or not clearing did anything. The negative
  half needs a tag the node has never held: `EndToEndClusterSuite` pushes two tags to an in-cluster
  `registry:2`, removes both from the node, and deploys the second only after the credential is gone.
  The registry is reached at `127.0.0.1:<nodePort>`, the one address containerd treats as insecure by
  default, so no TLS and no per-node containerd configuration is needed.
- **`PodSpecBuilder` materialises every list it was never given.** `getImagePullSecrets` on a spec
  that never set one is an empty list, not `null`, so a test asserting "absent" on a fabric8-built
  object is asserting something the builder does not do. That is also why adding the field changed
  nothing for an existing service: the rendered Deployment already carried the empty list.
- **Envoy Gateway cuts every route at fifteen seconds unless the route says otherwise.** Envoy's
  default route timeout applies to an `HTTPRoute` rule that names none, and it ends a gRPC stream or an
  SSE stream mid-flight. The gRPC rule says `timeouts.request: "0s"`, which also turns off the route's
  stream idle timeout. The HTTP rule still names none, so an SSE stream through the gateway is very
  likely cut at fifteen seconds today; changing that changes what every exposed service renders.
- **A JVM throttled to 100 millicores takes about ten seconds to start**, and on a busy node missed its
  readiness deadline entirely; the proxy's allotment is 250m with `SerialGC`, C1 only and one visible
  processor (research R19), which serves within two seconds. Whole-suite k3s failures that a single
  scenario run does not reproduce were exactly this.
- **The API server writes quantities back normalised**: `1000m` as `1`, `1024Mi` as `1Gi`. Compare
  resources by amount, not by string.
- **Garage refuses a secret file anyone but its owner can read, and a macOS bind mount reports 0640
  whatever the file's mode.** `GARAGE_RPC_SECRET_FILE` failed `File … is world-readable! (expected 0600)`
  in every local run; the component gives the RPC secret and the admin token as variables from a Secret,
  which have no mode to get wrong.
- **The AWS SDK for Java since 2.30 sends a trailing checksum in an `aws-chunked` upload, and Garage refuses
  it as `Invalid payload signature`.** Configure a client with `requestChecksumCalculation(WHEN_REQUIRED)`
  and `responseChecksumValidation(WHEN_REQUIRED)`; the docs' object storage page says so for every client,
  since other SDKs changed the same default.
- **A Garage key carries its generation in its name** (feature 039): `<bucket>` is generation 0 — every
  key made before re-issue existed — and `<bucket>#<n>` after. `StorageCredential.ensure` takes the
  generation in place from the status and looks for *that* key; looking for the bare bucket name after a
  re-issue, once generation 0 has expired, read as "the store lost the key" and patched the Secret back
  to a fresh generation-0 key. An old key ends by Garage's own clock (`UpdateKey.expiration`), set once
  and never moved, so the operator keeps no timer.
- **A write pause is `DenyBucketKey` on the key in place, not a new read-only key.** Every instance holds
  the same key, so taking write from it pauses them all at once with no rollout; a new key rolled out
  left the old one writing until each pod was replaced, and writes during the pause escaped the verify.
- **The storage-credential annotation is rendered only above generation 0**, as the route's removal is
  the one exception to "nothing changes for a service that does not ask": an annotation of `0` on every
  service with a bucket would have rolled them all on upgrade.
- **The operator's `write` helper in `OperatorClusterSuite` always names the resource `cart`.** A case
  that needs a second service applies its own `AnkkaService` by name; the first cloud cases waited thirty
  seconds for a resource `write` had put under another name.
- **A test that renders the overlays as shipped proves no replacement.** The cloud settings' defaults
  equal the components' literals, so a render passes with every replacement missing; `RemoteOverlaySuite`
  renders a copy of the local overlay beside it with a distinct value in each key and asserts each one
  arrives, and was seen to fail with one replacement broken.
- **The route's removal for a bucket is rendered for every service**, asked or not, so dropping
  `exposeObjectStorage` (or the bucket) leaves no route; that is why `RenderingUnchangedSuite` was repinned
  for feature 034, gaining one action line per fixture and no object. A repin that changes an object is a
  service rolling on upgrade, and is never accepted.

## Backups and recovery (feature 041)

Every project database archives through the Barman Cloud plugin into `platform.backups.<project>` when the
installation names a target (`ANKKA_BACKUP_*`, `BackupSettings`), rendered in the service pass beside the
cluster (`CnpgRendering.backupActions`) because a project may have no `AnkkaProject`. The project
reconciler reports lines, restores, rehearsals and clusters on the project's status; the control plane
journals a restore's or rehearsal's end from there. A restore is a second cluster beside `ankka-db`
(`RestoreRendering`); a switched service is told `ANKKA_DB_HOST` and `ANKKA_DB_LINE` literally on its pod.
A rehearsal runs in `ankka-<project>-rehearsal`, the one namespace the operator may delete a cluster in,
granted by a RoleBinding the control plane writes there (`bind` on `ankka-operator-rehearsal` alone).

- **Adding the plugin to a running cluster restarts its instance once** (about 20 seconds, measured). So
  turning backups on restarts every project database once; it rolls no service pod.
- **CNPG refuses to archive into a non-empty archive.** A restore archives nothing until a service is
  switched to it, and then under its own name; it never reuses the line it was made from.
- **A recovery that cannot reach its moment is never `Failed`.** CNPG leaves it `Setting up primary`; the
  operator reports a restore or rehearsal failed after `restoreTimeout` (30 minutes).
- **A restore gets no `Database` or `DatabaseRole` objects.** Its roles and databases come with the
  restored data, and the same CNPG objects on two clusters in one namespace collide.
- **The operator reads inside a database by `psql` over `pods/exec`**, as `postgres` on the socket,
  with every statement in `DatabaseQueries`; a literal `env` value beats the same key from `envFrom`.
- **CNPG's release limits its operator to 100m CPU.** With the plugin and a cluster per scenario on one
  k3s node, its health check timed out and the kubelet killed it every minute, and every write to a CNPG
  resource then failed with `no endpoints available for service "cnpg-webhook-service"`. `BackupStack`
  raises the limit on the test node; an installation with many projects may need to as well.
- **Garage's NetworkPolicy must admit CNPG's pods**, which carry `cnpg.io/cluster`, not the platform's
  managed-by label; `zero-trust.yaml` has a rule for project namespaces and one for `ankka-controlplane`.
- **The suite's operator runs with admin credentials**, so taking a RoleBinding away refuses it nothing.
  `RehearsalsClusterFeatures` refuses a delete with a ValidatingAdmissionPolicy, which binds everyone.
- **A Garage node that replaces a lost one has a new id, and refuses requests until it has a role.**
  Garage answers `500 Layout not ready` (or "no such key" on S3) for about one request in three, since
  the `garage` Service still routes to it. So `garage-layout` is a Deployment that reconciles every 15
  seconds and gives the new node the lost one's zone, not a Job someone has to run again. A role whose
  node is merely down, with nothing new to take its place, is left alone.
- **Every reconciler's executor comes from `Fabric8Executor.of(client, settings)`.** The project
  reconciler built a bare `Fabric8Executor(client)`, with no store, and every rehearsal failed with "an
  object storage action was rendered with no store" — in production as much as in the suite.
- **A base backup scheduled before the cluster archives fails, and the next is a day away.** The
  ScheduledBackup's `immediate` backup ran while CNPG was still adding the plugin ("requested plugin
  is not available", or "instance manager was restarted during backup"), for every project that
  existed before backups and every switched restore. Gating the schedule on `ContinuousArchiving` is
  not enough: on a cluster that existed before backups the condition is already `True` before the
  plugin is added ("the cluster has no plugin configured"). So the executor also takes the first base
  backup again (`BaseBackupRetry`): two minutes after the last failure, at most five times, and only
  while none has completed. The control plane's own database, whose schedule is the `backups`
  component's, is retried the same way from `PlatformBackups`: its first base backup can run before the
  operator has issued its credential, and a restore with no base backup waits for ever, never failing.
- **A rehearsal leaves the operator's report when its cluster is removed.** The rehearsal gauge read
  the report and missed it; the projector builds the same view the status route does, from the
  entity's record, and sets every backup metric from that.
- **A rehearsal schedule needs a namespace only the control plane can make.** `PUT
  /projects/{id}/database` with `rehearse` ensures the rehearsal namespace before it records the
  schedule, as `POST /projects/{id}/rehearsals` does; without it the operator had nowhere to rehearse.
- **An untyped resource's `status` is Scala collections in the control plane, not Java ones.** The
  platform registers Jackson's Scala module with fabric8's mapper, so `GenericKubernetesResource`'s
  additional properties come back as Scala `Map`s and `List`s; a read matching `java.util.Map` found
  nothing, silently, and the control plane never reported its own backups. An offline probe passed
  because it unmarshalled with fabric8's plain mapper. `ControlPlaneLine` accepts both.
- **The archive's lag says when a segment was sent, not what it held.** `lastRestorable` is
  `last_archived_time`, and a segment holding only commits from before a moment can be archived after
  it; a recovery to that moment replays everything, meets no later commit and fails "recovery ended
  before configured recovery target was reached", for ever. Before a restore's or a rehearsal's cluster
  is first rendered, `RecoveryPoint` writes an empty transaction on the source, switches the segment
  and waits for the archive to hold it.
- **A held control plane writes nothing to the cluster.** After its own database's restore it starts on a
  marker row (`ankka_restore_marker`) and its projector's client is a `HeldClient`, which compares every
  write with the cluster and records the difference, until `ankka installation restore --release`.
