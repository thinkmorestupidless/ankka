# Research: Secret Store

Decisions for [plan.md](plan.md), each with what in the repository it rests on. File references
are to this branch's base (`bfa402f5`). "Verify first" marks a claim read from code or a
dependency and not yet run; the task that touches it starts with a test that would show it false.
The words are the glossary's: a **service secret** is what a service keeps in its **secret
store**, encrypted with its **secret key**; a **project secret** is what a member sets, made of
**entries**; a **platform setting** is a variable the platform's own program reads.

## R1. The store is an `sdk` trait the runtime implements over `Database`

**Decision**: `sdk` gains `trait SecretStore { def put(name: String, value: String): Unit; def
get(name: String): Option[String]; def delete(name: String): Unit }`. `runtime` implements it as
`DatabaseSecretStore(database, key)`, and `AnkkaService` exposes one as `lazy val secrets`. The
methods are synchronous and block on the database's `Future`, as `DatabaseTimerScheduler` does.

**Rationale**: this is the `ServiceClients` shape already in the tree: a trait in `sdk`
(`sdk/ServiceClient.scala:91`), an implementation in `runtime` (`HttpServiceClients.scala:37`), a
`lazy val` on `AnkkaService` (`Ankka.scala:325`). `runtime` depends on `sdk` and not the reverse,
so a component can name the trait and never the database. `Database()` reuses the persistence
plugin's pool (`Database.scala:131-135`), so the store opens no second pool, and `SqlParam`
already binds `String` and `Array[Byte]` (`sql.scala:49-60`). Every place a store is offered runs
on `AnkkaExecutors.virtual`, where a blocking call parks the virtual thread and releases its
carrier; `TimerScheduler` is synchronous for the same reason (`TimedAction.scala:88-105`,
`TimerRuntime.scala:77-101`).

**Alternatives considered**: a `RuntimeExtension` the service registers and passes around, as
`TimerRuntime` is — rejected: every service would have to remember it, and FR-004 wants a service
with no key to start and the store to exist. An effect (`effects.putSecret`) interpreted by the
runtime — rejected: the store is offered where handlers already run sequential blocking code, and
an effect on an entity is exactly what the clarification refused.

## R2. Who is given a store, and where that is enforced

**Decision**: `secrets: SecretStore` is added to `WorkflowContext`, `ConsumerContext`,
`TimedActionContext`, `AgentContext`, `AutonomousAgentContext` and `EndpointClients`. It is **not**
added to `ComponentContext`, so `EntityContext` and `ViewComponentContext` have none and
`context.secrets` in an entity or a view does not compile. A workflow's store refuses outside a
step: `WorkflowEngine` marks the step's thread (`StepScope`, a thread-local set around the step
body as `RequestScope` and `CurrentTask.within` are) and the workflow's store throws
`CommandError(…, BadRequest)` naming "a step" when the mark is absent.

For a process or a module the sidecar cannot tell which component a `Client` call came from, so
the rule is the SDK's: the entity and view contexts of the Python, TypeScript and Rust SDKs offer
no secrets client (R9).

**Rationale**: `componentClient` reaches entities and views through the shared `ComponentContext`
(`contexts.scala:12-14`), so the store must sit one level down. Timed actions and agents have
their own context traits (`TimedAction.scala:32-37`, `Agent.scala:47`,
`AutonomousAgent.scala:46`), and endpoints take the `EndpointClients` bundle, whose own scaladoc
says it exists so "adding a client later is not a breaking change" (`EndpointClients.scala:9-14`).
A workflow's command handlers and its steps share one `WorkflowContext`
(`WorkflowEngine.scala:104, 213-218`), so the type cannot separate them; the mark can, and the
clarification gave the store to *steps*. On the sidecar side `InvokeRequest` carries only the
target, the gRPC server reads no caller metadata, and the metadata an SDK copies is written by the
guest (`client.proto:17-26`, `ClientLogic.scala:93`); the module's two instance pools share one
set of imports (`WasmConversation.scala:76-78`).

**Alternatives considered**: a store on every context that refuses at runtime in an entity —
rejected by the clarification (a compile error is better than a refusal). `Thread.isVirtual` as
the step test — rejected: it encodes where the engine happens to run commands today. Teaching the
sidecar who called — a protocol change far larger than this feature, for a rule the developer's
own process could bypass anyway by constructing a client.

