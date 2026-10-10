# Quickstart: validating Workflow Sources

The runs that show the feature works, by user story. Docker is required for every suite but the
pure ones. Contracts are in [contracts/](contracts/), shapes in [data-model.md](data-model.md).

## Before anything else: the claims the design rests on

Each is a test written before the code that depends on it (research.md, *verify first*).

```bash
sbt 'testkit/testOnly *WorkflowRecordCompatibilitySuite'   # R2: a state record without the field reads back; the new form round-trips
sbt 'runtime/testOnly *WorkflowChangesSuite'               # R3: seven kinds to three changes; both state packings; Unknown
sbt 'runtime/testOnly *ViewProjectionsSuite'               # R4: no existing view's projection name moved
sbt 'testkit/testOnly *WorkflowSourceSuite'                # R4: a row per state record, exactly once across a restart
```

## User Story 1 — a view lists where every checkout stands

```bash
sbt 'testkit/testOnly *WorkflowSourcesFeatures'
```

Expected: every scenario of `features/workflow-sources/views.feature` reported as a test of its
own, none ignored. In particular:

- `c1` ends with the row's standing `Completed` and the state `charge` recorded;
- `c2`, whose compensation records the failure, has the standing `Failed` and the reason;
- `c4`, which fails at `charge` with no compensation, has no change for the failure and its row is
  as it was;
- `c8`, a state recorded by the previous release (the suite writes the old record form through the
  compatibility suite's fixture), is delivered with the standing `Unknown`.

**Break it once**: in the engine, stamp the standing *before* the batch is folded (the standing of
the `Run` before the effect) and the `Completed` scenario must go red with `Running`; stop
`WorkflowChanges.read` returning `Nothing` for a `fail` record and the `c4` scenario must go red.

## User Story 2 — a consumer reacts when a workflow ends

```bash
sbt 'testkit/testOnly *WorkflowSourcesFeatures -- "*consumers*"'
sbt 'testkit/testOnly *ConsumerTestKitSuite'
```

Expected: one message on `transfers-settled` for `t1`, after its end and not before; one call to
`ledger` for `t2`; `t3`, timed out without a state, no change; a deleted `t1` runs the deletion
handler. `ConsumerTestKitSuite` hands a consumer a standing and reads what it published.

**Break it once**: deliver every record rather than `state` records and the "no change for the
changes before its end" assertion must go red (the consumer sees a `transition` with the standing
`Running` and must not publish for it, but the first assertion counts deliveries).

## User Story 3 — every language

```bash
sbt 'sidecar/testOnly *ConformanceSuite -- "*workflow*"'                                       # the Scala reference
cd sdks/python && uv run pytest -q && uv run conformance                                        # Python
cd sdks/typescript && npm run proto && npm test && npm run conformance                          # TypeScript
cd sdks/rust && cargo test --workspace && ./conformance.sh                                      # Rust
sbt 'sidecar/testOnly *ProtocolSuite *TranslateSuite'                                           # the field crosses both ways
```

Expected: the seven cases in `contracts/sdk-apis.md` green against each reference;
`discovery.lists-every-component` green, which means all four references added both components;
`discovery.workflow-source-needs-1.15` green, which means each SDK refuses an older runtime rather
than running without a standing.

**Break it once**: remove the standing from the Python request decoding and
`view.workflow-completed` must go red against the Python reference (the row's standing is missing),
while the Scala reference stays green.

## User Story 4 — rebuild and bounds

```bash
sbt 'runtime/testOnly *KeyedViewRulesSuite *TopologyJsonSuite'
sbt 'testkit/testOnly *WorkflowSourcesFeatures -- "*rebuilt*" "*topic and a workflow*" "*one change at a time*"'
sbt 'testkit/testOnly *TopologyFeatures'
```

Expected: a view over `checkout` at version 2 holds only version-2 rows for every checkout that
recorded a state; a service with a view over a topic and a workflow does not start, naming the
rule; a keyed view over `order` and `checkout` handles one change at a time; the topology shows a
`workflow` connection from `checkout` to `checkouts`, and the local console's legend reads it as "a
workflow subscription".

**Break it once**: name the workflow source's projection with the version appended to the id
instead of through `ViewProjections.name` and `ViewProjectionsSuite` must go red.

## User Story 5 — documentation

```bash
just docs-sync && just docs
just features
```

Expected: `docs check` passes with the new sections and the regenerated skills; the bdd checker
reports 0 findings; `features/documentation/workflow-sources.feature`'s three scenarios are
satisfied by the views, consumers, workflows and divergences pages.

## The whole thing, as CI runs it

```bash
sbt -Dankka.cluster.tests=off test
sbt scalafmtCheckAll scalafmtSbt
cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run test:slow && npm run conformance
cd sdks/rust && cargo test --workspace && cargo test -p shopping-cart --features slow && ./conformance.sh
just features && just docs
```

No k3s suite: nothing here depends on where a service runs.
