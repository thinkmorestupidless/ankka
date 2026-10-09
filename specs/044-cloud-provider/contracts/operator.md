# Contract: the operator's side

What the operator reads, decides, renders and reports for a cloud request. Everything here is
in `operator/`, except the variable names (`core`'s `PlatformVariables`, compiled in) and the
resource (`crd`).

## Settings

`Settings.cloud: Option[CloudSettings]`, read by `CloudSettings.read` as `BrokerSettings.read`
reads, from system property first and environment second:

| Property | Variable | Rule |
|---|---|---|
| `ankka.operator.cloud-provider` | `ANKKA_CLOUD_PROVIDER` | absent or `none`: no cloud settings; else must be in `PlatformVariables.CloudProviders`, or startup fails naming the known names |
| `ankka.operator.cloud-account` | `ANKKA_CLOUD_ACCOUNT` | required with a provider |
| `ankka.operator.cloud-location` | `ANKKA_CLOUD_LOCATION` | required with a provider |
| `ankka.operator.cloud-kms-key` | `ANKKA_CLOUD_KMS_KEY` | optional |
| `ankka.operator.cloud-acknowledgement-bound` | `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND` | a duration; `2m` when absent |
| `ankka.operator.cloud-rotation-grace` | `ANKKA_CLOUD_ROTATION_GRACE` | a duration; `1h` when absent |

A provider named with the account or location missing is a refusal to start naming the missing
one, as a half-set broker is.

## Names

`Names.cloudRequest(subject, kind, purpose)` yields the names in
[cloud-resource.md](cloud-resource.md). `cloud-provider` joins `Names.ReservedProjectIds` and
`ProjectId.Reserved`; `ReservedProjectIdsSuite` holds the two lists together.

## Rendering a request

`CloudRequests` builds one `CloudResource` per need, with `spec.provider` from the settings,
`metadata.labels` the subject's identity labels, and the owner reference of the resource it serves:

| Renderer | Inputs | Written in this feature by |
|---|---|---|
| `CloudRequests.identity(subject, serviceAccount)` | the per-service ServiceAccount (feature 022) | `Rendering`, on the bucket path (038 reuses it) |
| `CloudRequests.secretAccess(subject, identity, own, read)` | | nothing yet (038) |
| `CloudRequests.secretSync(project, secretName, entries, entryGeneration)` | | nothing yet (038) |
| `CloudRequests.bucket(subject, purpose, location, versioning, softDeleteDays, corsOrigins, kmsKey)` | the descriptor's bucket settings (034), the installation's location and key | `Rendering`, for `purpose: service` |
| `CloudRequests.bucketCredential(subject, bucket, identity, secretName, generation)` | | `Rendering` |
| `CloudRequests.wrappingKey(subject, identity, key)` | | nothing yet (042) |

Every parameter key is a constant in `CloudRequests.Keys`; `CloudRequestsSuite` asserts a rendered
request carries exactly its kind's keys and that `location` is the setting's string verbatim.
`credentialGeneration` is read from `AnkkaServiceSpec.storageCredentialGeneration`, a field the
control plane's projector never sets (research R7), so a value raised on the resource is not
reverted by a re-projection; absent, it is `1`.

## Observing

`Executor.observeCloudResource(namespace, name): Option[CloudObservation]` — `generation`,
`createdAt`, `status: Option[CloudResourceStatus]` — through `ifTypeExists`: a cluster without the
CRD reads as absent, never as an error.

## Deciding

`CloudProvisioning.decide(request, observed, now, bound): CloudPlan`, the table in research R5.
It is pure; `CloudProvisioningSuite` is its rule table.

For the bucket path, `ObjectStorage.decide` takes `cloud: Option[CloudBucketPlans]`, where
`CloudBucketPlans(identity: CloudPlan, bucket: CloudPlan, credential: Option[CloudPlan])` (the
credential plan is absent until the identity is `Ready`, because the credential request's
`identity` parameter is that fulfilment's output), and answers:

| Plans | `ObjectStoragePlan` |
|---|---|
| any `Failed(d)` | `Failed(Vector(d))` |
| identity `Ready(i, _, _)`, bucket `Ready(o, r, _)`, credential `Some(Ready(_, _, g))` | `Ready(recovered = r, cloud = Some(CloudBucket(o.bucket, o.endpoint, o.region, g)))` |
| otherwise | `Waiting(the first detail among them)` |

`Rendering.render` answers `Rendered(actions, withheld: Option[String])`: `withheld` carries the
plan's detail whenever the actions hold no `ApplyDeployment` because a request the Deployment
needs is unanswered, and the reconciler reports `UpdateInProgress` with it. Every existing caller
reads `.actions`.

## Actions, in order, for a service with `provisionObjectStorage` on the cloud path

1. everything before the bucket, as today (namespace, identity, database …);
2. `EnsureCloudResource(identity request)`;
3. `EnsureCloudResource(bucket request)`;
4. `EnsureCloudResource(storage-credential request)`, once the identity plan is `Ready`, with
   `identity` = its output;
5. `ApplyDeployment` **only** when the plan is `Ready` or `Failed`; while `Waiting`, no Deployment
   is applied and `withheld` carries the plan's detail;
6. the bucket's route, as today, only when `Ready`.

A changed `status.credentialGeneration` reaches the pod template as the annotation
`ankka.thinkmorestupidless.com/storage-credential-generation`, which rolls the pods and nothing
else; the annotation is absent on the Garage path, so `RenderingUnchangedSuite` stays pinned.

## The developer's container

| Variable | From |
|---|---|
| `ANKKA_S3_ENDPOINT` | `outputs.endpoint` of the bucket request |
| `ANKKA_S3_REGION` | `outputs.region` |
| `ANKKA_S3_BUCKET` | `outputs.bucket` |
| `ANKKA_S3_ACCESS_KEY`, `ANKKA_S3_SECRET_KEY` | Secret `<service>-storage`, as today |

On `Failed`, none of the five.

## Status

`status.objectStorage` is folded as today: `phase` from the plan (`Waiting`, `Provisioned`,
`Recovered`, `Failed`), `bucket` from the outputs, `recovered` from the bucket request, `detail`
from the plan. The acknowledgement bound's detail is `no provider for <provider> has answered`.

## Re-queueing

A pass that leaves any request without `observedGeneration` calls `enqueueAfter(ref, bound)`. The
`CloudResource` informer enqueues the controller owner of a changed object on the matching queue.

## RBAC, added to `components/operator/operator.yaml`

| Resource | Verbs |
|---|---|
| `cloudresources` | `get`, `list`, `watch`, `create`, `patch` |
| `cloudresources/status` | `get` |

`OperatorClusterSuite`, under the operator's minted token: an apply of a `CloudResource` succeeds, a
`delete` is refused, a status `patch` is refused.
