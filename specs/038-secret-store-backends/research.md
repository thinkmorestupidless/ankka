# Research: Secret Store Backends — Google Secret Manager Beside Postgres

**Feature**: 038-secret-store-backends | **Date**: 2026-10-08

Each entry is a decision the plan rests on, with what it was weighed against. The code facts come
from a read of the tree on this branch; the Google facts from Google's documentation, read on the
date above. Where a fact is Google's and could change, the entry says what to re-check.

## R1. How the platform reaches Secret Manager: its REST API over the JDK client, no library

**Decision**: One small client, `runtime/secrets/SecretManager.scala`, speaks the Secret Manager
REST API (`https://secretmanager.googleapis.com/v1`) with `java.net.http.HttpClient` — the client
`HttpServiceClients` already uses — and jsoniter for the bodies. Its access token comes from a
`AccessTokens` seam: on GKE the metadata server
(`http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token`, header
`Metadata-Flavor: Google`), which under Workload Identity Federation for GKE issues a token for the
pod's Kubernetes ServiceAccount with no Google service account and no annotation; in tests a fixed
token the fake reads as the caller's identity. The endpoint and the token source are configuration
(`ankka.secrets.secret-manager.endpoint`, `…token`), so the same store runs against Google and
against the test kit's fake.

**Rationale**: 044 FR-014 says ankka carries no cloud client library; 038's review session says the
`SecretStore` implementation stays in core because the sidecar image is the platform's. Both hold
with a REST client of a few hundred lines: six calls (create, addVersion, access, delete, list
versions, destroy version, plus disable for project secrets), one token endpoint, and no gax, grpc
or google-auth on the runtime's classpath — which would also have brought protobuf-java 4 beside
ScalaPB's 3 (`observability.md`). 044 FR-014 is read as "no cloud-specific code outside this store"
and amended to say so (see Cross-spec edits below).

**Alternatives considered**: `google-cloud-secretmanager` (gax, grpc, auth, protobuf 4: a dependency
set larger than the runtime's own, and a version clash in the sidecar); an `ankka-contrib` module
(the sidecar image could not carry it); the provider doing every read on a service's behalf (every
`get` a round trip through another process, and the provider becomes the one reader of everything).

**Re-check before release**: the REST paths (`POST /v1/projects/*/secrets?secretId=`,
`POST …/secrets/*:addVersion`, `GET …/versions/latest:access`, `DELETE …/secrets/*`,
`GET …/versions`, `POST …/versions/*:destroy`, `POST …/versions/*:disable`) and that a federated
token from the GKE metadata server is accepted by Secret Manager (Google lists services with
federated-token limitations; Secret Manager is not among them today).

## R2. The derived id: a prefix the grant conditions on, an injective tail, a digest when too long

**Decision**: A Secret Manager id is `[A-Za-z0-9_-]{1,255}`; a project id and a service name are
DNS labels (lowercase letters, digits, hyphens; never `_`); a service secret's name is
`[A-Za-z0-9._/-]{1,253}`. So `_` is free as both separator and escape:

- service secret: `s_<project>_<service>_<tail>`; prefix `s_<project>_<service>_`
- project secret entry: `p_<project>_<secret>_<entry>`; a project's prefix `p_<project>_`
- `<tail>` is the name with `.` → `_p` and `/` → `_s` (injective: a name holds no `_`)
- when the id would exceed 255: `s_<project>_<service>__<sha256 hex>` (the `__` marker cannot
  arise from the escape, since escapes are `_p` and `_s`); the name is kept on the secret as the
  annotation `ankka.thinkmorestupidless.com/name`, which a platform administrator can read

A prefix ends in `_` and a label never contains `_`, so `s_shop_cart_` is not a prefix of
`s_shop_cart2_x`: no service's prefix is a prefix of another's, and no `s_` prefix is a prefix of a
`p_` one. `DerivedIds` in `runtime` is the one derivation, with a property test that it is
injective and length-bounded.

**Alternatives**: hyphen separators (a label can contain `--`, so `a--b` would be ambiguous); base32
of the whole name (every id a digest, nothing readable in the console); a per-service Secret Manager
*folder* (Secret Manager has none).

