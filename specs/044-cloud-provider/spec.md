# Feature Specification: The Cloud Provider — What Touches a Cloud Account Runs Outside the Operator

**Feature Branch**: `044-cloud-provider`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "The operator keeps its minimal power: it depends on `crd` only, carries
no cloud client and holds no IAM power. Everything that touches a cloud account — Google service
accounts, Workload Identity bindings, IAM policy on Secret Manager secrets, GCS buckets and HMAC keys,
Cloud KMS keys, syncing project secrets from Secret Manager — runs in a separate process, shipped from
a new repository `ankka-gcp` as one image, that implements a contract ankka core defines. Specs 038,
039, 041 and 042 each express what they need as a request the operator writes and a fulfilment the
provider writes back. Rejected: rendering Google Config Connector resources from the operator."

## Context

The operator provisions three kinds of backing service, and in every case it does so by writing a
resource for another operator to fulfil, never by holding the power itself. A database is a
CloudNativePG `Cluster`, `Database` and `DatabaseRole` (`CnpgRendering`); a topic is a Strimzi
`KafkaTopic` (`TopicProvisioning`); a certificate is cert-manager's. The operator's ClusterRole in
`kustomization/components/operator/operator.yaml` says what that buys: no `delete` on any CNPG kind,
no `get` on Secrets, nothing on gateways or cluster issuers. The one exception is the object store
(feature 034): `GarageStore` calls Garage's administration API over the JDK's HTTP client with a token
from `ANKKA_OBJECT_STORE_*`, because Garage has no operator of its own. Even there the shape is kept
small — six operations behind `ObjectStore`, a credential issued speculatively and offered as a
`create` whose 409 is the only thing the operator ever learns about a Secret (`StorageCredential`).

Four features now want the operator to touch a cloud account. Feature 038 wants a per-service cloud
identity granted on that service's secrets and a project's secrets kept in step with a Kubernetes
Secret. Feature 039 wants a bucket per service, a per-service account granted on it, and an HMAC key
written once. Feature 041 wants a platform-owned bucket per project and a credential the database's
backup plugin reads. Feature 042 wants a key-management key the keyring may wrap with. Reviewed
together they were found to need, on Google Cloud, the power to set IAM policy on the cloud project,
to create service accounts and their keys, and to create and grant buckets and keys. That is
owner-equivalent power on the cloud project. The operator was designed never to hold it: "a process
whose entire job is to keep working while other things are broken should depend on as little as
possible" (`.claude/rules/kubernetes.md`), and a Google client library in the operator would be the
first dependency it has that is not Kubernetes.

The review also found that the four features had each invented a setting for the same thing: 038 a
secret backend, 039 a Google project, a location and an optional KMS key, 041 a backup target, 042
"the backend of 038" to pick a root key. Three Google products were keyed off three settings.

This feature puts the cloud-touching work where the database-touching work already is: in another
process, behind a resource. The operator renders a **request**; a **provider** fulfils it and writes
the answer into the resource's status and, when the answer is a credential, into a Kubernetes Secret
the operator never reads. ankka defines the resource, the request kinds and what a fulfilment must
say. The provider for Google Cloud is `ankka-gcp`, its own repository and its own image, carrying the
Google client and the broad power; ankka itself gains no Google dependency and no Google code.

Five decisions shape the contract.

- **The contract is a Kubernetes resource, because that is what the operator already speaks.** One
  custom resource, `CloudResource`, in `crd` beside `AnkkaService` and `AnkkaProject`, namespaced,
  with a `spec` the operator writes and a `status` the provider writes. The operator's grant on it is
  `get`, `list`, `watch`, `create` and `patch`; the provider's is the same on the spec plus `update`
  and `patch` on the status. Neither reads a cloud credential: a credential goes from the provider
  into a Secret in the requesting namespace, and the operator learns only the Secret's name.
- **A closed set of request kinds, named by meaning, not by Google.** An identity for a Kubernetes
  ServiceAccount; access for an identity to a set of secrets; a Kubernetes Secret kept in step with a
  project's secret entries; a bucket; a credential on a bucket for an identity; a wrapping key an
  identity may use. Every parameter is the platform's word (`location`, `secretIds`, `purpose`),
  never a provider's, so a second provider implements the same six kinds against its own account
  model and the operator cannot tell which answered.