**Verify first**: that a workflow command handler does not already run inside something that
would set the mark (it is set only in `startStep`).

## R3. The cipher and the stored form

**Decision**: AES-256-GCM from the JDK (`javax.crypto`), a fresh 12-byte random nonce per `put`,
and the secret's **name as associated data**. The stored bytes are
`0x01 ‖ nonce (12) ‖ ciphertext ‖ tag (16)`; the leading byte is the form's version. Nothing is
cached: every `get` is one row read and one decryption.

**Rationale**: authenticated encryption makes "wrong key" a detected failure and not garbage
(FR for the wrong-key scenario). Binding the name means a row copied under another name fails to
decrypt, so a value cannot be moved between names by anyone who can write the table. A version
byte costs one byte and is what a later rotation feature needs to tell forms apart. No library is
added: the tree has no `javax.crypto` use today, and `SecureRandom` is already used in `runtime`
(`RotatingTls.scala:10`). No cache, because another instance may have replaced or removed the
value (the two-instances scenario) and because a cache is a second place a plaintext lives.

**Alternatives considered**: pgcrypto in the database — the key would cross the wire to Postgres
and appear in statement logs. A per-row data key wrapped by the service key — the envelope a
rotation feature may want, and nothing here needs it; the version byte leaves room.

## R4. The secret key: where it comes from and what each state means

**Decision**: `reference.conf` gains `ankka.secrets.key = ""` and `key = ${?ANKKA_SECRET_KEY}`. The
value is the standard base64 of exactly 32 bytes.

| State | Behaviour |
|---|---|
| not set | the service starts; `put` and `get` throw `CommandError(…, Internal)` naming `ANKKA_SECRET_KEY`. `delete` needs no key and works |
| set and not base64 of 32 bytes | the service does **not** start; the error names the variable and the form it expects |
| set, and not the key a row was encrypted with | `get` throws `CommandError(…, Internal)` saying the key is not the one the secret was kept with; never `None` |

**Rationale**: the `${?ENV}` line is how `ANKKA_DB_*` is read (`reference.conf:105-133`) and puts
the key in the generated configuration reference. FR-004 asks only that a service *without* a key
starts; a key that is present and malformed is a mistake to stop on, as a missing `POD_IP` is in
the Kubernetes overlay. `delete` without a key is the answer to the question clarification left
for planning: removing a row decrypts nothing, and refusing it would leave a service that lost its
key unable to clean up.

**Alternatives considered**: reading `sys.env` directly — untestable in-process (the
`ANKKA_CONFIG` trap) and absent from the generated reference. A passphrase stretched with a KDF —
a second parameter to get wrong; the operator generates random bytes and a developer runs
`openssl rand -base64 32`.

## R5. One table, in the canonical DDL

**Decision**: `kustomization/components/postgres/ddl/40-secrets-postgres.sql` creates
`ankka_secrets (name TEXT PRIMARY KEY, ciphertext BYTEA NOT NULL, updated_at TIMESTAMPTZ NOT NULL
DEFAULT now())`, `IF NOT EXISTS`. `put` is an upsert. A `put`/`get` against a database without
the table fails with a message naming the file.

The file is added once and **named in seven lists**, each of which names the three files there
are today:

- `modules/testkit/.../AnkkaTestKit.scala:150-154`
- `operator/.../CnpgRendering.scala:35-40` (the `ankka-schema` ConfigMap)
- `operator/src/test/.../SchemaResourceSuite.scala:15`
- `operator/src/test/.../CnpgRenderingSuite.scala:140-146`
- `sidecar/src/test/.../SidecarClusterSuite.scala:213-215`
- `kustomization/components/postgres/kustomization.yaml:24-27`
- `kustomization/components/postgres/cluster.yaml:41-47` (before `99-grants`)

**Rationale**: FR-002 asks for a DDL file in the canonical directory. Everything that globs picks
it up unchanged: compose's bind mount, the sidecar image's `/opt/docker/ddl`, the template's
unzip. A deployed service gets the table when its pods next start, because `SchemaInit` runs every
file on every start under an advisory lock (`SchemaInit.scala:86-111`). The change is additive, so
the compatibility rule holds. Two services never share a database, so the name alone is the key.

