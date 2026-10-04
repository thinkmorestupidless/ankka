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
the value; the kubelet resolves it when the pod starts. So a project secret already reaches a pod
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

A service secret has no home at all. The domain plan's first example is a payment provider's
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

- **Project secrets reuse what exists.** A new control plane route and CLI command write a Secret
  into the project's namespace under the registry credential's grant and record only that it
  exists. The descriptor references it with `secretKeyRef`, which needs no change. A deploy token
  can do this, because it is a member.
- **Service secrets live in the service's database, encrypted, and are not an entity.** A
  `SecretStore` in the runtime keeps a table of names to ciphertexts in the service's own Postgres,
  encrypted under a key the operator renders into a Secret and injects as `ANKKA_SECRET_KEY`, routed
  to the sidecar for a process-hosted service and withheld from a wasm module's `config` import.
  The store is not an entity, a view or anything a projection reads: nothing it holds is
  journaled, snapshotted or replayed. A dump of every journal, snapshot and view table contains no
  secret value. A service that sets `ANKKA_SECRET_KEY` itself supplies its own key, as a service
  that sets `ANKKA_DB_*` supplies its own database, and the operator renders none.
- **One declaration of the platform's variables.** The prefix and name lists move to one place in
  `core`, a single source file with no dependencies. `controlplane-api` and the sidecar read it
  through the dependency on `core` they already have, and the operator compiles the same file, so
  it still depends on `crd` alone. A new platform variable is added once.

What this feature is not: a secret manager with versions, leases or audit of reads; a way to share
a secret between services, which cross the boundary by HTTP as everything else does; rotation of
the per-service key, which needs re-encryption and is a feature of its own; and a mount of the
Secret as files, which `secretKeyRef` into the environment already makes unnecessary for a value a
process reads at start.

## Clarifications

### Session 2026-10-03

- Q: May a service secret be read from an entity command handler, where it is a blocking database
  read on the single-writer path? → A: No. The store is not available in entities or views at
  all; endpoints, workflow steps, consumers, timed actions and agents have it.
- Q: May a service secret's name contain a slash, as the story's `provider/acme` does and the edge
  case refused? → A: Yes. Names are letters, digits, `.`, `_`, `-` and `/`, 1 to 253 characters;
  anything else is refused.
- Q: What does setting a project secret do to its other keys, and can anything be removed? → A:
  `set` merges: the keys it names are added or replaced and the rest are kept. One key can be
  removed with `ankka projects secrets unset <name> <key>`, by a patch. The Secret itself is
  never deleted.
- Q: What is a service secret's value, and how large may it be? → A: Text, up to 64 KiB measured
  as UTF-8 bytes. Binary material is the caller's to encode. An empty string is refused.
