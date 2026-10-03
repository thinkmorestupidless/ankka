# Feature Specification: Secret Store — Values a Service Holds Without Ever Journaling Them

**Feature Branch**: `023-secret-store`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "Secret store (core feature C2). A service needs two kinds of secret
it cannot have today. First, a static secret a developer sets once and references from the
descriptor, without `kubectl`: `ankka projects secrets set <name> <key>=<value>` writes a Secret
into the project's namespace through the control plane, which never reads it back, and the
descriptor references it with the `secretKeyRef` that already works. Second, a value a service
creates and reads at runtime, such as a credential an operator enters through the service's own
API, which must never enter a journal, a snapshot or a view: a `SecretStore` API in the runtime,
`put` and `get` by name, backed by a table in the service's own database, encrypted under a
per-service key the operator renders and injects as `ANKKA_SECRET_KEY`. Consolidate the three
duplicated lists of platform-reserved and sidecar-routed variable prefixes into one. Out of scope:
rotation of the per-service key, an external secret manager, and secrets shared between
services."

## Context

A deployed service receives its environment from its descriptor. An `EnvVar` in
`controlplane-api/.../api/descriptors.scala` is a name with either a literal value or a
`SecretKeyRef(name, key)`, and exactly one of the two; the control plane projects it onto the
resource as `EnvEntry(name, value, secretName, secretKey)` and the operator renders a reference as
`valueFrom.secretKeyRef` (`operator/.../Rendering.scala`, `environment`). The operator never reads
the value; the kubelet resolves it when the pod starts. So a static secret already reaches a pod
without ever passing through the control plane or the operator. What is missing is a way to put
the Secret there. Nothing in the CLI creates one; a developer uses `kubectl` against the project's
namespace, which needs cluster credentials the platform exists to make unnecessary, and which a
deploy token in a CI job cannot do at all.

The platform already writes two Secrets of its own, under one rule. The control plane writes a
project's registry credential as a `kubernetes.io/dockerconfigjson` Secret through
`Fabric8AnkkaServiceClient.ensurePullSecret`, with a grant of `create` and `patch` and no `get`,
`list` or `delete`, and records in its journal only that the Secret exists and where. The operator
writes a service's database connection Secret the same way, and since feature 014 that Secret holds
no password at all, the service authenticating by certificate. `EventCompatibilitySuite` asserts
that the registry event's wire form has no `password` field. The rule is that a credential goes to
the cluster, the journal records that it exists, and nothing the platform persists can leak it.

A runtime secret has no home at all. The domain plan's first example is a payment provider's
credential, entered per merchant by an operator through the service's backoffice, and today's
platform stores it as an entity's state, which puts it in the journal, in snapshots, in every view
built from them, and in every backup of any of those. A key value entity is the obvious place and
the wrong one for exactly that reason. The runtime has no secret API, no Kubernetes client, and no
key. The three lists that decide which variables are the platform's are also duplicated with no
shared source: `ServiceSpec.SidecarEnvPrefixes` in `controlplane-api`, `Rendering.SidecarEnvPrefixes`
in the operator, and `HostImports.ReservedPrefixes` in the sidecar's wasm host. The operator's
copy says it mirrors the API's. Adding a prefix means remembering three places, and the Kafka
variable missing from one of them is already a defect carried by 027-managed-broker.

Three decisions shape this feature.

- **Static secrets reuse what exists.** A new control plane route and CLI command write a Secret
  into the project's namespace under the registry credential's grant and record only that it
  exists. The descriptor references it with `secretKeyRef`, which needs no change. A deploy token
  can do this, because it is a member.
- **Runtime secrets live in the service's database, encrypted, and are not an entity.** A
  `SecretStore` in the runtime keeps a table of names to ciphertexts in the service's own Postgres,
  encrypted under a key the operator renders into a Secret and injects as `ANKKA_SECRET_KEY`, routed
  to the sidecar for a process-hosted service and withheld from a wasm module's `config` import.
  The store is not an entity, a view or anything a projection reads: nothing it holds is
  journaled, snapshotted or replayed. A dump of every journal, snapshot and view table contains no
  secret value. A service that sets `ANKKA_SECRET_KEY` itself supplies its own key, as a service
  that sets `ANKKA_DB_*` supplies its own database, and the operator renders none.
