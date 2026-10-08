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
  and `patch`; it never lists or reads a credential Secret. The operator's grant on Secrets today
  also has `get`, which the database credential path uses; removing it is a separate change that
  lands before this feature, so the storage credential is written without a read from the start.
- **The variables go to the app container.** For process hosting the runtime does not use them,
  so they belong to the process, unlike `ANKKA_DB_*` which goes to the sidecar. For wasm hosting
  they are answered by the `config` import, since a module reaching a bucket would do so through
  feature 030's outbound call and needs to know where. For web hosting they go to the process and
  never to the proxy: a web-hosted service has no database, and may still ask for a bucket. The sidecar prefix list and the wasm
  reserved list are the shared declaration feature 023 introduces.
- **Supplying your own opts out.** A descriptor that names any `ANKKA_S3_*` variable gets no
  provisioning, as with `ANKKA_DB_*`. A descriptor that both sets `provisionObjectStorage` and
  names one is refused at apply with the reason.
- **Nothing is deleted by the platform.** Deleting the service leaves the bucket and its objects.
  Re-applying the name recovers them and the status reports `recovered`, as the database does.
- **A bucket is private until its descriptor asks.** `exposeObjectStorage: true`, beside
  `provisionObjectStorage`, makes the service's bucket reachable from a browser so the service can
  issue presigned URLs. The store answers at one hostname the platform derives for the
  installation, one label under the base domain so the wildcard certificate covers it, with the
  bucket in the path: a wildcard is one label deep, so there is no hostname per bucket. The
  gateway routes only the paths of buckets whose services asked, and those services alone receive
  `ANKKA_S3_PUBLIC_ENDPOINT`. The gateway is the caller of every such request; the store decides
  by the signature, and answers nothing unsigned.
- **The store is an installation component.** Garage under `kustomization/components/`, with a
  policy admitting every workload namespace, and a bucket and an access key created per service
  by the operator through Garage's administration API. Both overlays list it. The operator
  reaches the store through one seam, so a cloud provider's buckets are a second implementation
  of it in a later feature.

What this feature is not: an SDK storage client, since every language has an S3 client and the
platform gains nothing by wrapping one; a CDN, or objects readable without a signature; lifecycle rules,
versioning or replication on the bucket; or a change to how databases are provisioned.

## Clarifications

### Session 2026-10-04

- Q: The operator's ClusterRole grants `get` on every Secret today, which contradicts SC-003. How is that resolved? → A: SC-003 stays as written. Removing `get` on Secrets from the operator is a separate change that lands before this feature.
- Q: A descriptor in the same project can take a variable from any Secret by name, and a member can name a project secret anything. Is a storage credential protected from both? → A: Yes. A descriptor may not take a variable from any `<service>-storage` Secret, its own included, and a project secret may not be named `<service>-storage`.
- Q: FR-005 says a service with its own store is reported as supplied and FR-007 says the status is absent when supplied; the spec also calls the working phase "ready" where the database says `Provisioned`. Which holds? → A: The database's phases exactly: `Waiting`, `Provisioned`, `Recovered`, `Supplied`, `Failed`. A service with its own store reports `Supplied`; the status is absent only when the service neither asks for a bucket nor gives an `ANKKA_S3_*` variable.
- Q: Which of web-hosted services, a store endpoint reachable from a browser for presigned URLs, and the console showing the bucket are in scope? → A: All three. A web-hosted service may ask for a bucket; the store is reachable from a browser for presigned URLs; the console shows a service's bucket beside its database.
- Q: Which buckets are reachable from a browser at the store's public hostname? → A: Only a bucket whose descriptor asks, with a second field that is off by default. The platform routes only that bucket's path and gives only that service the public address.

### Amended in planning, 2026-10-04

Planning found five things the spec did not have. Each is decided in [research.md](research.md)
and the text below was changed to match.

- **The store is Garage, not MinIO** (R1). MinIO's community edition was archived in April 2026
  and its images were removed from Docker Hub in September 2026. FR-008 and the assumptions name
  Garage.
