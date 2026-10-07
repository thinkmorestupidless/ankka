# Research: Multi-Source Views

Decisions R1–R19, each with what was found in the code, what was chosen, and what was not. Paths
are from the repository root. *Verify first* marks a claim the design rests on that has not been
run; each names the test that would show it false, and that test is written before the code that
depends on it.

## What planning found that the spec had assumed otherwise

1. **A view's declared queries have never been read.** `ViewDetail.queries` is a list of bare
   names; `sidecar/…/ClientLogic.scala:222-226` answers `get`, `by-id`, `by-key` and `all` by
   name and consults no declaration. The conformance views declare `by-id` and are asked `get`.
   Declared queries are the first thing that field's idea is used for (R6, R13).
2. **There is no query timeout in the database.** The spec's "the view client's existing query
   timeout" is `ComponentClient.await(future, askTimeout)` (`ViewClient.scala:94-128`): the caller
   stops waiting, and Postgres goes on running the statement. Nothing sets `statement_timeout`
   anywhere. A recursive query over a cycle would run until the connection died (R8).
3. **Whether a handler names row keys has to be known before the view starts**, because it
   decides how the view is run. So explicit keys are a second shape of view, not an extra effect
   on the one that exists (R1).
4. **A key value entity's changes cannot be made exactly once**, so FR-016's "and the record of
   how far its source has been read" holds for an event sourced source only (R4). The spec is
   corrected.
5. **Every view that reads entities pays two statements per change for the version**, declared or
   not, because an instance cannot know that a newer one will declare a higher version (R10).
6. **An instance of a release before this one has no such guard**, so a version may only be
   raised once every instance runs this release (R12).
7. **A Python view has no client.** `ViewServicer` passes none (`sdks/python/src/ankka/server.py:426-445`),
   so a Python keyed view's reads need one for the first time (R15).
8. **Scala has no unit test kit for views.** Python, TypeScript and Rust do. A keyed view's handler
   has logic worth a unit test, so Scala gains `KeyedViewTestKit` (R17).

---

## R1. Two shapes of view: plain and keyed

**Found**: `View[Src, Row]` (`modules/sdk/…/View.scala`) is typed by its one source, keeps the
current row and the change's context as mutable state on the instance, and its companion carries
`parallelism`, which `ProjectionRuntime.startView` turns into that many slices of the source, each
a projection on some node. Four handlers apply a `ViewEffect`, always under the source's entity id.

**Decision**: a second shape, `KeyedView[Row]`, beside the first. One or more entity sources, a
handler per source, effects that name keys, and reads of its own rows. The plain view is not
touched. Explicit keys exist only on the keyed shape.

**Rationale**: FR-015 makes "handles one change at a time" depend on whether handlers name keys,
and that must be decided when the projections are started. A flag on the existing companion would
let a view declare it sliced and name keys anyway; a type cannot be misdeclared. It also keeps
FR-009 true by construction: no existing view's code path changes shape.

**Alternatives**: one `View` with an optional key on `updateRow` — the runtime learns a view is
keyed at its first keyed effect, after it has been run sliced. A `keyed = true` override on
`View.Companion` — a convention where a type will do, and `Src` cannot type several sources.

## R2. The effect: an ordered list of row changes, reduced by one function

**Decision**: `KeyedViewEffect[Row]` in `core` is a vector of `RowChange.Upsert(key, row)` and
`RowChange.Delete(key)`; empty is "nothing". `RowChanges.reduce` gives the final change per key
(later wins), in order of first appearance. The in-process handlers, the remote host and all four
test kits apply an effect through it.

**Rationale**: "where both the runtime and the testkit need to reduce an effect, they share one
function". Today a view's effect is matched in four places (`ProjectionSupport.applyView`,
`ViewStateHandler`, `ViewTopicHandler`, `RemoteView.apply`); with one row and one key they could
not disagree. With an ordered list they can.

**Alternatives**: a map of key to change — loses "delete then write" as written and makes the
order of writes the map's. Leaving the plain `ViewEffect` and adding a `Rows` case to it — every
plain-view match would gain a case it must refuse.

## R3. One change at a time: the view's advisory lock, taken exclusively