- **One declaration of the platform's variables.** The prefix and name lists move to one place in
  `controlplane-api`, which both the operator and the sidecar read, so a new platform variable is
  added once.

What this feature is not: a secret manager with versions, leases or audit of reads; a way to share
a secret between services, which cross the boundary by HTTP as everything else does; rotation of
the per-service key, which needs re-encryption and is a feature of its own; and a mount of the
Secret as files, which `secretKeyRef` into the environment already makes unnecessary for a value a
process reads at start.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A runtime secret is stored and never journaled (Priority: P1)

A developer's service has an endpoint through which an operator enters a credential for an
external provider. The handler calls `secrets.put("provider/acme", value)`. Later a workflow step
calls `secrets.get("provider/acme")` and uses the value to call the provider. The developer dumps
the service's database and finds the value in exactly one place, encrypted. They restart the
service and the value is still there.

**Why this priority**: This is the half that does not exist in any form, and the domain plan's
deposit stage cannot hold a provider credential without it.

**Independent Test**: Through `AnkkaTestKit` against a throwaway Postgres with a test key: put a
value, dump every table, assert the plaintext appears nowhere and the secrets table holds one row
for the name whose ciphertext is not the plaintext; `restartService()`, get, assert equality.

**Acceptance Scenarios**:

1. **Given** a service with a secret key configured, **When** a handler puts a value under a
   name, **Then** a later get of that name from any component returns the value.
2. **Given** a put value, **When** every table of the service's database is read, **Then** the
   plaintext appears in no row of the journal, snapshot, durable state, timer or view tables, and
   the secrets table holds a row for the name whose stored bytes differ from the plaintext.
3. **Given** a put value, **When** the service is restarted so every entity is dropped from
   memory, **Then** a get returns the value.
4. **Given** a put value, **When** another instance of the same service, sharing the database,
   gets the name, **Then** it returns the value.
5. **Given** a name that was never put, **When** it is got, **Then** the answer is absent, not an
   error and not an empty string.
6. **Given** a put value, **When** the same name is put again, **Then** a get returns the newer
   value and the table holds one row for the name.
7. **Given** a put value, **When** it is deleted by name, **Then** a get returns absent and the
   row is gone.
8. **Given** a service with no secret key configured, **When** a handler puts or gets,
   **Then** the call fails naming the variable to configure, and the service started normally.
9. **Given** the secrets table's ciphertext and a different key, **When** decryption is
   attempted, **Then** it fails; the ciphertext is useless without the service's key.

---

### User Story 2 - A process-hosted or wasm service uses the store (Priority: P1)

A Python service's handler puts and gets a secret through the SDK's client; the sidecar does the
encryption and the database write, and the process never holds the key. A Rust module does the
same through an import. The app container of a process-hosted service does not receive
`ANKKA_SECRET_KEY`, and a wasm module asking `config` for it is answered absent.

**Why this priority**: Two of the domain plan's services that hold credentials are not Scala.

**Independent Test**: Conformance cases for put, get, absent and overwrite run against the Scala
reference and each SDK's process; the operator's rendering suite asserts the key's placement;
`WasmHostSuite` asserts the config import withholds it.

**Acceptance Scenarios**:

1. **Given** a Python service with the sidecar configured with a key, **When** a handler puts
   and gets through the client, **Then** the value round-trips and the sidecar's database holds
   the ciphertext.
2. **Given** a process-hosted service's rendered pod, **When** its containers' environments are
   read, **Then** `ANKKA_SECRET_KEY` is on the sidecar container and absent from the app
   container.
3. **Given** a wasm module, **When** it reads `config("ANKKA_SECRET_KEY")`, **Then** the answer is
   absent, and `put` and `get` through the import work.
4. **Given** a TypeScript service, **When** the same cases run, **Then** the same answers are
   given.

---

### User Story 3 - A static secret is set without cluster credentials (Priority: P2)

A developer runs `ankka projects secrets set checkout STRIPE_KEY=sk_live_...`, then references it
in the descriptor as an env entry with `secretKeyRef: {name: checkout, key: STRIPE_KEY}` and
applies. The pod starts with the value in its environment. The control plane never read the value
back, and cannot: its grant does not include `get`.