- **A fifth variable, `ANKKA_S3_REGION`** (R9). An S3 client signs for a region and the store
  refuses a signature made for another, so "any S3 client works" needs the region said. FR-004.
- **`ANKKA_S3_` is declared once and is not a reserved prefix** (R10). The platform's shared
  lists name variables kept *from* the developer's program; these are *for* it. FR-009.
- **A service owns its bucket's settings** (R16). A browser page that uploads through a presigned
  URL needs a CORS rule on the bucket, which the service sets with its own S3 client. FR-017.
- **Traffic to the store inside the cluster is not encrypted** (R17). Garage serves no TLS, and a
  certificate from the platform's authority would have to be configured in every S3 client. It
  is recorded as a limitation. FR-018.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service asks for a bucket and gets one it can use (Priority: P1)

A developer sets `provisionObjectStorage: true` and deploys. The service's pod starts with the
five variables set, and the service's code puts and gets an object with any S3 client. The
status reports the storage as `Provisioned`.

**Why this priority**: This is the feature. Nothing else in it matters if the bucket is not there
or the credential does not work.

**Independent Test**: In the k3s suite with the object store component installed, deploy a service with
the flag, run a put and a get from inside its pod with the injected credential, and assert the
object round-trips. Assert the status reports the storage phase as `Provisioned`.

**Acceptance Scenarios**:

- added `features/object-storage/provisioning.feature`: a service that asks for a bucket is given one
- added `features/object-storage/provisioning.feature`: a service keeps an object in its bucket and reads it back
- added `features/object-storage/provisioning.feature`: the status of a service names its bucket
- added `features/object-storage/provisioning.feature`: a service that asks for no bucket is given none
- added `features/object-storage/hostings.feature`: the variables of a bucket are given to the process and not to the platform's own program
- added `features/object-storage/hostings.feature`: a module that asks for a variable of its bucket is told its value
- added `features/web-hosting/object-storage.feature`: the variables of a bucket are given to the process of a web-hosted service and not to its proxy
- added `features/web-hosting/object-storage.feature`: a web-hosted service keeps an object in its bucket and reads it back
- added `features/object-storage/console.feature`: the console shows what a service has for object storage beside its database
- added `features/object-storage/provisioning.feature`: a bucket the object store cannot make yet is reported as still being made
- added `features/object-storage/provisioning.feature`: a descriptor that asks for a bucket is refused when the service's name cannot name one

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

- added `features/object-storage/isolation.feature`: a storage credential is refused by another service's bucket
- added `features/object-storage/isolation.feature`: the platform cannot read a storage credential back
- added `features/object-storage/isolation.feature`: a storage credential is made once and replaced only when a member asks
- added `features/object-storage/isolation.feature`: a descriptor cannot take a variable from the secret that holds a storage credential
- changed `features/secrets/project-secrets.feature`: a project secret may not take a name the platform uses

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

- added `features/object-storage/kept.feature`: deleting a service keeps its bucket and its objects
- added `features/object-storage/kept.feature`: a service deleted and deployed again reads the objects it kept before
- added `features/object-storage/own-object-store.feature`: a service with an object store of its own is given no bucket
- added `features/object-storage/own-object-store.feature`: a descriptor cannot both ask for a bucket and give a variable of an object store of its own
- added `features/object-storage/provisioning.feature`: a service that asks for a bucket is not started when the installation has no object store

---

### User Story 4 - A browser reaches one object through a URL the service signed (Priority: P2)

A developer sets `exposeObjectStorage: true` beside `provisionObjectStorage`. The service is
given the public address of its bucket, signs a URL for one object against it, and hands the URL
to a browser, which downloads or uploads that object without ever holding the credential. A
bucket whose descriptor does not ask is not reachable from outside the cluster at all.

**Why this priority**: Uploads from a browser and downloads of large exports should not pass
through the service's own instances. It is P2 because a service can proxy the bytes itself
until this exists, and because the default it adds nothing to is the safe one.