**Found**: `ViewVersions` (024) already gives each view an advisory lock, `(62716, hashtext(id))`,
taken exclusively by a rebuild and shared by a topic view's write, transaction-scoped.
`R2dbcProjection.exactlyOnce` hands the handler the `R2dbcSession` whose transaction also stores
the offset.

**Decision**: a keyed view's handler takes that same lock exclusively as the first statement of
its change's transaction, and each source is one projection with one instance over every slice.
The handler's reads go through the view client on another connection: they see committed rows,
and nothing else can commit to this table while the lock is held.

**Rationale**: sources run on whichever nodes sharding puts them, so the exclusion has to be in
the database, and the lock that already excludes a rebuild is the one that must exclude the other
writers too. Reading on a second connection makes the in-process handler and a handler in a
process or a module do exactly the same thing, where reading through the session would be
available to one of them only.

**Alternatives**: a single projection merging the sources — Pekko's projection has one source
provider and one offset per projection, and merging two journals needs an order between them that
does not exist. Optimistic revisions per row — catch two writers of one row, not a row that
appears after a handler's query (clarified away). Slicing each source and locking — every slice
queues behind the lock, so the slices buy nothing and cost an offset each.

*Verify first*: `session.updateOne`/`selectOne` can run `SELECT pg_advisory_xact_lock(...)` and
`SET LOCAL statement_timeout` inside an exactly-once handler. Shown false by
`KeyedViewSuite`'s first case failing at the lock statement.

## R4. A key value source stays at least once

**Found**: `DurableStateSourceProvider`'s own comment: a durable state store keeps no history, so
intermediate updates may be skipped, and promising exactly once over it "would be a lie". Plain
views over key value entities already write on their own connection and let the projection store
the offset afterwards.

**Decision**: a keyed view over a key value entity writes all of a change's rows in one
transaction under the lock; the offset is stored afterwards. The change may be handled again.

**Rationale**: the same reason, unchanged. E3 in `contracts/keyed-views.md` says which half of
FR-016 each kind of source gets, and the spec's FR-016 is corrected to say so.

## R5. The handler runs on a virtual thread

**Found**: a plain view's `onChange` is called inline on the projection's thread and does no I/O.
`ComponentClient.await` is how handlers block, and "endpoints, workflow steps, consumers, timers
and agent loops all run on `AnkkaExecutors.virtual`".

**Decision**: a keyed view's handler runs on `AnkkaExecutors.virtual`, with its `rows` handle
blocking as a view client's does. `KeyedView` keeps its per-change state (the handle, the context)
in a value handed to the handler, not in fields set on the instance.

**Rationale**: a read that blocks a dispatcher thread while its own result needs that dispatcher
to complete is the starvation the virtual executor exists to avoid.

## R6. Declared queries: declared with the view, validated in `ComponentRegistry.validate`

**Decision**: `View.Companion` and `KeyedView.Companion` both offer `query(name)(statement)`,
returning a `DeclaredQuery` the developer keeps as a `val`, as a command is kept. Descriptors
carry them; `RemoteViewDescriptor` carries the discovered ones. `QueryCheck.problems(descriptors)`
runs beside `TopicSourceRules.problems` in `ComponentRegistry.validate` and in the sidecar's
`Discovery`, so a Scala view and a discovered one are checked by one function.

**Rationale**: that seam is where "a service that breaks a rule does not start, and the problem
names the component" already lives, for both registries.

## R7. The check is a parser's, and the parser is JSqlParser

**Found**: no SQL parser is on the build. `SqlFragment` is positional only.

**Decision**: add `com.github.jsqlparser:jsqlparser` (5.x; Apache 2.0 or LGPL 2.1, dual) to
`runtime`, as a direct `libraryDependencies` entry so the published POM carries it. `QueryCheck`
uses it for rules Q1–Q8 (`contracts/declared-queries.md`): one statement, a select, every `WITH`
item a select, no `INTO` or locking clause, every relation the view's own table or a `WITH` item,
no schema-qualified relation, no function that takes a query or a relation as text.

**Rationale**: the spec requires parsing, and a parser we write for SQL is a second SQL. The check
fails closed: a statement the parser cannot read is refused with the parser's message, so what the
parser does not know is a refusal the developer sees at startup, never a statement let through.