- Q: Are the seven proposed glossary terms the right words? → A: Yes, all seven: secret store,
  service secret (formerly referred to as "runtime secret"), secret key, encrypted, project
  secret (formerly referred to as "static secret"), entry (one named value of a project secret,
  a Secret's key) and platform setting (a platform-reserved or sidecar-routed variable). The bare
  word "secret" names neither kind.

### Amended in planning, 2026-10-03

- FR-013 and the third decision under Context said the one declaration lives in
  `controlplane-api`, read by the operator and the sidecar. Neither can see that module: the
  operator depends on `crd` alone and the sidecar on `runtime`, `http`, `agent` and `protocol`.
  The declaration is in `core`, and the operator compiles the same source file (research R12).
- FR-016 and its scenario were added: planning found that a project secret's name could replace
  a Secret the platform keeps in the same namespace (research R15).
- FR-001 and FR-004 gained what planning decided and no scenario held: a workflow's store refuses
  outside a step; a malformed key stops the service starting; `delete` needs no key (research R2,
  R4).
- FR-010 no longer says the event carries the project: the event is the project entity's own.
  "Keys" of a project secret are "entries" throughout, as the glossary has it; `<key>` stays in
  the CLI's syntax and in `secretKeyRef`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service secret is stored and never journaled (Priority: P1)

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

- added `features/secrets/secret-store.feature`: a service secret kept by one component is read by another
- added `features/secrets/secret-store.feature`: the database holds a service secret only encrypted, and only in the secret store
- added `features/secrets/secret-store.feature`: a service secret is still read after the service restarts
- added `features/secrets/secret-store.feature`: a service secret kept on one instance is read on another
- added `features/secrets/secret-store.feature`: a service secret that was never kept is read as none, without a failure
- added `features/secrets/secret-store.feature`: keeping a service secret again replaces its value
- added `features/secrets/secret-store.feature`: a removed service secret is read as none
- added `features/secrets/secret-store.feature`: a service with no secret key starts
- added `features/secrets/secret-store.feature`: a service with no secret key cannot keep or read a service secret
- added `features/secrets/secret-store.feature`: a service secret cannot be read with another secret key
- added `features/secrets/secret-store.feature`: a value larger than a service secret may be is refused
- added `features/secrets/secret-store.feature`: an empty value is refused
- added `features/secrets/secret-store.feature`: a service secret's name may have a slash
- added `features/secrets/secret-store.feature`: a service secret's name is refused when it breaks the rule for names
- added `features/secrets/secret-store.feature`: a service secret's name longer than the limit is refused
- added `features/secrets/secret-store.feature`: an entity or a view is given no secret store
- added `features/secrets/secret-store.feature`: a workflow reads a service secret in a step and not in a command
- added `features/secrets/secret-store.feature`: a service whose secret key is malformed does not start

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

- added `features/secrets/languages.feature`: a service secret is kept and read in every language
- added `features/secrets/languages.feature`: the database holds a service secret only encrypted in every language
- added `features/secrets/languages.feature`: a service secret that was never kept is read as none in every language
- added `features/secrets/languages.feature`: keeping a service secret again replaces its value in every language
- added `features/secrets/secret-key.feature`: the process of a deployed service is not given the secret key
- added `features/secrets/secret-key.feature`: a module is not shown the secret key
- added `features/secrets/secret-key.feature`: a deployed service is given a secret key of its own
- added `features/secrets/secret-key.feature`: a secret key the descriptor gives is the one the service has
- added `features/secrets/secret-key.feature`: a service deleted and deployed again reads the service secrets it kept before

---

### User Story 3 - A project secret is set without cluster credentials (Priority: P2)

A developer runs `ankka projects secrets set checkout STRIPE_KEY=sk_live_...`, then references it
in the descriptor as an env entry with `secretKeyRef: {name: checkout, key: STRIPE_KEY}` and
applies. The pod starts with the value in its environment. The control plane never read the value
back, and cannot: its grant does not include `get`. Setting another key later keeps the first, and
`ankka projects secrets unset checkout STRIPE_KEY` removes one key; the Secret itself is never
deleted.

**Why this priority**: It closes a gap in the existing `secretKeyRef` path with the smallest
change, and a deploy token in CI needs it to set anything at all.

**Independent Test**: In the k3s suite that mints a token for the control plane's own
ServiceAccount, assert `get` on the written Secret is refused by the API server; in the control
plane HTTP suite, assert the journal event carries the name and entries and no value.

**Acceptance Scenarios**:

- added `features/secrets/project-secrets.feature`: a member sets a project secret
- added `features/secrets/project-secrets.feature`: setting an entry keeps the other entries of the project secret
- added `features/secrets/project-secrets.feature`: a member removes an entry of a project secret
- added `features/secrets/project-secrets.feature`: a project secret whose last entry is removed is no longer listed
- added `features/secrets/project-secrets.feature`: removing an entry that was never set changes nothing
- added `features/secrets/project-secrets.feature`: a project secret whose last entry was removed is listed again when an entry is set
- added `features/secrets/project-secrets.feature`: a project secret may not take a name the platform uses
- added `features/secrets/project-secrets.feature`: a variable taken from a project secret reaches the service
- added `features/secrets/project-secrets.feature`: the control plane records that a project secret was set and never a value
- added `features/secrets/project-secrets.feature`: the control plane cannot read a project secret back
- added `features/secrets/project-secrets.feature`: an entry set again is what an instance started afterwards is given
- added `features/secrets/project-secrets.feature`: a person who is not a member is told there is no such project
- added `features/secrets/project-secrets.feature`: a machine holding a deploy token sets a project secret
- added `features/secrets/project-secrets.feature`: a list of project secrets shows names and entries and never a value
- added `features/secrets/project-secrets.feature`: a service whose variable is taken from a project secret that does not exist does not become ready
- added `features/secrets/project-secrets.feature`: a project secret the platform could not keep is not recorded

---

### User Story 4 - The platform's variables are declared once (Priority: P2)

A developer adding a platform variable adds it to one declaration in `core`, and the operator's
routing to the sidecar, the control plane's refusal of it in a descriptor, and the wasm host's
withholding all follow. A test holds the three consumers to the one list.

**Why this priority**: Three lists have already diverged once; this feature adds a fourth
variable to them and should be the last to do it by hand.

**Independent Test**: A suite asserts that the operator's routing and the sidecar's reserved set
are the one list, by reading them from it rather than comparing literals.

**Acceptance Scenarios**:

- added `features/secrets/platform-settings.feature`: a platform setting a descriptor gives is kept from the process
- added `features/secrets/platform-settings.feature`: a module that asks for a platform setting is told that it is not set
- added `features/secrets/platform-settings.feature`: every platform setting kept from a process is kept from a module
- added `features/secrets/platform-settings.feature`: a variable newly made a platform setting is kept from a process and from a module alike
- added `features/secrets/platform-settings.feature`: a descriptor may not give a variable the platform alone sets, however it gives it

---

### Edge Cases

- **A value larger than a row should hold.** A value is text of at most 64 KiB, measured as UTF-8
  bytes; a larger one is refused at `put` with the cap named. A secret is a credential, not a
  document, and binary material is the caller's to encode.
- **An empty value.** Refused at `put`, so a `get` can never answer an empty string and absent is
  the only way to say there is no secret.
- **A `get` from an entity command handler.** It cannot be written: an entity's context, and a
  view's, has no store. A read there would block the entity's single-writer path, and a value
  read there is one line from an event or a state. In Scala it does not compile; in the other
  SDKs the entity and view contexts offer no secrets client.
- **The key is changed under a running service.** Every `get` fails to decrypt and reports it
  as a wrong key, never as a missing secret; rotation is out of scope and this is the failure a
  careless rotation produces.
- **A project secret the descriptor references does not exist.** The pod does not start, which
  Kubernetes reports; the control plane's status shows the Kubernetes reason as it does for any
  pod that cannot start.
- **An entry is removed while a deployed service's variable is taken from it.** Running pods keep
  the value they started with; a pod started afterwards does not start, exactly as for a Secret
  that does not exist, and the status shows the Kubernetes reason.
- **The last entry of a project secret is removed.** The Secret stays in the namespace, empty, since
  the grant has no `delete`; the listing no longer shows it. Setting an entry on that name again
  brings it back.
- **An entry that was never set is removed.** The control plane answers from its own record that
  there is no such entry, and writes nothing to the cluster or the journal.
- **A project secret named like one of the platform's own Secrets.** The control plane writes by
  name into the namespace where the platform keeps a service's database credential, certificates
  and secret key, and cannot look first. `payments-secret-key` would replace a service's key.
  Such names are refused, and nothing is written.
- **A descriptor's variable named like a platform variable and taken from a project secret.** The
  descriptor's env check refuses it as it refuses a literal of that name.
- **A secret name with a space or a colon.** A name is letters, digits, `.`, `_`, `-` and `/`,
  from 1 to 253 characters; a slash is a separator with no meaning to the store
  (`provider/acme`). Any other name, the empty one included, is refused at `put` naming the rule.
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
  `delete(name)`, available to endpoints, workflow steps, consumers, timed actions and agents,
  and to no entity and no view: their contexts MUST NOT offer it. A workflow's store MUST refuse
  a call made outside a step. A value is text, not empty and at most 64 KiB as UTF-8 bytes; `put`
  MUST refuse any other, naming the rule it broke.
- **FR-002**: Values MUST be stored in a table in the service's own database, encrypted under a
  key the service holds as `ANKKA_SECRET_KEY`, with a DDL file added to the canonical schema
  directory and to no other copy, additive so the compatibility rule holds.
- **FR-003**: The store MUST NOT be an entity, a view or a source any projection reads; no value
  it holds MUST appear in the journal, snapshot, durable state, timer or view tables.
- **FR-004**: A service with no key configured MUST start, and MUST fail a `put` or `get` with a
  message naming the variable. A key that is set and is not the base64 of 32 bytes MUST stop the
  service starting, with a message naming the variable and the form it expects. `delete` needs no
  key and MUST work without one.
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

**Project secrets**

- **FR-009**: The control plane MUST offer a route, and the CLI a command, to set a named Secret
  with one or more entries in a project's namespace, under a grant of `create` and `patch` only,
  through a narrow writer in the shape of `RegistryWriter`. Setting MUST merge: the entries named
  are added or replaced and every other entry of the Secret is kept.
- **FR-010**: The journal event for a set secret is the project's own: it MUST carry the Secret's
  name and the names of the entries set, and MUST have no field that could carry a value,
  asserted by `EventCompatibilitySuite`. The event for a removed entry MUST carry the name and
  the entry's name, and likewise no value.
- **FR-011**: The control plane MUST offer a listing of a project's secrets by name and entries,
  built from its own record, showing the entries currently set and no secret with none.
- **FR-012**: Membership rules MUST apply unchanged: a non-member sees 404, a member and a deploy
  token may set, unset and list.
- **FR-015**: The control plane MUST offer a route, and the CLI the command
  `ankka projects secrets unset <name> <key>`, to remove one entry of a Secret by a patch, under
  the same grant. Removing an entry the control plane has no record of MUST be refused as not
  found and write nothing. The Secret itself MUST never be deleted by the platform.
- **FR-016**: A project secret's name MUST be refused when it has a form the platform uses for
  its own Secrets in a project's namespace: a name beginning `ankka-`, or ending `-db`,
  `-cluster-tls`, `-service-tls`, `-database-tls` or `-secret-key`. The refusal MUST say the name
  is the platform's, and nothing MUST be written to the cluster or recorded. A test MUST hold the
  refused forms to the names the operator derives.

**One declaration**

- **FR-013**: The platform-reserved variable names and prefixes, and the sidecar-routed prefixes,
  MUST be declared once, in one source file in `core` that imports nothing outside the standard
  library. `controlplane-api` and the sidecar MUST read it through `core`; the operator MUST
  compile that same file and MUST NOT gain a dependency beyond `crd`. No other module may hold a
  list of its own, and a test MUST hold all three readers to the declaration and fail on a second
  copy.

**Documentation**

- **FR-014**: A build page MUST describe the store and its rule; the platform page on databases
  or a sibling MUST describe the key; the CLI reference and control plane routes pages MUST be
  regenerated; the configuration reference MUST list the variable.

### Key Entities

- **Secret row**: a name (letters, digits, `.`, `_`, `-`, `/`; 1 to 253 characters), a
  ciphertext of a text value of 1 byte to 64 KiB, an updated-at; one per name per service.
- **Project secret record**: a project, a Secret name, the entries currently set; held by the
  control plane with no value, and the only source of the listing, since the control plane
  cannot read a Secret back.
- **Per-service key**: rendered by the operator into a Secret named for the service, injected as
  one variable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A database dump of a service that has stored a secret contains the plaintext zero
  times.
- **SC-002**: A stored secret survives a restart and is readable from every instance.
- **SC-003**: A project secret can be set and referenced by a member or a deploy token with no
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
- Whether a project secret should also be mountable as a file for programs that read credentials
  from paths.
- Whether the listing of project secrets belongs in the console in this feature or later.
