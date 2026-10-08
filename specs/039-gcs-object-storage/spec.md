# Feature Specification: Object Storage on Google Cloud Storage

**Feature Branch**: `039-gcs-object-storage`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Allow Google Cloud Storage for object storage. Feature 034 shipped a
bucket per service on Garage; GCS becomes a second backend behind the same interface. The backend
is chosen per installation, and a service's code and descriptor do not change between Garage and
GCS. On GKE nothing the platform holds is a long-lived key where an identity will do. The first
user is KYC documents for a real-money casino, so retention, versioning, encryption and data
residency are part of the feature, and erasing one player's objects must stay possible. An
existing service's objects can be moved from Garage to GCS. Local development and the testkit
need no Google Cloud."

## Context

Feature 034 gives a service that sets `provisionObjectStorage: true` one bucket and one credential
scoped to it, in Garage, an installation component. The operator administers the store through one
trait, `ObjectStore` (`operator/.../ObjectStore.scala`): six operations — find a bucket, create a
bucket, list the access keys of a name, create a key, delete a key, allow a key on a bucket — and
`GarageStore` is its only implementation. `StorageCredential.ensure` issues the credential against
the trait: the key's secret is returned once by `createKey`, written into the Secret
`<service>-storage` that the operator creates and patches and never reads, and held nowhere else.
The workload receives `ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION`, `ANKKA_S3_BUCKET`,
`ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`, and uses any S3 client; the SDKs add no storage
API. The bucket is named `<project>.<service>` (`crd/.../Buckets.scala`), which is unambiguous
because neither part can hold a dot; the operator derives it in three places and the control plane
in three more, so both ends agree on it without either asking. The platform deletes no bucket and
no object. A bucket a descriptor marks `exposeObjectStorage` is reachable from a browser through
the installation's Gateway at `storage.<base>`, by a route the operator renders per bucket; a
bucket without the flag has no route, so nothing outside the cluster reaches it. Settings are
passed to the operator as `ANKKA_OBJECT_STORE_*` variables by the component's patch, and an
installation without them has no object store. Feature 034's research (R22) left a cloud provider's
buckets to a later feature because the credential model, the address and "private until asked"
each differ per provider. This is that feature, for Google Cloud.

**Who touches Google, and who does not.** Making a GCS bucket for a service means making a Google
service account, binding it to the service's Kubernetes ServiceAccount, granting it on the bucket,
and minting an HMAC key for it. The grant needs `setIamPolicy` on the bucket, the key needs
`storage.hmacKeys.create` on the account, and the identity that has those can mint a key for *any*
account it can see at any time. That is owner-shaped power over every service's objects, and the
operator is designed to hold as little as it can: it depends on `crd` alone, reaches Garage with
the JDK's HTTP client, and cannot `get` a Secret. So the operator does **not** talk to Google.
Feature **044-cloud-provider** defines a provider contract in ankka: the operator renders a
request naming the service, the project, the location and the settings below, and a provider the
installation deploys beside it — `ankka-gcp`, its own repository and image — makes the bucket, the
account, the binding and the key, writes the key's secret once into `<service>-storage`, and
reports the bucket's name and state in the request's status. The operator reads the status and
the Secret's name, never the secret, and holds no Google client, no Google credential and no IAM
permission. The provider reaches Google through its own Workload Identity, and the roles it holds
are listed on the install page as what they are. `GarageStore` stays in the operator, since Garage
is in the cluster and has no such power to hold.

**The service-facing interface is S3 with a static key, and that decides the credential.** The
choice is between GCS's S3-compatible XML API with an HMAC key, and Workload Identity with
Google's own client libraries. Workload Identity holds no secret, but a service's code would have
to use a GCS client on GKE and an S3 client everywhere else, which breaks the one promise 034
makes: any S3 client works and the code does not know where it runs. It also does not remove the
need to sign: a presigned URL for a browser upload — the KYC document's path — is signed either
with an HMAC key (SigV4, which GCS's S3-compatible API accepts for presigned URLs) or by a call to
Google's IAM signing API per URL, which again is Google-only code. So this feature issues an
**HMAC key** for a per-service Google service account. The HMAC key is a long-lived secret in
exactly the place 034's Garage key is, written once and never read back; it reaches one bucket
because the account it belongs to is granted on that bucket and nothing else. As a second path,
and not instead of the first, the service's own ServiceAccount (the operator already renders one
per service) is bound by Workload Identity to the same Google account, so a service that chooses a
Google client reaches its bucket with no key. A service that never reads `ANKKA_S3_ACCESS_KEY`
can be given none (User Story 5).

**What GCS changes about 034's promises, said once.**

