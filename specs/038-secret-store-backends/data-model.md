# Data Model: Secret Store Backends

**Feature**: 038-secret-store-backends | **Date**: 2026-10-08

## Settings

| Variable | Declared in | Set by | Read by | Values | Default |
|---|---|---|---|---|---|
| `ANKKA_SECRET_BACKEND` | `PlatformVariables` (PlatformOnly, RuntimeOnly) | `ankka-platform` ConfigMap → operator, control plane; operator → every platform container | runtime (`ankka.secrets.backend`), control plane | `postgres`, `secret-manager` | `postgres` |
| `ANKKA_SECRET_MOVE` | same | ConfigMap → operator → platform container | runtime (`ankka.secrets.move`) | `copy`, `check`, `remove`, unset | unset |
| `ANKKA_SECRET_RECORDS_URL` | same | an installation whose control plane is elsewhere; otherwise nobody | runtime (`ankka.secrets.records-url`) | URL | the Kubernetes overlay's `https://ankka-controlplane.ankka-controlplane.svc:9000`; unset locally = `LocalRecorder` |
| `ANKKA_SECRET_VERSIONS_KEPT` | same | ConfigMap → operator → platform container | runtime (`ankka.secrets.versions-kept`) | integer ≥ 1 | `2` |
| `ANKKA_CLOUD_PROVIDER` | `PlatformVariables` (044's wording) | ConfigMap → operator, control plane | control plane (refuses `secret-manager` under `none`) | `none`, `gcp` | `none` |
| `ANKKA_CLOUD_ACCOUNT` | same | ConfigMap → operator → platform container | runtime (`ankka.cloud.account`: the Google Cloud project id), control plane | string | unset |
| `ANKKA_CLOUD_LOCATION` | 044 (declared here with 044's wording, as the two above) | ConfigMap → operator → platform container | runtime (`ankka.cloud.location`: user-managed replication in it when set) | string | unset = automatic replication |
| `ANKKA_CLOUD_KMS_KEY` | 044 (not declared here) | — | runtime passes through on create when set | resource name | unset |
| `ANKKA_SECRET_RECORD_RETENTION` | control plane only | ConfigMap → control plane | `ReadRecordSweeper` | duration | `365d` |
| `ANKKA_SECRET_RECORDS_DB_{HOST,PORT,NAME,USER,PASSWORD}` | control plane only | `ankka-secret-reads-db-app` Secret | second r2dbc pool | — | — |

Runtime config keys (`reference.conf`): `ankka.secrets.{backend, timeout = 10s, record-timeout =
5s, records-url, move, versions-kept = 2}`, `ankka.secrets.secret-manager.{endpoint =
"https://secretmanager.googleapis.com", token = "" (metadata server when empty), identity = ""
(the certificate's when empty)}`, `ankka.cloud.{provider, account, location}`. The Kubernetes
cluster overlay (`ankka-cluster-kubernetes.conf`) sets `ankka.secrets.records-url` to the control
plane's in-cluster address; the local overlay leaves it empty.

## Secret backend (runtime)

```
enum SecretBackend: Postgres | SecretManager
SecretBackend.parse(config): Either[String, SecretBackend]     // refuses an unknown word
```

Chosen once per process at `Ankka.host`. `Postgres` + `database: none` → `SecretStore.unavailable`
(as today). `SecretManager` needs `ankka.cloud.account` and an identity, or start is refused naming
the missing one.

## Derived id (runtime `DerivedIds`)

| Input | Id | Prefix |
|---|---|---|
| service secret `name` of `<service>` in `<project>` | `s_<project>_<service>_<enc(name)>` | `s_<project>_<service>_` |
| entry `<entry>` of project secret `<secret>` of `<project>` | `p_<project>_<enc(secret)>__<enc(entry)>` | `p_<project>_` |

`enc`: `_` → `_u`, `.` → `_p`, `/` → `_s`, else the character — a prefix code whose output never
holds `__` and never ends in `_`, so the first `__` of an entry id ends the secret's name (an entry
of a Kubernetes Secret may contain `_`, as `STRIPE_KEY` does). When the id would exceed 255
characters: `<prefix>_<sha256-hex(name)>` (the prefix's trailing `_` plus `_` gives `__` and a hex
digit, where an encoded `_` is followed by `u`, `p` or `s`). Invariants (`DerivedIdsSuite`): ids are
distinct for distinct names; every id matches `[A-Za-z0-9_-]{1,255}`; no prefix is a prefix of
another prefix; a digest id is never an encoded one. Secret Manager's annotation keys allow no `/`,
so the names are kept as `ankka-project`, `ankka-service` and `ankka-name` on a service secret, and
`ankka-project`, `ankka-project-secret` and `ankka-entry` on an entry.

## Secret Manager objects (what the store creates)

- **Secret** `projects/<account>/secrets/<id>`: `replication.automatic` (or `userManaged` with the
  installation's locations), `annotations` as above, optional `customerManagedEncryption.kmsKeyName`.
- **SecretVersion** `…/versions/<n>`: payload ≤ 64 KiB (= `SecretRules.MaxValueBytes`); state
  `ENABLED` → `DISABLED` (project secret entry removed) or `DESTROYED` (kept count). A service
  secret's version is never `DISABLED` by the platform.
- `versions/latest` resolves to the newest `ENABLED` version.

## Read record (core `ReadRecord`; control plane table `secret_reads`)

| Field | Type | Notes |
|---|---|---|
| `id` | bigserial | table only |
| `at` | timestamptz | the runtime's clock at the operation |
| `project` | text | must equal the caller's certificate |
| `service` | text | must equal the caller's certificate |
| `hosting` | text | `embedded`, `process`, `module` |
| `name` | text | the secret's name, never the value |
| `operation` | text | `get`, `put`, `delete` |
| `outcome` | text | `read`, `none`, `refused`, `unavailable`, `written`, `removed` |
| `backend` | text | `postgres`, `secret-manager` |
| `trace_id` | text, nullable | from `Trace.currentContext` |
| `span_id` | text, nullable | the handler's span: "the request" of FR-013 |
| `component` | text, nullable | from `Trace.currentOrigin`; absent through the sidecar |
| `component_kind` | text, nullable | from `ComponentRegistry` |
| `latest_skipped` | boolean | Secret Manager: the version read was not the highest-numbered |

Indexes: `(project, service, at desc)`, `(project, name, at desc)`, `(at)` for the sweep. The DDL
is `kustomization/components/secret-reads/ddl/10-secret-reads.sql`, applied by that cluster's
`postInitApplicationSQLRefs` with a `99-grants.sql` literal; it is not one of the service schema's
seven-list files. Retention: rows with `at < now() - retention` are deleted daily.

Wire (controlplane-api): `SecretReadRecord` (the fields above, camel-cased, no `id`) and
`SecretReadsPage(records: Vector[SecretReadRecord])`, codecs in `Wire`.

## Move (runtime `SecretMove`)

```
enum MovePhase: Copy | Check | Remove
enum NameState: Equal | Different | MissingInSecretManager | MissingInDatabase
CopyCheckResult(names: Map[String, NameState])
MoveReport(phase, outcome: Copied | Checked | Removed | Refused | Unreachable, names, detail)
```

Transitions at start, readiness held until the phase has run to its end: `Copy` → copy rows not
already held → check → `Copied` (ready); `Check` → check → `Checked` (ready); `Remove` → check →
all equal: delete rows → `Removed` (ready); else `Refused` naming the names (ready, rows left). In
any phase, Secret Manager unreachable → `Unreachable`: not ready with the reason, nothing changed,
rows left. The report is held in memory and served in the observe document until the next start.

## Observe document (`ObservabilityDocuments.service`)

```json
"secretStore": {
  "backend": "secret-manager",
  "keyRead": false,
  "move": { "phase": "remove", "outcome": "Refused", "names": [{"name": "stripe", "state": "Different"}] }
}
```

`move` is absent when no phase is set. Values never appear; names do (they are what `services get`
shows today for a `secretKeyRef` problem, which the lifecycle rules already permit).

## The move's ledger (the service's own database)

```
ankka_secret_moves(name TEXT PRIMARY KEY, at TIMESTAMPTZ NOT NULL DEFAULT now())
```

Made by the move when it first runs, as the role the service connects as; names only, never a value.
A row per service secret kept on the Secret Manager backend during a move that the database does not
hold (reported as `only-in-secret-manager` by a service set back to Postgres), and the row
`*removed*` — a name no secret can have — once the removal step has run. An instance on the Postgres
backend that finds `*removed*` refuses to start through `StartRefusal`, naming the time and the
setting to change (FR-021); the operator reports it as the service's detail, as any start refusal.
Not in the service schema's seven-list DDL: it exists only in a database that has moved.

## AnkkaService status (crd) — Group B

`SecretStoreStatus(phase, detail)` on `AnkkaServiceStatus`, for the provider's answer to a secret
access request (`Waiting`, `Ready`, `Failed`), is spec 044's work and comes with T065. The move's
phase and outcome are not on the resource: `services get` reads them from the instances' topology
document (`secretStore`) through the control plane.

## Installation status (control plane `GET /platform`)

```
PlatformStatus(secretBackend, cloudProvider, cloudAccount: Option, cloudLocation: Option,
               secretRecordRetention, auditLog: on | off | unknown)
```

Read by any authenticated principal; the KMS key is never in it (044 FR-019 adds what an owner may
see). `auditLog` is `unknown` until 044's provider reports it.

## AnkkaProject spec (crd) — Group A projection, Group B consumer

```
AnkkaProjectSpec.secrets: Map[String, Vector[String]]   // project secret name → entries
AnkkaProjectSpec.secretsGeneration: Long                 // count of secret events folded
```

Projected by `ProjectTopicsTrigger` beside topics; the operator renders 044's `secret-sync` from
it (Group B).

## Secret Manager fake (testkit `FakeSecretManager`)

```
Identity = Service(project, service) | ControlPlane | Provider | Anonymous   // from the bearer token
Grant rule (R3): create → any identity;
  Service(p, s): add/access/list/destroy/get/delete on ids starting s_<p>_<s>_; access on p_<p>_
  ControlPlane: add/list/disable on p_*; never access
  Provider: access on p_*; never s_*
State: Map[id, Secret(annotations, versions: Vector[Version(n, bytes, state)])]
Controls: unreachable(Boolean), failNext(status), snapshot, versionsOf(id), calls (a log of (identity, method, id))
```

Answers Google's error shape `{"error": {"code", "status", "message"}}` with `PERMISSION_DENIED`,
`NOT_FOUND`, `ALREADY_EXISTS`, `INVALID_ARGUMENT`, `UNAVAILABLE`, `RESOURCE_EXHAUSTED`.

## Control plane project secret writer (Secret Manager backend)

`setEntries(project, name, entries)`: per entry, `createSecret(p_…)` (409 ignored) then
`addVersion`; the `Project` entity records names only, as today. `removeEntry`: list enabled
versions, disable each. Identity: the control plane's own token (`fake:controlplane` in tests).
