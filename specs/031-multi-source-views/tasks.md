# Tasks: Multi-Source Views — Several Sources, an Explicit Row Key, and a Recursive Read

**Input**: Design documents from `/specs/031-multi-source-views/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" claims each become a test
before the code that relies on them. Where a task says "case", it means a `test(...)` in the named
suite (or its equivalent in the SDK's test runner). A case that proves a scenario of `features/`
is named with that scenario's name, so the two can be found from each other; a feature file a
`GherkinSuite` runs needs no named cases, the suite makes one test per scenario.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a view walks a tree with a recursive query), US2 (two sources keep one row up
  to date), US3 (a view that reads entities is rebuilt when its version is raised)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CORE`/`CORET` =
`modules/core/src/{main,test}/scala/…/core`; `SDK` = `modules/sdk/src/main/scala/…/sdk`;
`RT`/`RTT` = `modules/runtime/src/{main,test}/scala/…/runtime`; `TK`/`TKT` =
`modules/testkit/src/{main,test}/scala/…/testkit`; `SC`/`SCT` =
`sidecar/src/{main,test}/scala/…/sidecar`; `CONF` = `SCT/conformance`; `CPA`/`CPAT` =
`controlplane-api/src/{main,test}/scala/…/controlplane/api`; `PROTO` =
`protocol/src/main/protobuf/ankka/protocol/v1`; `PY` = `sdks/python`; `TS` = `sdks/typescript`;
`RS` = `sdks/rust`; `FEAT` = `features/views`; `DOCS` = `docs`. "R*n*" is a section of
`research.md`; "Q*n*" and "C*n*" are rules of `contracts/declared-queries.md`; "K*n*", "E*n*" and
"O*n*" of `contracts/keyed-views.md`; "T*n*" and "L*n*" of `contracts/rebuild.md`; "D*n*" and
"S*n*" of `contracts/protocol.md`. A contract is named by its file under `contracts/`.

The branch `031-multi-source-views` exists, in the worktree
`.claude/worktrees/031-multi-source-views`, on `main` at feature 024. Work there. The feature
files, the glossary, the spec and the plan documents are written and uncommitted.

---

## Phase 1: Setup — the build has the parser, and the baseline is known

- [X] T001 Add `jsqlparser` (`com.github.jsqlparser:jsqlparser`, the latest 5.x) to `project/Dependencies.scala` as `V.jsqlparser` and a `val jsqlparser`, and to `runtime`'s `libraryDependencies` in `build.sbt` as a direct entry (R7; a consumer of `ankka-runtime` must see it in the POM). Run `sbt runtime/compile` and `sbt 'show runtime/libraryDependencies'`, and confirm `sbt cli/compile` does not gain it.
- [X] T002 Record the baseline the feature must not disturb: run `sbt 'shoppingCart/testOnly *CartViewSuite' 'testkit/testOnly *ViewVersionSuite *TopicSourceSuite *KeyValueDeletionSuite' 'runtime/testOnly *TopicSourceRulesSuite *TopologyJsonSuite' 'sidecar/testOnly *RemoteProjectionSuite *ProtocolSuite'`, note in the pull request description that they pass, and note `CartViewSuite`'s wall time: it is compared again in T086.
- [X] T003 [P] Run `python3 .github/ci-coverage.py` with the uncommitted files present (`features/views/`, `features/documentation/views.feature`, `specs/031-multi-source-views/`) and confirm each is claimed by an existing filter and no pattern is left matching nothing.

**Checkpoint**: `runtime` compiles with the parser; the suites this feature changes are known green.

---

## Phase 2: Foundational — protocol 1.13, written once

**Purpose**: every wire addition of this feature, in one edit, so no runtime ever speaks 1.13 and
does nothing with part of it (plan, "Slices").

**⚠️ CRITICAL**: blocks every user story's sidecar and SDK work. It changes no behaviour: the new
fields are generated and nothing reads them until the story that uses them.

- [X] T004 Edit `PROTO/discovery.proto` (`ViewDetail.sources = 5`, `ViewDetail.declared_queries = 6`, `message DeclaredQuery`, and the comment on `version` widened to entity sources), `PROTO/view.proto` (`ViewRequest.source_id = 6`, `ViewEffect.rows = 4`, `RowChanges`, `RowChange`) and `PROTO/client.proto` (`QueryRequest.values = 5`, `limit = 6`), exactly as `contracts/protocol.md` gives them, each new field commented `1.8`.
- [X] T005 Update `protocol/README.md` (the version is `1.13`; one sentence on what it added; `view.proto` is still "one request, one effect", and the effect may now be several row changes) and `protocol/WASM-ABI.md` (the two sentences `contracts/protocol.md` "The WebAssembly ABI" gives).
- [X] T006 Bump the version where `contracts/protocol.md` "Where the version is written" lists it: `WireProtocol.Version` in `RT/remote/Conversation.scala`; `Protocol.version` and its changelog comment in `CPA/Compatibility.scala`, with `CPAT/HostingSuite.scala`'s assertion; the changelog comment in `SC/Discovery.scala`.
- [X] T007 [P] Python: run `PY/scripts/proto.py` to copy `protocol/` into `PY/proto/` and regenerate the stubs; set `PROTOCOL_VERSION = "1.13"` in `PY/src/ankka/service.py`. `uv run pytest -q && uv run mypy` stay green.
- [X] T008 [P] TypeScript: run `npm run proto` in `TS` to copy `protocol/` into `TS/proto/` and regenerate; set the protocol version in `TS/src/spec.ts`. `npm run typecheck && npm test` stay green.
- [X] T009 [P] Rust: copy `protocol/` into `RS/ankka/protocol/` with the crate's script under `RS/scripts/`; set the protocol version in `RS/ankka/src/service.rs`. `cargo test --workspace` stays green.
- [X] T010 Run `sbt 'sidecar/testOnly *ProtocolSuite *TranslateSuite' 'controlPlaneApi/testOnly *HostingSuite'` and the CI step that diffs the three SDK copies against `protocol/`; all pass. A 1.7 process double is still accepted.