- **The name.** GCS bucket names are global across every Google customer, and a name with a dot
  is admitted only when it is a domain the installation has verified. `<project>.<service>`
  therefore cannot be used. The GCS name is the installation's prefix, the project, the service and
  a short digest of `<project>.<service>`: readable in a status, and unambiguous because the digest
  covers the dotted pair 034 already proved unique. A name some other Google customer already holds
  is a failure, reported, never adopted. Because the name is now decided where the bucket is made,
  and the control plane can no longer derive it, **the name travels in status**: the provider
  reports it on the request, the operator copies it onto the service's status and into
  `ANKKA_S3_BUCKET`, and the control plane shows what the status says. `Buckets.name` in `crd`
  stays the Garage name and nothing else.
- **"Private until asked".** On Garage a bucket without `exposeObjectStorage` has no route, so it
  is unreachable from the internet. A GCS bucket is reachable at `storage.googleapis.com` by
  anyone who holds a valid signature, flag or not. On GCS the guarantee becomes: every bucket has
  public access prevention and uniform bucket-level access, so nothing in it is ever readable
  without a signature or an identity granted on that bucket; `exposeObjectStorage` decides whether
  the service is given `ANKKA_S3_PUBLIC_ENDPOINT` and a CORS rule, not whether the bucket is
  reachable. The docs say so in those words.
- **Bucket settings through S3.** 034's FR-017 lets a service set its own CORS and lifecycle rules
  with its S3 client. GCS's S3-compatible API does not accept S3's CORS or lifecycle calls, so code
  that sets them works on Garage and fails on GCS. This feature moves CORS onto the platform for
  both backends: the descriptor's storage section names the **origins** the bucket admits, and the
  provider (GCS) or the operator (Garage) sets that rule. The origins are the descriptor's to name
  because the page that uploads is not, in general, the service's own: the first user's KYC page
  is served by the player frontend, a separate hosting, and a rule admitting only the KYC service's
  hostnames would admit nobody who uploads. An empty list is no rule. Any other bucket setting made
  through S3 is the service's own and is not portable.
- **Encryption in transit.** `storage.googleapis.com` is HTTPS, so 034's limitation (FR-018) does
  not apply to a GCS installation; the docs page says which store it describes.

**Regulated retention, and how it meets erasure.** The first user keeps identity documents for a
real-money casino. Anti-money-laundering rules require them to be kept for a period that starts
when the customer relationship *ends*, and data protection law requires a player's other personal
data to be erasable. Two facts settle the design:

- A bucket retention policy on GCS counts from each object's *creation*, not from an event the
  platform cannot see such as an account closing. A policy long enough to matter would refuse to
  delete a document a year old that an erasure must remove today, and once locked it can never be
  shortened or removed. So the platform sets **no retention policy on any bucket**, locked or
  unlocked, and offers no setting that does. Holding a document for the anti-money-laundering
  period is the service's rule: the service is the one that knows when an account closed.
- What the platform does guarantee is that nothing is lost by accident or malice before the
  service decides: **object versioning** keeps every overwritten and deleted object as a
  noncurrent version, and GCS **soft delete** keeps a deleted object recoverable for a window the
  installation sets. Deleting every version of an object is therefore always possible, and the
  deletion is final when the soft-delete window has passed; the status reports the window, so "how
  long until a deletion is final" is a number a member can read.

**Garage has no object versioning**, and this feature does not add it. A Garage bucket holds one
version of each object; a GCS bucket holds every version. So "delete every version" is a GCS
operation with no Garage counterpart, and what a service's code does on both backends is put, get,
list, delete the current object and sign a URL — the five things 034 promised. Erasing a subject's
objects on a deadline, across both backends, is **not** this feature's and not a service's code:
feature 042 (personal data erasure) defines it. This feature guarantees only the primitives 042
needs: on GCS, a deletion asked of every version leaves none listed and becomes final after the
soft-delete window; on Garage, a deletion of the one version is final at once.

Encryption is Google's default for every bucket; an installation may name a Cloud KMS key and every
bucket it makes is encrypted with it. Data residency is the bucket's location, which GCS fixes at
creation and never changes. The installation sets a default location; a project may name its own,
for a brand licensed in a jurisdiction whose regulator wants its documents kept there.

**Moving from Garage.** An installation that has Garage today and gains GCS keeps every existing
bucket on Garage until it is moved; new services get GCS. A move copies every object into the
service's new GCS bucket in the background while the service keeps writing, then pauses the
service's writes for a short window while it copies what changed, verifies the count and every
object's checksum, and switches the service's variables at its next rollout. The pause is enforced
by the only means that works for a service holding a static key: the service's credential is
replaced, for the window, by one that may read and not write. An S3 refusal carries no reason, so
the status, not the refusal, says the storage is moving. The Garage bucket is kept, untouched,
because the platform deletes nothing.

**A credential can be issued again.** 034 issues a key once and never replaces it. A regulated
store with a static key needs at least a way to retire one on demand — a leaked key, a rotation
policy — so this feature adds re-issue on both backends: a member asks, a new key is issued and
written, the old one is retired, and the service picks the new one up at its next rollout.