**Independent Test**: In the k3s suite, deploy a service with both fields, sign a GET and a PUT
URL from inside its pod against `ANKKA_S3_PUBLIC_ENDPOINT`, and use both from the host with
`curl` through the gateway's mapped port. Deploy a second service with a bucket and without the
field and assert its bucket's path answers nothing at the hostname.

**Acceptance Scenarios**:

- added `features/object-storage/reachable.feature`: a service whose bucket is reachable from the internet is told the address of its bucket on the internet
- added `features/object-storage/reachable.feature`: a browser reads an object through a signed URL
- added `features/object-storage/reachable.feature`: a browser on an origin the descriptor names keeps an object through a signed URL without the service setting a rule on its bucket
- added `features/object-storage/reachable.feature`: a bucket is not reachable from the internet until its descriptor asks
- added `features/object-storage/reachable.feature`: a request from the internet without a signed URL is refused
- added `features/object-storage/reachable.feature`: a signed URL stops working when the bucket is no longer reachable from the internet
- added `features/object-storage/reachable.feature`: only a bucket the platform made can be made reachable from the internet

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
- A presigned URL signed against the in-cluster endpoint: it does not work from a browser. A
  signature covers the address it was made for, so a service signs against
  `ANKKA_S3_PUBLIC_ENDPOINT` for a browser and uses `ANKKA_S3_ENDPOINT` for its own reads and
  writes; the docs say so.
- A service that stops asking for its bucket to be reachable: the route and the variable go, and
  URLs it issued before stop working, whatever their expiry.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The descriptor MUST accept `provisionObjectStorage: true`, the control plane MUST
  project it onto the resource, and `AnkkaServiceSpec` and `ankkaservice.yaml` MUST declare it
  together.
- **FR-002**: The operator MUST create one bucket per service named from the project and the
  service, and one credential scoped to that bucket.
- **FR-003**: The operator MUST write the credential to a Secret it creates and patches and never
  reads, lists or deletes, and MUST NOT rewrite a credential Secret that already exists.
- **FR-004**: The workload MUST receive `ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION`, `ANKKA_S3_BUCKET`,
  `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`: on the app container for process hosting, on
  the process's container and never the proxy's for web hosting, and through the `config` import
  for wasm hosting. A web-hosted descriptor MUST be allowed to set `provisionObjectStorage`.
- **FR-005**: A descriptor that names any `ANKKA_S3_*` variable MUST get no provisioning and MUST
  be reported as supplied; one that also sets the flag MUST be refused at apply.
- **FR-006**: Deleting a service MUST leave its bucket and objects, and re-applying the name MUST
  recover them and report so.
- **FR-007**: The status MUST report object storage with the database's phases, `Waiting`,
  `Provisioned`, `Recovered`, `Supplied` and `Failed`, and MUST be absent only for a service that
  neither asks for a bucket nor gives an `ANKKA_S3_*` variable.
- **FR-008**: A Garage component MUST be added under `kustomization/components/` with a network
  policy admitting workload namespaces and the gateway to its S3 port and the operator alone to
  its administration port, and listed in both overlays. The operator MUST reach the store through
  one interface, so that another store is a second implementation of it.
- **FR-009**: `ServiceSpec.problems` MUST refuse the flag beside an `ANKKA_S3_*` variable. The
  prefix MUST be declared once, in `PlatformVariables`, and read from there by the descriptor's
  rules and the operator; it MUST NOT join the lists of variables kept from the developer's
  program, since these variables are for it.
- **FR-010**: The platform docs MUST gain a page beside the databases page saying what is
  provisioned, what is never deleted, and what is not rotated.

- **FR-011**: `ServiceSpec.problems` MUST refuse a variable taken from a Secret named
  `<service>-storage`, for any service including the descriptor's own, and a project secret MUST
  NOT be given such a name; the storage credential joins the list of Secrets the platform issues.

- **FR-012**: The console's service page MUST show what the service has for object storage beside
  its database: the bucket's name, that it has a store of its own, or that it has none. The local
  console is unchanged: it shows no database either, and a service run on a developer's machine is
  given no bucket.