**Alternatives considered**: `CREATE TABLE IF NOT EXISTS` at first use, as view row tables are
made (`ProjectionRuntime.scala:84-96`) — rejected: the schema would then have two sources, and
the spec asks for one.

**Consequence**: a local database created before this version (a compose volume) has no table,
because Postgres runs `initdb.d` once. The error names the file; the docs say to apply it or
recreate the volume.

## R6. Refusals and faults are `CommandError`s

**Decision**: the store throws `core`'s `CommandError`:

| Case | Code | The message names |
|---|---|---|
| a name that breaks the rule, an empty value, a value over 64 KiB | `BadRequest` | the rule |
| used outside a step, in a workflow | `BadRequest` | "a step" |
| no secret key | `Internal` | `ANKKA_SECRET_KEY` |
| the key is not the one the secret was kept with | `Internal` | that, and the secret's name |
| the database cannot be reached | `Unavailable` | the store |

**Rationale**: `CommandError` already crosses every boundary this feature crosses: an endpoint
maps its codes to statuses (`HttpEndpoint.scala:33`), the sidecar maps them to `pb.Error`
(`Translate.scala:36-44`) and each SDK raises its own `CommandError` from one. A new exception
type would need a new mapping in six places. `Internal` and not `Unavailable` for the key faults:
`Unavailable` is retryable (`CommandError.scala:32-34`) and `ClientLogic` retries it
(`ClientLogic.scala:70-91`); a missing key does not fix itself.

## R7. One statement of the rules, held by a fixture

**Decision**: `sdk` gains `SecretRules` — the name rule (letters, digits, `.`, `_`, `-`, `/`; 1
to 253 characters), the value rule (not empty; at most 65,536 UTF-8 bytes) — used by
`DatabaseSecretStore` and by the testkit's in-memory store. `protocol/fixtures/secrets/rules.json`
lists accepted and refused names and values; the Scala rules and each SDK's unit-test double are
tested against it.

**Rationale**: "where two interpreters reduce the same thing, they share one function" for Scala.
The three other SDKs each have an in-memory double for unit tests, and a double with looser rules
than the sidecar lets a test pass that a deployment refuses; the fixture is how the graph builder's
four implementations are held together (`protocol/fixtures/graph-deltas/`). A subdirectory,
because the top level of `protocol/fixtures/` belongs to `EncodingFixturesSuite`.

## R8. Protocol 1.6: three unary calls on `Client`, with refusals in band

**Decision**: `client.proto` gains `GetSecret`, `PutSecret` and `DeleteSecret` on `service
Client`, each answering with a reply message that carries an `Error` in band, as `InvokeReply`
does. The `ankka1` import module gains `get_secret`, `put_secret` and `delete_secret` over the
same messages. The protocol version becomes `1.6`. `ClientLogic` gains three methods over
`service.secrets`; `ClientService` and `HostImports` delegate to them. Messages are in
[contracts/protocol.md](contracts/protocol.md).

**Rationale**: `ClientLogic` is the one implementation behind the gRPC server and the wasm
imports (`ClientLogic.scala:30-33`), so the two cannot disagree. A wasm import returns bytes and
has no status to carry, so the refusal must be in the reply; `Schedule` and `Cancel` use gRPC
statuses (`ClientService.scala:53-59`) and are the awkward ones for a module. Added calls and
imports are a minor change by the protocol's own rule (`WASM-ABI.md:131-135`), and only the major
must match at discovery (`Discovery.scala:126-130`).

**A newer SDK on an older runtime** is loud without a guard: a process's call is answered
`UNIMPLEMENTED` by grpc-java, which each SDK reports as "the runtime speaks protocol 1.3; secrets
need 1.6" (it knows the runtime's version from discovery); a module that imports `get_secret` does
not instantiate on a runtime that lacks it. A module that never calls the store does not import
it, so it runs on 1.3 unchanged.

**The files a minor bump touches** (from the 1.3 commit, `cca2af9f`): `Conversation.scala`
(`WireProtocol.Version`), `Discovery.scala`'s comment, `Compatibility.scala` and
`CompatibilitySuite`, `HostingSuite`, `ProtocolSuite`, `RemoteProjectionSuite` (asserts the
version string), `protocol/README.md`, `WASM-ABI.md`, the three SDK copies made by their scripts,
the three `PROTOCOL_VERSION` constants, and the docs pages in R18.