**The platform's own buckets.** Feature 041 (Postgres backup and recovery) keeps each project's
database backups in a platform-owned bucket in the installation's object store. Those buckets use
the same backend as the installation's service buckets — GCS on a GCS installation, made through
the same provider — but no service is ever granted on one, and no service credential reaches one.

What this feature is not: an SDK storage client; Amazon S3 or Azure as a backend (each is another
provider under 044); a retention policy, in any mode; event-based holds or per-object retention,
which GCS offers only through its own API; object versioning on Garage; erasure of a subject's
objects (042); or object storage on a developer's machine, which stays Garage.

## Clarifications

### Session 2026-10-08

- Q: Is a bucket's location set once per installation, or may a project choose its own? → A: The
  installation sets a default location; a project may name its own. The location is fixed when the
  bucket is created.
- Q: Should an installation be able to lock a bucket's retention policy? → A: Never. Object
  versioning and soft delete only, so a deletion always remains possible. (Revised in the review
  session below: no retention policy at all, locked or not.)
- Q: May a move from Garage pause the service's writes? → A: Yes, briefly. The bulk copy runs in the
  background with writes continuing; then writes are refused for a short window while the final
  delta is copied and verified; the switch happens at the next rollout.

### Session 2026-10-08 (review)

- Q: Who provisions in Google Cloud — the operator? → A: No. Granting a service account on a bucket
  and minting its HMAC key take `setIamPolicy` and `storage.hmacKeys.create`, which can mint a key
  for any account; the operator is designed to hold minimal power and depends on `crd` alone. The
  Google-touching provisioning moves to the provider of feature 044-cloud-provider (contract in
  ankka, implementation in the `ankka-gcp` repository and image). The operator renders a request
  and reads its status and the credential Secret's name; it holds no Google client, credential or
  IAM permission. Backend selection and the default location are 044's installation settings.
- Q: Is an unlocked retention floor compatible with erasure? → A: No. A GCS retention policy counts
  from each object's creation, so any floor long enough to matter blocks the deletion of an older
  document an erasure must remove. No bucket gets a retention policy; versioning and soft delete are
  the safety net, and retention is the service's rule. A deletion is final when the soft-delete
  window has passed.
- Q: Does "delete every version" hold on Garage? → A: No; Garage has no object versioning, and this
  feature adds none. The service-side promise is narrowed to put, get, list, delete and presigned
  URLs. Erasing a subject's objects across backends is defined by feature 042, not here and not in
  service code; this feature guarantees only that on GCS a deletion of every version leaves none,
  and on Garage the one version is gone at once.
- Q: Who knows a GCS bucket's name? → A: The provider decides it and reports it in the request's
  status; the operator copies it onto the service's status and into `ANKKA_S3_BUCKET`; the control
  plane shows the status. `Buckets.name` in `crd` remains the Garage name only.
- Q: Which origins does an exposed bucket's CORS rule admit? → A: The ones the descriptor's storage
  section names; an empty list is no rule. The uploading page is not in general the service's own —
  the KYC page is served by the player frontend — so "the service's own hostnames" would admit
  nobody.
- Q: How is a move's write pause enforced, and what does a refused write say? → A: By credential:
  for the window the service's key is replaced by a read-only one (the store contract gains a
  read-only credential operation). An S3 refusal carries no reason, so the service's status says
  "moving"; the refusal itself says nothing.
- Q: Can a credential be rotated? → A: Yes, on request. A member triggers re-issue; a new key is
  issued and written, the old one is deactivated and then deleted (two steps on GCS), and the
  service reads the new key at its next rollout.
- Q: How is a copied object verified? → A: Both sides are hashed with the same algorithm by the
  mover; ETags are never compared, since a multipart upload's ETag differs per store.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service on a GCS installation gets a bucket its existing code can use (Priority: P1)

An installation on GKE is configured with GCS as its object store. A developer deploys a service
whose descriptor and code are unchanged from the one they ran against Garage, with
`provisionObjectStorage: true`. The pod starts with the five variables pointing at GCS, the code
puts and gets an object with the same S3 client and settings it used against Garage, and the
status reports the storage as `Provisioned` with the bucket's GCS name and location, as the
provider reported them.

**Why this priority**: This is the feature. A second backend that needs different code or a
different descriptor is not a backend behind the same interface.

**Independent Test**: Against a real Google Cloud project, deploy a service with the flag to a
GKE installation configured for GCS, run a put and a get from inside its pod with the injected
variables and the S3 client the docs show, and assert the object round-trips and is in the GCS
bucket the status names. Run the same image and descriptor against a Garage installation and
assert the same result.

**Acceptance Scenarios**:

