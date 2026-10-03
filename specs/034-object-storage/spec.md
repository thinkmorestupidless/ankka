# Feature Specification: Object Storage — A Bucket Per Service, Provisioned Like a Database

**Feature Branch**: `034-object-storage`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "The platform provisions one kind of backing service, a Postgres
database per service. A service that stores documents, exports or uploads needs a bucket, and
today it brings its own with no help from the platform. Add `provisionObjectStorage` to the
descriptor: when it is true the operator gives the service one bucket and one credential scoped
to it, following the database rule exactly. The operator creates it, writes the credential where
the pod reads it, never reads it back, and deleting the service keeps the bucket. The workload
receives `ANKKA_S3_ENDPOINT`, `ANKKA_S3_BUCKET`, `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`;
the SDK adds no API, any S3 client works. A descriptor that sets any `ANKKA_S3_*` variable itself
supplies its own store. The local installation gets a MinIO component; a cloud installation names
a bucket provider in its overlay. Out of scope: the SDK offering a storage client, a CDN, and
lifecycle rules on the bucket."

## Context

The operator provisions exactly one kind of backing service. `Rendering.databaseActions` in
`operator/.../Rendering.scala` and `CnpgRendering.scala` render a CloudNativePG `Cluster` per
project namespace, and per service a `DatabaseRole`, a `Database`, a credential Secret named
`<service>-db`, a client certificate and a schema ConfigMap. The `Action` set in `Action.scala`
holds the namespace, deployment, service, route, RBAC and status actions, the CNPG objects, the
certificate and issuer, the network policy and the backend TLS policy, and nothing else. The
control plane decides whether to provision: `ServiceProjection.scala` sets
`provisionDatabase` to true unless the descriptor's environment names any `ANKKA_DB_*` variable,
in which case the service supplies its own database and the status reports it as supplied.
`Provisioning.decide` turns what the operator observes into a plan with a phase that the status
reports, and treats CNPG's transient messages as in progress rather than failed. The pod receives
the connection through `envFrom` on the credential Secret and literal TLS variables, and the
runtime cannot tell a provisioned database from a supplied one. Databases are never destroyed:
the CNPG objects carry reclaim `retain`, so a service re-applied under a deleted name recovers
its database and the status says so.

Nothing like this exists for object storage. The `AnkkaServiceSpec` in
`crd/.../AnkkaService.scala` has no field for it, the operator renders nothing for it, no
`ANKKA_*` variable names it, and no kustomization component installs a store. A service that
holds uploaded documents, exports a report to a file, or serves an image brings its own bucket
and its own credential, puts them in the descriptor's environment by hand, and the platform
knows nothing about them. The platform's own rule that a credential is written where the pod
reads it and never read back does not apply to a credential the developer pasted in.

The database model is the right shape because it already answers every question object storage
raises: who creates it, who holds the credential, what happens on deletion, how a service that
has its own opts out, and how the status reports progress. The decisions this feature makes
follow it line by line:

- **One field on the descriptor.** `provisionObjectStorage: true` asks for a bucket. The control
  plane projects it onto the resource. The CRD's case class and `ankkaservice.yaml` change
  together, which `CrdSchemaSuite` holds to each other in both directions, because a field the
  schema does not declare is refused by server-side apply forever.
- **One bucket and one credential per service.** The bucket is named from the project and the
  service, as the database is. The credential is scoped to that bucket and nothing else, so a
  credential leaked from one service reads no other service's objects, in the same project or
  another.
- **The credential is written, never read back.** The operator creates it and puts it in a Secret
  named `<service>-storage`, injected with `envFrom` as the database's is. Its grant is `create`
  and `patch`; it never lists or reads a credential Secret.
- **The variables go to the app container.** For process hosting the runtime does not use them,
  so they belong to the process, unlike `ANKKA_DB_*` which goes to the sidecar. For wasm hosting
  they are answered by the `config` import, since a module reaching a bucket would do so through
  feature 030's outbound call and needs to know where. The sidecar prefix list and the wasm
  reserved list are the shared declaration feature 023 introduces.
- **Supplying your own opts out.** A descriptor that names any `ANKKA_S3_*` variable gets no
  provisioning, as with `ANKKA_DB_*`. A descriptor that both sets `provisionObjectStorage` and
  names one is refused at apply with the reason.
- **Nothing is deleted by the platform.** Deleting the service leaves the bucket and its objects.
  Re-applying the name recovers them and the status reports `recovered`, as the database does.
- **The store is an installation component.** Locally, MinIO under `kustomization/components/`,
  with a zero-trust policy admitting every workload namespace and a bucket and user created per
  service by the operator through MinIO's API or its operator's resources. In the cloud, the
  overlay names a provider, and the operator's rendering for it is a second implementation of
  the same actions. Which cloud provider ships first is an open question below.

