# Contract: keyed views

A **keyed view** is the second shape of view: one or more entity sources, a handler per source,
and effects that name every row they write or delete by row key. The first shape, the **plain
view** that exists today (one source, one handler, the row kept under the source's entity id), is
unchanged. "A view of several sources" in the features and the glossary is a keyed view with more
than one source; the code needs a name for the shape because a keyed view may also have one.

Why two shapes and not one view that may do either: whether a handler names row keys decides
whether the view must handle one change at a time, and the runtime has to know that when it starts
the view, not when a handler first returns a key. The shape is the declaration.

| | plain view | keyed view |
|---|---|---|
| sources | one: an entity's events, a key value entity's state, or a topic | one or more, each an entity's events or a key value entity's state |
| handlers | one, `on-change` | one per source, named for the source's component id |
| row key | the source's entity id, always | whatever the handler names |
| the current row | handed to the handler | read by the handler, by key or by declared query |
| rows per change | at most one | any number |
| when its source is deleted | the row is deleted unless the handler says otherwise | nothing, unless the source's deletion handler names rows |
| changes handled at once | one per slice of the source, `parallelism` slices | one, across all sources and instances |
| declared queries | yes | yes, and its own handlers may ask them |
| version | yes | yes |

## K1–K6: what is refused when the view is registered

Checked for a Scala view when its descriptor is built and again in `ComponentRegistry.validate`,
and for a discovered view where discovery is read. The problem names the view.

| Rule | Refused | Problem says |
|---|---|---|
| K1 | a keyed view with no source | "declares no source" |
| K2 | a keyed view with a topic and an entity among its sources | "a topic and an entity may not be sources of one view" |
| K3 | a keyed view whose sources are topics only | "a keyed view reads entities; it names the topic 't'" |
| K4 | two sources that read the same component | the component |
| K5 | a source naming a component that is not an event sourced or key value entity (discovery only; Scala's types cannot spell it) | the component and its kind |
| K6 | a keyed view declared to an older runtime (SDK side, at discovery) | "a keyed view needs protocol 1.13; the runtime speaks 1.x" |

No rule is needed to keep two views' projection names apart: `ViewProjections.name` cannot give
two views one name ([rebuild.md](rebuild.md)).

## The effect

A handler returns row changes: an ordered list, each an upsert of a row under a key or a deletion
of a key. An empty list is "nothing". The algebra is `KeyedViewEffect` in `core`; building one
writes nothing.

| Rule | |
|---|---|
| E1 | A key is non-empty text. An empty key fails the change. |
| E2 | Changes are applied in the order given. Two for one key: the later wins, so "delete, then write the same key" writes. |
| E3 | All of one change's rows are written in one transaction. For an event sourced source that transaction also records the source's progress, so the change is applied exactly once. For a key value source the rows are written together and the progress is recorded afterwards, so the change may be handled again, as it may for a plain view today. |
| E4 | A row that cannot be encoded, or a write the database refuses, fails the change: none of its rows is written and it is handled again. |
| E5 | The runtime deletes no row the handler did not name. Moving a row is a deletion of the old key and an upsert of the new one in one effect. |
| E6 | One change's row changes may weigh at most 4 MiB together as they cross from a process or a module, the transport's limit, and the same bound is applied in process so that hosting does not decide how many rows a change may write. |

`RowChanges.reduce` (in `core`) turns the list into the final change per key, in order of first
appearance. The runtime's two handlers, the remote host and every test kit apply an effect through
it, so they cannot disagree about E2.

## What a handler may read

For the length of one change, a keyed view's handler has a handle on its own view's rows:

| Read | Answers |
|---|---|
| `get(key)` | the row under a key, or none |
| `ask(query, values)` | the rows of one of the view's declared queries |

and the change's context: the source's entity id (the subject) and its sequence number. It has no
handle on any other view. The handle fails outside a handler.

Reads see what is committed. Nothing else can commit to this view's table while the change is
being handled (O1), so what a handler reads is what it writes over.

## O1–O4: one change at a time

| Rule | |
|---|---|
| O1 | Before a keyed view's handler runs, its transaction takes the view's advisory lock **exclusively** (`ViewVersions.LockClass`, the hash of the view's id) and holds it until it commits or rolls back. Every source's handler, on every instance, takes the same lock. |
| O2 | Each source is one projection with one instance, covering every slice. Slicing a source would only queue behind O1. The source's own order is therefore kept; the order between two sources is whichever takes the lock first. |
| O3 | Once the lock is held, the transaction's statement timeout is set to the ask timeout, and a handler in Scala is given the same time to answer; a handler in a process is bounded by the conversation's request timeout. A handler that outlives them fails its change, which is handled again; it does not hold the view. The timeout is set after the lock is taken, so a change waiting its turn is not failed by the wait, and with `SELECT set_config('statement_timeout', …, true)` rather than `SET LOCAL`: the projection's session reads a row count from every statement it is given, and a `SET` reports none. |
| O4 | A plain view takes the same lock **shared** for its guarded write (see [rebuild.md](rebuild.md)), so plain views do not queue behind each other, and nothing changes in how many slices they run. |

The cost is stated in the documentation: a keyed view has one writer, and its throughput is one
handler at a time however many instances the service has. A view that can be a plain view should
be one.

## Handlers and names

A keyed view declares one handler per source, named for the source's component id, each of kind
`Update`. The names are declared, so they are bounded, and they are what a span and a call's
origin carry. A plain view's one handler is still `on-change`.

## Topology

A keyed view has one declared connection per source: an event subscription from each event
sourced entity, a state subscription from each key value entity. `DeclaredConnections.sourcesOf`
returns them all, and is still the one reading of a descriptor that both `ProjectionRuntime` and
the topology use.