**Verify first**: that an unused `extern` import is absent from a Rust module's import section
(otherwise every module built with the new crate would need a 1.6 runtime).

## R9. The store in Python, TypeScript and Rust

**Decision**: each SDK gains a `Secrets` client with `put`, `get` and `delete`, offered where the
Scala contexts offer it and nowhere else. Shapes are in [contracts/sdk-apis.md](contracts/sdk-apis.md).

- **Python**: `client.secrets` is not added to `ComponentClient`, because entities are handed
  that client (`context.py:67-76`). `Secrets` is a separate object passed to consumers, timed
  actions, agents, autonomous agents, endpoints and workflow steps.
- **TypeScript**: `get secrets()` on the workflow (steps), consumer, timed action, agent,
  autonomous agent, endpoint and graph consumer classes; none on the two entity classes or the
  view, where the property does not exist in the type.
- **Rust**: every handler takes one `&Context` (`context.rs:105`), so the type cannot differ.
  `ctx.secrets()` returns `Option<Secrets>`: `None` in an entity, a view and a workflow command.

**Rationale**: in all three the store must be reachable without being on the client an entity
already holds. Python's views have no client today; TypeScript's and Rust's do, so the rule is
written per kind and not as "wherever the client is". `Option` in Rust is the scenario's own
words — "is given none" — and needs no panic.

**Verify first**: how a Python and a TypeScript workflow step receive their context, so that the
step has the store and a command does not (`server.py:230, 280, 313`; `server/workflow.ts:80,
133`).

## R10. Test kits

**Decision**:

- **`AnkkaTestKit`** generates a secret key per kit and puts it in the service's config, so the
  store works with no setup. `start` gains `secretKey: Option[String]`, and
  `restartService(secretKey = …)` restarts under another key or none. `current.secrets` is the
  running service's store.
- **`InMemorySecretStore`** in `testkit` is the store for unit tests; `ConsumerTestKit` gives one
  to the context it builds. It applies `SecretRules`.
- The **Python, TypeScript and Rust integration kits** set a generated `ANKKA_SECRET_KEY` on the
  runtime's container unless the caller's `env` names one, and each unit kit gains an in-memory
  double.

**Rationale**: the spec's assumption is that "the test kit generates one". `configFor` builds the
service's config from a map (`AnkkaTestKit.scala:234-253`) and `restartService` reuses it
(`:118-121`), so the same key survives a restart by default — which is the durability scenario —
and a different key is the wrong-key scenario. Each SDK kit already takes an `env` it puts on the
container (`integration.py:164-165`, `integration.ts:244-257`, `integration.rs:344-365`).

## R11. The operator makes each service's secret key, once

**Decision**: for every service whose descriptor does not set `ANKKA_SECRET_KEY`, `Rendering`
emits `Action.EnsureSecretKey(namespace, name, labels)` before `ApplyDeployment`, and adds
`ANKKA_SECRET_KEY` to the platform's container as a `secretKeyRef` to `<service>-secret-key`, key
`key`. `Fabric8Executor` performs the action by generating 32 random bytes and **creating** the
Secret; a `409 AlreadyExists` is success. It never reads the Secret. Names it has created or seen
exist are remembered for the life of the process, so it asks once per service, not once per
reconcile. The Secret carries the identity labels and **no owner reference**.

**Rationale**:

- *The action carries no key.* `Action` values are inert descriptions and `describe` prints them
  (`Action.scala:150-183`); a key in the action would be in the operator's log. The executor is
  the only thing that performs a mutation, so it is where the bytes are made.
- *Create and tolerate 409, not get-then-create.* `EnsureCredentials` checks with a `get`
  (`Executor.scala:225-246`), which is fine for a Secret holding a host name and would bring a key
  back over the wire here. FR-006 says the operator never reads it back, and it need not.
- *No owner reference* is what keeps the database's Secret, `Database` and `DatabaseRole` when a
  service is deleted (`CnpgRenderingSuite:118-139`, `OperatorClusterSuite` 19). The key takes the
  same rule, so a service deployed again under its name reads what it kept.