- added `features/object-storage-gcs/provisioning.feature`: a service that asks for a bucket on an installation that keeps objects in Google Cloud Storage is given one
- added `features/object-storage-gcs/provisioning.feature`: a service keeps an object in its Google Cloud Storage bucket and reads it back with the client it used against Garage
- added `features/object-storage-gcs/provisioning.feature`: the status of a service names its bucket, its object store and its location as the provider reported them
- added `features/object-storage-gcs/provisioning.feature`: a bucket whose name another Google customer holds is reported as failed and not used
- added `features/object-storage-gcs/provisioning.feature`: a bucket Google Cloud Storage cannot make yet is reported as still being made
- added `features/object-storage-gcs/names.feature`: two services whose project and name would join to the same hyphenated name are given different buckets

---

### User Story 2 - A credential reaches one bucket, and the operator touches no Google (Priority: P1)

Two services in two projects each get a GCS bucket. Each one's HMAC key is refused by the other's
bucket. The operator rendered both requests and read both statuses without holding any Google
client, credential or permission; the provider made both buckets, both Google accounts and both
keys, and neither the operator nor the provider can read either service's key back. A member asks
for one service's credential to be issued again; the old key stops working and the service's next
rollout reads the new one.

**Why this priority**: 034's isolation rule is the reason a bucket per service is worth having;
on GCS it rests on Google's IAM rather than Garage's key permissions, and has to be shown again.
And the identity that mints keys is the one long-lived power that unlocks every bucket, which is
why it is in a provider the installation deploys on purpose and not in the operator.

**Independent Test**: Against a real Google Cloud project, deploy two services in two projects,
take each key from inside its own pod, attempt the other's bucket, and assert Google refuses it.
Assert the operator's pod has no Google key file, key variable or Google client on its classpath,
and that its ServiceAccount is bound to no Google identity. Mint a token for the provider's
ServiceAccount and assert a `get` on a storage credential Secret is refused. Re-issue one service's
credential; assert the old key is refused and a rolled-out pod reads a different key that works.

**Acceptance Scenarios**:

- added `features/object-storage-gcs/isolation.feature`: a storage credential is refused by another service's Google Cloud Storage bucket
- added `features/object-storage-gcs/isolation.feature`: the operator holds no Google client, credential or permission and reads only the provider's status
- added `features/object-storage-gcs/isolation.feature`: the provider reaches Google Cloud through its workload identity and holds no Google key
- added `features/object-storage-gcs/isolation.feature`: neither the operator nor the provider can read a storage credential back
- added `features/object-storage-gcs/isolation.feature`: a service's storage account is granted on its own bucket and on nothing else
- added `features/object-storage-gcs/isolation.feature`: a storage credential issued again is a new one, and the old one is refused
- changed `features/object-storage/isolation.feature`: a storage credential is made once and replaced only when a member asks
- added `features/object-storage/isolation.feature`: a storage credential issued again at a member's asking replaces the one the service had

---

### User Story 3 - Documents are kept against accident and every version can be deleted (Priority: P1)

A KYC service on a GCS installation stores identity documents. A bug overwrites one and a
mistaken call deletes another; both are recovered from their noncurrent versions. When every
version of an object is deleted, none is listed, and the status tells a member when that deletion
becomes final. No bucket has a retention policy, so no deletion is ever refused by the store on
account of an object's age.

**Why this priority**: The first user is regulated. Without versioning, one bad deploy can
destroy evidence the operator is legally required to keep; with a retention policy, a player's
right to erasure becomes impossible for as long as the policy runs. Both are failures a regulator
sees.

**Independent Test**: Against a real Google Cloud project with a short soft-delete window
configured for the test, overwrite and delete objects and read them back by version; delete every
version and assert no version is listed and the soft-deleted object is restorable only within the
window; assert the bucket's metadata holds no retention policy.

**Acceptance Scenarios**:

- added `features/object-storage-gcs/retention.feature`: an object that is overwritten can be read as it was before
- added `features/object-storage-gcs/retention.feature`: an object that is deleted can be read back as its noncurrent version
- added `features/object-storage-gcs/retention.feature`: deleting every version of an object leaves none listed
- added `features/object-storage-gcs/retention.feature`: the status of a bucket says how long a deleted object can still be recovered
- added `features/object-storage-gcs/retention.feature`: no bucket is made with a retention policy
- added `features/object-storage-gcs/retention.feature`: a bucket is encrypted with the installation's key when the installation names one
- added `features/object-storage-gcs/retention.feature`: a bucket is made in the location the installation names
- added `features/object-storage-gcs/retention.feature`: a bucket is made in the location its project names, when the project names one

---

### User Story 4 - A browser uploads a document through a URL the service signed (Priority: P2)

A service with `exposeObjectStorage: true` on a GCS installation names, in its descriptor, the
origins that may upload — the player frontend's hostname, not its own. It is given
`ANKKA_S3_PUBLIC_ENDPOINT`, signs a PUT URL for one object with its S3 client, and a page served
from the named origin uploads the file to it from a browser. The bucket's CORS rule, set by the
provider, admits the named origins and nothing else. A request with no signature is refused, for
this bucket and for every other.

