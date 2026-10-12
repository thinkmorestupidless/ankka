# Quickstart: proving Secret Store Backends

How to show the feature works, from a laptop with Docker to a GKE installation. Each check names
what would make it red: a check that cannot fail proves nothing.

## Prerequisites

- Docker (OrbStack on the development Mac); `sbt`; `uv` on `PATH` for `just features`.
- No Google credential, no network, for everything but the last section.

## 1. The living features and the glossary

```bash
just features
```

Red when a scenario a spec names is missing from `features/secrets/`, a step uses an undefined
word, or two scenarios contradict. Clean today (46 specs, 0 findings).

## 2. Both backends pass one scenario set (US1, US6; SC-001, SC-006)

```bash
sbt 'testkit/testOnly *SecretStore*Suite'          # PostgresSecretStoreSuite and SecretManagerSecretStoreSuite
sbt 'testkit/testOnly *SecretManagerFakeSuite'     # the fake refuses what Google would (backend.feature)
sbt 'testkit/testOnly *DerivedIdsSuite'            # injective, bounded, no prefix is a prefix of another
```

Expected: the same scenario names green under both suites; the Secret Manager suite also asserts
`ankka_secrets` is empty after every put. Red if the kit wires the Postgres store under the
`secret-manager` setting (the table fills), or if the fake is reached with the wrong identity.
Time the two suites against `SecretStoreSuite` on `main`: within +10%.

## 3. Every language, through the sidecar (US1 last scenario)

```bash
sbt 'sidecar/testOnly *ConformanceSuite -- *secret.*'
sbt -Dankka.conformance.secrets=secret-manager 'sidecar/testOnly *ConformanceSuite -- *secret.*'
cd sdks/python && uv run conformance            # likewise typescript, rust
```

Red if the switch is not forwarded in `Test / javaOptions` (the second run would silently run
Postgres: assert the fake's `calls` is non-empty in the suite).

## 4. The read record (US3; SC-004)

```bash
sbt 'testkit/testOnly *ReadRecordSuite'            # record shape, outcomes, never the value, fail-closed
sbt 'controlPlane/testOnly *SecretReadsSuite'      # POST by a service caller, owner listing, 403s, the sweep
sbt 'controlPlane/testOnly *EventCompatibilitySuite'   # no new event carries a value
```

Expected: three gets from two components → three records with component and kind; through the
`ScriptedService` posing as the control plane, a `failNext(503)` makes the next `get` `Unavailable`
and no value is returned; the local recorder's `RecordedReads` holds `sk-acme-1` nowhere (grep the
whole captured log too). Red if a record is posted after the value is returned (the suite's fake
control plane answers slowly and asserts the handler has not returned yet).

## 5. The move (US5; SC-005)

```bash
sbt 'testkit/testOnly *SecretMoveSuite'
```

Expected, per `moving.feature`: start on Postgres with ten secrets; restart with
`secretBackend = secretManager(fake)` and `ANKKA_SECRET_MOVE=copy` → not ready until copied, the
fake holds ten ids, the table still has ten rows; `check` → every name `Equal`; `remove` → zero
rows; `unreachable(true)` during `copy` → not ready, probe body names Secret Manager, rows intact;
set back to Postgres before `remove` → reads from the table, report names the secret kept since.
Red if any log line, status or observe document holds a value (the suite greps everything captured
for each value).

## 6. Project secrets on the Secret Manager backend (US4)

```bash
sbt 'controlPlane/testOnly *ControlPlaneHttpSuite -- *secret*'
```

Expected: `PUT /projects/{id}/secrets/checkout` adds versions under `p_<project>_checkout_*` in the
fake as `fake:controlplane`; the fake refuses that identity an access (`calls` shows the attempt in
the negative case); `DELETE …?entry=` disables; the entity records names only.

## 7. The operator and the settings (FR-003, FR-017; golden suites)

```bash
sbt 'operator/testOnly *RenderingUnchangedSuite *RenderingGoldenSuite *SecretsRenderingSuite'
sbt 'controlPlane/testOnly *PlatformDeclarationSuite *RemoteOverlaySuite'
```

Expected: with no setting, no object changes at all (the record's address is the runtime overlay's
default, not a rendered line); with `secret-manager`, the literals appear on the platform container only. `RemoteOverlaySuite`
asserts each replacement sets the variable once. Red on a second `PlatformVariables`.

## 8. Kubernetes (k3s), on the `cluster` workflow — never on a pull request

```bash
gh workflow run cluster --ref 038-secret-store-backends-impl -f suite=SecretsClusterSuite
```

`controlplane`'s `SecretsClusterSuite` deploys the control plane into k3s with the `secret-reads`
component, and the sample on the Postgres backend, and asserts: the record's table is made in
`ankka-secret-reads-db` as the role that owns it; a `get` through the sample's `/secrets` route inside
the cluster leaves a row; the owner listing (`GET /projects/{id}/secret-reads`, what `ankka projects
secret-reads list` calls) shows it to an owner and refuses a deploy token; the control plane refuses a
record whose body names another service (a probe pod with the sample's certificate posts it); a read
while the control plane is scaled to zero is `503` and leaves no record; the row survives `services
delete`. A plain munit suite, not a Gherkin one: its cases are named after the scenarios they show.
Group B adds the fake provider cases (the hold, the grant's phase in `services get`), named in the
suite and ignored until 044.

## 9. GKE (outside this repository)

`ankka-gcp`'s nightly runs `features/secrets/grants.feature` and `secret-manager.feature` against a
real account: a token minted for one service's ServiceAccount accesses its own secret and is
`PERMISSION_DENIED` on another's and on `secrets.list`; Cloud Audit Logs hold one Data Access entry
per read naming the principal. Until that exists, the checklist for a hand-run installation is in
`docs/platform/secrets.md` ("Verifying an installation").

## Reading a failure

- `Internal … secret access not granted for s_…` — the provider has not written the grant (Group
  B) or the condition names the project id instead of its number.
- `Unavailable … read record not acknowledged` — `ankka.secrets.records-url` unreachable: the control
  plane's `ankka-controlplane-http` policy, the service's certificate, or the record database.
- A suite that passes in 3s on both backends ran the fake for neither: check the kit's `settings`.