- *The platform's container only.* The variable is added through `container`'s `extraEnv`, which
  the app container of a process-hosted service is not built from (`Rendering.scala:721-769`); for
  a module the one container is the platform's and `config` withholds the name (R12).
- *A descriptor that sets it gets none* — the operator sees `spec.env`, so no field is added to
  the resource and `ankkaservice.yaml` does not change. This is `provisionDatabase`'s rule made
  without a field (`ServiceProjection.scala:122`).
- The operator's grant is already `secrets: get, create, patch`; nothing is added.

**Consequences**: every existing Deployment's pod template gains a variable, so the first
reconcile after the operator is upgraded rolls every service once, by the surge-and-hand-off the
platform already uses. A pod cannot start before its Secret exists, which is why the action comes
before the Deployment.

**Alternatives considered**: a key only for services that ask — there is nothing in a descriptor
to ask with, and a store that works locally and fails deployed is the worst shape. Deriving the
key from the service's certificate or a cluster secret — couples the data to something that
rotates.

## R12. One declaration of the platform's variables — in `core`, compiled into the operator

**Decision**: `modules/core/.../core/PlatformVariables.scala` is the one declaration, a
`private[ankka]` object with no imports beyond the standard library. `controlplane-api`
(`ServiceSpec`) and `sidecar` (`HostImports`) read it through their existing dependency on `core`.
The operator compiles **the same file**: `operator`'s build adds that one source to
`Compile / unmanagedSources`. The operator's `dependsOn` stays `crd`. The lists in
`descriptors.scala`, `Rendering.scala` and `HostImports.scala` are deleted.

The declaration, with what reads each part:

| Part | Members | Read by |
|---|---|---|
| set by the platform alone (exact names) | `ANKKA_HTTP_PORT`; `ANKKA_CLUSTER_MODE`, `POD_IP`, `ANKKA_CLUSTER_SERVICE`, `ANKKA_CLUSTER_POD_SELECTOR`, `ANKKA_CLUSTER_CONTACT_POINTS`, `ANKKA_NAMESPACE_PREFIX`; the five `ANKKA_PROCESS_*`/`ANKKA_SIDECAR_*` names; the three `ANKKA_WASM_*` names | `ServiceSpec.problems` refuses them |
| for the platform's program only (a descriptor may give them) | prefixes `ANTHROPIC_`, `ANKKA_MODEL_`, `ANKKA_DB_`; the name `ANKKA_SECRET_KEY` | the operator's split; the module's `config` |
| given to both programs | prefix `ANKKA_KAFKA_` | the operator's split |
| read by the platform's program and not set through a descriptor's list | prefixes `ANKKA_CLUSTER_`, `ANKKA_WASM_`, `ANKKA_SIDECAR_`, `ANKKA_PROCESS_`, `ANKKA_AUTH_`; names `ANKKA_BASE_DOMAIN`, `ANKKA_HTTPS_PORT` | the module's `config` |

`withheldFromModule(name)` is the union of the first, second and fourth parts.

**The spec was amended to this.** FR-013 and the Context first said the declaration lives in
`controlplane-api`, "which both the operator and the sidecar read". Neither can: the operator
depends on `crd` alone (`build.sbt:329-357`) and that is a stated build-level fact; `sidecar`
depends on `runtime`, `http`, `agent` and `protocol` and does not see `controlplane-api`
(`:466-515`). No module sees all three consumers. `core` is the one module both
`controlplane-api` and `sidecar` already reach, and compiling one dependency-free file into the
operator shares the declaration without giving the operator a dependency. If the file ever grows
an import of something in `core`, the operator stops compiling, which is the guard.

**What changes in behaviour**, pinned before the lists move by the suites that exist
(`DescriptorSuite:274-298`, `HostingSuite:77-100`, `ProcessHostingRenderingSuite:86-113`,
`WasmHostSuite:275-316`):

- `ANKKA_SECRET_KEY` is for the platform's program only, in all three places.
- `ANKKA_NAMESPACE_PREFIX` is withheld from a module. Today the descriptor refuses it and the
  operator sets it, and `config` answers it (`HostImports.scala:176-177`).
- Nothing else. `ANKKA_KAFKA_` stays readable by a module, as it is given to a process; changing
  that is 027's.