**Why this priority**: The KYC upload goes from the player's browser straight to the bucket; it is
the reason exposure exists. It is P2 because a service can proxy the bytes until it works.

**Independent Test**: Against a real Google Cloud project, sign a PUT and a GET inside the pod,
send the CORS preflight with a named origin as `Origin` and assert it is allowed, send it with the
service's own hostname (not named) and assert it is not, then use both URLs from outside the
cluster. Send an unsigned GET for an object in an exposed and an unexposed bucket and assert both
are refused. Run the same descriptor on a Garage installation and assert the same preflight
answers.

**Acceptance Scenarios**:

- added `features/object-storage-gcs/reachable.feature`: a service whose bucket is reachable from the internet is told the address of its bucket on the internet
- added `features/object-storage-gcs/reachable.feature`: a browser on an origin the descriptor names keeps an object through a signed URL
- added `features/object-storage-gcs/reachable.feature`: a browser on an origin the descriptor does not name cannot send an object to the bucket
- added `features/object-storage-gcs/reachable.feature`: a request without a signed URL is refused by every bucket
- changed `features/object-storage/reachable.feature`: a browser on an origin the descriptor names keeps an object through a signed URL without the service setting a rule on its bucket

---

### User Story 5 - A service reaches its bucket with no key at all (Priority: P3)

A developer on a GCS installation writes their service with Google's own client library and no
credential configured. It reaches its bucket through its Workload Identity and no other bucket. A
descriptor that says it needs no key is given no `ANKKA_S3_ACCESS_KEY` or `ANKKA_S3_SECRET_KEY`
and no key is issued for it.

**Why this priority**: It removes the last long-lived secret for services that can afford
Google-only code, at almost no cost, since the per-service Google account exists for the key
anyway. It is P3 because nothing the first user needs depends on it, and a keyless service cannot
sign a URL without Google's signing API.

**Independent Test**: Against a real Google Cloud project, deploy a service whose code uses
Google's client with default credentials, read and write its bucket, attempt another service's
bucket and assert it is refused. Deploy one that declines a key and assert its pod has no key
variables and its Google account has no HMAC key.

**Acceptance Scenarios**:

- added `features/object-storage-gcs/keyless.feature`: a service reaches its bucket through its workload identity with no storage credential
- added `features/object-storage-gcs/keyless.feature`: a service reaching Google Cloud Storage through its workload identity is refused by another service's bucket
- added `features/object-storage-gcs/keyless.feature`: a service that declines a storage credential is given none
- added `features/object-storage-gcs/keyless.feature`: a descriptor that declines a storage credential is refused on an installation whose object store is Garage

---

### User Story 6 - An existing service's objects move from Garage to GCS (Priority: P2)

An installation that has run Garage gains GCS. A member moves one service's storage: every object
is copied into the service's new GCS bucket while the service keeps writing; then, for a short
window, the service's credential is swapped for a read-only one, its status says the storage is
moving, the objects that changed are copied, the copy is verified by hashing both sides, the
service's next rollout reads the new variables, and the status reports the store as GCS. The
Garage bucket is left as it was. A member who has not moved a service sees it still reported on
Garage.

**Why this priority**: The casino is live and keeps documents today; a backend that only new
services can use leaves every existing document where versioning does not apply. It is P2
because new installations do not need it.

**Independent Test**: In a GKE installation with both stores, put objects into a Garage-backed
service, run the move, assert the counts and hashes match, assert the service's new pods read
the GCS variables and get the objects, and assert the Garage bucket still holds every object.
During the pause, assert a write from the pod is refused and a read succeeds, and that the
service's status says moving. Interrupt a move and run it again; assert it completes with no
object copied twice into a changed state.

**Acceptance Scenarios**:

- added `features/object-storage-gcs/move.feature`: a service's objects are moved from Garage to Google Cloud Storage
- added `features/object-storage-gcs/move.feature`: a service reads its moved objects after its next rollout
- added `features/object-storage-gcs/move.feature`: a move that stopped part way is finished by running it again
- added `features/object-storage-gcs/move.feature`: a move that finds an object it cannot verify does not switch the service
- added `features/object-storage-gcs/move.feature`: objects written during the bulk copy are copied in the write pause before the switch
- added `features/object-storage-gcs/move.feature`: during the pause the service can read and not write, and its status says the storage is moving
- added `features/object-storage-gcs/move.feature`: a move that fails during the pause gives the service its writes back on Garage
- added `features/object-storage-gcs/move.feature`: a service not yet moved keeps its bucket on Garage
- added `features/object-storage-gcs/move.feature`: the Garage bucket is kept after a move

---

### Edge Cases

- **A name another Google customer holds.** Bucket names are global; creation is answered as
  taken, or a bucket of that name exists in a Google project that is not the installation's. The
  provider reports the request `Failed` naming the bucket and grants nothing on it; the service's
  storage is `Failed` with that reason. The member's fix is a different installation prefix,
  which is why the prefix is a setting.
