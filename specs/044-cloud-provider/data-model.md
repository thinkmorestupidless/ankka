# Data Model: The Cloud Provider

What exists, who writes it, and what each state means. Decisions are in
[research.md](research.md); the wire shapes are in [contracts/](contracts/).

## In the cluster

### Cloud request — `CloudResource`

| | |
|---|---|
| Group, version, kind | `ankka.thinkmorestupidless.com/v1alpha1`, `CloudResource`, plural `cloudresources`, short name `cres` |
| Scope | namespaced; in the project's namespace |
| Name | `<service>-<suffix>` for a service's, `<project>.<suffix>` for a project's (`Names.cloudRequest`, R3) |
| Labels | the subject's identity labels (`Labels.identity`): managed-by, project, service |
| Owner reference | the `AnkkaService` or `AnkkaProject` it serves, `controller: true`, `blockOwnerDeletion: true` |
| `spec` written by | the operator, by server-side apply, field manager `ankka-operator`; never its status |
| `status` written by | the provider, through the status subresource; never its spec |
| Deleted | with its owner, by the garbage collector. Never by the operator, never by the provider |

#### `spec`

| Field | Type | Meaning |
|---|---|---|
| `provider` | string | `ANKKA_CLOUD_PROVIDER` copied in; a provider fulfils only requests naming it |
| `kind` | string | one of `identity`, `secret-access`, `secret-sync`, `bucket`, `bucket-credential`, `wrapping-key` |
| `subject` | object | `project` (required) and `service` (empty for a project's request) |
| `credentialGeneration` | integer | `1` on a `bucket-credential`; `0` (absent) on every other kind |
| `parameters` | map of string to string | the kind's fields, named in [contracts/cloud-resource.md](contracts/cloud-resource.md) |

#### `status`

| Field | Type | Meaning |
|---|---|---|
| `observedGeneration` | integer, optional | the `metadata.generation` the rest of the status describes. Absent until a provider has read the request once |
| `phase` | string | `Waiting`, `Ready`, `Recovered` or `Failed` |
| `detail` | string, optional | why it waits or failed; never a secret |
| `account` | string | the account the thing was made in |
| `location` | string | the location it was made in, in the installation's words |
| `credentialGeneration` | integer, optional | the generation of the credential now in the Secret (`bucket-credential` only) |
| `recovered` | boolean | the thing existed before this request; `phase` is `Recovered` when ready and recovered |
| `providerVersion` | string | what fulfilled it, e.g. `ankka-gcp 0.1.0`, `scripted 0.7.0` |
| `outputs` | map of string to string | the kind's outputs, named in the contract |

### Storage credential — Secret `<service>-storage`

As feature 034 made it, with one more writer:

| | |
|---|---|
| Namespace | the project's |
| Entries | `ANKKA_S3_ACCESS_KEY`, `ANKKA_S3_SECRET_KEY` |
| Owner reference | none; it outlives the service's resource |
| Written | once, by the provider's `create`; a 409 ends the credential the provider had just made and the Secret is left as it was |
| Patched | by the provider, on a raised `credentialGeneration`, with the new credential's values |
| Read by | the kubelet, for the developer's container. Never the operator, never the provider, never the control plane |

### Platform settings — ConfigMap `ankka-platform` (namespace `ankka-gateway`)

Six new keys beside `baseDomain`, `httpsPort` and `otlpEndpoint`:

| Key | Variable it becomes | Default in the component |
|---|---|---|
| `cloudProvider` | `ANKKA_CLOUD_PROVIDER` | `none` |
| `cloudAccount` | `ANKKA_CLOUD_ACCOUNT` | empty |
| `cloudLocation` | `ANKKA_CLOUD_LOCATION` | empty |
| `cloudKmsKey` | `ANKKA_CLOUD_KMS_KEY` | empty |
| `cloudAcknowledgementBound` | `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND` | `2m` |
| `cloudRotationGrace` | `ANKKA_CLOUD_ROTATION_GRACE` | `1h` |

Copied by kustomize `replacements` onto the operator's and the control plane's containers, and
into ConfigMap `ankka-cloud` in `ankka-cloud-provider`, which a provider's Deployment reads.

### The provider's identity — component `cloud-provider`

| Object | Name | Namespace |
|---|---|---|
| Namespace | `ankka-cloud-provider` | |
| ServiceAccount | `ankka-cloud-provider` | `ankka-cloud-provider` |
| ClusterRole | `ankka-cloud-provider` | cluster |
| ClusterRoleBinding | `ankka-cloud-provider` | cluster |
| ConfigMap | `ankka-cloud` | `ankka-cloud-provider` |

A provider adds its Deployment, and on GKE the ServiceAccount's Workload Identity annotation.

### `AnkkaService`, one new spec field

| Field | Type | Meaning |
|---|---|---|
| `storageCredentialGeneration` | integer, default `1` | copied into the `storage-credential` request's `spec.credentialGeneration`; raising it asks for a new credential; the control plane's projector never sets it, so a value raised on the resource survives re-projection (R7) |

`status.objectStorage` is unchanged in shape: on the cloud path `bucket` is the provider's output,
`phase` and `detail` are folded from the two requests, `recovered` is the bucket request's.

## In the operator's memory

### `Settings.cloud: Option[CloudSettings]`

| Field | From | Rule |
|---|---|---|
| `provider` | `ANKKA_CLOUD_PROVIDER` | absent or `none` means no settings; otherwise must be in `PlatformVariables.CloudProviders` |
| `account` | `ANKKA_CLOUD_ACCOUNT` | required when there is a provider |
| `location` | `ANKKA_CLOUD_LOCATION` | required when there is a provider |
| `kmsKey` | `ANKKA_CLOUD_KMS_KEY` | optional |
| `acknowledgementBound` | `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND` | duration; `2m` as shipped |
| `rotationGrace` | `ANKKA_CLOUD_ROTATION_GRACE` | duration; `1h` as shipped; read by the operator only to validate it |

### `CloudObservation`

What `Executor.observeCloudResource` returns for one request: `generation: Long`, `createdAt:
Instant`, `status: Option[CloudResourceStatus]`. Absent when the object or the type is absent.

### `CloudPlan`

| Case | Meaning |
|---|---|
| `Waiting(detail: Option[String])` | not yet answered for this generation, or the provider says it is working |
| `Ready(outputs, recovered, credentialGeneration)` | the provider's answer for this generation |
| `Failed(detail)` | the provider's refusal, word for word |

Derived by `CloudProvisioning.decide` (the table in R5). `CloudBucketPlans(identity, bucket, credential: Option[CloudPlan])` make an `ObjectStoragePlan`
for the bucket path (R6).

### `ObjectStoragePlan.Ready`, one new field

`cloud: Option[CloudBucket(bucket, endpoint, region, credentialGeneration)]`: present on the cloud
path, absent on Garage, so every Garage render is byte for byte what it was.

## In the scripted provider's memory (test sources)

| Record | Fields | Used by |
|---|---|---|
| `made` | `(kind, subject) → (account, location, outputs)` | answering `Recovered` to a second request for the same subject, and `Failed` to one naming another account or location |
| `issued` | `secretName, generation, at` | "no new credential on a second reconcile", "one more on a bump" |
| `ended` | `secretName, generation, at, why` (`conflict`, `rotated`) | "ended the one just made", "ended after the grace" |
| `failing` | `name → reason` | a scripted refusal |
| `reached` | a counter of anything outside the cluster | always zero |

## In the control plane

### `CloudConfig`

`provider`, `account`, `location`, `kmsKey: Option[String]`, from `ankka.controlplane.cloud` in
`reference.conf` (`${?ANKKA_CLOUD_*}`); `None` when the provider is `none`.

### `Installation` (wire, `controlplane-api`)

`platformVersion: String`, `cloud: Option[CloudInstallation]`, where `CloudInstallation` is
`provider`, `account`, `location`, `kmsKey: Option[String]`; `kmsKey` is omitted for a caller who
owns no organization.

### `CloudProviderNeeded` (`controlplane-api`)

A function from a choice's words and the installation's provider to zero or one problem string,
applied by each consumer feature to its setting.

## Phases, in one place

| Where | Values | Who sets it |
|---|---|---|
| `CloudResource.status.phase` | `Waiting`, `Ready`, `Recovered`, `Failed` | the provider |
| `CloudPlan` | `Waiting`, `Ready`, `Failed` | the operator, from the status and the clock |
| `AnkkaService.status.objectStorage.phase` | `Waiting`, `Provisioned`, `Recovered`, `Supplied`, `Failed` (unchanged) | the operator |
| `AnkkaService.status.lifecycle` | unchanged; `UpdateInProgress` while a cloud bucket waits | the operator |

## Rules, in one place

- A request's name is a function of its kind and subject; a second render finds the first.
- The operator writes `spec` and never `status`; the provider writes `status` and never `spec`.
- Neither process holds `get` or `list` on Secrets; a credential goes `create` once, `patch` on a
  bump, and is never read back.
- The operator acts on no status whose `observedGeneration` is behind `metadata.generation`.
- A request with no `observedGeneration` for the acknowledgement bound is reported as unanswered,
  naming the provider.
- Nothing in a cloud is deleted by the platform; a removed request is nothing to do.
- A changed account or location is `Failed`, never a move.
- A `bucket` with `purpose: backup` is a project's, grantable only to a `bucket-credential` whose
  identity is the project's database's, and renders no variable and no route for any service.