**The check that can fail**: the operator's and the sidecar's behaviour is tested by *iterating
the declaration* — every member of the second part is rendered onto a process-hosted pod and
asked of a module's `config` — so a member added to the declaration is tested with no test
changed. A suite in `controlPlane`'s tests walks the source tree and fails if a second
`PlatformVariables.scala` exists or if `operator`, `sidecar` or `controlplane-api` declares a
`Vector` or `Set` of `ANKKA_`/`ANTHROPIC_` literals of its own; without it, a copy pasted back
into the operator would pass every behavioural test.

**Alternatives considered**: `crd` — it carries fabric8, which would reach the CLI through
`controlplane-api`, a published module whose POM says `core` alone. A resource file symlinked into
each module, as the DDL and the CRD are — three hand-written loaders, and one more resource the
native CLI silently lacks until `native-smoke.sh` is taught it. The operator depending on `core` —
ends "the operator has no ankka dependencies" for one object. A field on the resource saying where
each variable goes — a writer of the resource could then route the key to the developer's
program, and the operator trusts no writer.

**Verify first**: that a class present in both `core`'s and `operator`'s jars, identical, on
`controlPlane`'s and `sidecar`'s test classpaths causes no warning under the build's flags.

## R13. A project secret is written by a merge patch, then a create

**Decision**: `AnkkaServiceClient` gains `setSecretEntries(namespace, name, entries)` and
`removeSecretEntry(namespace, name, key)`. Setting sends a JSON merge patch
`{"stringData": {…}}`; a `404` means the Secret does not exist, and it is then **created** (type
`Opaque`, labelled `app.kubernetes.io/managed-by: ankka` and
`ankka.thinkmorestupidless.com/project-secret: "true"`); a `409` on that create means another
request won the race, and the patch is sent again. Removing sends `{"data": {"<key>": null}}`. The
endpoint reaches the client through a two-method `ProjectSecretWriter` that `ServiceProjector`
implements, beside `RegistryWriter`.

**Rationale**: the clarification made `set` a merge. Server-side apply, which
`ensurePullSecret` uses (`Fabric8AnkkaServiceClient.scala:50-80`), cannot merge: a manager's apply
replaces what that manager applied before, so setting one entry would remove the others, and the
control plane cannot read them to send them again. A merge patch leaves what it does not name. It
is the `patch` verb, and the create is `create`: the grant is unchanged
(`controlplane-rbac.yaml:67-69`). FR-009 said "a one-method writer"; `unset` makes it two.

**Verify first**, on k3s with the control plane's own token: a merge patch of a Secret that does
not exist is a `404` and not a `403`; `stringData` in a merge patch is merged into `data`; a
`null` removes one entry and leaves the rest; and `get` is still refused.

## R14. What the control plane records, and where the listing comes from

**Decision**: the `Project` entity records project secrets. Two events:
`ProjectSecretEntriesSet(name, entries, actor, at)` and `ProjectSecretEntryRemoved(name, entry, actor,
at)`; neither has a field that could carry a value. State gains
`secrets: Map[String, ProjectSecretRef]` (the entries, who last set it, when), defaulting to
empty so an older snapshot reads. A secret whose last entry is removed leaves the map. The listing
is an entity **query**, not a view: `GET /projects/{projectId}/secrets` asks the entity.

The order is the registry's: authorize, validate, write the cluster, then record. A cluster that
refuses is `503` and nothing is recorded. Removing an entry the record does not have is `404` and
writes nothing.