## R3. The IAM condition: create cannot be limited by name, so create is unconditioned

**Decision** (settled in the clarify session): the provider grants a service
`secretmanager.secrets.create` on the Google Cloud project with no condition, and `versions.add`,
`versions.access`, `versions.list`, `versions.destroy`, `secrets.get` and `secrets.delete` with the
condition `resource.name.startsWith("projects/<number>/secrets/s_<project>_<service>_")`; and
`versions.access` on `…/secrets/p_<project>_`. No `secrets.list`. The control plane's identity is
granted `secrets.create` unconditioned and `versions.add`, `versions.list`, `versions.disable` on
`p_` with no `versions.access`. The condition names the project by *number*, not id.

**Rationale**: Google authorises a create on the parent project before the secret exists, so a
name condition has nothing to match. A secret created under another service's prefix cannot be
read or written by its creator, and the owner's `put` adds a version to it, so the squat costs
nothing (edge case and grants scenario in the spec).

**What this changes in 044**: the `secret-access` kind's `own` and `read` are prefixes, not ids. See
Cross-spec edits.

## R4. The read record's keeper and the write path: the control plane, over the service's certificate

**Decision**: The runtime posts one record per `get`, `put` and `delete` to the control plane,
`POST /secret-reads`, before it returns a value, and refuses the operation as `Unavailable` when the
post is not answered 2xx within `ankka.secrets.record-timeout` (5s as shipped). The call is the
`HttpServiceClients` shape: the JDK client with the service's own `RotatingTls` certificate, requiring
the callee's URI `ankka://platform/controlplane`. The control plane admits the route by the caller's
certificate (`Caller.Service`), which no control plane route reads today, through a new
`CallerMatcher.AnyService` in `http`, and refuses a body whose project and service are not the
caller's. The address is the runtime's Kubernetes overlay's default
(`ankka-cluster-kubernetes.conf`: `https://ankka-controlplane.ankka-controlplane.svc:9000`, the
namespace the operator's `ZeroTrust.ControlPlaneNamespace` fixes), overridable by the platform
setting `ANKKA_SECRET_RECORDS_URL`; the operator renders nothing for it, so no pod template changes
on upgrade. The control plane's `ankka-controlplane-http` network policy already admits every
ankka workload. An upgrade applies the control plane before any service is deployed again, which
the deploy script's order already does; a service on the new runtime against an old control plane
has its secret reads refused until the control plane follows.

With no address set — a developer's machine, the test kit — the record goes to a `LocalRecorder`:
an info log line of the same fields and, in the test kit, `RecordedReads` a test can read. A
deployed service always has the address, because the operator renders it.

**Rationale**: the spec wants the record written over the service's own identity, outside the
service's database, fail-closed. The certificate is the identity; the control plane is what owns
the record's database; the policy and the TLS listener are already there. A direct database write
from every service would give every service a credential to the platform's record.

**Alternatives**: a per-service outbox in its own database, forwarded later (the spec forbids the
service's database, and an outbox can be deleted by the service); writing to Cloud Logging on the
Secret Manager backend only (the Postgres backend needs the record too, and one path is simpler).

## R5. The read record's store: a CNPG cluster of its own, read and swept by the control plane

**Decision**: `kustomization/components/secret-reads/`: a CNPG `Cluster` `ankka-secret-reads-db` in
`ankka-controlplane`, `bootstrap.initdb` with its own schema ConfigMap (`secret_reads` table and the
`99-grants.sql` literal, since post-init SQL runs as `postgres`), listed by both overlays — the
record exists on both backends, so the component is not optional. The control plane opens a second
r2dbc pool from `ankka.controlplane.secret-records.connection-factory` (fed by
`ANKKA_SECRET_RECORDS_DB_*` from the cluster's `-app` Secret) through the existing `Database`
class, and `PostgresReadRecordStore` holds every statement. A `ClusterSingleton` sweeper (the
`ProjectionSweeper` shape) deletes rows older than `ANKKA_SECRET_RECORD_RETENTION` (`365d` as
shipped) once a day. The listing route is `GET /projects/{id}/secret-reads` with `service`, `name`,
`from`, `to`, `limit` query parameters, owner-only through `organizationOf` + `requireOwner`.

**Rationale**: 041 backs the record up as a store in its own right with a bucket of its own, which
means a CNPG cluster of its own; the control plane's journal is the wrong place (an event per read,
forever) and the service's database is forbidden. The DDL is *not* one of the seven-list files:
those are the service schema, and adding to `CnpgRendering.SchemaFiles` would roll every project's
schema ConfigMap.

## R6. Where the backend is chosen and how the settings travel

**Decision**: `Ankka.host` reads `ankka.secrets.backend` (`ANKKA_SECRET_BACKEND`: `postgres` unset
or by name, `secret-manager`). `postgres` builds `DatabaseSecretStore` as today; `secret-manager`
builds `SecretManagerStore(client, project = ankka.cloud.account, identity)` where the identity is
the service's own (`ServiceIdentity` from the certificate, or `ankka.secrets.secret-manager.identity`
in a test). Either is wrapped in `RecordingSecretStore`. A `database: none` service on
`secret-manager` has a working store; on `postgres` it stays `SecretStore.unavailable`. The settings
travel exactly as `ANKKA_OTLP_ENDPOINT` does: ConfigMap key → kustomize replacement → operator and
control plane env placeholder → `Settings` → a literal on the platform container, rendered **only
when set**, so an installation that sets nothing renders what it rendered before
(`RenderingUnchangedSuite`).

`PlatformVariables` gains `ANKKA_SECRET_BACKEND`, `ANKKA_SECRET_MOVE`, `ANKKA_SECRET_RECORDS_URL`,
`ANKKA_SECRET_VERSIONS_KEPT` (all `PlatformOnly` and `RuntimeOnly`), and 044's `ANKKA_CLOUD_PROVIDER`
and `ANKKA_CLOUD_ACCOUNT` with 044's wording — the runtime needs the account and the control plane
refuses `secret-manager` when the provider is `none` (044 FR-012); 044 adds its other two. The
retention and the record database settings are the control plane's alone and are not platform
variables (as `ANKKA_ORGANIZATION_CREATION` is not).

## R7. The move's phases run in the runtime at start and gate readiness

**Decision**: `SecretMove`, a `RuntimeExtension` registered by `Ankka.host` when
`ankka.secrets.move` is set, runs the phase on a virtual thread before readiness:

- `copy`: every `ankka_secrets` row decrypted with the key, `put` to Secret Manager unless the
  derived secret already has an enabled version; then the copy check.
- `check`: the copy check — per name, SHA-256 of the database value against SHA-256 of the
  `latest` enabled version; `equal`, `different`, `missing` (in either place).
- `remove`: the copy check; when every name is equal, `DELETE FROM ankka_secrets`; otherwise leave
  the rows and report the names.

Readiness is held until the phase has run to its end, in every phase, and while Secret Manager is
unreachable in any phase (the extension's `readiness` says no, and the probe answers 503 with the
reason in its body so the kubelet's `Unhealthy` event carries it); `check` and `remove`, once run,
report ready whatever they found. The result is
the `secretStore` section of the observe document (`ObservabilityDocuments.service`): backend,
phase, per-name outcome, which the control plane reads with the topology and folds into
`services get`. The digest, not the value, is what is compared, logged or shown; the runtime never
logs a name's value anywhere in the move.

**Rationale**: the clarify session chose a phased setting with no call path into a service; the
observe document is the one channel from a running instance to `services get`, and readiness is the
one hold the platform already has.

## R8. Kept count: destroy beyond N after the new version is readable

**Decision**: after `addVersion` answers, the store lists the secret's enabled versions (newest
first), and destroys those beyond `ankka.secrets.versions-kept` (`ANKKA_SECRET_VERSIONS_KEPT`, 2 as
shipped), oldest first. A destroy that fails is logged at warn and never fails the `put`: the next
`put` destroys again. `get` reads `versions/latest:access`, which resolves to the newest *enabled*
version, so a version disabled by hand is skipped; the record notes `latestSkipped` when the
version accessed is not the highest-numbered one (the access reply names the version).

## R9. Project secrets on Secret Manager: the control plane writes versions, the provider syncs

**Decision**: `SecretManagerProjectSecretWriter` (control plane) implements `ProjectSecretWriter`:
`setEntries` creates `p_<project>_<name>_<entry>` when absent and adds a version per entry;
`removeEntry` disables every enabled version of the entry's secret. The control plane's identity is
its own ServiceAccount under Workload Identity, with the grant of R3. The sync into the project's
Kubernetes Secret is 044's `secret-sync` request, which the operator renders from
`AnkkaProject.spec.secrets` (names → entries) and `spec.secretsGeneration`, both projected by
`ProjectTopicsTrigger` from the `Project` entity's secret events; the operator holds a service's
rollout while the sync's reported generation is behind the project's. The move of project secrets
is the provider's seeding clause (044 FR-010 amended): an entry the Kubernetes Secret holds and
Secret Manager has no version of is copied up first.

**Dependency**: everything in this entry past the writer is Group B (waits on 044).

## R10. The Secret Manager fake: an HTTP server on loopback that enforces the grants

**Decision**: `FakeSecretManager` in `testkit`: a JDK `HttpServer` on `127.0.0.1:0` (the
`ScriptedService` shape) implementing the REST subset of R1 with Google's status shapes (`403
PERMISSION_DENIED`, `404 NOT_FOUND`, `409 ALREADY_EXISTS`, `400 INVALID_ARGUMENT`), an identity
read from the bearer token (`fake:<project>/<service>`, `fake:controlplane`, `fake:provider`), the
grant rule of R3 applied to every call, `unreachable(true)` and `failNext` for the `Unavailable`
cases, and `snapshot`/`versionsOf` for assertions. `AnkkaTestKit.start(…, secretBackend =
SecretBackend.secretManager(fake))` sets the endpoint, token and identity through `settings`, and
the sidecar's `ConformanceSuite` runs its `secret.*` cases against it under
`-Dankka.conformance.secrets=secret-manager`. The fake lives in testkit's main sources so the
sidecar, the control plane and a developer's own tests can use it.

**Alternatives**: a gRPC fake (needs ScalaPB stubs of Google's protos: a library the runtime does
not have); an emulator (Google ships none); mocking the store trait (tests nothing of the client).

## R11. Secret Manager error mapping and timeouts

**Decision**: `403` → `Internal` naming the missing grant and the derived id; `404` on access →
`get` reads none; `404` on addVersion (secret deleted under a running service) → create and retry
once; `429`, `503`, connect/read timeout, `UnknownHost` → `Unavailable`; `409` on create → the
secret exists, continue with addVersion; any other `4xx`/`5xx` → `Internal` with Google's `status`
and `message`. The store's timeout becomes `ankka.secrets.timeout` (10s as shipped; today a
constant in `DatabaseSecretStore`), shared by both backends.

## R12. Google-side prerequisites the documentation must state

Workload Identity Federation for GKE on the cluster (`--workload-pool=<project>.svc.id.goog`) and
on **every** node pool a service may land on (`--workload-metadata=GKE_METADATA`). The operator
renders no node selector for it: Autopilot refuses the selector and a Standard installation that
mixes pools is told to pin its pools, so a platform setting for it is not added. Also: the Secret
Manager API enabled; Data Access audit logging on for `secretmanager.googleapis.com` (FR-014's
status reads it through the provider — Group B — and says "unknown" until then); the provider's
roles, which are owner-equivalent.

## Cross-spec edits made with this plan

Three lines of `specs/044-cloud-provider/spec.md` were brought in line with 038's clarifications,
each the smallest change that removes a contradiction:

- **FR-005**: `secret-access` carries `own` and `read` as *prefixes* of secret ids (R3), not ids.
- **FR-010**: the seeding clause — an entry the Kubernetes Secret holds and the cloud account has
  no version of is copied up as the first version before anything is synced down (038 FR-020).
- **FR-014**: the one exception — the Secret Manager store of 038, in `runtime`, over the REST API
  with no client library.

## What stays open for the implementation

- Whether the kubelet's `Unhealthy` event carries the probe body (R7); if it does not, the move's
  reason reaches `services get` through the observe document only, read before readiness.