- **A name over 63 characters.** The readable part is shortened and the digest kept, so every
  project and service pair has a GCS name; 034's refusal for names that are too long remains a
  Garage rule only, and the control plane applies it only when the installation's store is Garage.
- **The HMAC key limit.** Google allows a small number of HMAC keys per service account. The
  provider issues a speculative key and deletes extras, as 034's credential logic does; a failure
  between the two could accumulate keys. The provider deactivates and deletes every key of the
  service's account that no Secret holds, and reports `Failed` naming the limit if it is still
  reached.
- **The provider's Google identity lacks a permission.** Google answers with a refusal, which is a
  `Failed` naming the permission, not a `Waiting`: waiting does not grant it.
- **Google is briefly unavailable or rate-limits.** Reported as `Waiting` with the reason, as 034
  treats an unreachable Garage.
- **The provider is not deployed, or is down.** A request with no status past a bound is the
  service's storage `Waiting` naming the provider, as 044 defines; the operator cannot make the
  bucket itself and does not try.
- **A service already on GCS through its own `ANKKA_S3_*` variables.** It is `Supplied`, as on
  Garage; this feature provisions nothing for it.
- **The installation's soft-delete window is changed.** New buckets take the new value; existing
  buckets keep theirs until a member asks for it to be reapplied, because shortening the window on
  a bucket holding regulated documents is a decision, not a reconcile.
- **Versioning and cost.** Every overwritten object is kept as a version until it is deleted; a
  service that overwrites large objects often pays for every version. The docs say so, and a
  service may set an age after which noncurrent versions are deleted through the descriptor's
  portable field (FR-017).
- **A re-issue while a move is paused.** Refused with the reason; the move's read-only credential
  is the one in force until the move ends.
- **A key rolled out before the old one is deleted.** Re-issue writes the new key, deactivates the
  old one at the next rollout's completion, and deletes it after; a pod on the old key during the
  rollout keeps working until it is replaced.

## Requirements *(mandatory)*

### Functional Requirements

**The backend and who provisions it**

- **FR-001**: An installation MUST be configurable with Google Cloud Storage as its object store
  through feature 044's installation settings: the Google project, the default bucket location,
  the bucket name prefix, the soft-delete window and, optionally, a Cloud KMS key.
- **FR-002**: The operator MUST provision a GCS bucket by rendering a 044 request and reading its
  status and the name of the credential Secret it wrote; the operator MUST hold no Google client,
  no Google credential and no IAM permission, and MUST NOT be bound to any Google identity. The
  bucket, the Google service account, its binding, its grant, its HMAC key and the bucket's
  settings MUST be made by the provider, which reaches Google through its own Workload Identity and
  is given no Google service account key in any form.
- **FR-003**: A service's descriptor and code MUST NOT change between a Garage and a GCS
  installation for what 034 provides: the descriptor fields, the five variables, and put, get,
  list, delete of the current object and presigned URLs through an S3 client configured as the docs
  show. Object versioning is a GCS property this feature does not promise on Garage.
- **FR-004**: The workload MUST receive the five variables of 034 FR-004 with values for GCS: the
  endpoint `https://storage.googleapis.com`, the region GCS accepts for a signature, the bucket's
  GCS name as the provider reported it, and an HMAC key's id and secret.

**Names and isolation**

- **FR-005**: A service's GCS bucket MUST be named from the installation's prefix, the project, the
  service and a digest of `<project>.<service>`, MUST contain no dot, MUST fit 63 characters, and
  MUST differ for every pair that 034's name distinguishes. The provider MUST report the name in
  the request's status; the operator MUST copy it onto the service's status and into
  `ANKKA_S3_BUCKET`; the control plane MUST show the name from the status and MUST NOT derive it.
  `Buckets.name` in `crd` MUST remain the Garage name only.
- **FR-006**: The provider MUST create one Google service account per service with a bucket, MUST
  grant it on that service's bucket and on nothing else, and MUST issue the HMAC key for that
  account. A key or account MUST NOT reach another service's bucket, in the same project or another.
- **FR-007**: A bucket that exists under the derived name in a Google project other than the
  installation's, or that GCS reports as taken, MUST be reported `Failed`, and the provider MUST NOT
  grant anything on it.
- **FR-008**: The HMAC secret MUST be written once to `<service>-storage` exactly as 034 FR-003
  writes Garage's key, by the provider, and neither the operator nor the provider MUST read it from
  the cluster or from Google afterwards.
- **FR-009**: Every GCS bucket MUST have public access prevention enforced and uniform bucket-level
  access on. No object is readable without a signature or a granted identity.
- **FR-010**: A member MUST be able to have a service's storage credential issued again, on both
  backends: a new key is issued and written to `<service>-storage`, the old key is deactivated when
  the service's next rollout completes and deleted after, and the action is recorded in the control
  plane's audit. Re-issue MUST be refused while the service's storage is moving.