**Why this priority**: It closes a gap in the existing `secretKeyRef` path with the smallest
change, and a deploy token in CI needs it to set anything at all.

**Independent Test**: In the k3s suite that mints a token for the control plane's own
ServiceAccount, assert `get` on the written Secret is refused by the API server; in the control
plane HTTP suite, assert the journal event carries the name and keys and no value.

**Acceptance Scenarios**:

1. **Given** a project, **When** a member sets a secret with one key through the CLI, **Then**
   a Secret of that name exists in the project's namespace holding that key.
2. **Given** that Secret, **When** a descriptor references it and is applied, **Then** the
   service's pod starts with the value in the named variable.
3. **Given** a set secret, **When** the control plane's journal is read, **Then** the event
   records the project, the Secret's name and its keys, and the wire form has no field carrying a
   value, which `EventCompatibilitySuite` asserts by absence.
4. **Given** the control plane's ServiceAccount, **When** it attempts to read the Secret back,
   **Then** the API server refuses the read.
5. **Given** a set secret, **When** it is set again with the same key and a new value, **Then**
   the Secret is patched and a restarted pod sees the new value.
6. **Given** a non-member, **When** they set a secret in the project, **Then** the answer is 404,
   as for everything in a project they cannot see.
7. **Given** a deploy token, **When** it sets a secret, **Then** it succeeds, since the token is a
   member.
8. **Given** a project with secrets, **When** a member lists them, **Then** the listing shows
   names and keys and never values.

---

### User Story 4 - The platform's variables are declared once (Priority: P2)

A developer adding a platform variable adds it to one list in `controlplane-api`, and the operator's
routing to the sidecar, the control plane's refusal of it in a descriptor, and the wasm host's
withholding all follow. A test holds the three consumers to the one list.

**Why this priority**: Three lists have already diverged once; this feature adds a fourth
variable to them and should be the last to do it by hand.

**Independent Test**: A suite asserts that the operator's routing and the sidecar's reserved set
are the one list, by reading them from it rather than comparing literals.

**Acceptance Scenarios**:

1. **Given** the operator and the sidecar, **When** their prefix lists are read, **Then** they are
   the declaration in `controlplane-api`, not copies.
2. **Given** a new prefix added to the declaration, **When** the operator renders a process-hosted
   pod and the wasm host answers `config`, **Then** both honour it with no other change.

---

### Edge Cases

- **A value larger than a row should hold.** A size cap, refused at `put` with the cap named; a
  secret is a credential, not a document.
- **A `get` from an entity command handler.** It is a blocking database read inside the entity's
  single-writer path. [NEEDS CLARIFICATION: whether to allow it, refuse it, or allow it with a
  documented cost]
- **The key is changed under a running service.** Every `get` fails to decrypt and reports it
  as a wrong key, never as a missing secret; rotation is out of scope and this is the failure a
  careless rotation produces.
- **A static Secret the descriptor references does not exist.** The pod does not start, which
  Kubernetes reports; the control plane's status shows the Kubernetes reason as it does for any
  pod that cannot start.
- **A static secret named like a platform variable.** The descriptor's env check refuses it as it
  refuses a literal of that name.
- **A secret name with a slash or a space.** Names are a bounded character set; others are
  refused at `put` naming the rule.
- **Two instances put the same name at once.** Last write wins, as for an upsert; the store makes
  no ordering promise beyond that.
- **A `put` inside a workflow step that is retried.** The second `put` overwrites with the same
  value; `put` is idempotent for equal values.
- **A dump of the database restored elsewhere with a different key.** Every secret is
  unreadable, by design; the key is part of the service's identity, not its data.

## Requirements *(mandatory)*

### Functional Requirements

**Runtime store**

- **FR-001**: The runtime MUST offer a `SecretStore` with `put(name, value)`, `get(name)` and
  `delete(name)`, available to every component context that has the component client.
- **FR-002**: Values MUST be stored in a table in the service's own database, encrypted under a
  key the service holds as `ANKKA_SECRET_KEY`, with a DDL file added to the canonical schema
  directory and to no other copy, additive so the compatibility rule holds.
