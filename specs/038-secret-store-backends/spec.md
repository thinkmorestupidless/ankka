# Feature Specification: Secret Store Backends — Google Secret Manager Beside Postgres

**Feature Branch**: `038-secret-store-backends`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Put secret storage behind an interface; the existing Postgres store
(spec 023) becomes one implementation and Google Secret Manager the next. The backend is chosen per
installation, not per service, and a service's code and descriptor do not change between backends.
On GKE a service reaches Secret Manager with its own Workload Identity, admitted only to its own
secrets and its project's, so a compromised service cannot read another's, with no long-lived GCP
key anywhere. Every read of a secret is auditable. A new version is picked up without a redeploy.
Moving an installation from Postgres to Secret Manager is a supported, testable operation with no
secret value passing through a person. Local development and the testkit keep working with no GCP.
The first user is a regulated real-money operator (eitheror's casino and payments) holding PSP
credentials."

## Clarifications

### Session 2026-10-08

- Q: Is a Postgres-backend read record kept by the platform enough for the regulator, or must a
  real-money installation refuse to run its money services on the Postgres backend? → A: The
  Postgres backend's durable, operator-queryable read record is enough. Both backends keep a read
  record; Secret Manager adds Cloud Audit Logs. No service is refused on Postgres.
- Q: How does a project secret kept in Secret Manager reach a service's variable? → A: The platform
  keeps a Kubernetes Secret in step with the Secret Manager entry, so `secretKeyRef` and every
  hosting mode work unchanged. The operator does the sync (not GKE's Secret Manager add-on). The
  value is then also in the cluster's Secret store, and pod-start reads are not audited per read.
  *(Who does the sync is superseded in the review session below: the cloud provider, not the
  operator.)*

### Session 2026-10-08 (review)

- Q: Where does the Google-touching work live — the Secret Manager store, the grants, the sync? → A:
  The `SecretStore` implementation stays in ankka core: the sidecar image is the platform's, so a
  Python, TypeScript or Rust service could not reach an implementation shipped from ankka-contrib
  without a second image. Everything that touches Google Cloud IAM or the Kubernetes Secret store on
  Google's behalf — a service's Google identity, its grants on secrets, the control plane's grant, and
  the copy of a project secret into the project's Kubernetes Secret — is the **cloud provider's**, a
  separate process defined by 044-cloud-provider (its contract in core, its Google implementation in
  the `ankka-gcp` repository). The operator renders a 044 request and reads its status; it never
  holds a Google credential or the power to grant.
- Q: Why not the operator? → A: Granting a service access to secrets it creates itself needs
  `setIamPolicy` on the Google Cloud project, a conditional binding at project level. That power can
  grant anything to anyone — it is owner-equivalent — and the operator's whole design is minimal
  power (`crd` only, no client it does not need). The power goes in one process built to hold it.
- Q: Where is the backend named? → A: Once, in 044's installation settings, beside the Secret
  Manager project and any KMS key. This feature reads the setting; it does not define its own.
- Q: Where does the read record live? → A: In the control plane's store, written by the runtime or
  the sidecar over the service's own identity — never in the service's database, where a
  `database: none` service could not record, a compromised service could delete its own audit, and a
  041 restore would rewind it. Kept for a retention the installation sets (default one year),
  listable by an owner, and listed by 041 among the things a restore leaves alone.
- Q: What can the record name for a polyglot service? → A: The sidecar cannot tell which component
  called (`secrets.md`), so the record names the service, the kind of hosting, and the request and
  trace where known; the component and its kind only when known, which is every Scala call.
- Q: What happens to superseded versions? → A: `put` adds a version and Secret Manager destroys
  nothing. The platform destroys superseded versions beyond a kept count the installation sets
  (default 2). Service secrets are never disabled, only superseded or deleted, so `latest` always
  resolves to a readable version.
- Q: Is the IAM-condition design verified? → A: No. Whether a condition on `resource.name` limits
  `secrets.create` and `versions.access` to a service's own derived ids is the whole isolation
  design and is a research task before planning. The fallback is one secret prefix per service,
  enforced by the provider when it creates the secret, not by IAM conditions.
- Q: What identities do the control plane and the provider use? → A: Workload Identity, never a
  key file — the same rule as a service.
- Q: What does a 041 restore do to secrets? → A: On the Postgres backend a service's secrets are
  rows in its database and are rolled back to the restore point; 041 lists the names changed since.
  On Secret Manager a restore does not touch them.
- Q: Is `ANKKA_SECRET_KEY` ignored on Secret Manager? → A: Not until the removal step. The key is
  rendered and required through the move, since the service decrypts its rows with it; after the
  removal step it is still rendered and no longer read.

## Context

Feature 023 gave ankka two kinds of secret, and they reach a service by two different paths.

A **service secret** is kept and read while the service runs. Components call `put`, `get` and
`delete` on a `SecretStore`, a trait in `sdk` (`modules/sdk/.../SecretStore.scala`). The runtime
builds exactly one implementation, `DatabaseSecretStore`, unconditionally, in `Ankka.scala`: one
table, `ankka_secrets`, in the service's own database, each value AES-256-GCM under the service's
secret key (`ANKKA_SECRET_KEY`), the name as associated data and a version byte in front
(`SecretCipher`). Nothing is cached: every `get` is a database read, so a value another instance
replaced is seen on the next read, and there is no second place a plaintext lives. A Python or
TypeScript process and a Rust module reach the same store through the sidecar's `GetSecret`,
`PutSecret` and `DeleteSecret`, which call `service.secrets` in `ClientLogic`. So the interface the
feature asks for already exists for service secrets, and every language already goes through it. What
is missing is a second implementation and a way to choose it.

A **project secret** is a Kubernetes Secret in the project's namespace. A member sets its entries
through the control plane (`PUT /projects/{id}/secrets/{name}`, `ankka projects secrets set`), which
writes them with a merge patch through `ProjectSecretWriter` under a grant of `create` and `patch`
and no `get`, and records names only. A descriptor takes a variable from an entry with
`secretKeyRef`, and the kubelet resolves it **when the pod starts**. No ankka code reads the value at
all. That path is Kubernetes-specific end to end: a Secret Manager installation needs a different
answer for how the value reaches the pod, not only a different place to keep it.

Neither path records who read a value. `DatabaseSecretStore` logs a name at debug on `put` and
`delete` and logs nothing on `get`. A project secret's value is read by the kubelet, which the
Kubernetes audit log may or may not record depending on the cluster's audit policy. Secret Manager
records every access of a version in Cloud Audit Logs as a Data Access entry, attributed to the
principal that made it, when Data Access logging is turned on for the service. An operator holding
PSP credentials under a gambling licence needs a record of reads on whichever backend it runs; this
feature gives Postgres one of its own, and Secret Manager adds Google Cloud's.

Five decisions shape this feature.

- **The backend is the installation's.** One platform setting names the backend for every service
  the installation runs, as `ANKKA_BROKER_*` and `ANKKA_OBJECT_STORE_*` name the broker and the
  object store. A service's code, its descriptor and its SDK calls are the same on either. A service
  cannot choose a different backend from its installation's; it can still supply its own key on the
  Postgres backend, as 023 allows.
- **A service's identity is its key to Secret Manager.** The operator already gives every service
  its own Kubernetes ServiceAccount (`Rendering.identityActions`). On GKE with Workload Identity
  Federation, that ServiceAccount is a principal Google Cloud IAM can grant to directly, with no
  Google service account and no key file. The platform grants a service access to its own service
  secrets and read access to its project's project secrets, and nothing else. A pod that holds
  another service's token cannot exist, because the token is the ServiceAccount's.
- **The grants are the cloud provider's to write, not the operator's.** Granting a service access
  to secrets it will create itself needs `setIamPolicy` on the Google Cloud project: a power that can
  grant anything to anyone, owner-equivalent. The operator is built to hold as little power as it
  can (`crd` only), so it does not hold this. It renders a request for the cloud provider of
  044-cloud-provider — a separate process, its contract in core and its Google implementation in the
  `ankka-gcp` repository — and reads the provider's status before it rolls the service out. The
  same provider keeps a project secret's Kubernetes Secret in step with Secret Manager. The
  `SecretStore` implementation itself stays in core: the sidecar image is the platform's, and a
  Python, TypeScript or Rust service cannot reach anything shipped outside it.
- **Service secrets keep their rules and their semantics.** The name and value rules of
  `SecretRules` are unchanged. `get` still reads the current value every time, with no cache, so a
  `put` on one instance is seen by the next `get` on every instance. A `put` adds a version; a
  `delete` removes the secret and every version.
- **Every read leaves a record a person can find.** On Secret Manager the record is Cloud Audit
  Logs. On both backends the platform keeps a record of its own of every read, in the control
  plane's store and never in the service's database, so a service cannot erase the audit of its own
  reads and a restore of its database does not rewind it. Moving backends adds Google's record; it
  does not change whether there is one.
- **Versions are kept to a count.** A `put` adds a version and Secret Manager destroys none on its
  own, so an installation would keep every PSP key it ever held, readable and billed. The platform
  destroys versions beyond a kept count the installation sets, and never disables a service secret's
  version, so `latest` always resolves.
- **Moving is a supported operation.** An installation on Postgres can move to Secret Manager
  service by service, by the platform, with the values copied by the service that already holds
  them and the project secrets copied by the platform that already writes them. No person sees a
  value, and the copy can be checked before the old copy is removed.

What this feature is not: the cloud provider itself and its contract, which are 044's; rotation of
the Postgres backend's secret key (still its own feature); another cloud's secret manager, though
the seam is what one would need; a secret shared between services, which still cross the boundary by
HTTP; customer-managed encryption keys for Secret Manager, which the installation names in 044's
settings without this feature knowing; or a change to what a component may call the store from.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service keeps and reads secrets in Secret Manager without changing (Priority: P1)

An installation on GKE is set to the Secret Manager backend. A payments service, unchanged from the
one that ran on Postgres, takes a PSP's credential through its backoffice endpoint and calls
`secrets.put("psp/acme/api-key", value)`. A workflow step later calls `secrets.get` and calls the
PSP. The value is in Secret Manager under a name derived from the project, the service and the
secret's name, the service's database holds no row for it, and the pod holds no Google credential
file and no key.

**Why this priority**: This is the feature. The PSP credentials of the first user live here.

**Independent Test**: Against a Secret Manager fake that checks the caller's identity and the name
it touches, run the 023 secret store scenarios with the backend set to Secret Manager and assert
they pass unchanged; assert the service's `ankka_secrets` table is empty. On a GKE cluster, deploy
a service, put and get from inside it, and assert the secret exists in Secret Manager under the
derived name.

**Acceptance Scenarios**:

- added `features/secrets/secret-manager.feature`: a service secret kept by one component is read by another, and the database holds nothing of it
- added `features/secrets/secret-manager.feature`: a service secret kept on one instance is read on another with no restart
- added `features/secrets/secret-manager.feature`: a service secret kept again is read by every instance with no descriptor applied again
- added `features/secrets/secret-manager.feature`: a service secret that was never kept is read as none, without a failure, on the Secret Manager backend
- added `features/secrets/secret-manager.feature`: a removed service secret is read as none and no version of it remains
- added `features/secrets/secret-manager.feature`: a name or a value that breaks the rules of the secret store is refused before Secret Manager is called
- added `features/secrets/secret-manager.feature`: a service in every language passes every behaviour of the secret store on the Secret Manager backend
- added `features/secrets/grants.feature`: an instance of a service holds no credential for Google Cloud and does not read its secret key

---

### User Story 2 - A compromised service cannot read another service's secrets (Priority: P1)

An attacker has code running in the `wallet` service's pod in the `spinvibe` project. With the
identity the pod has, they try to read `payments`' service secrets, another project's service
secrets, and another project's project secrets. Every attempt is refused by Google Cloud IAM, not by
ankka code in the pod, and each refusal is in the audit log.

**Why this priority**: The reason to use a service's own identity is the blast radius. Without it,
Secret Manager is one shared vault behind one credential, which is worse than one key per service
in Postgres.

**Independent Test**: On a GKE cluster, mint a token for one deployed service's ServiceAccount and
call Secret Manager directly with it: assert that accessing that service's own secret succeeds, that
accessing another service's and another project's is `PERMISSION_DENIED`, and that listing every
secret in the Google Cloud project is refused.

**Acceptance Scenarios**:

- added `features/secrets/grants.feature`: a service is refused another service's service secret of the same project
- added `features/secrets/grants.feature`: a service is refused the secrets of another project
- added `features/secrets/grants.feature`: a service reads an entry of a project secret of its own project
- added `features/secrets/grants.feature`: a service is refused a write to an entry of a project secret of its own project
- added `features/secrets/grants.feature`: a service cannot list the secrets Google Cloud holds for the installation
- added `features/secrets/grants.feature`: a service deleted and deployed again reads the service secrets it kept in Secret Manager

---

### User Story 3 - Every read of a secret can be found afterwards (Priority: P1)

A compliance officer is asked which components read the PSP credential for `acme` in the last month,
and when. On the Secret Manager backend they query Cloud Audit Logs for the secret's name and get one
entry per read, each naming the service's identity. On the Postgres backend, the same question is
answered from the platform's own record.

**Why this priority**: The first user is regulated. A secret store whose reads cannot be accounted
for does not pass a PSP's or a licensing body's security review, and this is the open question the
eitheror plan names.

**Independent Test**: With the backend set to each in turn, get a service secret three times from two
components and assert three read records exist in the control plane's store, each with the secret's
name, the service, the hosting, the time and the trace, the component where the caller is Scala, and
none with the value; drop the service's database and assert the records remain. On GKE, assert the
Data Access entries in Cloud Audit Logs for the same reads.

**Acceptance Scenarios**:

- added `features/secrets/read-record.feature`: the access log records a read of a service secret
- added `features/secrets/read-record.feature`: a read of a service secret is recorded with what is known of it and never the value
- added `features/secrets/read-record.feature`: a read that finds none or is refused is recorded with its outcome
- added `features/secrets/read-record.feature`: an installation whose access log is off is told what to turn on
- added `features/secrets/read-record.feature`: an owner is answered from the read record which services read a secret on the Postgres backend
- added `features/secrets/read-record.feature`: the read record outlives a restore of the service's database
- added `features/secrets/read-record.feature`: a read through a process or a module is recorded without a component
- added `features/secrets/read-record.feature`: a read record whose retention has passed is removed

---

### User Story 4 - A project secret is kept in Secret Manager and reaches the service (Priority: P2)

A member runs `ankka projects secrets set psp ACME_WEBHOOK_KEY=...` on a Secret Manager installation.
The value goes to Secret Manager; the control plane can add a version and cannot read one. The
cloud provider copies the entry into the project's Kubernetes Secret, so a descriptor that takes a
variable from `{name: psp, key: ACME_WEBHOOK_KEY}` with the `secretKeyRef` it already uses starts
with the value set. Setting the entry again is picked up by instances started afterwards, as on
Kubernetes Secrets today.

**Why this priority**: Most of a casino's credentials are set once by a person (a PSP's merchant key,
a games aggregator's signing key), and they are the ones that would otherwise stay outside the
audited store.

**Independent Test**: In the control plane HTTP suite, against the Secret Manager fake, set and unset
entries and assert the fake received an added version and a disabled version, and that the control
plane's identity was refused an access. On GKE, deploy a service whose variable is taken from the
entry and assert the variable's value inside the pod.

**Acceptance Scenarios**:

- added `features/secrets/synced-project-secrets.feature`: a member sets an entry and the value goes to Secret Manager, where the control plane cannot read it
- added `features/secrets/synced-project-secrets.feature`: a variable taken from an entry reaches a service through the project's secret in the cluster
- added `features/secrets/synced-project-secrets.feature`: an entry set again is what an instance started afterwards is given, and a running instance keeps what it had
- added `features/secrets/synced-project-secrets.feature`: a service whose variable is taken from a removed entry does not become ready
- added `features/secrets/synced-project-secrets.feature`: the cloud provider keeps the project's secret in the cluster in step with Secret Manager
- added `features/secrets/synced-project-secrets.feature`: a service of the project is not started until the cloud provider has synced an entry it takes a variable from
- added `features/secrets/synced-project-secrets.feature`: a list of project secrets shows names and entries and never a value on the Secret Manager backend

---

### User Story 5 - An installation moves from Postgres to Secret Manager (Priority: P2)

eitheror's installation began on the Postgres backend. The platform administrator turns on
Secret Manager. Each service, as it is rolled to the new setting, copies its own service secrets
from its table into Secret Manager, using the key it already has. The platform copies each project
secret's entries. A check compares what each backend holds, by name and by a digest of each value,
and only then are the Postgres rows removed. No person, log or event sees a value.

**Why this priority**: eitheror's casino is live; there is no starting again on the new backend.

**Independent Test**: Start a service on Postgres with ten service secrets and a project with two
project secrets, switch the installation to the Secret Manager fake, roll the service, and assert:
every name reads the same value through the store; the copy check reports equal; the Postgres rows
remain until the removal step runs and are gone after it; no log line, event or status holds a
value.

**Acceptance Scenarios**:

- added `features/secrets/moving.feature`: a service started on the Secret Manager backend with the move turned on copies its service secrets before it is ready
- added `features/secrets/moving.feature`: the copy check reports for each name whether the database and Secret Manager hold the same value
- added `features/secrets/moving.feature`: the removal step refuses while a copy check reports a difference
- added `features/secrets/moving.feature`: the removal step removes the rows once every name is equal
- added `features/secrets/moving.feature`: a service that cannot reach Secret Manager during the move does not become ready and leaves its rows
- added `features/secrets/moving.feature`: the platform moves a project secret into Secret Manager with its values and its record unchanged
- added `features/secrets/moving.feature`: a moved service set back to the Postgres backend before the removal step reads its secrets from its database

---

### User Story 6 - A developer and the test kit need no Google Cloud (Priority: P2)

A developer runs a service on their laptop and in `AnkkaTestKit` with no Google Cloud project, no
credentials and no network. The service uses the Postgres backend locally, as today. A test that
must prove behaviour on the Secret Manager backend uses an in-process fake the platform provides,
which enforces the same identity and name rules as the real thing.

**Why this priority**: Every other test in ankka is offline and deterministic. Secret Manager has no
official emulator, so without a fake the new backend is tested only on GKE, nightly.

**Independent Test**: Run the secret store suites with no network and no Google credentials in the
environment; assert they pass on both backends, the Secret Manager one through the fake.

**Acceptance Scenarios**:

- added `features/secrets/backend.feature`: a local platform whose secret backend is not set is on the Postgres backend
- added `features/secrets/backend.feature`: a test that asks for the Secret Manager backend is given the Secret Manager fake
- added `features/secrets/backend.feature`: the Secret Manager fake refuses what Google Cloud would refuse

---

### Edge Cases

- **A secret's name has characters Secret Manager refuses.** A service secret's name may hold `.` and
  `/` and be 253 characters; a Secret Manager secret id is letters, digits, `_` and `-`, up to 255,
  and it must also carry the project and the service. The id is derived from the name by a fixed,
  injective rule; a name whose id would be too long is kept under an id built from a digest of it,
  with the name itself kept on the secret where a platform administrator can read it. Two names
  never share an id.
- **A value exactly at the limit.** 023's limit, 64 KiB as UTF-8, is Secret Manager's limit for a
  version's payload. The rules are checked before any call, so both backends refuse the same values.
- **Secret Manager is unreachable or slow.** A get or put fails as `Unavailable` within the store's
  timeout, as a database failure does today; nothing falls back to Postgres or to a cache.
- **The service's identity has not been granted yet.** A service deployed before its grant has
  propagated is refused by Google Cloud. The store reports it as `Internal`, naming the missing grant,
  never as a missing secret; the service's status says its secret store is not admitted.
- **Reads exceed Secret Manager's quota.** `get` reads every time, and Secret Manager limits access
  requests per Google Cloud project per minute. A refusal for quota is `Unavailable`, and the read
  record counts it. A service that reads a secret per request is warned in the documentation.
- **Two instances put the same name at once.** Each adds a version; the latest wins, as the upsert
  does on Postgres.
- **A secret is put more times than the kept count.** The platform destroys the versions beyond the
  count, oldest first, after the new one is readable; a read never finds a destroyed version because
  `latest` is never one.
- **A version is disabled by hand in Google Cloud.** Service secrets are never disabled by the
  platform, and `get` reads the latest *enabled* version, so a version disabled by hand is skipped;
  the read record notes that the latest was not the one read.
- **A deleted secret is put again.** It is created again; its old versions are gone.
- **The installation is switched back to Postgres after the removal step.** Every service secret is in
  Secret Manager only, so every get reads none. The platform refuses to switch an installation's
  backend while a service's secrets live only in the other one, naming the services.
- **A service supplies its own `ANKKA_SECRET_KEY` on a Secret Manager installation.** The descriptor
  is accepted. Through a move the key is read, to decrypt the rows being copied; after the removal
  step it is no longer read, and the status says so. The operator keeps rendering
  `<service>-secret-key` either way, since `EnsureSecretKey` creates and never reads.
- **The cloud provider is absent or has not answered.** A service whose grant request has no status
  does not roll out; its status names the request it waits on. The operator never makes the grant
  itself.
- **Data Access audit logging is turned off later.** Reads still succeed, and the platform's own
  read record still holds them; the installation's status reports that Google Cloud's record is off.
- **A project secret's name is one the platform uses.** Refused, as 023's FR-016 refuses it, on both
  backends.

## Requirements *(mandatory)*

### Functional Requirements

**The seam**

- **FR-001**: The runtime MUST choose its `SecretStore` implementation from one platform setting
  naming the backend, `postgres` or `secret-manager`, with `postgres` when it is unset. No component,
  SDK or protocol call MUST change: the `SecretStore` trait, `SecretRules`, the sidecar's
  `GetSecret`/`PutSecret`/`DeleteSecret` and the module imports stay as they are.
- **FR-002**: Both backends MUST pass one set of secret store scenarios, run against each, covering
  every service secret scenario of feature 023 that does not name the database or the secret key.
- **FR-003**: The backend setting MUST be the installation's: one of the cloud settings
  044-cloud-provider declares (with the Secret Manager project and any KMS key), the operator MUST
  give it to every service it renders, a descriptor MUST NOT set it, and this feature MUST NOT
  declare a setting of its own for it.
- **FR-004**: The control plane MUST choose its `ProjectSecretWriter` implementation from the same
  installation setting.

**Secret Manager backend: service secrets**

- **FR-005**: On the Secret Manager backend, `put` MUST add a version to the secret derived from the
  project, the service and the name, creating the secret when it does not exist; `get` MUST read the
  latest enabled version on every call with no cache; `delete` MUST delete the secret and every
  version. The value MUST NOT be written to the service's database.
- **FR-005a**: The platform MUST NOT disable a service secret's version; a version is superseded or
  the secret is deleted. After a `put`, the platform MUST destroy the versions beyond a kept count
  the installation sets (default 2), oldest first, once the new version is readable.
- **FR-006**: The secret id MUST be derived from the project, the service and the name by one rule
  that is deterministic and injective, using only characters Secret Manager accepts, and the original
  name MUST be kept on the secret as an annotation.
- **FR-007**: Errors MUST map to the store's existing codes: refused by IAM → `Internal` naming the
  grant; unreachable, timed out or over quota → `Unavailable`; not found → `get` reads none.
- **FR-008**: The secret's replication and location MUST be the installation's setting, so an
  installation can keep its secrets in named regions.

**Identity and grants**

- **FR-009**: A service MUST reach Secret Manager with the identity of its own Kubernetes
  ServiceAccount through Workload Identity Federation for GKE. No Google service account key, key
  file or long-lived credential MUST exist in the service's pod, its descriptor, its Secrets or the
  operator's.
- **FR-010**: The cloud provider (044) MUST grant each service the right to create, add versions to,
  read and delete only the secrets derived for that service, and to read only the secrets derived
  for its project's project secrets. It MUST NOT grant list on the Google Cloud project's secrets.
- **FR-010a**: The operator MUST express a service's grant as a 044 request rendered with the
  service's identity and its derived ids, and MUST NOT roll the service out until the provider's
  status reports the grant made. Neither the operator nor the control plane MUST hold
  `setIamPolicy` on the Google Cloud project or any Google credential; the power to grant is
  owner-equivalent and belongs to the provider alone.
- **FR-011**: The grant MUST follow the service's name, not its ServiceAccount's instance, so a
  service deleted and deployed again under the same name reads what it kept, as its database does.
- **FR-012**: The control plane's identity MUST be granted, by the provider, to add and disable
  versions of project secrets and MUST NOT be granted to read one, as its Kubernetes grant today has
  no `get`. The control plane's and the provider's own Google identities MUST come from Workload
  Identity, never a key file.

**Audit**

- **FR-013**: On both backends, every `get` MUST produce a read record holding the secret's name, the
  project, the service, the kind of hosting, the outcome (read, none, refused, unavailable), the
  time, the request and trace ids where known, and the component and its kind where the caller can
  be known (every Scala call; never through the sidecar, which cannot tell which component called),
  and never the value. `put` and `delete` MUST produce a record of the same shape.
- **FR-013a**: The record MUST be written to the control plane's store by the runtime or the sidecar
  over the service's own identity, MUST NOT be kept in the service's database, and MUST survive the
  service's deletion and a restore of its database (041 lists it among the things a restore leaves
  alone). It MUST be listable by an owner by secret name, service and time range, on both backends,
  and MUST be kept for a retention the installation sets (default one year) and then removed. On
  Postgres it is the platform's audit of record, and a real-money installation MAY run its money
  services on that backend.
- **FR-014**: On the Secret Manager backend the platform MUST report, in the installation's status,
  whether Data Access audit logging is on for Secret Manager.

**Project secrets on Secret Manager**

- **FR-015**: On the Secret Manager backend, setting an entry MUST add a version of the secret derived
  for that project secret's entry, and removing an entry MUST disable its versions; the control
  plane's record and its events MUST be exactly those of feature 023, with no field that could carry
  a value.
- **FR-016**: A descriptor's `secretKeyRef` MUST keep working unchanged on both backends, and a service
  whose variable is taken from an entry MUST start with the entry's value.
- **FR-016a**: On the Secret Manager backend, the cloud provider MUST keep each project's Kubernetes
  Secret in step with the latest enabled version of each of its entries in Secret Manager: an entry
  set or removed MUST be reflected in the Kubernetes Secret within one minute, and the operator MUST
  NOT roll out a service of that project until the provider's status reports the entry synced. The
  provider's Google identity MUST be granted read on the derived ids of project secrets only, never
  on a service secret.
- **FR-016b**: The documentation and the installation's status MUST state plainly that on the Secret
  Manager backend a project secret's value is also held in the cluster's Secret store, and that a
  service's read of it at pod start is the kubelet's and is not recorded per read by Secret Manager;
  the per-read record of FR-013 covers service secrets.

**Moving an installation**

- **FR-017**: A service started on the Secret Manager backend with the move turned on MUST copy every
  row of its `ankka_secrets` table into Secret Manager, decrypting with its own secret key, before it
  reports ready, and MUST NOT overwrite a secret already in Secret Manager.
- **FR-018**: The platform MUST offer a copy check per service and per project that compares each
  name's value across the backends by a digest and reports equal, different or missing, and never a
  value.
- **FR-019**: The platform MUST offer a removal step that deletes a service's `ankka_secrets` rows only
  when its last copy check reported every name equal.
- **FR-020**: The platform MUST copy every entry of every project secret from Kubernetes Secrets into
  Secret Manager without the value passing through the control plane's journal, logs, responses or a
  person's terminal.
- **FR-021**: The platform MUST refuse to switch an installation's backend while any service's service
  secrets are held only in the backend being left, naming the services.

**Local and test**

- **FR-022**: A local run and `AnkkaTestKit` MUST need no Google Cloud project, credential or network.
- **FR-023**: The test kit MUST offer a Secret Manager fake, in process, that enforces the identity and
  name grants of FR-010 and FR-012, so the scenarios of User Stories 1, 2 and 4 run offline.

**Documentation**

- **FR-024**: The platform pages MUST describe choosing a backend, the Google Cloud prerequisites
  (Workload Identity Federation, the Secret Manager API, Data Access audit logging, and the cloud
  provider of 044 with the power it holds), the move and its check, the kept-version count, and the
  read record and its retention; the configuration reference MUST list the new settings; the
  limitations page MUST state what remains unsupported.

### Key Entities

- **Secret backend**: where an installation keeps service secrets and project secrets: `postgres`
  (feature 023) or `secret-manager`. One per installation.
- **Derived secret id**: the Secret Manager id of one service secret or one project secret entry,
  from the project, the service or project secret, and the name; the original name kept on it.
- **Grant**: the right of one service's identity, or the control plane's, to act on the derived ids
  of that service or project; requested by the operator, written by the cloud provider (044).
- **Read record**: one read, write or removal of a secret: name, project, service, hosting, outcome,
  time, request and trace ids where known, component where known; never a value. In the control
  plane's store, kept for the installation's retention, listable by an owner.
- **Kept count**: how many versions of a service secret the platform keeps; the rest are destroyed.
- **Project secret sync**: the cloud provider's copy of a project secret's entries from Secret Manager
  into the project's Kubernetes Secret, so `secretKeyRef` resolves unchanged.
- **Copy check**: per service or project, each name and whether the two backends hold equal values.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every service secret scenario of feature 023 that does not name the database passes on
  both backends, in every language, with no change to a service's code or descriptor.
- **SC-002**: With one service's identity, an access to any other service's or project's secret is
  refused by Google Cloud in 100% of attempts in the GKE suite, and the attempts appear in
  the audit log.
- **SC-003**: A search of a Secret Manager service's pod, its namespace's Secrets, its descriptor and
  the operator's Secrets finds zero Google credentials or key files, and the operator's and the
  control plane's Google identities hold no `setIamPolicy` on the Google Cloud project.
- **SC-004**: For every read in a test run, exactly one read record exists in the control plane's
  store, it is still there after the service's database is dropped, and a search of every record,
  log line and event for every value used finds it zero times.
- **SC-008**: A secret put 100 times holds no more than the kept count of versions one minute later,
  and every read in that minute returned the newest value.
- **SC-005**: A service with 100 service secrets moves to Secret Manager with every copy check equal,
  and no person, log, event or status holds a value at any point.
- **SC-006**: The secret store suites pass offline, with no Google credentials, in the same time
  budget as today plus no more than 10%.
- **SC-007**: A value put on one instance is read by a get on any other instance within one second on
  both backends, with no restart.

## Assumptions

- The installation runs on GKE with Workload Identity Federation for GKE enabled on the cluster and
  node pools; the Secret Manager backend is not offered elsewhere in this feature.
- Grants are written by the cloud provider of 044-cloud-provider when the operator requests them, and
  by nothing else. The power to write them, `setIamPolicy` on the Google Cloud project, is
  owner-equivalent; this feature does not pretend it is narrower, which is why it is not the
  operator's. 044 defines the request, its status and the provider's own identity.
- **Research before planning**: whether an IAM condition on `resource.name` limits
  `secretmanager.secrets.create` and `secretmanager.versions.access` to a service's own derived ids
  — including for a secret that does not exist yet — is the whole isolation design and is not yet
  verified. If it does not hold, the fallback is one secret prefix per service that the provider
  enforces when it creates the secret, with the service granted only on secrets the provider created
  for it, and User Story 2 is re-read against that.
- Secret Manager's access quota per Google Cloud project is high enough for a service that reads a
  credential per outbound call; an installation that exceeds it is a sizing problem, not a reason
  to cache.
- The Postgres backend, its key and its table stay exactly as feature 023 built them.
- Project secrets on the Secret Manager backend are synced into Kubernetes Secrets by the cloud
  provider, not by GKE's Secret Manager add-on. The add-on is a cluster feature the installation
  would have to enable and version separately, its sync to Kubernetes Secrets is not available on
  every GKE release ankka supports, and it would leave a second controller deciding when a value
  changes; the provider already holds the Google identity the sync needs, and the operator orders
  its rollouts after the provider's status.
- A restore of a service's database under 041 rolls its Postgres-backend secrets back to the restore
  point, and 041 lists the names of secrets changed since; on the Secret Manager backend a restore
  does not touch secrets. The read record is in neither place a restore rewinds.
- Secret Manager encrypts at rest with Google-managed keys unless the installation names a KMS key
  in 044's settings, which this feature passes through and does not otherwise know.

## Dependencies

- Builds on 023-secret-store (the trait, the rules, project secrets, `ProjectSecretWriter`) and
  022-service-identity (a service's identity in the cluster).
- Depends on 044-cloud-provider for the installation's cloud settings, the grant request and its
  status, the provider's identity, and the project secret sync.
- Shares the "one per installation, a second implementation of a seam" shape with
  039-gcs-object-storage, written alongside it; both hand their Google provisioning to 044.
- Named by 041-postgres-backup-recovery: a Postgres-backend restore rolls service secrets back and
  lists the names changed; the read record is left alone.
- Answers the eitheror plan's open question on whether the Postgres-backed store satisfies the PSPs
  and the regulator: with this feature's read record it does, and Secret Manager is chosen for key
  custody and IAM, not because Postgres is refused.

## Open Questions

- Rotation of the Postgres backend's secret key remains its own feature; after a move it is moot for
  that installation.
- Whether a project secret should also be mountable as a file, from Secret Manager, for programs that
  read credentials from paths (023's open question, unchanged).
- Whether the read record is shown in the console beside a service's secrets.
- Whether the kept count should be per secret as well as per installation; nothing needs it yet.