**Versioning, residency and encryption**

- **FR-011**: Every GCS bucket MUST have object versioning on, soft delete with the installation's
  window and, when the installation names one, its Cloud KMS key as the default encryption key.
  No bucket MUST be given a retention policy, locked or unlocked, and the platform MUST offer no
  setting that does; a retention policy found on a bucket the platform made MUST be reported
  `Failed` naming it.
- **FR-012**: A bucket MUST be created in its project's location when the project names one, and in
  the installation's default location otherwise. A project's location MUST be settable by a member
  and recorded in the control plane's audit; changing it MUST NOT move or change an existing bucket,
  whose location is fixed at creation, and the status MUST report each bucket's location.
- **FR-013**: On GCS, a deletion asked of every version of an object MUST leave no version listed,
  and the status MUST state the soft-delete window so the time a deletion becomes final can be
  read. On Garage a deletion is final at once. Erasing a subject's objects is feature 042's and
  MUST NOT be required of a service's own code by this feature.
- **FR-014**: Changing the installation's soft-delete window MUST NOT change an existing bucket's
  setting; reapplying it to an existing bucket MUST be an explicit action by a member, recorded in
  the control plane's audit.

**Exposure and settings**

- **FR-015**: The descriptor's storage section MUST accept a list of origins. For a service with
  `exposeObjectStorage`, the workload MUST receive `ANKKA_S3_PUBLIC_ENDPOINT` for GCS, the status
  MUST show the bucket's public address, and the bucket MUST carry a CORS rule admitting exactly
  the named origins for signed reads and writes, set by the provider on GCS and by the operator on
  Garage; an empty list is no rule. For a service without the flag, the bucket MUST have no CORS
  rule and the variable MUST be absent, whatever the list says.
- **FR-016**: A service MUST set no CORS rule on either backend; 034's FR-017 is narrowed to say
  that a bucket setting made through S3 is the service's own and is not portable.
- **FR-017**: The descriptor MUST accept an optional age after which noncurrent versions are
  deleted, honoured on GCS and accepted and without effect on Garage, which keeps one version.
- **FR-018**: The docs MUST say that on GCS a bucket is reachable from the internet by any valid
  signature whether or not it is exposed, and that exposure decides only the public address and
  the CORS rule.

**Keyless access**

- **FR-019**: On a GCS installation the service's Kubernetes ServiceAccount MUST be bound by
  Workload Identity to its Google service account, so a Google client with default credentials
  reaches its bucket and no other.
- **FR-020**: A descriptor MUST be able to decline the key; such a service MUST be given no
  `ANKKA_S3_ACCESS_KEY` or `ANKKA_S3_SECRET_KEY`, MUST have no HMAC key issued, and on a Garage
  installation MUST be refused at apply with the reason.

**Moving from Garage**

- **FR-021**: An installation MUST be able to run both stores, with new buckets on GCS and each
  existing bucket on the store it was made in; the status MUST name the store of each service's
  bucket.
- **FR-022**: A member MUST be able to move one service's bucket from Garage to GCS. The move MUST
  copy every current object with its content type and user metadata, MUST verify the count and
  every object's content by hashing both sides with the same algorithm (never by comparing ETags),
  MUST switch the service's variables only when verification passes and only at its next rollout,
  MUST be resumable, and MUST leave the Garage bucket unchanged.
- **FR-023**: Objects a service writes to Garage between the copy and the rollout MUST be copied
  before the switch, or the switch MUST NOT happen. The move MUST do this by a write pause enforced
  by credential: after the background copy, the service's credential in `<service>-storage` is
  replaced by one that reads and does not write (the store contract gains an operation that issues
  a read-only credential for a bucket), the service is rolled so it holds it, the objects changed
  since the copy began are copied and verified, and the pause ends when the service rolls onto GCS
  or the move fails. The status MUST report the pause and its start and MUST say the storage is
  moving; a move that fails during the pause MUST restore a writing credential with the service
  still on Garage.

**Local and testing**

- **FR-024**: A local installation, `AnkkaTestKit` and every suite that runs without Google Cloud
  MUST be unchanged and MUST need no Google account and no provider. The GCS suites MUST run only
  when a Google project is configured, and MUST be a separate CI workflow on demand and nightly,
  never a pull request job.
- **FR-025**: The docs MUST gain a GCS section on the object storage page (configuration through
  044, the provider's identity and the roles it holds, versioning and soft delete, the absence of a
  retention policy and why, the differences in FR-018, re-issue, origins) and an installation page
  for GKE with GCS and the provider.

### Key Entities

- **Store**: Garage or GCS; one is the installation's default for new buckets; each bucket records
  which it is in.
- **Provider request**: feature 044's request for a bucket, rendered by the operator, naming the
  service, project, location, origins, version age and the credential Secret; answered in status
  with the bucket's name, location and state.
