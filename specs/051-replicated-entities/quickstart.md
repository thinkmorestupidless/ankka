# Quickstart: proving replicated entities work

Each feature file has one suite that runs it whole. Before trusting a green run, break the thing
once and watch it go red: the last column says how.

## Prerequisites

Docker (OrbStack on the development Mac) for the shared Postgres: every installation in a test is
its own database in the one container. `uv`, `node`/`npm` and `cargo` for the SDK halves; the sidecar
image built from this branch for the Python and TypeScript kits:

```bash
sbt docker:publishLocal
export ANKKA_SIDECAR_IMAGE=ankka-sidecar:$(git rev-parse --short HEAD)   # never :latest for a branch test
```

## The spike, first

The evidence the design rests on still runs:

```bash
sbt -Dankka.spikes=on 'testkit/testOnly *ReplicationOrderSpike'
```

Expect `C applied : B6 B7 B8 A1 A2 A3 A4 A5` for the naive flavour. If Pekko ever changes that,
research R1 needs rereading.

## The living features

| Feature file | Suite | Run | Red when |
|---|---|---|---|
| `features/replication/home.feature` | `testkit/…/replication/HomeFeatures` | `sbt 'testkit/testOnly *HomeFeatures'` | make the follower's puller skip the sequence check: "same history in the same order" still passes, so also compare bytes; drop the `installations.isEmpty` short-circuit: "behaves as it did before" fails on the journal bytes |
| `creation.feature` | `CreationFeatures` | `sbt 'testkit/testOnly *CreationFeatures'` | never write kind 7: "marked in conflict" fails; let a copy in conflict persist: "refuses writes" fails |
| `forwarding.feature` | `ForwardingFeatures` | `sbt 'testkit/testOnly *ForwardingFeatures'` | handle a persisting command at the follower locally: "answered with the home's reply" passes and the home's journal does not grow, so the second assertion is the one that must be there; serve reads outside the set: "refused naming the home" fails |
| `handover.feature` | `HandoverFeatures` | `sbt 'testkit/testOnly *HandoverFeatures'` | write kind 3 without waiting for the report: "waits until the new home holds everything" fails; let the follower switch source before the kind 3 record: "lagged through a handover" fails on order |
| `replication-set.feature` | `ReplicationSetFeatures` | `sbt 'testkit/testOnly *ReplicationSetFeatures'` | keep pulling after kind 4 removes self: "stops mirroring" fails; default the set to every installation: "held by its home alone" fails |
| `projections.feature` | `ProjectionsFeatures` | `sbt 'testkit/testOnly *ProjectionsFeatures'` | hard-code `localOrigin = true` again: "publishes each change once" counts two at the broker |
| `transport.feature` | `TransportFeatures` | `sbt 'testkit/testOnly *TransportFeatures'` | fall back to the runtime's transport with no `db.*`: "refuses to start" passes for the wrong reason, so assert the message names the transport |
| `history.feature` | `HistoryFeatures` | `sbt 'testkit/testOnly *HistoryFeatures'` | write kind 3 at sequence 1 for an existing entity: "keeps its history" fails on the first record |
| `languages.feature` (Scala rows and the old-runtime scenario) | `LanguagesFeatures` | `sbt 'testkit/testOnly *LanguagesFeatures'` | Python, TypeScript and Rust rows report `ranElsewhere`; a run showing them passed here has bound them to nothing |
| `languages.feature` (Python rows) | `sdks/python/tests/test_replicated.py` | `cd sdks/python && uv run pytest -q tests/test_replicated.py` | point `ANKKA_SIDECAR_IMAGE` at an image before the minor: discovery must refuse, naming it |
| `languages.feature` (TypeScript rows) | `sdks/typescript/tests/replicated.test.ts` | `cd sdks/typescript && npm run test:slow -- tests/replicated.test.ts` | likewise |
| `languages.feature` (Rust rows) | `sdks/rust` conformance | `cd sdks/rust && ./conformance.sh` | the `es.replicated-context` case |
| `testkit.feature` | `TestkitFeatures` | `sbt 'testkit/testOnly *TestkitFeatures'` | remove `Mode.Follower` from the pure kit: "refused as not at the home" fails |
| `features/documentation/replicated-entities.feature` | `testkit/…/ReplicatedEntitiesDocumentationSuite` | `sbt 'testkit/testOnly *ReplicatedEntitiesDocumentationSuite'` | delete the "One installation" paragraph from `docs/reference/limitations.md` |

The whole directory in one go:

```bash
caffeinate -i sbt 'testkit/testOnly *replication.*'
```

munit's filter matches the full name: a glob without the leading `*` matches nothing and reports
green with zero tests. Check the count.

## The compatibility fixtures

```bash
sbt 'testkit/testOnly *JournalRecordCompatibilitySuite'
```

`modules/testkit/src/test/resources/journal/journal-record.txt` and `state-record.txt` are pinned
from `main` before any field is added. The suite reads them with the new case classes and the new
form into copies of the old ones. If the pinned bytes change in a diff, the inclusion annotation is
missing on a new field (`.claude/rules/messaging.md`, the Jackson `null` trap).

## Two installations on one machine

The fixture service the features drive has a main for this, `FleetMain` in `testkit`'s tests:

```bash
docker compose up -d
createdb -h localhost -U ankka fleet_a && createdb -h localhost -U ankka fleet_b   # the DDL from kustomization/components/postgres/ddl/
ANKKA_INSTALLATION=a ANKKA_DB_NAME=fleet_a ANKKA_INSTALLATION_B_DB_NAME=fleet_b ANKKA_HTTP_PORT=9000 \
  sbt 'testkit/Test/runMain com.thinkmorestupidless.ankka.testkit.replication.FleetMain'
ANKKA_INSTALLATION=b ANKKA_DB_NAME=fleet_b ANKKA_INSTALLATION_A_DB_NAME=fleet_a ANKKA_HTTP_PORT=9001 \
  sbt 'testkit/Test/runMain com.thinkmorestupidless.ankka.testkit.replication.FleetMain'
```

Create a vehicle at `:9000` with `b` in its replication set, read it at `:9001`, write to it at
`:9001` and see the write land at `a`. `ankka services` on the local console shows both
installations' entities with their replication state (`ankka:replication`).

## The conformance suite

```bash
sbt 'sidecar/testOnly *ConformanceSuite'                                        # the Scala reference
cd sdks/python && uv run conformance                                             # the Python process
cd sdks/typescript && npm run conformance                                        # the TypeScript process
cd sdks/rust && ./conformance.sh                                                 # the module
```

Each must pass `es.replicated-declares`, `es.replicated-context`, `es.replicated-effects` and
`consumer.mirrored-declares`.

## What a passing run does not prove

- Replication between two Kubernetes installations: that is `ankka-replication`'s transport, and
  this repository has no suite for it. A k3s suite with two namespaces is a later addition once the
  private transport exists.
- The wake daemon's cost at scale: one `eventsBySlices` per sibling per replicated component. A
  measurement belongs with the private transport, which replaces it.
