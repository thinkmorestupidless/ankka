# Data Model: Replayable Topic Sources

What this feature adds or changes, as values and as stored state. Behaviour is in
[contracts/topic-sources.md](contracts/topic-sources.md).

## Values

### `StartFrom` — `sdk`

| Case | Field | Meaning |
| --- | --- | --- |
| `Earliest` | | the oldest message the broker holds |
| `Latest` | | after the newest; only what is published from then on |
| `At` | `time: Instant` | the first message published at or after `time` |

On the wire, `StartFrom` in [contracts/protocol.md](contracts/protocol.md): a named position or
`at_millis`.

### `ChangeSource.Topic` — `sdk` (changed)

| Field | Type | |
| --- | --- | --- |
| `topic` | `String` | unchanged |
| `decoder` | `Serializer[Src]` | unchanged |
| `startFrom` | `Option[StartFrom]` | new; `None` is "not declared" |

### `ViewDescriptor`, `ConsumerDescriptor` — `sdk` (changed)

One new field each: `version: Option[Int]`, `None` being "not declared", which is version 1.

### `RemoteSource.Topic`, `RemoteViewDescriptor`, `RemoteConsumerDescriptor` — `runtime/remote` (changed)

`RemoteSource.Topic(name, startFrom: Option[StartFrom])`; `version: Option[Int]` on each
descriptor. The discovered `Spec`'s protocol version is carried to where rule T1 is applied, so
that a consumer from an SDK below 1.4 is not held to it.

### `ServiceIdentity` — `runtime`

| Field | Type | |
| --- | --- | --- |
| `project` | `Option[String]` | set only for a deployed service |
| `service` | `Option[String]` | set for a deployed service, and for a local one that states its name |

Three shapes and no others: deployed (both), named local (service only), unnamed (neither). A
project without a service cannot be built.

Resolving it can fail: a deployed workload whose certificate carries no identity, or a local name
that breaks the rule for one. `ServiceIdentity.resolve` therefore answers
`Either[String, ServiceIdentity]`, the left being the sentence that names what is wrong, and
`AnkkaService.identity` holds that `Either`. A service with no topic source never looks at it and
starts as it does today; `ProjectionRuntime` refuses a topic source when it is a `Left`, with that
sentence. It is not a fourth shape of identity: nothing is ever named from it.

Resolved once at startup ([contracts/group-names.md](contracts/group-names.md)).

### `TopicSubscription`, `Subscribed` — `runtime`

In [contracts/subscriber.md](contracts/subscriber.md).

### `TopicSourceStatus` — `runtime`, in memory only

What the log lines and the metrics are written from. One for each topic source of the running
service, held by `ProjectionRuntime` and reached through an extension, as the recorder is.

| Field | Type | |
| --- | --- | --- |
| `kind` | `ComponentKind` | `View` or `Consumer` |
| `componentId` | `ComponentId` | |
| `topic` | `String` | |
| `group` | `String` | from `ConsumerGroups.name` |
| `startFrom` | `StartFrom` | as resolved: a view that declared none has `Earliest` |
| `version` | `Int` | declared, or 1 |
| `recordedVersion` | `Option[Int]` | a view's; `None` for a consumer |
| `behind` | `Boolean` | set at startup, or by a refused write |

The list is fixed once the service has started; only `recordedVersion` and `behind` change, and
only in one direction.

## Stored state

### `ankka_view_versions` — the service's own database (new)

```sql
CREATE TABLE IF NOT EXISTS ankka_view_versions (
  component_id TEXT PRIMARY KEY,
  version      INTEGER NOT NULL CHECK (version >= 1),
  built_at     TIMESTAMPTZ NOT NULL DEFAULT now()
)
```

One row for each topic-sourced view the service has ever started. Created by the runtime with the
view tables, in the same transaction and under the same advisory lock, and only by a service that
has a topic-sourced view. Never dropped, and a row is never deleted: a view removed from a service
and added back later finds the version it was last built at.

| Transition | When | In one transaction with |
| --- | --- | --- |
| no row → `version = 1` | the first start of a view after this feature, whatever it declares | the view table's creation; the table's rows are not touched |
| `version = r` → `version = d`, `d > r` | a rebuild | the exclusive lock and `TRUNCATE` of the view's table |

There is no transition that lowers `version`.

`built_at` is when the row was last written. It is the answer to "when was this view last
rebuilt", which the first person to debug a short view will ask.

### A view's row table — unchanged in shape

`ankka_view_<id>` keeps `row_key`, `payload`, `updated_at`. What changes is how a topic-sourced
view writes it: every upsert and delete is conditional on the recorded version
([contracts/topic-sources.md](contracts/topic-sources.md), "Every write is checked"). An
entity-sourced view writes as it does today.

### On the broker

Group ids, as [contracts/group-names.md](contracts/group-names.md) names them, each with a
committed offset for every partition it has been assigned, from the moment of assignment. A group
this feature stops using — the pre-feature name, or a lower version's — is left as it was.

## Configuration

| Key | Variable | Default | |
| --- | --- | --- | --- |
| `ankka.service.name` | `ANKKA_SERVICE_NAME` | empty | The service's name for a local run. Not read in `kubernetes` mode. Refused in a descriptor. |

In `modules/runtime/src/main/resources/reference.conf`, so the generated configuration table
lists it and the docs build requires the prose beside the table to say what it does.
