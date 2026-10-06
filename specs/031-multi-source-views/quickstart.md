# Quickstart: validating Multi-Source Views

The runs that show the feature works, by user story. Docker is required: every suite here but the
pure ones starts Postgres. Contracts are in [contracts/](contracts/), shapes in
[data-model.md](data-model.md).

## Before anything else: the claims the design rests on

Each is a test written before the code that depends on it (research.md, *verify first*).

```bash
sbt 'runtime/testOnly *QueryCheckSuite'          # R7: what JSqlParser reads, finds and distinguishes
sbt 'testkit/testOnly *DeclaredQuerySuite'       # R8: read-only transaction, statement_timeout, 57014
sbt 'testkit/testOnly *KeyedViewSuite'           # R3: the lock through an exactly-once session
sbt 'testkit/testOnly *EntityViewVersionSuite'   # R10, R11: version-named projections, pause
```

## User Story 1 — a declared, recursive query

```bash
sbt 'runtime/testOnly *QueryCheckSuite'
sbt 'testkit/testOnly *DeclaredQueriesFeatures *RecursiveQueriesFeatures'
```

Expected: every scenario of `features/views/declared-queries.feature` and
`recursive-queries.feature` reported as a test of its own, none ignored. In particular:

- the thousand-row tree answers 999 rows in one statement;
- a service declaring a statement that writes, holds two statements, or reads another table does
  not start, and a connection-level counter shows the database was sent nothing for it (SC-002);
- the cycle case ends with `Timeout` and `pg_stat_activity` shows no statement still running.

**Break it once** (the cheapest proof each check can fail): remove Q6 from `QueryCheck` and the
"reads another view's table" scenario must go red; remove `SET LOCAL statement_timeout` and the
cycle scenario must hang to the suite's own timeout.

## User Story 2 — a keyed view

```bash
sbt 'core/testOnly *RowChangesSuite'
sbt 'testkit/testOnly *KeyedViewTestKitSuite *SeveralSourcesFeatures *KeyedViewSuite'
sbt 'runtime/testOnly *TopologyJsonSuite'
```

Expected: `several-sources.feature`'s thirteen scenarios each a passing test. `KeyedViewSuite`
holds what a feature cannot say: SC-006 (two sources each writing one row a thousand times on two
instances; the row's counter is 2000) and SC-003 (row count equal to distinct keys, before and
after a restart).

**Break it once**: take the lock shared instead of exclusive and SC-006 must lose writes.

## User Story 3 — rebuild

```bash
sbt 'runtime/testOnly *TopicSourceRulesSuite *ViewProjectionsSuite'
sbt 'testkit/testOnly *EntityViewVersionSuite *ViewVersionSuite'
```

`ViewProjectionsSuite` is what holds an existing view to the name its offsets are under, and two
views to names neither's id can spell. A plain view whose version-1 name changed would read its
whole source again on upgrade, with no test in a fresh database able to see it.

Expected: `rebuilding.feature`'s seven scenarios as named cases; 024's `ViewVersionSuite`
unchanged and green; `TopicSourceRulesSuite`'s refusal case renamed to "a version on a consumer
that reads an entity is refused" and asserting a view is *not* refused.

**Break it once**: drop the guard from the plain view's write and the rolling-update case must
find a row written at version 1.

## Every language

```bash
sbt 'sidecar/testOnly *ProtocolSuite *RemoteProjectionSuite *WasmHostSuite'
sbt 'sidecar/testOnly *ConformanceSuite'                                    # the Scala reference
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run test:slow && npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
```

Expected: the five `languages.feature` scenarios as conformance cases, passing for each target.
Read what each run says it ran: a filter that matched nothing reports green.

## What must not have changed

```bash
sbt 'shoppingCart/test'                       # CartViewSuite: a plain view, as it was
sbt -Dankka.cluster.tests=off test            # everything else
just features                                 # 031 clean; the other specs' findings are theirs
just docs                                     # every page, sample and generated table
```

`CartViewSuite`'s wall time is compared with `main`'s before merge: the guard adds two statements
to each change of a plain view (R10), and that should be within noise.

## The documentation scenarios

Read `docs/build/views.md` and `docs/reference/limitations.md` against the five scenarios of
`features/documentation/views.feature` and the changed scenario of
`features/documentation/topic-sources.feature` (a rebuild of a view reading an entity is not
bounded by what a broker retains), and `docs/build/topics.md`'s sentence that a version applies to
a topic source only, which is no longer true of a view.