What this feature is not: an SDK storage client, since every language has an S3 client and the
platform gains nothing by wrapping one; a CDN or public hosting of objects; lifecycle rules,
versioning or replication on the bucket; or a change to how databases are provisioned.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service asks for a bucket and gets one it can use (Priority: P1)

A developer sets `provisionObjectStorage: true` and deploys. The service's pod starts with the
four variables set, and the service's code puts and gets an object with any S3 client. The
status reports the storage as ready.

**Why this priority**: This is the feature. Nothing else in it matters if the bucket is not there
or the credential does not work.

**Independent Test**: In the k3s suite with the MinIO component installed, deploy a service with
the flag, run a put and a get from inside its pod with the injected credential, and assert the
object round-trips. Assert the status reports the storage phase as ready.

**Acceptance Scenarios**:

1. **Given** a descriptor with `provisionObjectStorage: true`, **When** it is applied, **Then**
   the pod's app container has `ANKKA_S3_ENDPOINT`, `ANKKA_S3_BUCKET`, `ANKKA_S3_ACCESS_KEY` and
   `ANKKA_S3_SECRET_KEY` set, read from a Secret the operator wrote.
2. **Given** that pod, **When** a put and a get are run from inside it with those variables,
   **Then** the object round-trips.
3. **Given** that service, **When** `services get` is read, **Then** the status reports object
   storage with a phase of ready and the bucket's name.
4. **Given** a descriptor without the flag, **When** it is applied, **Then** no bucket is
   created, no `ANKKA_S3_*` variable is set, and the status reports no storage.
5. **Given** a process-hosted service with the flag, **When** it is applied, **Then** the
   variables land on the app container and not on the sidecar.
6. **Given** a wasm-hosted service with the flag, **When** the module asks `config` for
   `ANKKA_S3_BUCKET`, **Then** it is answered.

---

### User Story 2 - A credential reaches one bucket and nothing else (Priority: P1)

Two services in two projects each get a bucket. Each one's credential is refused by the other's
bucket. The operator's ServiceAccount cannot read either credential back.

**Why this priority**: A bucket per service with a credential that reads every bucket is one
shared bucket with extra steps. Isolation is what makes the per-service model true, and the
grant on the credential is the same rule the database and the registry credential follow.

**Independent Test**: In the k3s suite, deploy two services in two projects with the flag, take
each credential from inside its own pod, and attempt the other's bucket; assert the refusal
comes from the store. Mint a token for the operator's ServiceAccount and assert a `get` on a
credential Secret is refused by the API server.

**Acceptance Scenarios**:

1. **Given** two services in two projects with buckets, **When** service A's credential is used
   against service B's bucket from inside A's pod, **Then** the store refuses it.
2. **Given** two services in one project with buckets, **When** one's credential is used against
   the other's bucket, **Then** the store refuses it.
3. **Given** the operator's ServiceAccount, **When** a token minted for it tries to `get` a
   service's storage credential Secret, **Then** the API server refuses it.
4. **Given** a credential Secret that already exists, **When** the operator reconciles again,
   **Then** it does not rewrite the credential, so a running service's credential does not change
   under it.

---

### User Story 3 - Deletion keeps the bucket and a supplied store opts out (Priority: P2)

A member deletes a service and applies it again under the same name. The bucket and its objects
are still there and the status says they were recovered. Another developer sets `ANKKA_S3_*`
variables for a bucket they already own and gets no provisioning.

**Why this priority**: Both halves are the database rule applied; without them the feature is
a different, less safe model than the one it claims to follow.

**Independent Test**: In the k3s suite, put an object, delete the service, apply it again, get
the object, and assert the status reports `recovered`. Deploy a service with its own `ANKKA_S3_*`
variables and assert nothing is created and the status reports the store as supplied.

**Acceptance Scenarios**:

1. **Given** a service with a bucket holding an object, **When** the service is deleted, **Then**
   the bucket and the object remain in the store.
2. **Given** that deleted service, **When** a descriptor with the same name and the flag is
   applied, **Then** the pod reaches the same bucket, the object is readable, and the status
   reports the storage as recovered.
3. **Given** a descriptor that sets `ANKKA_S3_ENDPOINT` and the other three itself, **When** it
   is applied, **Then** no bucket or credential is created and the status reports the storage as
   supplied.
4. **Given** a descriptor that both sets the flag and names an `ANKKA_S3_*` variable, **When**
   it is applied, **Then** it is refused with a message saying the two cannot be combined.
5. **Given** the store is not installed in the cluster, **When** a service asks for a bucket,
   **Then** the status reports the storage phase as failed with a message naming the missing
   component, and the service's pods are not started with empty variables.

