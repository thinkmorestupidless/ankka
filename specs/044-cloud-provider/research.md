# Research: The Cloud Provider

Decisions, each with what was chosen, why, and what else was weighed. The spec settles the
*shape* (a resource, six kinds, a provider outside the operator); these settle how that shape
lands in the code that exists. File references are to the tree as it is before this feature.

## R1. One resource, typed in `crd`, partial on purpose

**Decision**: `CloudResource` is a fabric8 `CustomResource[CloudResourceSpec, CloudResourceStatus]`
in `crd`, beside `AnkkaService` and `AnkkaProject`, with the same group and version
(`ankka.thinkmorestupidless.com/v1alpha1`), plural `cloudresources`, short name `cres`, namespaced,
with a status subresource. Its YAML is `kustomization/components/crd/cloudresource.yaml`, symlinked
from `operator/src/main/resources/ankka/crd/` as the other two are, and `CrdSchemaSuite` holds the
case classes and the schema to each other in both directions (`spec`, `spec.subject`, `status`).

**Rationale**: the operator writes it by server-side apply and reads its status; both need a typed
class and a *closed* structural schema, or the first apply of an undeclared field is a 500 forever
(`.claude/rules/kubernetes.md`, "a field on `AnkkaServiceSpec` is not a field on the resource until
`ankkaservice.yaml` declares it"). The CNPG and Strimzi classes in the operator are partial fabric8
`CustomResource`s for exactly this reason; `CloudResource` follows them, but lives in `crd` because
the control plane's suites and `ankka-gcp` both need the type, and `crd` depends on nothing.

**Alternatives**: a `GenericKubernetesResource` built from maps, as the `ReferenceGrant` and the
cert-manager objects are. Rejected: there is no schema suite for a map, and a provider in another
language needs a documented shape anyway, which the typed class and its YAML are.

## R2. `parameters` and `outputs` are string maps; a list is comma-joined

**Decision**: `spec.parameters: Map[String, String]` and `status.outputs: Map[String, String]`, both
declared in the schema as objects with `additionalProperties: {type: string}`. A list-valued
parameter (`secret-access`'s `own` and `read`, `bucket`'s `corsOrigins`, `secret-sync`'s `entries`)
is one string, comma-separated; an entry of `secret-sync` is `NAME=id`. The contract
([contracts/cloud-resource.md](contracts/cloud-resource.md)) names every key per kind.

**Rationale**: a map of strings is a closed schema with one property, round-trips through every
language a provider might be written in, diffs cleanly in `kubectl get -o yaml`, and needs no Jackson
subtlety (the `Option[Long]`-as-`Integer` trap in the rules file is avoided by having no typed
numbers in the map at all). Secret ids and origins contain no comma: a secret id is a Kubernetes
name and an origin is a URL.

**Alternatives**: a typed sub-object per kind (six case classes in one `spec`), rejected because
every kind's fields would be optional on every request and the schema would say nothing about which
belong together; `x-kubernetes-preserve-unknown-fields` with `Map[String, Any]`, rejected because
the Scala Jackson module's handling of `Any` is exactly the kind of thing only a real API server
would reveal.

## R3. Names: a request is named from its kind and its subject, and a project's carries a dot

**Decision**: a service's request is `<service>-<suffix>` and a project's is `<project>.<suffix>`,
both in the project's namespace. The suffixes are the kind's: `identity`, `secret-access`,
`bucket`, `storage-credential` (the `bucket-credential` of a service's bucket); a project's are
`secret-sync`, `backup-bucket`, `backup-credential`, `wrapping-key`. `Names.cloudRequest` in the
operator derives them; nothing else spells one.

**Rationale**: the spec's edge case "two requests for one thing" asks for a deterministic name so a
second render finds the first, and gives `<service>-bucket` and `<project>-backup-bucket` as
examples. A service named `shop-backup` in project `shop` would make the second collide with the
first under those examples; a dot cannot appear in a service name (a DNS label) and can in a
resource name (a DNS subdomain), so `shop.backup-bucket` can never equal any `<service>-bucket`. The
spec's example is amended to the dotted form.

## R4. The operator renders a request as it renders a `KafkaTopic`: an `Action`, server-side apply, an owner

**Decision**: `Action.EnsureCloudResource(CloudResource)`, executed by `Fabric8Executor` with the
same `fieldManager("ankka-operator").forceConflicts().serverSideApply()` as every other `Ensure*`.
`CloudRequests` (new, in the operator) builds the object: the spec's `provider` copied from
`Settings.cloud`, `subject`, `kind`, `parameters`, `credentialGeneration`, the service's identity
labels, and `Labels.ownerReference(resource)` (or the `AnkkaProject`'s, for a project's request).
The operator never writes a status (`SetStatus` has no `CloudResource` case, and the ClusterRole
has no verb on `cloudresources/status` but `get`).

**Rationale**: it is the pattern the rules file names as the operator's whole idea, and the only one
that gives cascade deletion for free: a `CloudResource` owned by its `AnkkaService` goes with it,
which is the spec's "Removal" edge case with no sweep.

## R5. Reading a fulfilment: an observation, a decision, an informer

**Decision**: `Executor.observeCloudResource(namespace, name): Option[CloudObservation]` reads the
object through `ifTypeExists` (a cluster without the CRD reads as "absent", like the Strimzi reads),
returning `metadata.generation`, `creationTimestamp` and the status, if any. `CloudProvisioning.decide`
is a pure function of the rendered request, the observation, the clock and the acknowledgement
bound, yielding `CloudPlan`:

| Observation | Plan |
|---|---|
| absent (not yet applied this pass) or status with no `observedGeneration`, within the bound of `creationTimestamp` | `Waiting(None)` |
| status with no `observedGeneration`, past the bound | `Waiting(Some("no provider for <provider> has answered"))` |
| `observedGeneration` behind `metadata.generation` | `Waiting(Some("waiting on the provider for generation N"))` |
| `phase: Waiting` | `Waiting(detail)` |
| `phase: Ready` or `Recovered` | `Ready(outputs, recovered, credentialGeneration)` |
| `phase: Failed` | `Failed(detail)` |

The `Operator` gains a third informer, on `CloudResource` in watched namespaces, wrapped in
`try`/`NonFatal` like the `AnkkaProject` one so a cluster without the type still runs; its handler
enqueues the owner named by the object's controller owner reference (an `AnkkaService` on the
service queue, an `AnkkaProject` on the project queue). A pass that leaves a request unacknowledged
calls `queue.enqueueAfter(ref, bound)` so the bound is reported when it passes and not at the next
five-minute resync.

**Rationale**: SC-004 wants the status to recover within 30 seconds of a provider starting; the
resync is five minutes. The decision is pure so `CloudProvisioningSuite` is a rule table like
`ProvisioningSuite`, "no fake, no seam, just data".

## R6. The bucket path on an installation whose object store is its cloud account's

**Decision**: the operator takes the cloud path for `provisionObjectStorage` when `Settings.cloud`
is set and `Settings.objectStore` (Garage) is not. `ObjectStorage.decide` gains one input,
`cloud: Option[CloudBucketPlans]` (the identity, bucket and credential plans), and one output: `ObjectStoragePlan.Ready`
carries `cloud: Option[CloudBucket(bucket, endpoint, region, credentialGeneration)]`. Rendering with
a `CloudBucket` emits `EnsureCloudResource` for the `identity`, `bucket` and `storage-credential`
requests (the credential request once the identity is `Ready`, since its `identity` parameter is
that fulfilment's output) instead of `EnsureBucket`/`EnsureStorageCredential`, sets `ANKKA_S3_ENDPOINT`, `_REGION` and
`_BUCKET` from the outputs, reads `_ACCESS_KEY` and `_SECRET_KEY` from `<service>-storage` exactly as
today, and puts `ankka.thinkmorestupidless.com/storage-credential-generation: <n>` on the pod
template so a changed generation rolls the pods (FR-009).

While the requests are `Waiting`, the requests are applied and the Deployment is **not**: the
endpoint and region are the fulfilment's, so there is nothing truthful to render, and the service
is reported `UpdateInProgress` with the plan's detail. On `Failed` the Deployment is applied with no
`ANKKA_S3_` variable and `status.objectStorage` is `Failed` with the provider's detail, word for
word, which `services get` shows as `object storage: <detail>` through the existing fold.

An installation with both Garage and a cloud provider keeps Garage: 039's move is 039's.

**Rationale**: the bucket feature says a `Failed` service starts with no `ANKKA_S3_` variable and
a `Ready` one starts with all five set from the fulfilments; both fall out of rendering from the
plan. Not rendering the Deployment while waiting is the one departure from Garage, where the
endpoint is a setting and the pod can wait on its Secret.

**Alternatives**: a sixth `ANKKA_OBJECT_STORE_KIND` setting, rejected because the spec says the
features define no cloud setting of their own and "has a cloud provider and no Garage" already says
it.

## R7. The credential generation lives on the `AnkkaService`

**Decision**: `AnkkaServiceSpec.storageCredentialGeneration: Long = 1`, declared in the schema,
copied by the operator into the `storage-credential` request's `spec.credentialGeneration`. The
control plane's projector does **not** set the field: a field its manager never owns cannot be
reverted by a re-projection, and the operator reads `1` when it is absent. A later feature gives a
member a command and the projector a value; this feature ships the field, the operator's half and
the provider's half, and the k3s scenario raises it on the resource as `kubectl` would, then forces
a re-projection and reads it still raised. `docs/reference/limitations.md` says a rotation has no
command yet.

**Rationale**: FR-009 and SC-006 are about the provider, the Secret and the roll, none of which
needs a route to be proven. A route and a CLI command would carry an authorization decision (who may
rotate) this spec does not make.

## R8. The scripted cloud provider: a pure fulfilment and a thin controller, under the shipped identity

**Decision**: `operator/src/test/scala/.../operator/cloud/ScriptedCloudProvider.scala` and
`ScriptedFulfilment.scala`. The fulfilment is pure: given a spec, the account, the location, the
rotation grace and what it remembers, it answers with a status and, for a credential kind, a Secret
to offer. Its answers are deterministic and visibly made up (`bucket = <account>-<project>-<service>`,
`endpoint = https://storage.scripted.invalid`, `identity = <serviceAccount>@<account>.scripted`,
`key = <kmsKey>` or `scripted-key`). It remembers what it made per `(kind, subject)`, with the account and location it made it in, so
a second request for the same subject answers `Recovered` and one naming another account or
location answers `Failed`, records every credential it issues and
ends (`issued`, `ended`, each with the generation and the clock), ends a credential whose `create`
met a 409, and after a generation bump ends the previous one at `fulfilledAt + grace`. A script can
make a named request fail with a given reason.

The controller is an informer on `CloudResource` for one provider name, built on a fabric8 client
the suite hands it, that calls the fulfilment and writes with `editStatus`, `create` and `patch` and
nothing else. In a k3s suite that client is minted from the `ankka-cloud-provider` ServiceAccount's
token under the shipped ClusterRole, as `OperatorClusterSuite` mints the operator's, so the
features' "the cloud provider cannot read a secret back" is the API server refusing.

**Rationale**: FR-020 puts the fake in the operator's test sources and asks it to honour FR-008 and
FR-009; separating the pure part gives `ScriptedCloudProviderSuite` the four-row credential table
offline, and the controller is small enough to read. Running it under the real identity is what
makes SC-002 a proof rather than a reading of the YAML.

## R9. Settings: six variables, declared once, read by three processes

**Decision**: `PlatformVariables` gains `CloudProvider`, `CloudAccount`, `CloudLocation`,
`CloudKmsKey`, `CloudAcknowledgementBound`, `CloudRotationGrace` (the six names) and
`CloudProviders: Set[String] = Set("gcp")`, the one list of known provider names FR-014 allows, with
`CloudProviderNone = "none"`. They join `PlatformOnly`. The operator's `Settings.cloud:
Option[CloudSettings]` is read as `BrokerSettings.read` reads: `ANKKA_CLOUD_PROVIDER` absent or
`none` means none; otherwise the account and the location are required, the key optional, the two
durations defaulted, and an unknown provider name refuses to start, naming the known ones. The
control plane reads the same four names into `CloudConfig` through `reference.conf`
(`ankka.controlplane.cloud`); it has no use for the durations.

The platform ConfigMap gains `cloudProvider`, `cloudAccount`, `cloudLocation`, `cloudKmsKey`,
`cloudAcknowledgementBound` and `cloudRotationGrace`; both overlays' `replacements` copy them onto
the operator's and the control plane's containers, as `otlpEndpoint` is copied. The `cloud-provider`
component renders a ConfigMap `ankka-cloud` in `ankka-cloud-provider` from the same replacements,
which a provider's Deployment reads with `envFrom`, since a replacement reaches only what the overlay
renders and `ankka-gcp`'s Deployment is not in it.

**Rationale**: the spec's FR-011, and the rules file's "which variables are the platform's is said
once"; `PlatformDeclarationSuite` fails a literal list of `ANKKA_` names anywhere but there, which is
why the known provider names live beside the variables.

## R10. The provider's grant is the operator's grant

**Decision**: the `cloud-provider` component ships namespace `ankka-cloud-provider`, ServiceAccount
`ankka-cloud-provider`, ClusterRole `ankka-cloud-provider` and its ClusterRoleBinding. The rules:
`cloudresources` get/list/watch; `cloudresources/status` get/update/patch; `secrets` create/patch.
The operator's ClusterRole gains `cloudresources` get/list/watch/create/patch and
`cloudresources/status` get. Neither has `delete` on `cloudresources`, neither has `get` or `list`
on Secrets; `OperatorClusterSuite` proves both under minted tokens, the positive and the negative
half each.

**Rationale**: the clarification of 2026-10-09; the spec's Context said "the same on the spec" for
the provider and FR-003 says it writes only the status, so the narrower reading (no `create` or
`patch` on the spec) is the one shipped. `cloud-provider` joins the reserved project ids in both
lists (`Names.ReservedProjectIds`, `ProjectId.Reserved`), or a project of that name would render
the provider's namespace.

## R11. The control plane: an installation route, a refusal helper, no new status field

**Decision**: `GET /installation` (authenticated, any member) answers `Installation(platformVersion,
cloud: Option[CloudInstallation(provider, account, location, kmsKey: Option[String])])`; `kmsKey` is
present only when the caller is an owner of some organization, decided in the endpoint as every
cross-entity check is. `ankka installation` prints it. `CloudProviderNeeded` in `controlplane-api`
is the one place that words a refusal (FR-012): `<choice> needs the cloud provider <name>, and the
installation has none`; each consumer feature applies it to its own setting when it adds it.
`ServiceStatus` is unchanged: the bucket's name already travels in `status.objectStorage.bucket`
and the phase and detail in the existing fields (FR-018).

**Rationale**: there is no settings route today; the smallest one that says what FR-019 asks is a
read of four values. The refusal cannot be applied to settings that do not exist yet, and a helper
with a test is the honest part of it this feature can deliver.

## R12. Where each scenario is tested

| Feature file | Where | Note |
|---|---|---|
| `bucket.feature`, all four | `CloudProviderClusterFeatures` (controlplane tests, k3s) | the operator and the scripted provider in-process, the provider under its minted token; `pause` images with `"http": false` so the node stays light; the variables read from the Deployment |
| `credential.feature` 1, 2, 5, 6 | `CloudProviderClusterFeatures` | the fake's records are the assertions; the k3s suite sets `ANKKA_CLOUD_ROTATION_GRACE=20s` |
| `credential.feature` 3, 4 | `OperatorClusterSuite` | the two identities' tokens, a `get` refused by the API server |
| `absent.feature` 1, 2 | `CloudProviderClusterFeatures` | bound set to `30s`; the provider started after the bound reports |
| `absent.feature` 3 (outline, four rows) | `CloudProviderNeededSuite` (controlplane-api) | the four settings are 038's, 039's, 041's and 042's and do not exist; the helper's wording is tested with each row's words, and each consumer re-points its row at the real refusal when it adds the setting |
| `absent.feature` 4 | `RenderingUnchangedSuite`, `RenderingGoldenSuite` | an installation with no provider renders byte for byte what it rendered before; no `EnsureCloudResource` anywhere |
| `kinds.feature` 1–5 | `CloudRequestsSuite` (operator, offline) | each kind's renderer, given the subject and the inputs its consumer will supply, yields the request with the contract's keys and nothing else; the triggers ("keeps its project secrets in its cloud account") are the consumers' settings and are theirs to wire; `ranElsewhere` names this |
| `kinds.feature` 6 (outline, six rows) | `CloudRequestsSuite` | every parameter key is in the contract's set for the kind and `location` is the setting's string verbatim |
| `providers.feature` 1 | `CloudProviderClusterFeatures` | the whole suite with no network beyond the k3s container; the scripted provider's `reached` counter is zero |
| `providers.feature` 2 | `ankka-gcp`'s nightly, by the suite's external mode (R13) | not green in this repository; SC-007 is the gate on 038 and 039 |
| `providers.feature` 3 | `SettingsSuite`, `CloudRequestsSuite` | a second name in `CloudProviders` and nothing else changes the rendered requests but `spec.provider` |

## R13. Running the features against a real provider

**Decision**: `CloudProviderClusterFeatures` takes `-Dankka.cloud.external=<path to kubeconfig>`: it
then starts no k3s and no scripted provider, uses that cluster, and expects a provider to be running
in it under the shipped component with the installation's real `ANKKA_CLOUD_*`, which the suite reads
from the environment. Everything else, the operator in-process and the scenarios, is the same. The
switch is forwarded in `Test / javaOptions` like every other (the rules file's first trap).

**Rationale**: FR-021 asks that the files be runnable against `ankka-gcp`; the scenarios already
are, and a kubeconfig is the one input that differs. The mode is exercised by hand in this feature
(there is no real provider yet) and nightly by `ankka-gcp` once it exists; the plan says so rather
than counting it green.

## R14. Nothing is deleted, and recovery is by memory and by the account

**Decision**: a provider treats a removed `CloudResource` as nothing to do; the scripted provider
remembers across requests, and `ankka-gcp` finds by name in the account. The operator's
`EnsureCloudResource` carries the owner reference, so the request goes with its service; the Secret
`<service>-storage` has no owner reference, as today, and outlives it. `recovered` on the status is
set by the provider from what it found, not by the operator from timestamps: the operator cannot see
the account, and the provider is the one who looked.

**Rationale**: the spec's FR-016 and the "Removal" edge case; `ObjectStorageStatus.recovered`
already exists and is copied from the plan.

## R15. Documentation

**Decision**: a new `docs/platform/cloud-provider.md` is the contract for whoever writes a provider
and for whoever installs one: the resource, the six kinds with their keys, the Secret rule, the
grant, the settings and the component. `docs/platform/object-storage.md` gains "On an installation
whose object store is its cloud account's"; `docs/platform/install-cloud.md` gains the settings and
the component in its "Apply it, in order"; `docs/reference/configuration.md`'s "Set by the platform"
prose names `ANKKA_CLOUD_*`; `docs/reference/limitations.md` records: one provider per installation,
no rotation command, nothing deleted in a cloud. `just docs-reference` rewrites the routes page for
`GET /installation`.

## R16. What is deliberately not here

- No `managed-database` kind (the spec's open question stays open).
- No console surface: the console has no installation page, and a row for a bucket's provider is
  not asked for.
- No `ankka-gcp` code, image or workflow: SC-007 is that repository's, and this feature ends at the
  contract, the fake and the external mode.
- No Garage change: `GarageStore`, `StorageCredential` and `ObjectStoreStack` are untouched.

## Verify first, gathered

Things the implementation must check against a real API server before trusting the offline suites:

- Server-side apply of a `CloudResource` under the operator's token, the first time (a `patch`
  on an absent object), and that the owner reference survives.
- `editStatus` on `cloudresources/status` under the provider's token, and that `metadata.generation`
  increments on a spec change and not on a status write (the subresource guarantees it, if the YAML
  declares it).
- A `create` of a Secret under the provider's token meeting a 409, and a `patch` of one it cannot
  `get`.
- The informer on `CloudResource` under the operator's token delivering a status-only update.
- That `kubectl delete ankkaservice` removes the owned `CloudResource`s and not the Secret.

## Verified during implementation

*(filled in as the implementation proves or disproves the above)*