**Checkpoint**: the wire is 1.13 everywhere and nothing behaves differently.

---

## Phase 3: User Story 1 — A view walks a tree with a recursive query (Priority: P1) 🎯 MVP

**Goal**: a view declares queries by name; the statement is checked when the service starts; a
handler asks by name with bound values and gets rows; a recursive query returns a subtree in one
statement. In four languages.

**Independent Test**: `sbt 'runtime/testOnly *QueryCheckSuite' 'testkit/testOnly *DeclaredQuerySuite *DeclaredQueriesFeatures *RecursiveQueriesFeatures'` and the tree conformance cases per language (quickstart, "User Story 1").

### Tests for User Story 1 (write first; they fail)

- [X] T011 [P] [US1] Write `RTT/QueryCheckSuite.scala`, pure. First the R7 *verify first* cases against JSqlParser directly, so a failure here is known to be the parser's: it reads `payload::jsonb->>'parent' = :row`, `payload::jsonb @> :v::jsonb`, and `contracts/scala-api.md`'s `WITH RECURSIVE` statement; its table finder reports the table inside a subquery, a join, a `LATERAL` and a `WITH` item's body, and does not report the `WITH` item itself; a name inside `-- comment`, `/* comment */` and `'a literal'` is not reported. Then one case per rule Q1–Q9, each asserting the problem's words name the view, the query and what the rule's table says (Q6: the other table's name). Then the scanner: `:name` rewritten to `$n` in order of first appearance, a repeated name bound to one `$n`, and nothing rewritten inside a string, a quoted identifier, a dollar-quoted string, either comment form or a `::` cast; a statement where scanner and parser disagree is refused.
- [X] T012 [P] [US1] Write `TKT/DeclaredQuerySuite.scala` on `AnkkaTestKit`, holding what a feature cannot say. R8 *verify first*: a declared query runs in a `READ ONLY` transaction (a statement calling a function that writes is refused by the database, `25006`); a never-ending recursive statement ends with `CommandError(Timeout)` and `pg_stat_activity` shows no active statement for the service's database afterwards. SC-002: wrap the connection factory to count statements, start a service whose view declares each refused statement of `FEAT/declared-queries.feature`'s outline, and assert the count for that statement's text is zero. C2: a value the query does not take is refused `BadRequest` naming it, and the database is sent nothing. C4: `limit` reads at most that many rows, in the statement's order. C6: a statement with no `payload` column is `Internal` naming view and query. C7: an asked query is counted as a call to the view under the query's name.
- [X] T013 [P] [US1] Write `TKT/views/ViewsSteps.scala` (`abstract class ViewsSteps(feature: String) extends GherkinSuite(feature) with LogCapturing`, the `TopologySteps` pattern) with the steps `FEAT/declared-queries.feature` and `FEAT/recursive-queries.feature` use, over fixtures in `TKT/views/ViewsKit.scala`: a `node` event sourced entity whose one event sets its row's `under` and `kind`; a plain view `nodes` whose declared queries are chosen per scenario from a fixed table keyed by the step's words ("reads its own table for the rows holding the value", "writes a row", "deletes a row", "holds a second statement after the query", "reads a table that is no view's", "reads the table of", "names another table only in a comment and in a value", "the recursive query … that takes the value … and reads every row under it", "a recursive query … that never ends"); a DataTable step for "holds these rows"; "the database is sent nothing" and "was sent one statement" over T012's statement counter. Add `TKT/views/DeclaredQueriesFeatures.scala` and `TKT/views/RecursiveQueriesFeatures.scala`, each one line naming its file as `"../../features/views/<name>.feature"`. Both fail: undefined behaviour, not undefined steps.
- [X] T014 [P] [US1] In `SCT/ProtocolSuite.scala` add cases: declared queries in discovery reach `RemoteViewDescriptor.declaredQueries`; a discovered statement that reads another table is a discovery problem with Q6's words (D4); a 1.7 process double that sends none is hosted as before.
- [X] T015 [P] [US1] In `CONF/ConformanceSuite.scala` add the cases named for `FEAT/languages.feature`'s first two scenarios: "a recursive query answers with the same rows in every language" (place nodes through `POST /tree/{id}` and `POST /tree/{id}/under/{parent}`, ask `GET /tree/{id}/below`, assert exactly the subtree, in key order, and not a second tree's nodes) and "a service whose view declares a statement that reads another table does not start in every language" (the target's own discovered declaration of `tree-rows`'s `under` is the recursive statement and passes; the same declaration with that statement replaced by one reading `ankka_view_cart_rows` is refused by the check the target's start went through — `Discovery.validate` for a process or a module, `QueryCheck` in process — naming the view, the query and the table). A process target is started by its SDK's script before the suite, so a second start with another environment cannot be made from the suite; what each language must get right is that its declaration reaches the one check intact, and `ConformanceTarget.declaredStatement`/`problemsWithStatement` hold exactly that.

### Implementation for User Story 1

- [X] T016 [P] [US1] Create `SDK/DeclaredQuery.scala`: `final case class DeclaredQuery(name: String, statement: String)` (`data-model.md`).
- [X] T017 [US1] In `SDK/View.scala`: `Companion.query(name)(statement)` registering into a private buffer and returning the handle, throwing if called after `descriptor` was taken; `Companion.table`; `ViewDescriptor.queries: Vector[DeclaredQuery] = Vector.empty`, filled by `descriptor`. Update the doc comment on `version` (it is corrected in US3; here only stop it contradicting the proto).
- [X] T018 [US1] Create `RT/QueryCheck.scala` per `contracts/declared-queries.md`: `check(table, name, statement): Either[String, CheckedQuery]` (Q1–Q8 with JSqlParser, the scanner, the cross-check), `problems(descriptors): Vector[String]` reading `ViewDescriptor.queries` and `RemoteViewDescriptor.declaredQueries` and adding Q9, and `CheckedQuery(name, sql, values)`. Identifiers compared as Postgres reads them. T011 passes.
- [X] T019 [US1] In `RT/Ankka.scala`, add `QueryCheck.problems(descriptors)` to `ComponentRegistry.validate` beside `TopicSourceRules.problems`. Add a case to `RTT/QueryCheckSuite.scala` that `Ankka.service(…).validate` surfaces a problem, as `TopicSourceRulesSuite` has for its rules.
- [X] T020 [US1] In `RT/Database.scala` add `readOnly[A](timeout: FiniteDuration)(work: Transaction => Future[A]): Future[A]`: `inTransaction` whose first statements are `SET TRANSACTION READ ONLY` and `SET LOCAL statement_timeout = <ms>`.
- [X] T021 [US1] In `RT/ViewClient.scala`: `ViewQueries` takes the view's `Vector[CheckedQuery]`; `askAsync(query, limit, values*)` and `ask` per C1–C7 (unknown query `NotFound`; values not exactly the query's `BadRequest`; run through `Database.readOnly` with the ask timeout less 500 ms, never under 1 s; `57014` to `Timeout`; no `payload` column to `Internal`; read at most `limit` rows without cancelling the connection publisher — `Sink.last`'s trap applies to results read short too, so drain or use the statement's fetch size and stop at `limit`; counted under the query's name). `ViewClient.forView` builds the checked queries from the companion once. A declared query named like a fixed way of asking (`ViewQueries.Names`) never reaches this method: Q9 refuses it at startup.
- [X] T022 [US1] In `RT/remote/RemoteDescriptors.scala` add `declaredQueries: Vector[DeclaredQuery] = Vector.empty` to `RemoteViewDescriptor`; in `SC/Discovery.scala` read `ViewDetail.declared_queries` into it and add `QueryCheck.problems(built)` beside `TopicSourceRules.problems(built)`. T014 passes.
- [X] T023 [US1] In `SC/ClientLogic.scala`, `query`: keep `get`/`by-id`/`by-key`/`all` as they are; any other name is looked up in the registry's view of that id and asked through `ViewQueries.askAsync` with `request.values` and `request.limit`; a view or query the registry does not hold is `NotFound` naming both (replacing "a remote view answers 'get' and 'all'"). Add cases to `SCT/RemoteProjectionSuite.scala` driving `Client.Query` from the process double: a declared query by name with values, an undeclared name, a missing value.
- [X] T024 [US1] Make T013's two feature suites pass: finish `ViewsKit`'s fixtures against T017–T021. No scenario is tagged `@ignore`, and each suite reports its scenario count (10 and 3 tests).
- [X] T025 [US1] In `CONF/ConformanceReference.scala` add the `tree-node` event sourced entity, the `tree-rows` plain view with the declared query `under` (and, when `ANKKA_CONFORMANCE_BROKEN_QUERY` is set, a declared query `broken` that reads `cart-rows`' table), and the endpoint routes `PUT /tree/{id}` (parent in the body) and `GET /tree/{id}/under`. T015 passes for the Scala reference.
- [X] T026 [P] [US1] Python: in `PY/src/ankka/view.py` add `query(name, statement)` and `table_of(component_id)`, collect class attributes holding a query in `__init_subclass__`, and send them as `declared_queries`; in `PY/src/ankka/client.py` add `Views.ask(view_id, name, row, limit=None, **values)`; in `PY/src/ankka/start_from.py` (or a sibling) add the 1.13 gate S1 for a declared query, and apply it in `PY/src/ankka/server.py` where the 1.7 gate is. Cases in `PY/tests/test_views.py` (new): discovery carries the queries; `ask` sends name, values and limit; the gate refuses a 1.7 sidecar naming the query.
- [X] T027 [P] [US1] TypeScript: `query(name, statement)` and `tableOf(componentId)` in `TS/src/view.ts`; `static declared` on a view, registered in `TS/src/service.ts` and sent in `TS/src/spec.ts`; `Views.ask(viewId, name, values, rowShape, limit?)` in `TS/src/client.ts`; the 1.13 gate in `TS/src/startFrom.ts` and `TS/src/server/discovery.ts`; exports in `TS/src/index.ts`. Cases in `TS/test/views.test.ts` (new), as T026's.
- [X] T028 [P] [US1] Rust: `DeclaredQuery`, `query(name, statement)` and `table_of(id)` in `RS/ankka/src/components/view.rs`; `fn declared() -> Vec<DeclaredQuery>` on `View`, defaulting to empty, sent in `to_component`; `Client::ask(view, name, values)` in `RS/ankka/src/client.rs`; the 1.13 gate in `RS/ankka/src/start_from.rs` and `RS/ankka/src/service.rs`; re-exports in `RS/ankka/src/prelude.rs`. Cases in `RS/ankka/tests/views.rs` (new), as T026's.
- [X] T029 [P] [US1] Python conformance: in `PY/examples/shopping_cart/conformance.py` add `tree-node`, `tree-rows` with `under` and the broken query under the variable, and the two routes in `PY/examples/shopping_cart/endpoint.py`. `uv run conformance` passes T015's cases.
- [X] T030 [P] [US1] TypeScript conformance: the same in `TS/examples/shopping-cart/conformance.ts` and `endpoint.ts`. `npm run conformance` passes T015's cases.
- [X] T031 [P] [US1] Rust conformance: the same in `RS/examples/shopping-cart/src/conformance.rs` and `endpoint.rs`. `./conformance.sh` passes T015's cases in both guest shapes; read its first line of output to confirm the shape it ran.

**Checkpoint**: a tree is walked in one statement, from every language; a statement that is not a
read of the view's own table stops the service. Remove Q6 from `QueryCheck` once and watch
"a statement that reads another view's table is refused" go red; put it back.

---

## Phase 4: User Story 2 — Two sources keep one row up to date (Priority: P1)

**Goal**: a keyed view: several entity sources, a handler each, row changes by key, reads of its
own rows, one change at a time.

**Independent Test**: `sbt 'core/testOnly *RowChangesSuite' 'testkit/testOnly *KeyedViewSuite *SeveralSourcesFeatures *KeyedViewTestKitSuite'` and the keyed conformance cases per language (quickstart, "User Story 2").

### Tests for User Story 2 (write first; they fail)

- [X] T032 [P] [US2] Write `CORET/effect/RowChangesSuite.scala`, pure: `reduce` keeps the later change for a key (E2: delete then upsert writes; upsert then delete deletes), orders keys by first appearance, and returns nothing for an empty effect; the builders (`updateRow`, `deleteRow`, `updateRows`, `deleteRows`, `ignore`, `++`) build what they say.
- [X] T033 [P] [US2] Write `RTT/KeyedViewRulesSuite.scala`, pure: one case per K1–K5 for a Scala descriptor and for a `RemoteKeyedViewDescriptor`, each asserting the problem's words; K2's are exactly "a topic and an entity may not be sources of one view". And write `RTT/ViewProjectionsSuite.scala`, pure, holding the properties `contracts/rebuild.md` lists: a plain view at version 1 is named exactly `ankka-view-<id>`, the string `ProjectionRuntime.startView` builds today (assert against that literal for `cart-rows`, so a change to the function that moves an existing view's offsets fails here); the four forms of the table; and distinctness over ids that try to collide (`summary` at 2 and `summary-v2` at 1; `v2-summary`; an id ending `.v2`; keyed `a` over `b-c` and `a-b` over `c`; a plain view named `keyed-view-x`).
- [X] T034 [P] [US2] In `RTT/TopologyJsonSuite.scala` add a case: a keyed view of an event sourced and a key value entity has exactly two declared connections, an event subscription and a state subscription, and the existing "a view or a consumer is connected to what it reads" case is unchanged. In `TKT/ComponentDescriptorsSuite.scala` add: a keyed view declares one handler per source, named for the source's component id.
- [X] T035 [P] [US2] Write `TKT/KeyedViewSuite.scala` on `AnkkaTestKit`. R3 *verify first*, first: a keyed view over one event sourced entity handles a change, proving the lock and `SET LOCAL` run through the exactly-once session. Then SC-006: two nodes of one cluster on one database (`kit.startPeer`, as `TKT/ViewVersionSuite.scala` and `TKT/PeerSuite.scala` do; never two independent services, which would each run the same projection), a keyed view whose two sources each read row `s1` and write it back with a counter incremented, a thousand events on each; the counter is 2000. SC-003: row count equals distinct keys named, before and after `restartService()`. E3: a keyed view over a key value entity writes a change's rows together. E4: a change naming one writable row and one whose serializer throws writes neither and is handled again. E1: an empty key fails the change. O3: a handler asking a never-ending query fails its change with the timeout and the next change is handled. E6: a change whose rows weigh more than 4 MiB together fails naming the view and the size, and writes none. FR-014: a handler asking its handle a declared query of another view is refused `NotFound` and its change fails. A keyed view of three sources writes rows from all three (the number of sources is not limited).
- [X] T036 [P] [US2] Write `TKT/KeyedViewTestKitSuite.scala`: `change` and `deleted` per source apply effects through `RowChanges.reduce`; rows round-trip the serializer; `answering` supplies a query's rows; a handler asking a query with no answer fails naming the query; building a kit for a view with a refused statement fails with `QueryCheck`'s problem.
- [X] T037 [P] [US2] Extend `TKT/views/ViewsSteps.scala` and `ViewsKit.scala` for `FEAT/several-sources.feature`: `shipment` and `customer` entities whose one event is a script (rows to write with what to add, rows to delete, a query to ask and "write each row answered", a row to read and rewrite, "a row that cannot be written"); a keyed view `shipments` interpreting it; steps for two instances (a node and a `kit.startPeer` peer of one cluster), "at the same time", "handles one of the two events … before it handles the other" (assert on a handler log of begin/end pairs never interleaving), restart, "does not start" with the developer told, and the plain view `customers`. Add `TKT/views/SeveralSourcesFeatures.scala`. It fails.
- [X] T038 [P] [US2] In `SCT/ProtocolSuite.scala` add cases: `sources` in discovery builds a `RemoteKeyedViewDescriptor`; D1, D2, D3 each refused with their words. In `SCT/RemoteProjectionSuite.scala` add: a keyed view in the process double is sent `source_id` and no `row`, answers `rows`, and the rows are written; its `Client.Query` for `get` and for a declared query of its own view is answered while its change is in flight. In `SCT/TranslateSuite.scala`: `ViewRequest.sourceId` and `ViewOutcome.Rows` both ways.
- [X] T039 [P] [US2] In `CONF/ConformanceSuite.scala` add the cases named for `FEAT/languages.feature`'s last three scenarios: "a view of several sources reads every one of them in every language", "a view finds the rows an event is about by asking a query of its own in every language", and "the topology shows a view connected to each of its sources in every language" (the service's topology has a declared connection from each source to `joined-rows`).

### Implementation for User Story 2

- [X] T040 [US2] Create `CORE/effect/KeyedViewEffect.scala`: `RowChange`, `KeyedViewEffect`, `KeyedViewEffects` (the builders), `RowChanges.reduce` (`data-model.md` "The effect"). T032 passes.
- [X] T041 [US2] Create `SDK/KeyedView.scala` per `contracts/scala-api.md` and `data-model.md`: `KeyedView[Row]`, `KeyedChange[Row]` with `ViewRows[Row]` (`get`, `ask`), `KeyedSource`, `KeyedView.Companion` (`source`, `query`, `table`, `version`, `create`, `descriptor`), `KeyedViewDescriptor` (`kind = View`, `tableName`, one `DeclaredHandler` per source). `descriptor` refuses K1–K4 with their words. `ViewRows` is an interface here; `runtime` and `testkit` implement it.
- [X] T042 [US2] Create `RT/KeyedViewRules.scala`: `problems(descriptors)` for K1–K5 over `KeyedViewDescriptor` and `RemoteKeyedViewDescriptor`; add it to `ComponentRegistry.validate` in `RT/Ankka.scala`. `QueryCheck.problems` reads a keyed view's queries too. T033 passes.
- [X] T043 [US2] In `RT/DeclaredConnections.scala` replace `sourceOf` with `sourcesOf: Vector[DeclaredSource]` (a plain view and a consumer give one); update its callers in `RT/ProjectionRuntime.scala` (`rejectUnsupported`, `rejectUnsupportedRemote`) and `RT/TopologyJson.scala` (`declared` yields one connection per source; the doc comment says "exactly the sources the view declared"). T034 passes and `TopologyJsonSuite`'s other cases are unchanged.
- [X] T044 [US2] In `RT/ViewVersions.scala` add the lock as a statement usable through an `R2dbcSession` and a `Database.Transaction` (`lockFragment(componentId, shared)`), leaving `rebuild` and `guarded` as they are.
- [X] T045 [US2] Create `RT/KeyedViewHandlers.scala`: `KeyedViewEventHandler` (an `R2dbcHandler`: take the lock exclusively and `SET LOCAL statement_timeout` through the session; run the source's handler on `AnkkaExecutors.virtual` with a `KeyedChange` whose `rows` is a `ViewQueries` on another connection; reduce; apply every change through the session; E1, E6) and `KeyedViewStateHandler` (a `Handler`: the same inside `Database.inTransaction`). Spans and call origin as `ProjectionSupport.handling`, named for the source's component id. Capture `Observability` at construction.
- [X] T046 [US2] In `RT/ProjectionRuntime.scala`: collect `KeyedViewDescriptor`s; create their tables; create `RT/ViewProjections.scala` with `name(componentId, source: Option[ComponentId], version: Int)`, the one function `contracts/rebuild.md`'s table describes, for all four forms (T033's naming suite passes); `startKeyedView` starting, per source, one daemon with one instance over slices 0–1023 under that function's name at version 1. In `RT/ViewClient.scala`, `forView` takes a `KeyedView.Companion`. T035 passes.
- [X] T047 [US2] Create `TK/KeyedViewTestKit.scala` per `contracts/scala-api.md` "Testing". T036 passes.
- [X] T048 [US2] In `RT/remote/RemoteDescriptors.scala` add `RemoteKeyedViewDescriptor`; in `RT/remote/Conversation.scala` add `ViewRequest.sourceId: Option[ComponentId]` and `ViewOutcome.Rows(changes)`; in `RT/remote/RemoteProjection.scala` add the remote keyed event and state handlers, which are T045's with `conversation.handleView` in place of the Scala handler and `Serializer.bytes` for rows; start them from `ProjectionRuntime.startRemoteKeyedView`.
- [X] T049 [US2] In `SC/Discovery.scala` build a `RemoteKeyedViewDescriptor` from `sources` (D1–D3, and the existing check that a source names a declared component, per source); in `SC/Translate.scala` map `source_id` and `rows`; in `SC/ClientLogic.scala` a keyed view's `get` and declared queries are answered as a view's. T038 passes, including for `sidecar/wasm`, which reaches both through `Translate` and `ClientLogic`.
- [X] T050 [US2] Make `SeveralSourcesFeatures` pass (13 tests). The two-instance scenarios run on the harness T035 uses.
- [X] T051 [US2] In `CONF/ConformanceReference.scala` add the `joined-left` and `joined-right` event sourced entities, the keyed view `joined-rows` over both with the declared query `of-right` its right-hand handler asks, and routes to record on each and to read `joined-rows`. T039 passes for the Scala reference.
- [X] T052 [P] [US2] Python: create `PY/src/ankka/keyed_view.py` (`KeyedView`, `@on(source, codec)`, `@on_deleted(source)`, `self.rows`, `self.subject`, discovery with `sources`) and `PY/src/ankka/effects/keyed_view.py`; in `PY/src/ankka/server.py` dispatch a view request with `source_id` to the keyed view's handler **with a client** for `self.rows`, and answer `rows`; register in `PY/src/ankka/service.py`; S1 and S2 (K1–K4) in the gate; `KeyedViewTestKit` in `PY/src/ankka/testkit/unit.py`. Cases in `PY/tests/test_views.py`.
- [X] T053 [P] [US2] TypeScript: create `TS/src/keyedView.ts` (`KeyedView`, `on(...)`, `static sources`, `this.rows`) and `TS/src/effects/keyed.ts`; dispatch by `sourceId` in `TS/src/server/stateless.ts`; register in `TS/src/service.ts` and `TS/src/spec.ts`; S1 and S2; `KeyedViewTestKit` in `TS/src/testkit/kinds.ts`, exported from `TS/src/testkit/index.ts` and `TS/src/index.ts`. Cases in `TS/test/views.test.ts`. Erasable syntax only.
- [X] T054 [P] [US2] Rust: create `RS/ankka/src/components/keyed_view.rs` (`KeyedView`, `Sources`, `Source::of`) and `RS/ankka/src/effects/keyed_view.rs`; `ctx.rows()` in `RS/ankka/src/context.rs`; dispatch by `source_id` in `Registered::view` (`RS/ankka/src/components/mod.rs`) and register in `RS/ankka/src/service.rs`; S1 and S2; `KeyedViewTestKit` in `RS/ankka/src/testkit/kinds.rs`. Cases in `RS/ankka/tests/views.rs`.
- [X] T055 [P] [US2] Python conformance: `joined-left`, `joined-right`, `joined-rows` and the routes in `PY/examples/shopping_cart/conformance.py` and `endpoint.py`. `uv run conformance` passes T039's cases.
- [X] T056 [P] [US2] TypeScript conformance: the same in `TS/examples/shopping-cart/`. `npm run conformance` passes T039's cases.
- [X] T057 [P] [US2] Rust conformance: the same in `RS/examples/shopping-cart/src/`. `./conformance.sh` passes T039's cases in both guest shapes.

**Checkpoint**: one event writes several rows; two sources write one row and lose nothing. Take
the lock shared instead of exclusive once and watch SC-006 lose writes; put it back.

---

## Phase 5: User Story 3 — A view that reads entities is rebuilt when its version is raised (Priority: P2)

**Goal**: any view that reads entities, plain or keyed, may declare a version; a higher one empties
the table once and reads every source again; an instance behind stops.

**Independent Test**: `sbt 'runtime/testOnly *TopicSourceRulesSuite' 'testkit/testOnly *EntityViewVersionSuite *ViewVersionSuite'` (quickstart, "User Story 3").

### Tests for User Story 3 (write first; they fail)

- [X] T058 [P] [US3] In `RTT/TopicSourceRulesSuite.scala` rename the case "a version on a view or consumer that reads an entity is refused" to "a version on a consumer that reads an entity is refused" (the scenario's name in `features/topics/versions.feature`), and change it to assert the consumer is refused and the view, Scala and remote, is **not**; the non-positive case now also covers an entity-sourced view. In `SCT/ProtocolSuite.scala` change the case asserting "view 'over-entity' declares a version, which applies to a topic" to assert it is accepted and its version reaches the descriptor.
- [X] T059 [P] [US3] Write `TKT/EntityViewVersionSuite.scala` on `ViewVersionSuite`'s two-instance harness, one case per scenario of `FEAT/rebuilding.feature`, named for it. Rows carry the version that wrote them. R10 *verify first*: in the rolling-update case assert the version-1 instance's handler is called for no event after the rebuild. R11 *verify first*: assert exactly one L2 line for the behind instance, not a growing number. Also: a plain view with no version on one instance stops when another declares version 2; a view first registered at version 3 with no recorded row is built once; a plain view at version 1 stores its offsets under `ankka-view-<id>` with one key per slice range and as many ranges as its `parallelism` (read the offset store's rows: FR-009, FR-017), and at version 2 under `ankka-view.v2-<id>` with the version-1 rows still there; an `eventually` waits on rows carrying version 2, never on "a row exists".
- [X] T060 [P] [US3] SDK cases, failing: change the existing assertions that a version on an entity-sourced view is refused (`PY/tests/test_topic_sources.py`; `TS/test/topic-sources.test.ts`, the `declares a version, which applies to a topic` match for a view; `RS/ankka/tests/registration.rs`) to assert it is accepted and sent in discovery; keep or add in each the assertion that one on an entity-sourced consumer is still refused; and add that a version on an entity view is refused by S1 against a 1.7 sidecar.

### Implementation for User Story 3

- [X] T061 [US3] In `RT/TopicSourceRules.scala` change T4: `versionProblems` takes the kind and refuses a version over an entity for a consumer only; update the rule's comment. T058's runtime cases pass.
- [X] T062 [US3] In `RT/ViewVersions.scala` add the guard through a session and through a transaction (`lock` shared or exclusive, read the recorded version, answer `Wrote`-or-`Behind` without writing), and update the object's comment: it is every view's, not a topic-sourced view's.
- [X] T063 [US3] In `RT/ProjectionRuntime.scala`: create `ankka_view_versions` and ensure a row for every view (plain, keyed, remote), not only topic views; add `startEntityView`, the sequence of `contracts/rebuild.md` "At startup, per view" (recorded = declared: start; lower: `ViewVersions.rebuild` then start, logging L1 or L3; higher: start nothing, log L2), off the start thread as `startTopicView` is; every view's projection and daemon names, plain views' included, from `ViewProjections.name` (T046), at the declared version; `startView` and `startRemoteView` no longer build a name themselves, and `ViewProjectionsSuite` still passes, which is what holds a plain view at version 1 to the name its offsets are under.
- [X] T064 [US3] Guard every write of a view that reads entities: `ViewEventHandler` and `ViewStateHandler` in `RT/ProjectionRuntime.scala` and `ProjectionSupport.applyView` (shared lock), the remote plain handlers in `RT/remote/RemoteProjection.scala` (shared), and T045's and T048's keyed handlers (exclusive; add the version read after the lock they already take). On `Behind`: fail the change, pause the projection through `ProjectionManagement`, log L2 once. T059 passes.
- [X] T065 [P] [US3] Python: in `PY/src/ankka/start_from.py` accept a version on an entity-sourced view (still refuse a consumer's); add it to the S1 gate; correct the docstring on `View.version` in `PY/src/ankka/view.py`.
- [X] T066 [P] [US3] TypeScript: the same in `TS/src/service.ts` (`topicProblems`), `TS/src/startFrom.ts` and the comment on `version` in `TS/src/view.ts`.
- [X] T067 [P] [US3] Rust: the same in `RS/ankka/src/start_from.rs` and the doc comment on `View::version` in `RS/ankka/src/components/view.rs`. T060 passes in all three.
- [X] T068 [US3] Correct `SDK/View.scala`'s comment on `Companion.version` and `SDK/KeyedView.scala`'s: raising it rebuilds a view that reads a topic or entities.

**Checkpoint**: a keyed view and a plain view are each rebuilt by raising a version. Drop the
guard from the plain view's write once and watch the rolling-update case find a row written at
version 1; put it back.

---

## Phase 6: The documentation (FR-010; the scenarios of `features/documentation/views.feature`)

**Purpose**: the pages say what Phases 3 to 5 built. Each page stands alone: no feature numbers,
no "see above". Samples are marked regions of tested code.

- [X] T069 [P] [US1] Mark `// docs:start` regions in `TKT/views/ViewsKit.scala` (a declared recursive query on a view; asking it) and in `TKT/KeyedViewTestKitSuite.scala` and `TKT/KeyedViewSuite.scala` (a keyed view's companion and handlers; a unit test of one), and the equivalent regions in `PY/examples/shopping_cart/conformance.py` and `TS/examples/shopping-cart/conformance.ts`. *Done differently*: the keyed view's region and its test are the conformance reference's `JoinedRows` (`CONF/ConformanceReference.scala`, `CONF/JoinedRowsSuite.scala`), a view tested in every language, rather than the scripted double in `KeyedKit.scala`.
- [X] T070 [US1] `DOCS/build/views.md`: a section "Declared queries" (declaring one, the table's name, values as text, what stops a service from starting — every rule Q1–Q9 in prose — asking it, the timeout and the limit, a recursive query walking a tree, that the check guards the developer and is not isolation), in Scala, Python and TypeScript, with included samples. Scenario "the documentation of views describes declared queries and the recursive query".
- [X] T071 [US2] `DOCS/build/views.md`: a section "Keyed views" (the two shapes and when to use which; sources; a handler per source; row changes by key; that a view of one source that names no row key keeps each row under the entity id it came from; reading the view's own rows; that the platform deletes no row a view did not name and how a row is moved; that a keyed view handles one event at a time and what that costs; exactly once and at least once by source). Replace the opening's "one source" claims and the first *Limits* bullet. Scenarios "…describes the row key a view names" and "…a moved row is deleted and written by the view".
- [X] T072 [US3] `DOCS/build/views.md`: a section "Rebuilding by version" for views that read entities (raise the version; emptied once; serves a partial table meanwhile; behind; a consumer cannot), replacing the second *Limits* bullet; and in `DOCS/build/topics.md` replace "A version applies to a topic source only…" with what is now true (a view may; a consumer over an entity may not). Scenario "the documentation says how a view that reads entities is rebuilt".
- [X] T073 [P] [US2] `DOCS/reference/limitations.md`: replace "Views read one source into one table" (a keyed view reads several entities into one table; a topic and an entity may not be sources of one view; a keyed view reads no topic; a query names one table), remove "A view over an entity is not rebuilt when its code changes", add that the reads other than declared queries have no timeout in the database, and beside the limit on a topic source's rebuild say that a rebuild of a view reading an entity is not bounded by what a broker retains. Scenarios "the documentation says that a topic and an entity may not share a view" and, changed, `features/documentation/topic-sources.feature`'s "the documentation's limitations say what bounds a topic source's rebuild".
- [X] T074 [P] [US3] `DOCS/deploy/upgrading.md`: raise a view's version only in a deploy after the one that brought every instance to this release, and why (R12).
- [X] T075 [P] [US2] `DOCS/reference/glossary.md` (Row; View; add Keyed view, Declared query, Row key), `DOCS/build/testing.md` (`KeyedViewTestKit`, and that an unanswered query fails), `DOCS/reference/sidecar-protocol.md`'s hand-written text on views (several sources, row changes, declared queries), and the SDK reference pages `DOCS/reference/{python,typescript,rust}-sdk.md` where they list a view's declarations and test kits.
- [X] T076 [US1] `tools/docs/skill/ankka-views-consumers/SKILL.md`: rule 1 ("One source, one table…") and the stale lines saying a topic view cannot be rebuilt, rewritten to what is true. Run `just docs-sync` to refresh included samples, the generated protocol table and the rendered skills under `marketplace/` and `ankka.g8/`, then `just docs` and `sbt 'controlPlaneApi/testOnly *DocumentationDescriptorsSuite'`. The template's copy of the skill is written with every `$` escaped; T083's Scala template run is what would catch a miss.
- [X] T077 [US1] Read `DOCS/build/views.md`, `DOCS/reference/limitations.md` and `DOCS/build/topics.md` against the five scenarios of `features/documentation/views.feature` and the changed scenario of `features/documentation/topic-sources.feature`, one at a time, and note in the pull request description the section that satisfies each (R18: read off the pages, not matched as strings).

**Checkpoint**: `just docs` passes; every scenario of the documentation feature has a section.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T078 [P] Update `CLAUDE.md`: under *Architecture*, a short section on the two shapes of view, the lock and the version-named projections; `specs-from` and the features paragraph need no change; add to *Traps* whatever the *verify first* cases actually cost (one entry each, only for what bit). *Done differently*: `CLAUDE.md` was split into `.claude/rules/` while this feature was built, so the section and its four traps are in `.claude/rules/messaging.md`.
- [X] T079 [P] In `RT/ObservabilityDocuments.scala` and `RT/TopologyJson.scala`, confirm nothing else assumed one source per view (the list in research R16); `cli/src/main/resources/console/topology.js` folds declared edges generically and needs no change — confirm by running a service with a keyed view locally and opening the local console (`just console`): two declared connections lead into the keyed view.
- [X] T080 Run `sbt scalafmtAll scalafmtSbt` and `sbt compile` warning-free (`-Wunused` is on).
- [X] T081 Run `caffeinate -i sbt -Dankka.cluster.tests=off test`; every suite green; read the conformance run's own count of cases.
- [X] T082 [P] Run each SDK's full line: `cd sdks/python && uv sync && uv run pytest -q && uv run mypy && uv run conformance`; `cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run test:slow && npm run conformance`; `cd sdks/rust && cargo test --workspace && cargo test -p shopping-cart --features slow && ./conformance.sh`.
- [X] T083 [P] Run the template suites, each of which expands a project and runs its own tests against this tree's SDK: `sbt -Dankka.template.tests=python 'cli/testOnly *PythonTemplateSuite'`, the same for `typescript` and `rust` with their suites, and `sbt -Dankka.template.tests=scala 'cli/testOnly *TemplateSuite'`. Nothing in a template declares anything new, so they must pass unchanged; a suite whose language was named fails rather than skips when its tools are missing.
- [X] T084 Run `just features`: no finding in `specs/031-multi-source-views`, `features/views/`, `features/documentation/views.feature` or `features/topics/versions.feature`. The findings in other unbuilt specs are theirs.
- [X] T085 Run `python3 .github/ci-coverage.py` with every new file present and committed-to-be: each is claimed by a filter and no pattern is left matching nothing. If T012 or T015 added a `-D` switch or an environment variable a forked test reads, confirm `build.sbt` forwards it in `Test / javaOptions` or the target's environment.
- [X] T086 Compare `CartViewSuite`'s wall time with T002's note. If the guard's two statements moved it beyond noise, say so in the pull request description with both numbers; do not remove the guard.
- [X] T087 Walk `quickstart.md` top to bottom, including each "break it once", and tick each off in the pull request description.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: after Setup. Blocks every story's sidecar and SDK tasks.
- **US1 (Phase 3)**: after Foundational.
- **US2 (Phase 4)**: after US1's `DeclaredQuery`, `QueryCheck` and `ViewQueries.ask` (T016–T021), which a keyed handler's reads use; its conformance tasks after US1's (same files).
- **US3 (Phase 5)**: after US2's handlers (T045, T048), which it guards. Its plain-view half (T061–T063 and the plain part of T064) needs only Phase 2.
- **Documentation (Phase 6)**: after the story each task documents.
- **Polish (Phase 7)**: after everything.

### Within a story

Tests first, and seen to fail for the right reason. Pure suites before suites that need Postgres.
`core` before `sdk` before `runtime` before `testkit` and `sidecar`; the SDKs after the sidecar
can host what they declare.

### What a task waits for, where it is not the one before it

- T018 waits for T011; T021 for T012, T018 and T020; T024 for T013 and T021.
- T025 waits for T021; T029–T031 for T025 and their SDK's T026–T028.
- T041 waits for T040 and T016; T045 for T041, T044 and T021; T046 for T045; T050 for T037 and T046.
- T049 waits for T048; T052–T054 for T049; T055–T057 for T051 and their SDK's T052–T054.
- T064 waits for T062, T063, T045 and T048; T065–T067 for T061.
- T076 waits for T069–T075.

## Parallel opportunities

- Phase 2: T007, T008, T009 once T004 is done.
- US1 tests: T011–T015 together. US1 SDKs: T026, T027, T028 together, then T029, T030, T031.
- US2 tests: T032–T039 together. US2 SDKs: T052, T053, T054 together, then T055, T056, T057.
- US3: T058, T059, T060 together; T065, T066, T067 together.
- Documentation: T073, T074, T075 beside T070–T072 (different pages); T070, T071 and T072 are one
  page and are done in order.

```bash
# US1, the tests, at once:
Task: "Write RTT/QueryCheckSuite.scala …"            # T011
Task: "Write TKT/DeclaredQuerySuite.scala …"         # T012
Task: "Write TKT/views/ViewsSteps.scala …"           # T013
Task: "ProtocolSuite cases for declared queries …"   # T014
Task: "ConformanceSuite cases for the tree …"        # T015
```

## Implementation Strategy

### Built in slices, released once

1. Phases 1–3: declared queries. A whole capability, and the MVP to review: a tree is walked from
   four languages.
2. Phase 4: keyed views.
3. Phase 5: versions for views that read entities.
4. Phases 6–7: the documentation and the full runs.

**No release carries Phase 3 without Phases 4 and 5.** Protocol 1.13 is written once, in Phase 2,
with `sources`, `rows` and the widened `version` in it; a runtime that speaks 1.13 and cannot host
a keyed view is the difference between runtimes of one version that the protocol's own rule
forbids. `main` may hold a slice alone for as long as no tag is cut from it.

### What to watch

- **The parser is the unknown.** T011's first cases are JSqlParser's alone. If it cannot read a
  statement the documentation needs, write the documentation's statements in what it reads and
  state the limit; do not replace the parser with a pattern (R7).
- **A check that cannot fail.** Each story's checkpoint names one line to remove and the scenario
  that must go red. Do it.
- **A filter that matched nothing reports green.** Read each run's own count of what it ran:
  the feature suites' test counts, the conformance run's case list, the Rust script's first line.
- **Fixed ports, forked properties.** Every HTTP suite binds `127.0.0.1:0`; a new `-D` switch
  needs forwarding in `Test / javaOptions`.
- **Long runs sleep.** `caffeinate -i` for T081.