- **FR-013**: The descriptor MUST accept `exposeObjectStorage: true`, off by default, projected
  and declared as FR-001 requires of `provisionObjectStorage`. `ServiceSpec.problems` MUST refuse
  it on a descriptor that does not set `provisionObjectStorage` or that names an `ANKKA_S3_*`
  variable.
- **FR-014**: For a service that sets it, the platform MUST make the service's bucket reachable
  through the gateway at the store's hostname, which the platform derives as one label under the
  base domain, routing that bucket's path and no other, and MUST give the workload
  `ANKKA_S3_PUBLIC_ENDPOINT` where FR-004 puts the other five. A service that does not set it
  MUST NOT receive the variable, and its bucket's path MUST NOT be routed.
- **FR-015**: The store MUST refuse a request from outside the cluster that carries no valid
  signature; no bucket is readable anonymously.
- **FR-016**: The status MUST show the bucket's public address when the bucket is reachable from
  outside the cluster, and the console MUST show it with the bucket.

- **FR-017**: The storage credential MUST own its bucket's settings, so that a service can set a
  CORS rule or a lifecycle rule on its own bucket with its S3 client. The platform sets neither.
- **FR-018**: The docs' limitations page MUST say that traffic between a workload and the store
  inside the cluster is not encrypted, and that a request from outside the cluster is encrypted
  as far as the gateway.

### Key Entities

- **Bucket**: one per service, named from project and service, never deleted by the platform.
- **Storage credential**: an access key and secret scoped to one bucket, held in a Secret the
  operator writes once.
- **Public address**: where a bucket whose descriptor asked is reached from a browser; the
  store's one hostname and the bucket's path. Derived by the platform; nobody chooses it.
- **Storage status**: phase (`Waiting`, `Provisioned`, `Recovered`, `Supplied`, `Failed`), bucket
  name, public address when there is one, recovered flag, detail; absent when the service neither
  asks nor supplies.

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
- **SC-005**: `CrdSchemaSuite` fails if either new field is added to either the case class or the
  schema alone.
- **SC-006**: A URL signed inside a pod against the public address downloads and uploads an
  object from the host through the gateway, and the path of a bucket whose descriptor did not ask
  answers nothing there.

## Assumptions

- Garage is the store because it is S3-compatible, runs as one small container on kind, and has
  an administration API the operator can create buckets and keys through. The example cloud
  overlay runs it too; a cloud provider's buckets are a later feature.
- The store addresses a bucket by path, so one hostname serves every bucket and one route per
  exposed bucket separates them.
- The bucket name derivation reuses the rules that derive the database name and user, with the
  store's own limits applied on top.
- The S3 client each service uses is its own dependency. The docs show one tested example and
  the settings any client needs; the SDKs wrap nothing.
- Feature 023's shared declaration of reserved and sidecar-routed prefixes exists by the time this
  is built; if not, the prefix is added to the three lists that exist today.

## Dependencies

- **The operator's grant on Secrets without `get`**: a separate change, landed before this
  feature, that removes `get` from the operator's ClusterRole and reworks the database credential
  path that uses it. A hard dependency: SC-003 cannot hold while the verb is granted, since RBAC
  cannot refuse `get` for some Secret names and allow it for others.
- **023-secret-store** for the shared prefix declaration this feature extends; not a hard
  dependency, since the three existing lists can be extended directly.
- **030-wasm-request-and-clock** for a wasm module to reach the bucket at all; without it the
  variables are answered but unusable from a module.
- Gates the domain plan's stage 4, where KYC documents and exports are stored; stages before it
  can bring their own store through `ANKKA_S3_*`.

## Open Questions

- Resolved in planning (R22): Garage in the cloud overlay too. A cloud provider's buckets are
  reachable at the provider's own address, so "private until the descriptor asks" would rest on
  the provider's policy and not on a route the platform renders; that is a feature of its own.
- Resolved in planning (R12): a project secret that already has a name ending `-storage` is
  kept. It cannot be set again or named by a descriptor, and the upgrade notes say so.
