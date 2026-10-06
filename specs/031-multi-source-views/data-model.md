# Data Model: Multi-Source Views

What is declared, what is stored, and what crosses between them. Nothing here changes a stored
form: a view's table, the journal and durable state are as they were.

## Declared (values on descriptors; building one reads and writes nothing)

### DeclaredQuery — `sdk`

| Field | Type | Rule |
|---|---|---|
| `name` | `String` | the wire name; `[a-z0-9-]+`; not a fixed way of asking; unique in its view (Q9) |
| `statement` | `String` | as the developer wrote it; checked by `QueryCheck` (Q1–Q8) |

Carried by `ViewDescriptor.queries`, `KeyedViewDescriptor.queries` and, discovered,
`RemoteViewDescriptor.declaredQueries` and `RemoteKeyedViewDescriptor.declaredQueries`.

### CheckedQuery — `runtime`, what `QueryCheck` returns

| Field | Type | |
|---|---|---|
| `name` | `String` | |
| `sql` | `String` | the statement with each `:name` replaced by `$n` |
| `values` | `Vector[String]` | the value names, in the order of `$1…$n` |

Built once per view at startup and held by `ViewQueries`; never built at a call.

### KeyedViewDescriptor — `sdk`

| Field | Type | Rule |
|---|---|---|
| `componentId` | `ComponentId` | |
| `sources` | `Vector[KeyedSource[V]]` | non-empty (K1); entities only (K2, K3); distinct components (K4) |
| `rowSerializer` | `Serializer[Row]` | |
| `queries` | `Vector[DeclaredQuery]` | |
| `version` | `Option[Int]` | none is 1; ≥ 1 (T5) |
| `create` | `ViewComponentContext => V` | |

`kind` is `ComponentKind.View`: a keyed view is a view to the registry, the topology and the
console. `tableName` is `ViewDescriptor.tableFor(componentId)`, the one derivation.
`declaredHandlers` is one `DeclaredHandler(<source component id>, HandlerKind.Update)` per source.

### KeyedSource — `sdk`

| Field | Type | |
|---|---|---|
| `source` | `ChangeSource.EventSourced[Src]` or `ChangeSource.KeyValue[Src]` | what is read, with its decoder |
| `onChange` | `V => (Src, KeyedChange[Row]) => KeyedViewEffect[Row]` | as `contracts/scala-api.md` declares it |
| `onDelete` | `V => KeyedChange[Row] => KeyedViewEffect[Row]` | default: nothing |

### ViewDescriptor — `sdk`, changed

Gains `queries: Vector[DeclaredQuery] = Vector.empty`. `version` now means something for an
entity source. Nothing else changes; `source`, `parallelism` and `OnChange` are as they were.

### RemoteKeyedViewDescriptor — `runtime/remote`

| Field | Type |
|---|---|
| `componentId` | `ComponentId` |
| `sources` | `Vector[RemoteSource.Component]` |
| `rowManifest` | `String` |
| `declaredQueries` | `Vector[DeclaredQuery]` |
| `version` | `Option[Int]` |

`RemoteViewDescriptor` gains `declaredQueries`. Its `queries: Set[MethodName]` stays, and stays
unread.

## The effect — `core`

```text
KeyedViewEffect[Row] = Vector[RowChange[Row]]          (empty: nothing)
RowChange[Row]       = Upsert(key: String, row: Row) | Delete(key: String)
RowChanges.reduce    : Vector[RowChange[Row]] => Vector[(String, Option[Row])]
                       the final change per key (later wins), keys in order of first appearance
```

`ViewEffect` (`UpdateRow | DeleteRow | Ignore`) is unchanged and is still a plain view's.

## What a keyed handler is handed — `sdk`

`KeyedChange[Row]`, valid for one change:

| Member | |
|---|---|
| `subject: String` | the source's entity id |
| `sequenceNumber: Long` | the event's sequence number, or the state's revision |
| `rows.get(key): Option[Row]` | E/K rules in `contracts/keyed-views.md` |
| `rows.ask(query, values*): Vector[Row]` | one of this view's declared queries |

## Stored

### A view's table — unchanged

`ankka_view_<id>`: `row_key TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TIMESTAMPTZ`. A
keyed view's `row_key` is what its handlers named; a plain view's is its source's entity id.

### `ankka_view_versions` — existing, now for every view

`component_id TEXT PRIMARY KEY, version INTEGER NOT NULL CHECK (version >= 1), built_at
TIMESTAMPTZ`. Created by a service with any view (it was: with a topic-sourced view), and a row at
version 1 ensured for each view that has none. An additive change: a table that was absent appears,
and nothing a running application needs is altered.

### Projection offsets — existing tables, new names

| View | Version | `ProjectionId` name | key |
|---|---|---|---|
| plain | 1 | `ankka-view-<id>` | `<min>-<max>` per slice range, as today |
| plain | *n* ≥ 2 | `ankka-view.v<n>-<id>` | as today |
| keyed | 1 | `ankka-keyed-view-<id>+<source id>` | `0-1023` |
| keyed | *n* ≥ 2 | `ankka-keyed-view.v<n>-<id>+<source id>` | `0-1023` |

Every name comes from `ViewProjections.name`, and none can be spelled by another view's id
(`contracts/rebuild.md`). An earlier version's offsets are never read again and never deleted. `projection_management`
holds a pause for a name whose instance found the view behind.

## Relationships and states

```text
view ──declares──▶ source (1 for a plain view, 1..n for a keyed view)
view ──declares──▶ declared query (0..n) ──checked once──▶ checked query
view ──keeps────▶ table (exactly 1) ──holds──▶ row (0..n, one per row key)
view ──recorded at──▶ version (1 row in ankka_view_versions)
```

A view on one instance, by declared *d* and recorded *r*:

| | State | What the instance does |
|---|---|---|
| *d* = *r* | current | reads its sources, writes under the guard |
| *d* > *r* | rebuilding, then current | one instance empties the table and records *d*; all start reading under *d*'s names |
| *d* < *r* | behind | reads nothing and writes nothing for the view; serves the rows as they are |

Transitions are 024's; *behind* is entered at startup, or while running when a guarded write finds
*r* has moved.