**Alternatives**: asking the database to plan the statement (`EXPLAIN`) and reading the plan's
relations — the database's own parse, but the statement reaches the database, which SC-002
forbids, and `validate` has no database. `libpg_query` through JNI — a native library in every
image for one function. A hand-written tokenizer — "pattern matching the text", which the spec
rules out.

*Verify first*, all in `QueryCheckSuite`, written before `QueryCheck`:
- JSqlParser reads every statement the documentation shows, including `payload::jsonb->>'f'`,
  `@>`, and a `WITH RECURSIVE` with `UNION ALL` and a join on the `WITH` item.
- Its table finder does not report a `WITH` item as a table, and does report a table inside a
  subquery, a join, a `LATERAL`, and a `WITH` item's body.
- A data-modifying `WITH` item, `SELECT … INTO`, `FOR UPDATE` and two statements are each
  recognisable from the parsed form.
- A name in a comment or a string literal is not reported as a table.

If the first fails for syntax the documentation needs, the documentation's statements are written
in what the parser reads, and the limitation is stated; the parser is not replaced by a pattern.

## R8. The database enforces reading, and ends a statement that does not end

**Decision**: a declared query runs in its own transaction, `READ ONLY`, with
`SET LOCAL statement_timeout` to the service's ask timeout less 500 ms (never under 1 s). A
statement the database cancels (`57014`) is `Timeout`.

**Rationale**: the parser decides what is *declared*; whether a statement writes is a fact about
what runs, and a function call can write where the text shows no `UPDATE`. A read-only transaction
makes "never writes" the database's promise. And "the database is no longer running the statement"
is a scenario: only the database can stop it. The margin lets the caller hear the database's
answer rather than its own wait running out.

**Scope**: declared queries only. `get`, `all`, `where`, `ordered` and `count` keep the path they
have; giving them a statement timeout is a change to every existing read and is not this
feature's. The limitation is recorded in *What is left as it is*.

*Verify first*: `SET TRANSACTION READ ONLY` then `SET LOCAL statement_timeout` then a statement,
on one r2dbc connection inside `Database.inTransaction`, behaves as on `psql`; a cancelled
statement surfaces an exception carrying `57014`. `DeclaredQuerySuite`: "a recursive query that
never ends is stopped", asserting on `pg_stat_activity` that nothing is running for the
connection.

## R9. Values are named in the statement and bound by position

**Decision**: a statement names its values `:name`. `QueryCheck` rewrites each to `$n` with a
small scanner over the text (skipping strings, quoted identifiers, dollar-quoted strings, comments
and `::`), and refuses the statement unless the names the scanner found are exactly the named
parameters the parser found. Values are text.

**Rationale**: r2dbc-postgresql binds `$n` only. Rewriting from the parser's tree would mean
sending the database the parser's re-rendering of the statement, not the developer's text, and a
lossy re-rendering of a Postgres operator would be a wrong query nobody wrote. The cross-check is
what keeps a second reading of the text from disagreeing with the first. Text values are enough
for a table whose payload is JSON text, and one type crosses the wire in every language with no
mapping to get wrong; a statement casts where it needs a number.

**Alternatives**: typed values — four SDKs' worth of type mapping for a cast the statement can
write. Deriving the values from a separate declared list — two places to keep in step.

## R10. Versions for entity views: the projection's name carries the version

**Found**: an entity view's offsets are stored under `ProjectionId("ankka-view-<id>", "<slice
range>")`. A topic view's version changes its *group*, so the new version starts with no
committed offset and the old group's offsets are left alone. `ShardedDaemonProcess` runs a named
process only on nodes that initialised that name.

**Decision**: at version *n* ≥ 2 the projection (and daemon process) name carries the version; at
version 1 it is exactly today's. The version sits beside the kind, before the id
(`ankka-view.v<n>-<id>`), and a keyed view's names have a prefix and a separator of their own
(`ankka-keyed-view-<id>+<source id>`), because a component id may contain `.`, `-` and `_` and
could otherwise spell another view's versioned name: `summary` at version 2 and `summary-v2` at
version 1. `ConsumerGroups` records the same trap for groups and answers it the same way. One
function, `ViewProjections.name`, gives every name. A rebuild is `ViewVersions.rebuild`, unchanged. Every write of a
view that reads entities is guarded: the view's lock (shared for a plain view, exclusive for a
keyed one), then the recorded version, then the write only if it equals the declared one — in the
change's own transaction. `ankka_view_versions` is created by any service with a view.