---

### Edge Cases

- A service name whose bucket name would exceed the store's name length or character rules: the
  bucket name is derived with the same care as the database name, and a name that cannot be
  derived is refused at apply, naming the limit.
- The store is briefly unavailable while the operator reconciles: the plan reports in progress,
  not failed, following `Provisioning.decide`'s treatment of transient messages.
- A credential rotated by hand in the store: the Secret still holds the old one; the platform
  issues and rotates nothing after creation, and the docs say so, as they do for a supplied
  database.
- An object store that is reachable from pods but not from a browser: a presigned URL the
  service issues points at the in-cluster endpoint and does not work from outside. Whether the
  store's endpoint is exposed through the gateway is an open question below.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The descriptor MUST accept `provisionObjectStorage: true`, the control plane MUST
  project it onto the resource, and `AnkkaServiceSpec` and `ankkaservice.yaml` MUST declare it
  together.
- **FR-002**: The operator MUST create one bucket per service named from the project and the
  service, and one credential scoped to that bucket.
- **FR-003**: The operator MUST write the credential to a Secret it creates and patches and never
  reads, lists or deletes, and MUST NOT rewrite a credential Secret that already exists.
- **FR-004**: The workload MUST receive `ANKKA_S3_ENDPOINT`, `ANKKA_S3_BUCKET`,
  `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`, on the app container for process hosting and
  through the `config` import for wasm hosting.
- **FR-005**: A descriptor that names any `ANKKA_S3_*` variable MUST get no provisioning and MUST
  be reported as supplied; one that also sets the flag MUST be refused at apply.
- **FR-006**: Deleting a service MUST leave its bucket and objects, and re-applying the name MUST
  recover them and report so.
- **FR-007**: The status MUST report an object storage phase in the shape of the database phase,
  absent when supplied.
- **FR-008**: A MinIO component MUST be added under `kustomization/components/` with a zero-trust
  policy admitting workload namespaces, and listed in both overlays, with the cloud overlay able
  to name a different provider.
- **FR-009**: `ServiceSpec.problems` MUST refuse the flag beside an `ANKKA_S3_*` variable, and
  the prefix MUST join the shared reserved-variable declaration.
- **FR-010**: The platform docs MUST gain a page beside the databases page saying what is
  provisioned, what is never deleted, and what is not rotated.

### Key Entities

- **Bucket**: one per service, named from project and service, never deleted by the platform.
- **Storage credential**: an access key and secret scoped to one bucket, held in a Secret the
  operator writes once.
- **Storage status**: phase, bucket name, recovered flag, detail; absent when supplied.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A service with the flag round-trips an object from inside its pod in the k3s suite,
  and a service without the flag has no storage variables.
- **SC-002**: A credential used against another service's bucket is refused by the store in
  both the same-project and cross-project cases.
- **SC-003**: The operator's ServiceAccount is refused a `get` on a storage credential Secret by
  the API server, shown with a minted token, not by inspection of the manifest.
- **SC-004**: An object survives deletion and recreation of its service, and the status reports
  recovery.
- **SC-005**: `CrdSchemaSuite` fails if the new field is added to either the case class or the
  schema alone.

## Assumptions

- MinIO is the local store because it is S3-compatible, runs on kind, and has an API the
  operator can create buckets and users through; the cloud provider is chosen in the plan.
- The bucket name derivation reuses the rules that derive the database name and user, with the
  store's own limits applied on top.
- The S3 client each service uses is its own dependency; the platform's samples show one in each
  language without the SDK wrapping it.
- Feature 023's shared declaration of reserved and sidecar-routed prefixes exists by the time this
  is built; if not, the prefix is added to the three lists that exist today.

## Dependencies

- **023-secret-store** for the shared prefix declaration this feature extends; not a hard
  dependency, since the three existing lists can be extended directly.
- **030-wasm-request-and-clock** for a wasm module to reach the bucket at all; without it the
  variables are answered but unusable from a module.
- Gates the domain plan's stage 4, where KYC documents and exports are stored; stages before it
  can bring their own store through `ANKKA_S3_*`.

## Open Questions

- MinIO on the cloud overlay too, or a cloud bucket provider from the first release? A provider
  means a second rendering and a credential model per cloud; MinIO everywhere means the
  installation runs its own store on cloud disks.
- Presigned URLs need the store's endpoint reachable from a browser. Exposing it through the
  gateway at a platform-derived hostname touches the no-custom-hostnames limitation and the
  gateway's one-caller model. Decide whether this feature exposes it, or leaves presigned URLs
  for a later feature.
- Whether the local console should show a service's bucket beside its database.