- **FR-003**: The store MUST NOT be an entity, a view or a source any projection reads; no value
  it holds MUST appear in the journal, snapshot, durable state, timer or view tables.
- **FR-004**: A service with no key configured MUST start, and MUST fail a `put` or `get` with a
  message naming the variable.
- **FR-005**: The store MUST be reachable from Python and TypeScript through new `GetSecret`,
  `PutSecret` and `DeleteSecret` calls on the sidecar protocol's `Client` service, a minor version
  bump, and from Rust through imports of the same names in the `ankka1` module.

**Key provisioning**

- **FR-006**: The operator MUST render a per-service key into a Secret it writes and never reads
  back, and inject it as `ANKKA_SECRET_KEY`, when the descriptor does not set it itself.
- **FR-007**: `ANKKA_SECRET_KEY` MUST be routed to the sidecar container for a process-hosted
  service, absent from the app container, and withheld from a wasm module's `config` import.
- **FR-008**: A service that sets `ANKKA_SECRET_KEY` in its descriptor MUST get no rendered key,
  as a service setting `ANKKA_DB_*` gets no database.

**Static secrets**

- **FR-009**: The control plane MUST offer a route, and the CLI a command, to set a named Secret
  with one or more keys in a project's namespace, under a grant of `create` and `patch` only,
  through a one-method writer in the shape of `RegistryWriter`.
- **FR-010**: The journal event for a set secret MUST carry the project, the name and the keys
  and MUST have no field that could carry a value, asserted by `EventCompatibilitySuite`.
- **FR-011**: The control plane MUST offer a listing of a project's secrets by name and keys.
- **FR-012**: Membership rules MUST apply unchanged: a non-member sees 404, a member and a deploy
  token may set and list.

**One declaration**

- **FR-013**: The platform-reserved variable names and prefixes, and the sidecar-routed prefixes,
  MUST be declared once in `controlplane-api` and read from there by the operator and the
  sidecar, with a test that holds them to it.

**Documentation**

- **FR-014**: A build page MUST describe the store and its rule; the platform page on databases
  or a sibling MUST describe the key; the CLI reference and control plane routes pages MUST be
  regenerated; the configuration reference MUST list the variable.

### Key Entities

- **Secret row**: a name, a ciphertext, an updated-at; one per name per service.
- **Project secret record**: a project, a Secret name, its keys; held by the control plane with
  no value.
- **Per-service key**: rendered by the operator into a Secret named for the service, injected as
  one variable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A database dump of a service that has stored a secret contains the plaintext zero
  times.
- **SC-002**: A stored secret survives a restart and is readable from every instance.
- **SC-003**: A static secret can be set and referenced by a member or a deploy token with no
  cluster credential, and the control plane's ServiceAccount is refused a read of it by the API
  server.
- **SC-004**: Adding a platform variable prefix is one edit, verified by a test that reads the
  consumers from the declaration.
- **SC-005**: Python, TypeScript and Rust services store and read secrets through the same
  conformance cases as Scala.

## Assumptions

- Symmetric authenticated encryption with a random nonce per value; the algorithm is an
  implementation choice and not a wire contract.
- The per-service key is generated by the operator once and kept with reclaim semantics like the
  database: deleting the service does not delete it, so a re-applied service name reads its
  secrets again.
- A local run supplies `ANKKA_SECRET_KEY` from the developer's environment or the test kit
  generates one.
- The static-secret route follows the registry route's shape, including the write-cluster-first
  rule: a cluster that refuses is a 503 and records nothing.

## Dependencies

- Gates stage 2 of the domain plan, deposits, with 025-polyglot-service-client.
- Shares the one-declaration change with 027-managed-broker, which adds the Kafka prefix to it.
- Depends on nothing in this series.

## Open Questions

- Rotation of the per-service key: in scope later, needing re-encryption on read; out of scope
  here.
- Whether `secrets.get` is permitted from an entity command handler, per the edge case.
- Whether a static Secret should also be mountable as a file for programs that read credentials
  from paths.
- Whether the listing of project secrets belongs in the console in this feature or later.
