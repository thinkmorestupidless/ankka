# Quickstart: validating backup and recovery

The runs that show the feature works, cheapest first. Docker is required from step 2. Contracts:
[project-and-service](contracts/project-and-service.md), [operator](contracts/operator.md),
[installation](contracts/installation.md). Data: [data-model.md](data-model.md). Decisions:
[research.md](research.md).

Every command switches the k3s suites off unless it is one; a k3s run is minutes and belongs under
`caffeinate -i` on a laptop, and the user runs them through the `cluster` workflow in CI
(`gh workflow run cluster --ref <branch> -f suite=<Name>`).

## 1. Pure: names, settings, rendering, the SQL, the entities, the wire

```bash
sbt -Dankka.cluster.tests=off \
    'crd/testOnly *BackupNamesSuite *CrdSchemaSuite *AnkkaProjectCodecSuite' \
    'core/testOnly *PlatformVariablesSuite' \
    'controlPlaneApi/testOnly *DatabaseSettingSuite *ReservedSecretNamesSuite *ControlPlaneFixturesSuite *DocumentationDescriptorsSuite' \
    'operator/testOnly *BackupSettingsSuite *CnpgRenderingSuite *RestoreRenderingSuite *DatabaseQueriesSuite *RenderingGoldenSuite *RenderingUnchangedSuite *CrdSchemaSuite' \
    'controlPlane/testOnly *ProjectEntitySuite *ServiceEntitySuite *EventCompatibilitySuite *RestoreHoldSuite *PlatformDeclarationSuite'
```

Expect: `platform.backups.shop` and the rehearsal namespace refused as a project id; a retention
override below the floor refused naming the floor; a second restore refused naming the first; a
switch to a cluster without the service's database refused naming the moment; every rendered object
of the operator contract as a golden file; `RenderingUnchangedSuite` green with no Deployment
changed (`git diff --stat operator/src/test/resources/unchanged` shows only the `Cluster` variant
added); the SQL parsers on fixture rows; every new event field defaulted and the pre-feature JSON
replaying; the hold computing the differences from a fixture of resources.

To see a check fail once: remove `databaseCluster` from `ankkaservice.yaml` and run `CrdSchemaSuite`;
set `ANKKA_DB_HOST` in the Secret instead of the literal and run `RestoreRenderingSuite`.

## 2. A real Postgres: the line of history, the message id, the pool

```bash
sbt -Dankka.cluster.tests=off \
    'testkit/testOnly *HistoryLinesSuite *MessageIdSuite' \
    'runtime/testOnly *DivergenceSuite *PoolRecoverySuite'
```

Expect: a line row written once per `ANKKA_DB_LINE`; an event with a `db_timestamp` before the
second line's start published with the first line's id, twice the same; a new event's id on the
second line; `ProduceAll`'s messages indexed; a promotion (no row) changing nothing; the divergence
answer against the in-memory broker and against Kafka in a container; a connection killed under the
pool (`pg_terminate_backend`) not handed out, with `SELECT 1` on acquire.

## 3. The spikes (once, before the rendering exists)

```bash
caffeinate -i sbt -Dankka.spikes=on 'operator/testOnly *BackupSpike'
```

Expect: S1 (a running cluster gains `plugins` and `archive_timeout` with every pod's `startTime`
unchanged), S2 (`exec` answers `psql`; a pod's `ANKKA_DB_HOST` is the literal over the Secret's),
S3 (a two-database cluster restored to a moment lists both databases, both roles and `ankka_tls`,
and no `app`).

## 4. The k3s suites, one feature file each

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' \
    'controlPlane/testOnly *BackupsClusterFeatures'          # US1: archive, first base backup, failure within 5 min, clears
caffeinate -i sbt 'controlPlane/testOnly *RestoringClusterFeatures'      # US2: A and not B; switch one; back
caffeinate -i sbt 'controlPlane/testOnly *AfterRestoreClusterFeatures'   # US3: the ids, the count, the listing (Strimzi; longest)
caffeinate -i sbt 'controlPlane/testOnly *ReplicasClusterFeatures'       # US4: primary pod and PVC deleted; 60 s; nothing lost
caffeinate -i sbt 'controlPlane/testOnly *RehearsalsClusterFeatures'     # US5: the throwaway, the report, the grant, the expiry
caffeinate -i sbt 'controlPlane/testOnly *ControlPlaneRestoreClusterFeatures'   # US6: the overlay as written; held; released
caffeinate -i sbt 'controlPlane/testOnly *DurabilityClusterFeatures'     # US7: three Garage pods; the secondary; a deletion mirrored
sbt 'operator/testOnly *OperatorClusterSuite'                            # the grant: delete refused, allowed in a rehearsal namespace
```

Expect each scenario reported as it ends. The backup failure case prints the measured time from
denying the key to the status saying failing, and from restoring it to the status clearing; both
under five minutes. The replicas case prints the time from the primary's deletion to the first
successful write after it; under sixty seconds. `.github/cluster-suites.py` lists the seven new
classes without being edited.

## 5. The console, the docs, the features

```bash
just test-console                 # the project page's database section; the restore journey in Playwright
just docs                         # every page, the generated route and CLI tables (just docs-reference first)
just features                     # the seven feature files, the glossary, the spec's references
sbt -Dankka.cluster.tests=off 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite' 'cli/testOnly *CliReferenceSuite'
```

## 6. By hand, on kind

```bash
kind create cluster --name ankka --config kustomization/kind.yaml && ./kustomization/deploy-local.sh
ankka status                                              # target object-store; shares the cluster's failure domain
sbt shoppingCart/run  # or: ankka services deploy cart sample-shopping-cart:latest -p shop
ankka projects status -p shop                             # backing up; last base backup within a minute; lag under 60 s
# write to the cart; note the time; write again
ankka projects restore -p shop 2026-10-08T10:20:00Z       # a name; `restores get` until Verified
ankka services switch cart -p shop --to ankka-db-r202610081021
ankka projects status -p shop                             # cart on the restore; ankka-db listed as left
ankka services switch cart -p shop --to ankka-db
ankka projects rehearse -p shop 2026-10-08T10:20:00Z && ankka projects rehearsals -p shop
```

Then the control plane's procedure from `docs/operate/recovery.md`, which ends with
`ankka installation restore --release`.

## 7. SC-007 on the reference installation

A project with 50 GiB written (the shopping cart under its load script), `ankka projects rehearse`,
and the report's elapsed time under 3600 s. Recorded in `docs/operate/recovery.md` with the date.