**Rationale**: the control plane cannot read a Secret back, so its record is the only source of a
listing (the spec's Key Entities). A project's secrets are that one entity's state, so reading
the entity is exact and immediate; a view would trail a write by a second or two and the CLI would
list a secret as missing just after setting it (the trap about projections). `ProjectRows` matches
on project events and gains two cases that change nothing. `EventCompatibilitySuite` gets the
absence assertion FR-010 asks for, and pins an old snapshot with no `secrets`.

## R15. A project secret may not take a name the platform uses

**Decision**: `controlplane-api` gains `ProjectSecrets.problems`. A name is a Kubernetes Secret
name (lowercase letters, digits, `-` and `.`; begins and ends with a letter or digit; at most 253
characters) that does **not** begin `ankka-` and does **not** end `-db`, `-cluster-tls`,
`-service-tls`, `-database-tls` or `-secret-key`. An entry's name is letters, digits, `.`, `_` and
`-`, at most 253 characters. A value is not empty and at most 64 KiB. A suite in `controlPlane`'s
tests holds the refused forms to the operator's own naming functions.

**Rationale**: the control plane writes into the namespace where the platform keeps its own
Secrets, by a name a member chooses, with a patch and no way to look first. The platform's names
there are `ankka-registry`, `ankka-db-*`, `<service>-db`, `<service>-cluster-tls`,
`<service>-service-tls`, `<service>-database-tls` (`Registries.SecretName`,
`CnpgRendering.scala:26, 255`, `ZeroTrust.scala:88, 90, 319`) and now `<service>-secret-key`. A
project secret named `payments-secret-key` with an entry `key` would replace a service's secret
key, and everything it had kept would be unreadable for good. Refusing the forms, not the names
of the services that exist today, also covers a service created later. The cost is that a name
like `orders-db` is refused; the refusal says why.

**Alternatives considered**: a prefix on the Kubernetes name (`secret.checkout`) with the
descriptor's `secretKeyRef` translated — it would break every descriptor that names a Secret made
with `kubectl`, which works today. Refusing only the names of existing services — a service
created afterwards collides. A precondition in the patch that tests a label — JSON Patch can, but
its `add` fails on a Secret with no `data`, and the two together are more machinery than a rule.

**Not in scope**: a descriptor can still name any Secret in `secretKeyRef`, the platform's
included. Refusing that is the 021 branch's (it refuses certificate Secrets); when the two meet,
`<service>-secret-key` joins its list.

## R16. Routes, wire types, the CLI and the console

**Decision**: three routes, three wire types, three commands; all in
[contracts/control-plane.md](contracts/control-plane.md).

- `PUT /projects/{projectId}/secrets/{name}`, `DELETE
  /projects/{projectId}/secrets/{name}?entry={key}`, `GET /projects/{projectId}/secrets`.
- `ankka projects secrets set <name> <key>=<value>…`, `unset <name> <key>`, `list`, with the
  project from `-p` as the service commands take it. A pair written `<key>=-` reads its value from
  standard input through `Console.in`, so a value need not be on a command line; at most one pair
  may.
- The console gets zod mirrors of the three wire types and **no page**.

**Rationale**: membership is `authz.project(principal, projectId, write)`, which answers a
non-member "no such project" and treats a deploy token as the member it is
(`Authorization.scala:63-68`); nothing is added for FR-012. A value on a command line is in shell
history and the process list; `registry set` has `--password-stdin` for the same reason
(`Main.scala:359-387`). A new `Wire` codec without a fixture fails `ControlPlaneFixturesSuite`,
and a fixture without a schema fails the console's `fixtures.test.ts`, so the mirrors are required
by the build; a page is the spec's open question, and this answers it "later".

## R17. Where each scenario is tested

**Decision**: the levels that exist, each used for what only it can show.

| Level | What it holds |
|---|---|
| pure (`core`, `sdk`, `controlplane-api`) | the rules fixture; the cipher (round trip, wrong key, moved row, tampered byte, version byte); `PlatformVariables`; `ProjectSecrets.problems`; no `secrets` on an entity or a view (`compileErrors`) |
| `testkit` with Postgres (`SecretStoreSuite`) | every scenario of `secret-store.feature`: the dump of every table, restart, a second instance (`startPeer`), no key, another key, the step mark |
| `sidecar` (`ConformanceSuite`, `secret.*`) | the same answers from the Scala reference, a Python and a TypeScript process and a Rust module; `WasmHostSuite` for `config` and the imports |
| operator, offline | the key's placement per hosting; no key rendered when the descriptor sets one; the action's order; no owner reference |
| control plane, fast HTTP | set, merge, unset, list, 404s, 503 and nothing recorded, the deploy token, the reserved names |
| k3s | the operator's key Secret made once, unchanged over ten reconciles, kept through delete and re-apply; the control plane's patch-then-create with its shipped grant, and `get` refused; one end-to-end case through the real CLI: set, deploy, the pod has the value, set again, a new pod has the new one |

Conformance cases drive routes on each reference's `ConformanceEndpoint` and read the database
through the kit, never a Scala type. Every target runs in the test JVM on `AnkkaTestKit`
(`ConformanceTarget.scala:84-93`), so the key is the kit's.

**What no k3s suite shows directly**: "a service deleted and deployed again reads the service
secrets it kept before". No image deployed by a k3s suite keeps a secret. It is held by two facts
that are each tested — the key Secret and the database survive the delete and the new pod names
the same Secret (operator, k3s), and the same key over the same database reads the value
(`SecretStoreSuite`) — and by one run on the local cluster in the quickstart.

**Verify first**: each new conformance case is seen failing against an unchanged SDK, and the
munit filter is `'*secret.*'`, with the leading wildcard.

## R18. Documentation

**Decision**:

- New page `docs/build/secrets.md`: the store, who has it, the rules, testing it. In `nav` and in
  a skill's `pages:`. Samples from tested code by `docs:start` markers.
- `docs/platform/`: the secret key (made by the platform, kept on delete, supplying your own) and
  project secrets (set, unset, list, referencing from a descriptor, the reserved names), on the
  databases page or a sibling.
- `docs/reference/configuration.md`: `ankka.secrets.key` arrives in the generated table; the
  prose beside it must mention it or the coverage check fails. `ANKKA_SECRET_KEY` joins the
  hand-written "What each variable means".
- `docs/reference/control-plane-api.md`: the table regenerates; each new route needs its literal
  `` `METHOD /path` `` in the prose (`ControlPlaneRoutesReferenceSuite:96-108`).
- `docs/reference/cli.md`: regenerated by `just docs-reference`.
- `docs/reference/sidecar-protocol.md`: the generated table gains three rows; the version history
  gains 1.6. `docs/reference/wasm-abi.md`: three imports. `docs/concepts/polyglot.md:79`.
- `docs/reference/glossary.md` and `docs/reference/limitations.md`: the store's limits (no
  rotation, no sharing, no file mount).