- **Credentials are written once, where the pod reads them, exactly as today.** The provider offers
  a credential as a `create` of a deterministically named Secret and learns from the 409 that one is
  there; it never reads a Secret back. A new credential is asked for by raising the request's
  credential generation, lands in the same Secret, and the old one is revoked only after the status
  says the new one is in place and a bound has passed.
- **The provider is owner-equivalent on the cloud project, and that is why it is not the operator.**
  On GKE it runs under Workload Identity with the roles its request kinds need; it holds no key file.
  Its ClusterRole in the cluster is as narrow as the operator's: `CloudResource` and its status, and
  `create`/`patch` on Secrets, cluster-wide and with no `get`, `list` or `delete`, exactly the
  operator's own grant on them. The two processes hold different powers and neither holds the
  other's.
- **The installation names its provider once.** `ANKKA_CLOUD_PROVIDER` (`none` or `gcp`),
  `ANKKA_CLOUD_ACCOUNT` (the cloud project or account the installation's resources live in),
  `ANKKA_CLOUD_LOCATION` (the default location for anything that has one) and, optionally,
  `ANKKA_CLOUD_KMS_KEY` (the one key the installation wraps with) are declared in `core`'s
  `PlatformVariables` and set on the `ankka-platform` ConfigMap as `otlpEndpoint` is. Features 038,
  039, 041 and 042 read these and define no cloud setting of their own; their backend choices
  (`secret-manager`, `gcs`) are refused unless the provider is one that can fulfil them.

**Rejected: rendering Google Config Connector resources from the operator.** It keeps the "render
another operator's resources" pattern with no new process of ours, which is attractive. It was
rejected because Config Connector is a GKE add-on installing on the order of three hundred custom
resource definitions, because its resources are Google's vocabulary and so the operator would render
Google-shaped objects and a second cloud would need a second rendering, and because no verified path
exists for it to return an HMAC key's secret into a Secret exactly once without the operator reading
it. A provider of ours is one image and one CRD, and is tested against a real account on its own
cadence.

## Clarifications

### Session 2026-10-08

- Q: May a project name its own cloud account? → A: No. One account per installation; a brand that
  needs its own account or jurisdiction is a second installation.

- Q: Where does the Google-touching provisioning that 038, 039, 041 and 042 need live? → A: Outside
  the operator, in a separate process implementing a contract ankka core defines. The operator keeps
  depending on `crd` only and gains no cloud client and no IAM power. The Google implementation is
  `ankka-gcp`, its own repository and image.
- Q: Could ankka-contrib hold it instead? → A: No. ankka-contrib holds modules built on ankka's
  published SDK; this is operator-side provisioning behind an unpublished seam, and it holds
  credentials a library in a service's classpath must never hold.
- Q: Config Connector instead of a provider of ours? → A: Rejected, for the three reasons in the
  Context.
- Q: One cloud setting or one per feature? → A: One: the provider, the account, the default location
  and the one wrapping key are named here, once; 038, 039, 041 and 042 reference them.

### Session 2026-10-09

- Q: SC-003 said a grep finds no cloud SDK coordinates, but the object storage suites use the AWS S3
  client as a test dependency, playing a service's program against the store. Keep it or reword? →
  A: Reword. What SC-003 protects is that no cloud client reaches the platform's code or images; the
  test client is how the suites prove a bucket works for a real S3 client, and stays.

- Q: FR-005 named the cloud identity field `principal`, but the glossary reserves `principal` for who
  a call came from. Which word is the contract's? → A: `identity`. The `identity` kind's output and
  the `secret-access`, `bucket-credential` and `wrapping-key` parameter are named `identity`;
  `principal` stays the caller's word.
- Q: Where do the acknowledgement bound and the rotation grace live, and how does a provider learn
  the grace? → A: Two more platform variables, `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND` (`2m` as
  shipped) and `ANKKA_CLOUD_ROTATION_GRACE` (`1h` as shipped), on the `ankka-platform` ConfigMap
  beside the other four. The operator reads the bound, a provider reads the grace, and the fake
  honours a short one so the k3s rotation scenario runs in seconds.
- Q: How is the provider's grant on Secrets scoped? → A: As the operator's is: a ClusterRole with
  `create` and `patch` on Secrets, no `get`, `list` or `delete`, bound cluster-wide. Neither
  process can read a Secret, and no per-namespace binding has to follow the namespaces the
  operator makes.
- Q: Spec 039's glossary terms `provider` and `storage account` name what this spec calls `cloud
  provider` and `cloud identity`. Fold them now? → A: No. 039's terms and feature steps stay as
  written; 039's own amendment (SC-005) rewords them to this spec's vocabulary and refuses the old
  words. This feature changes no glossary entry and no feature file of 039's.
- Q: Which of a provider's cluster objects does ankka ship? → A: All but the Deployment: the
  `CloudResource` CRD and a `cloud-provider` kustomization component holding the
  `ankka-cloud-provider` namespace, the provider's ServiceAccount and its ClusterRole with its
  binding. A provider, `ankka-gcp` or the fake, adds only its Deployment and, on GKE, the
  annotation binding the ServiceAccount to its cloud identity.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service asks for a bucket and the provider makes it (Priority: P1)

A developer applies a descriptor with `provisionObjectStorage: true` to an installation whose object
store is Google Cloud Storage (feature 039). The operator renders an `identity` request, a `bucket`
request and a `bucket-credential` request; the provider makes the service's cloud identity, creates
the bucket in the installation's account and location, grants the identity on that bucket alone,
issues a key, writes it once into `<service>-storage`, and reports all three requests ready. The operator sees the Secret's name in
the status, renders the Deployment with it, and reports the bucket `Provisioned`. The developer's
code is the one that runs on Garage.

**Why this priority**: This is the first path the contract has to carry — 039 cannot ship without it —
and it exercises every part of the shape: a request, a fulfilment, a credential written once, a status
the operator folds.

**Independent Test**: With the fake provider installed in a k3s suite (`features/cloud-provider/
bucket.feature`), apply a descriptor and observe: three `CloudResource` objects (`identity`, `bucket`, `bucket-credential`) in the service's
namespace, owned by the `AnkkaService`; a Secret `<service>-storage` the fake wrote; a Deployment
reading it; status `Provisioned`. The same scenario runs in `ankka-gcp`'s nightly suite against a real
account.

**Acceptance Scenarios**:

- added `features/cloud-provider/bucket.feature`: a descriptor that asks for a bucket becomes a bucket request and a bucket credential request
- added `features/cloud-provider/bucket.feature`: a service is given its bucket when the cloud provider fulfils the requests
- added `features/cloud-provider/bucket.feature`: a bucket the cloud provider cannot make fails the service with the reason the cloud provider gave
- added `features/cloud-provider/bucket.feature`: a service deleted and applied again under the same name is given the bucket it had

---

### User Story 2 - A credential is written once and never read by anyone but the pod (Priority: P1)

The provider mints a credential — an HMAC key, a backup store's key — and puts it in a Secret the pod
reads. Neither the operator nor the provider reads it afterwards; neither can, by their grants. A new
credential is asked for by a generation bump and the old one is revoked after the new one is in use.

**Why this priority**: The whole reason the provider exists is to hold power the operator must not;
a credential path that let either process read a secret back would undo it.

**Independent Test**: `features/cloud-provider/credential.feature`: under the provider's real
ClusterRole, a `bucket-credential` request yields a Secret; a second reconcile of the same request
issues nothing new (the fake records every issue); a generation bump issues one more, patches the
Secret, and the fake reports the first revoked only after the bound.

**Acceptance Scenarios**:

- added `features/cloud-provider/credential.feature`: a bucket credential request is fulfilled by offering the secret once
- added `features/cloud-provider/credential.feature`: a bucket credential request whose secret is already there ends the credential just made
- added `features/cloud-provider/credential.feature`: the cloud provider can write a secret and cannot read one back
- added `features/cloud-provider/credential.feature`: the operator cannot read a storage credential the cloud provider made
- added `features/cloud-provider/credential.feature`: raising the credential generation replaces the credential and ends the old one after the rotation grace
- added `features/cloud-provider/credential.feature`: a cloud request that goes with its service deletes nothing

---

### User Story 3 - No provider is installed, and the platform says so (Priority: P2)

An installation names `gcp` but the provider is not running, or names `none` and a feature asks for
something only a provider can give. Nothing hangs silently: the service's status says what is missing.

**Why this priority**: A request nobody answers is the failure mode of every split-process design;
the operator cannot know a provider exists except by being answered.

**Independent Test**: `features/cloud-provider/absent.feature`: with no provider deployed, apply a
descriptor that needs one and read the status within the bound.

**Acceptance Scenarios**:

- added `features/cloud-provider/absent.feature`: a cloud request nobody acknowledges is reported after the acknowledgement bound
- added `features/cloud-provider/absent.feature`: the status recovers when a cloud provider answers
- added `features/cloud-provider/absent.feature`: a setting that needs a cloud provider is refused when the installation has none
- added `features/cloud-provider/absent.feature`: an installation with no cloud provider serves everything itself

---

### User Story 4 - Each feature's need is one request kind (Priority: P2)

Features 038, 041 and 042 express what they need in the same six kinds, so the provider is one
program and the operator renders requests the way it renders CNPG objects.

**Why this priority**: The contract is only worth defining if every consumer fits it without a kind
of its own; this story is the proof.

**Independent Test**: `features/cloud-provider/kinds.feature`, one scenario per consumer, against the
fake: the rendered request has the fields its feature needs and nothing provider-shaped.

**Acceptance Scenarios**:

- added `features/cloud-provider/kinds.feature`: a service whose project secrets are kept in the cloud account asks for an identity and access to its secrets
- added `features/cloud-provider/kinds.feature`: a project whose project secrets are kept in the cloud account asks for them to be kept in step
- added `features/cloud-provider/kinds.feature`: a project whose backups are kept in the cloud account asks for a backup bucket and a credential for its database
- added `features/cloud-provider/kinds.feature`: a keyring on an installation that names a wrapping key asks to wrap with it
- added `features/cloud-provider/kinds.feature`: a keyring on an installation that names no wrapping key keeps its own secret
- added `features/cloud-provider/kinds.feature`: no cloud request is in a cloud's own words

---

### User Story 5 - The platform's own suites need no cloud (Priority: P3)

ankka's k3s suites run a fake provider that answers every kind with dummy values and dummy Secrets.
`ankka-gcp` has its own nightly workflow against a real account. A second provider implements the
same six kinds.

**Why this priority**: Keeping a cloud out of ankka's CI is what makes the split pay; it is also what
lets the contract be proven without Google.

**Independent Test**: The fake provider is a test controller in the operator module's test sources,
installed by the k3s suites that need it; `ankka-gcp`'s README names its nightly workflow and the
account it needs.

**Acceptance Scenarios**:

- added `features/cloud-provider/providers.feature`: the scripted cloud provider fulfils every cloud request without a cloud
- added `features/cloud-provider/providers.feature`: a cloud provider for a real cloud is tested with the same features against a real cloud account
- added `features/cloud-provider/providers.feature`: a cloud provider for another cloud needs only its name known to the platform

---

### Edge Cases

- **Two requests for one thing.** A request is named deterministically from its kind and its
  subject (`<service>-bucket`, `<service>-storage-credential`, `<project>.backup-bucket`: a project's
  carries a dot, which no service name can, so the two can never collide), so a
  second render finds the first; the operator writes by server-side apply, as everything else.
- **The provider is slower than the operator's resync.** The operator never acts on a status whose
  `observedGeneration` is behind the spec's; it waits, and after the acknowledgement bound reports
  it is waiting on the provider.
- **A provider restarts mid-fulfilment.** Every step is idempotent against the account (find or
  create; grant what is granted), and the credential's `create` tells it whether a key was already
  written, as `StorageCredential` does today. A key issued and never written is revoked.
- **The installation changes its account or location.** Existing resources stay where they are; the
  status carries the account and location a resource was made in, and a request whose spec now
  differs from its status reports `Failed` with "made in another account", never a move.
- **A request the provider does not know.** A kind the provider's version does not implement is
  reported `Failed` naming the kind and the provider's version, so an older provider under a newer
  platform fails loudly.
- **Secret sync and a rollout.** A `secret-sync` fulfilment that has not reached the latest entry
  generation holds the project's rollouts, as 038 asks; the operator reads that from the status.
- **Removal.** A `CloudResource` is owned by the `AnkkaService` or `AnkkaProject` that caused it and
  goes with it. The provider treats removal as nothing to do. Nothing is deleted in the cloud by
  the platform, ever; cleaning an account is an administrator's act outside ankka.

## Requirements *(mandatory)*

### Functional Requirements

**The resource**

- **FR-001**: `crd` MUST define a namespaced `CloudResource` with a `spec` of `provider`, `kind`,
  `subject` (the project and, when there is one, the service), `credentialGeneration` and a
  `parameters` map of the kind's fields, and a `status` of `observedGeneration`, `phase` (`Waiting`,
  `Ready`, `Recovered`, `Failed`), `detail`, `account`, `location`, `credentialGeneration`,
  `credentialReportedAt`, `recovered`, `providerVersion`, and the kind's outputs (a bucket's name, a cloud identity, a key's
  name, a Secret's name). The
  CRD's case class and its YAML MUST be held to each other by `CrdSchemaSuite`.
- **FR-002**: The operator MUST write a `CloudResource` by server-side apply, owned by the
  `AnkkaService` or `AnkkaProject` it serves, named deterministically from the kind and subject, and
  MUST never write its status. The operator's ClusterRole MUST gain `get`, `list`, `watch`, `create`
  and `patch` on `cloudresources` and nothing else for this feature.
- **FR-003**: A provider MUST write only a `CloudResource`'s status, by `update` or `patch` on the
  status subresource, and MUST set `observedGeneration` to the spec generation it fulfilled before
  reporting any phase for it.
- **FR-004**: The operator MUST NOT act on a status whose `observedGeneration` is behind the spec's,
  and MUST report the feature's status on the service or project (`status.objectStorage` for a
  bucket, and the like for the other features) as `Waiting` with "no provider for `<provider>` has
  answered" when a request has had no `observedGeneration` for the acknowledgement bound
  (`ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND`, two minutes as shipped). While a request a service's
  Deployment needs is unanswered, the service's lifecycle is `UpdateInProgress` with that detail.

**The kinds**

- **FR-005**: The contract MUST define exactly these kinds, and a provider MUST implement all six or
  report `Failed` naming the kind: `identity` (a cloud identity bound to a Kubernetes
  ServiceAccount: `serviceAccount`; output `identity`), `secret-access` (a cloud identity's access
  to secret ids by *prefix*: `identity`, `own: [prefixes]`, `read: [prefixes]` — a create cannot be
  limited by a name, so `own` is create on the account and everything else under the prefix; 038's
  clarify session of 2026-10-08), `secret-sync` (a Kubernetes Secret kept in
  step with a project's entries: `secretName`, `entries: [name → id]`, `entryGeneration`; output the
  entry generation synced), `bucket` (`purpose: service | backup`, `location`, `versioning`, `softDeleteDays`,
  `corsOrigins`, `kmsKey`; outputs `bucket`, `endpoint`, `region`), `bucket-credential` (`bucket`, `identity`,
  `secretName`; output `secretName`), and `wrapping-key` (`identity`, `key`; output `key`). The
  field is `identity`, never `principal`: a principal is who a call came from.
- **FR-006**: A kind's parameters MUST be the platform's vocabulary and MUST NOT name a provider's
  product, role, resource type or region name; `location` is a string the installation chose and
  the provider interprets.
- **FR-007**: A `bucket` with `purpose: backup` MUST be named so no service's bucket can collide with
  it, MUST have no route and no `ANKKA_S3_*` rendered for any service, and MUST be grantable only to
  a `bucket-credential` whose identity is a project's database's cloud identity (041): a provider MUST
  report any other `bucket-credential` naming it `Failed` with "a backup bucket is granted only to
  its project's database".

**Credentials**

- **FR-008**: A credential a provider mints MUST be offered as a `create` of the named Secret in the
  requesting namespace; on `Created` the provider reports ready; on a conflict it MUST revoke what it
  just issued and report ready naming the existing Secret. A provider's grant on Secrets MUST be the
  operator's: a ClusterRole with `create` and `patch`, bound cluster-wide, and no `get`, `list` or
  `delete`.
- **FR-009**: Raising a request's `credentialGeneration` MUST cause the provider to issue a new
  credential, write it into the same Secret by `patch`, report the generation in place, and revoke
  the previous credential no sooner than the installation's rotation grace
  (`ANKKA_CLOUD_ROTATION_GRACE`, one hour as shipped) after that report. The operator MUST roll the service when the status's `credentialGeneration`
  changes.
- **FR-010**: A `secret-sync` request MUST be fulfilled by `patch` of the named Secret within one
  minute of a change to any entry it names, and its status MUST say the entry generation synced, so
  the operator can hold a rollout until it matches (038 FR-016a). An entry the named Secret holds and
  the cloud account has no version of MUST be copied into the account as that entry's first version
  before anything is synced down (038 FR-020: the move of a project secret).

**Settings**

- **FR-011**: `core`'s `PlatformVariables` MUST declare `ANKKA_CLOUD_PROVIDER` (`none` or a known
  provider name; `none` as shipped), `ANKKA_CLOUD_ACCOUNT`, `ANKKA_CLOUD_LOCATION`,
  `ANKKA_CLOUD_KMS_KEY` (optional), `ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND` (a duration; `2m` as
  shipped) and `ANKKA_CLOUD_ROTATION_GRACE` (a duration; `1h` as shipped), set once on the
  `ankka-platform` ConfigMap and given to the operator, the control plane and the provider. The
  operator reads the acknowledgement bound and a provider reads the rotation grace; neither is a
  descriptor's or a request's to set. Features 038, 039, 041 and 042 MUST read these and MUST
  define no cloud account, location or key setting of their own.
- **FR-012**: The control plane MUST refuse a backend or target that needs a provider (`secret-manager`,
  `gcs`, a `gcs` backup target, a wrapping key) when `ANKKA_CLOUD_PROVIDER` is `none`, naming the
  provider needed, and the operator MUST write no request for an installation whose provider is
  `none`.
- **FR-013**: The operator MUST copy `ANKKA_CLOUD_PROVIDER` into every request's `spec.provider`, and
  a provider MUST fulfil only requests naming it.

**The provider's power and the platform's innocence**

- **FR-014**: ankka MUST carry no cloud client library and no cloud-specific code in any module,
  image or kustomization component, with one exception: the Secret Manager store of 038, in
  `runtime`, which speaks Secret Manager's REST API over the JDK's HTTP client with no library,
  because the sidecar image is the platform's and a process or module could reach nothing shipped
  outside it. The only other cloud-specific artefact in the repository MUST be the list of known
  provider names.
- **FR-015**: `ankka-gcp` MUST run under Workload Identity on GKE, MUST be given no key file, and its
  documentation MUST state plainly that its cloud roles are owner-equivalent on the account and that
  this is why the operator does not hold them.
- **FR-016**: The provider MUST delete nothing in the cloud and nothing in the cluster on the
  removal of a request, and MUST report `recovered: true` when it finds a resource already made for a
  request's subject.
- **FR-017**: A provider MUST report a request whose spec now names a different account or location
  from the one the resource was made in as `Failed` with "made in another account or location", and
  MUST NOT move or recreate it.
- **FR-017a**: ankka's kustomization MUST ship the `CloudResource` CRD and a `cloud-provider`
  component holding the `ankka-cloud-provider` namespace, the provider's ServiceAccount and its
  ClusterRole with its binding, so the grant is the contract's and one identity serves every
  provider. A provider MUST add only its Deployment in that namespace and, on GKE, the annotation
  binding that ServiceAccount to its cloud identity; the fake provider of FR-020 MUST run under
  the same ServiceAccount.

**Status and visibility**

- **FR-018**: The operator MUST fold each request's phase and detail into the service's or project's
  status under the feature that caused it (038's secret store, 039's object storage, 041's backups,
  042's keyring), so `services get` and `projects get` show the provider's answer without naming
  `CloudResource`.
- **FR-019**: The control plane MUST show the installation's provider, account and location on the
  installation's settings, and MUST NOT show the KMS key's name to a member who is not an owner of at
  least one organization.

**Testing**

- **FR-020**: ankka's operator module MUST carry a fake provider in its test sources that fulfils all
  six kinds with dummy outputs and dummy Secrets, honouring FR-008 and FR-009, and every k3s suite
  that needs a provider MUST install it; no suite of ankka MUST reach a cloud.
- **FR-021**: The feature files under `features/cloud-provider/` MUST be runnable against the fake
  and against `ankka-gcp`, and `ankka-gcp` MUST run them nightly against a real account.

### Key Entities

- **CloudResource**: one request to the installation's provider: a kind, a subject, parameters,
  a credential generation; and the provider's answer: a phase, outputs, the account and location it
  was made in. Owned by the service or project it serves.
- **Provider**: a process outside ankka that fulfils `CloudResource`s naming it, under its own cloud
  identity and the narrowest cluster grant that lets it write statuses and credentials.
- **Request kind**: one of six named shapes, each a need of a platform feature expressed in the
  platform's words, with its parameters and its outputs.
- **Installation cloud settings**: the provider, the account, the default location and the one
  wrapping key, declared once and read by every feature that touches a cloud.
- **Credential generation**: a counter on a request; raising it asks for a new credential in the
  same Secret and schedules the old one's revocation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With the fake provider, a descriptor asking for a bucket on `gcs` reaches `Provisioned`
  within 60 seconds of apply in the k3s suite, with a Secret neither process read.
- **SC-002**: The operator's and the provider's ClusterRoles, applied to their real identities, let
  every scenario pass and hold no `get` or `list` on Secrets and no `delete` on `cloudresources`; a
  suite proves it under the real identities, as `OperatorClusterSuite` does today.
- **SC-003**: No module's compile-scope dependencies and no image carry a cloud client library, and
  no main source imports one. A cloud SDK may appear only as a test dependency that plays a service's
  own program, as the S3 client the object storage suites sign requests with does.
- **SC-004**: With no provider running, a request's absence is reported in the service's status
  within 2 minutes 30 seconds of apply, and the status recovers within 30 seconds of a provider
  starting.
- **SC-005**: Features 038, 039, 041 and 042 each express their cloud needs in the six kinds with no
  kind added; their specs reference `ANKKA_CLOUD_*` and define no cloud setting.
- **SC-006**: A credential rotation by generation bump completes — new credential written, service
  rolled, old credential revoked — within the rotation grace plus 5 minutes, and the service serves
  throughout.
- **SC-007**: `ankka-gcp`'s nightly suite runs the same feature files as the fake and is green
  against a real account before 039 or 038's `secret-manager` backend is called done.

## Assumptions

- The operator's RBAC gains `get`, `list`, `watch`, `create` and `patch` on `cloudresources`, and
  `get` on `cloudresources/status`, and nothing else. It keeps no `get` or `list` on Secrets.
- A provider runs in the `ankka-cloud-provider` namespace under the ServiceAccount ankka's
  `cloud-provider` component ships (FR-017a); on GKE `ankka-gcp`'s install binds that
  ServiceAccount to a cloud identity holding the roles the six kinds need.
  Which roles those are is `ankka-gcp`'s README's to say; this spec says only that they are
  owner-equivalent.
- One account and one default location per installation. A project may name its own location
  (039), carried in the `bucket` request; a project may not name its own account.
  A brand licensed in another jurisdiction, with its own billing, is a second installation, not a
  project naming its own account: an account boundary is an installation boundary.
- The acknowledgement bound (2 minutes) and the rotation grace (1 hour) are the two duration
  platform variables of FR-011, with those defaults; neither is a descriptor's to set. The fake
  provider reads the same variable, so a k3s suite sets a grace of seconds.
- `GarageStore` stays as it is: Garage has no operator and no account, and a request to a provider
  for it would be a process for its own sake. The `ObjectStore` seam remains the operator's for
  Garage; on `gcs` the operator renders requests instead of calling a store.
- The fake provider is a test controller in `operator`'s test sources, started by the k3s suites
  that need it; it is not shipped in any image.
- `ankka-gcp`'s build, release and versioning against a published ankka are its own README's; it
  pins the `crd` artifact's version it fulfils and reports its version in every status.

## Dependencies

- **034 (object storage)**: the Garage path, `StorageCredential`'s write-once rule and the status
  shape this feature generalises.
- **038 (secret store backends)**: consumer of `identity`, `secret-access` and `secret-sync`; its
  backend setting is constrained by FR-012.
- **039 (object storage on GCS)**: consumer of `bucket` and `bucket-credential`; its FR-001 and
  FR-002 are superseded by FR-011 and FR-015 here. Its glossary terms `provider`, `storage account`
  and `workload identity`, and the feature steps that use them, are its own amendment's to reword to
  `cloud provider` and `cloud identity`; this feature leaves them as they are.
- **041 (backup and recovery)**: consumer of `bucket` with `purpose: backup` and `bucket-credential`
  for the database's identity.
- **042 (personal data erasure)**: consumer of `wrapping-key`.
- **014 (zero trust)** and **022 (service identity)**: the per-service ServiceAccount an `identity`
  request binds.
- **`ankka-gcp`** (new repository): the Google Cloud provider; one image; nightly suite against a real
  account.

## Open Questions

- Whether a provider should also fulfil a `managed-database` kind so a project could ask for a
  cloud-managed Postgres instead of CNPG; nothing in 038–043 needs it and 041 covers durability
  another way, so it is noted and not specified.