**Rationale**: a name that has read nothing begins at the source's first event, which is "read
every source again from its beginning" with no offset deleted and nothing to undo; it is the
entity form of 024's group. During a rolling update the old name runs only on old instances and
the new only on new ones, so the two versions never share an offset. The guard is 024's R7
argument, word for word: a conditional write alone loses the race with the truncation under
`READ COMMITTED`.

**Cost**: two statements more per change for every view that reads entities, in the transaction
it already has. Measured before merge by `CartViewSuite`'s timing staying within noise.

**Alternatives**: deleting the projection's offsets — races an instance still running the old
version, which would re-read from the start with the old handler. Guarding only views that
declare a version — the instance that declares none is exactly the one that must stop.

*Verify first*: a daemon process name initialised by one of two nodes of a cluster runs on that
one only; `EntityViewVersionSuite`'s rolling-update case asserts the version-1 instance handles no
event after the rebuild.

**Found false in implementation.** A sharded daemon process's coordinator is a cluster singleton that
runs on the oldest node, and only if that node has started the same name. Under a daemon name carrying
the version, the instances declaring the higher one started a process the oldest instance never knew,
and none of its projections ran ("Trying to register to coordinator" forever). The daemon process is
therefore named for version 1 whatever the version (`ViewProjections.daemon`), and only the
projection's id carries it — which is all the rebuild needs, since that is what offsets are stored
under. Every instance runs the same daemon process; an instance behind the recorded version still
starts it, so the coordinator can live there, and its guard refuses and pauses every write. During a
roll a slice held by an older instance is paused until that instance leaves, so the view may lag
until the roll completes; no row from the older version survives it. And a daemon process and an offset row accept a name holding `.` and
`+`; `ViewProjectionsSuite` holds the names, and `KeyedViewSuite`'s first case runs one.

## R11. Behind: start nothing, or pause

**Found**: Pekko Projection 1.1.0 has `ProjectionManagement` with `pause`, stored with the
offsets (`projection_management`).

**Decision**: at startup, a view recorded above its declared version starts no projection. A
running projection whose guarded write finds the view at another version fails that change (so its
offset is not stored), pauses itself, and logs L2 once.

**Rationale**: failing alone would restart the projection with backoff for as long as the old
instance lives, logging each time. A pause is per projection name, and the old name is never run
again by a version that is not behind.

*Verify first*: `ProjectionManagement(system).pause(id)` called from a handler's future takes
effect for a `ShardedDaemonProcess`-hosted projection; `EntityViewVersionSuite` asserts one L2
line, not a growing number.

## R12. An instance older than this release cannot be stopped

**Found**: the guard is new code. A runtime before it writes rows for a view that reads entities
unconditionally.

**Decision**: documented, on the upgrading page and in `contracts/rebuild.md`: raise a view's
version in a deploy after the one that brought every instance to this release. Nothing detects it.

**Rationale**: there is no statement an old instance runs that a new one could make fail without
changing the table, and the table's shape is fixed by FR-004.

## R13. Protocol 1.13

**Decision** (`contracts/protocol.md`): additive fields only, so a minor.
`ViewDetail.sources` and `ViewDetail.declared_queries`; `ViewRequest.source_id`; a fourth
`ViewEffect` case, `rows`; `QueryRequest.values` and `limit`. `ViewDetail.version`'s meaning
widens to views that read entities. An SDK that declares a keyed view, a declared query or a
version on an entity view refuses discovery from a runtime below 1.13, as 024's SDKs refuse a start
position from one below 1.7.

**Rationale**: an older runtime ignores fields it does not know. A keyed view would arrive with no
`source` and be refused, loudly, but a declared query would simply not exist and be found missing
at its first call, and a version would be refused with a message about topics. The SDK's own
refusal names the cause.

**Not done**: stamping `ankka.protocol` on view requests, as consumers have for `produce_all`. A
`rows` reply can only come from a view the runtime registered as keyed, which a runtime below 1.13
cannot do, so there is no request on which a newer SDK could answer what an older runtime would
misread.