- Each SDK's page for the store's API; the skills rendered again by `just docs-sync`.

**Rationale**: FR-014, and the documentation rules: a page stands alone, samples come from tested
code, reference facts are generated, a new page is in `nav` and a skill.

## Verify first, gathered

1. A workflow command handler runs with no step mark (R2).
2. An unused `extern` import is not in a Rust module's import section (R8).
3. How a Python and a TypeScript workflow step receives its context (R9).
4. One class in two jars on a test classpath is quiet (R12).
5. Merge patch on a Secret: `404` when absent, `stringData` merged, `null` removes, with the
   control plane's own token (R13).
6. Each conformance case fails before the SDK change, and the filter has its wildcard (R17).

## Verified during implementation

1. **V1 held.** A workflow command handler runs on the actor's thread; only a step's body runs in the
   engine's virtual-thread `Future`, which is where `StepScope` is set (`WorkflowEngine.startStep`).
2. **V2 did not hold as the crate stood, and was fixed.** `abi::imports::call` dispatched every import in
   one `match`, so any module making one call imported all of them; adding the secret imports there would
   have made every module need a 1.6 runtime. They go through `call_secret`, and the sample module, which
   keeps no secret, imports none of them (`wasm-objdump -j Import`).
3. **V3.** Python and TypeScript bind a workflow step with the same binding as a command, so each sets an
   in-step mark on the workflow instance (`_in_step`, `_enterStep()`), set only where a step is run.
4. **V4 held.** `PlatformVariables` compiled into both `core` and the operator is quiet on every test
   classpath that sees both.
5. **V5** — run on k3s with the control plane's own token in `ControlPlaneClusterSuite` case 8; see the
   pull request for the run.
6. **V6 held, and found a weak case.** Every `secret.*` conformance case fails against each SDK with its
   routes moved aside — except `secret.absent`, which passed on a 404 from the router. It now first shows
   the route answers and that the 404 names the secret.

Two things the plan did not foresee: the module loader keeps its own list of admitted imports
(`ModuleLoader.Imports`), now held equal to `HostImports.values` by `WasmHostSuite`; and a `CommandError`
thrown from a workflow command handler left its caller unanswered, which the engine now answers as a
refusal. Two routes changed shape for the endpoint DSL: keeping a secret in the conformance reference is a
`POST` (no `putBody`-less body on `PUT` there), and removing an entry is `DELETE
/projects/{id}/secrets/{name}?entry=` (at most two path parameters on `DELETE`).