- **Bucket (GCS)**: named from the prefix, project, service and digest, as status reports it;
  versioned, with soft delete, public access prevention, uniform access, no retention policy, the
  project's or installation's location and optional KMS key. Never deleted by the platform.
- **Storage account**: one Google service account per service, granted on its bucket only, bound to
  the service's Kubernetes ServiceAccount, owning the service's HMAC key.
- **Storage credential**: the HMAC key's id and secret, in `<service>-storage`, written once and
  replaced only by re-issue or, for a move's pause, by a read-only credential.
- **Origins**: the list on the descriptor's storage section naming who may upload or read from a
  browser; the CORS rule's content on both backends.
- **Storage status**: 034's, plus the store, the location, the soft-delete window and, during a
  move, its progress and the pause.
- **Project location**: an optional location a project names for its new buckets; the
  installation's default applies otherwise.
- **Move**: one service's copy from Garage to GCS: counts, verified hashes, state (copying,
  paused, verified, switched, failed), resumable.
- **Platform bucket**: a bucket the platform owns for its own data, such as feature 041's database
  backups; same backend and provider as the installation's, never granted to a service.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: One service image and descriptor round-trip an object on a Garage installation and on
  a GCS installation with no change to either.
- **SC-002**: A key used against another service's GCS bucket is refused by Google in the same
  project and across projects, and so is a keyless service's identity.
- **SC-003**: The operator's pod holds no Google client, credential or permission, shown by its
  classpath, its environment, its filesystem and its ServiceAccount's bindings, and buckets are
  still made. The provider's identity holds `setIamPolicy` on the installation's buckets and
  `storage.hmacKeys.create` on its service accounts — it can mint a key for any account it manages —
  and that identity is the provider's alone, deployed by a platform administrator on purpose.
- **SC-004**: A deletion of every version leaves no version listed; a bucket the platform made holds
  no retention policy; both shown against a real bucket.
- **SC-005**: A browser-origin preflight from a named origin is allowed and from an unnamed one,
  the service's own hostname included, is refused, on both backends, with no CORS call in the
  service's code.
- **SC-006**: A move of a bucket of 10,000 objects completes with every hash verified, the service
  reads all of them after its rollout, and the Garage bucket still holds all of them; objects
  written during the bulk copy are among them, a write during the pause is refused and a read is
  not, and the pause lasts no longer than the time to copy what changed during the bulk copy plus
  two rollouts.
- **SC-007**: `sbt -Dankka.cluster.tests=off test` and the local installation pass on a machine
  with no Google credentials and no provider deployed.
- **SC-008**: A project that names a location gets its new buckets there and the installation's
  other projects keep the default, shown against real buckets.
- **SC-009**: After a re-issue, the old key is refused by Google and the service's rolled-out pod
  reads and writes with the new one.

## Assumptions

- The installation runs on GKE with Workload Identity Federation for GKE enabled; a GCS installation
  on another Kubernetes is out of scope.
- The provider of feature 044 is deployed, and its Google identity is granted, in the installation's
  Google project, the right to create and administer buckets, service accounts, HMAC keys and their
  IAM bindings, and nothing outside that project. The install page lists the exact roles and says
  what they can do.
- GCS's XML API accepts SigV4 with an HMAC key for put, get, list, delete, multipart upload and
  presigned URLs, and deletes a noncurrent version by its id. The S3 client settings 034's docs
  give for Garage (checksum calculation when required) are what GCS needs too; to be verified
  first in planning.
- A KYC service decides when a document must be deleted from its own domain state — when an
  account closed, when an application was abandoned. Holding to the anti-money-laundering period
  is the service's rule, and the platform refuses no deletion on account of an object's age.
- 034's operator-without-`get`-on-Secrets prerequisite has landed.

## Dependencies

- **034-object-storage**: the seam, the credential logic, the plan and the descriptor fields this
  feature implements a second time.
- **044-cloud-provider**: the request the operator renders, the status it reads, the installation
  settings that choose the backend and the default location, and the `ankka-gcp` provider that
  makes everything Google.
- **041-postgres-backup-recovery**: keeps its backups in platform buckets on this backend; this
  feature must ensure no service credential or identity is granted on one.
- **042-personal-data-erasure**: defines erasure of a subject's objects; depends on this feature
  never setting a retention policy, on deletion of every version on GCS, and on the soft-delete
  window being readable for finality.
- **038-secret-store-backends**: not a dependency. The storage credential stays a Kubernetes Secret
  written once, as the database credential does, and moves only if that feature moves those.
- **Google Cloud**: a project for the installation and one for the GCS test suites.
- Gates the casino's KYC documents on the production installation, and the move of Spinvibe's
  existing documents from MinIO, which is a migration of the casino's own and not this feature.

## Open Questions

- None from this feature's clarification sessions; the three original questions and the eight
  review questions were settled on 2026-10-08.
- Whether the write pause of a move needs a bound the member sets, after which the move gives up
  and restores the writing credential, is left to the plan.