## R14. Remote hosting

**Decision**: `RemoteKeyedViewDescriptor` beside `RemoteViewDescriptor`; `Conversation.handleView`
gains nothing — `ViewRequest` carries an optional source id and `ViewOutcome` a `Rows` case. The
remote keyed handlers are the in-process ones with the conversation in place of the Scala handler,
as `RemoteViewEventHandler` is to `ViewEventHandler`. A process's or a module's reads are
`Client.Query` to the sidecar, which is `ClientLogic.query` learning declared names from the
registry. `runtime` still names nothing generated.

**Found for modules**: a view runs on a fresh instance and the `query` import already exists
(`HostImports.scala:112-115`), so a module's keyed handler can read. E6's 4 MiB bound is the
transport's for a process and is applied in process too.

## R15. SDK shapes

**Decision** (`contracts/sdk-apis.md`): each SDK gains a keyed view beside its view, a query
declaration on both, `ask` on its view client, a `rows` handle for a keyed handler, the entity
version, and a keyed test kit. Python's keyed-view servicer is given a client, which a Python view
never had.

## R16. Topology and names

**Found**: `DeclaredConnections.sourceOf` returns at most one source, and `TopologyJson.declared`
documents "exactly one for a source … so a reader may say nothing else feeds a view", with
`TopologyJsonSuite.scala:236` asserting one edge. A view declares one handler, `on-change`.

**Decision**: `sourcesOf` returns a vector; a keyed view yields one connection per source, and
`rejectUnsupported*` reads the same vector. A keyed view declares one handler per source, named
for the source's component id. The sentence in `TopologyJson` becomes "exactly the sources the
view declared".

**Rationale**: handler names are interned into the recorder's table, which is bounded because
they are declared; a source's component id is.

## R17. Test kits

**Decision**: Scala gains `KeyedViewTestKit` in `testkit`: rows in a map, changes fed per source,
effects applied through `RowChanges.reduce`, `get` answered from the map. A declared query is SQL
and the kit has no database, so the kit is told the answer (`answering(query)(values => rows)`)
and **fails loudly** when a handler asks a query it was not told about, as `TestModelProvider`
does when its script runs out. It also runs `QueryCheck` on the view's declarations, so a
statement that would stop the service fails the unit test. Python's, TypeScript's and Rust's kits
gain the same.

**Rationale**: a kit that returned no rows for an unscripted query would pass a handler that
updates nothing.

## R18. How each scenario becomes a test

**Found**: CLAUDE.md's rule — a feature file one suite can run whole is run by `GherkinSuite`; a
scenario no suite can reach is a test named after it. 024's `ViewVersionSuite` is the second kind.

**Decision**:

| Feature | Run by |
|---|---|
| `features/views/declared-queries.feature`, `recursive-queries.feature`, `several-sources.feature` | `ViewsFeatures` in `testkit`, three subclasses of one `ViewsSteps` (the `TopologySteps` pattern), over a view whose behaviour a scripted event states |
| `features/views/rebuilding.feature` | `EntityViewVersionSuite` in `testkit`, cases named for the scenarios, on `ViewVersionSuite`'s two-instance harness |
| `features/views/languages.feature` | conformance cases named for the scenarios, run for the Scala reference and each SDK |
| `features/topics/versions.feature`: the changed scenario | `TopicSourceRulesSuite`, its case renamed |
| `features/documentation/views.feature` | read off the pages in the documentation slice, as 024's were: a test that looked for their sentences would be the check that finds a string somewhere. The samples those pages show are included from tested code, and `DocumentationDescriptorsSuite` and the docs build hold what can be held |

## R19. What is left as it is

- `get`, `all`, `where`, `ordered` and `count` have no statement timeout (R8).
- A plain view cannot name row keys or read rows beyond its own.
- A keyed view cannot read a topic.
- The local console does not list declared queries (the spec's open question).
- A view that reads entities and is behind says so in the service's log only. 024's metrics
  series and service document list topic sources, by topic and group, and an entity view has
  neither; showing every view's version there is a change to that list and is not made here.
- Remote plain views keep `RemoteProjection.Parallelism`; a keyed view has none to keep.
