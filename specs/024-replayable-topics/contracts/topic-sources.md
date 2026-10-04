# Contract: what a topic source declares, and what the runtime does with it

The behaviour every language's declaration reaches. The declarations themselves are in
[scala-api.md](scala-api.md) and [sdk-apis.md](sdk-apis.md); how they cross the wire is
[protocol.md](protocol.md); the group a source reads under is [group-names.md](group-names.md).

## Registration rules

One function, `TopicSourceRules.problems`, in `runtime`. `ComponentRegistry.validate` applies it to
every view and consumer, in-process and discovered, so a service that breaks a rule does not
start; `Discovery.validate` applies it too, so a process is told through `ReportError` with every
other problem. Each problem names the component.

| # | Rule | Applies to |
| --- | --- | --- |
| T1 | A consumer that reads a topic declares a start position. | consumers; a discovered one only when its SDK states protocol 1.7 or later |
| T2 | A start position is `earliest`, `latest` or a time. | both |
| T3 | A start position is declared only on a topic source. | both |
| T4 | A version is declared only by a component that reads a topic. | both |
| T5 | A version is a whole number of 1 or more. | both |

A view that reads a topic and declares no start position starts at `earliest`. T3 cannot be broken
in Scala, where the start position is an argument of `ChangeSource.fromTopic`.

## Start position

Applied per partition, the first time the group is assigned a partition it holds no committed
offset for. Resolved to an offset and **committed there and then**, before any message is read.

| Declared | Offset |
| --- | --- |
| `earliest` | the partition's beginning |
| `latest` | the partition's end at the moment of assignment |
| a time | the first message whose timestamp is at or after it; the end if there is none |

A time earlier than anything retained therefore reads from the beginning, and a time in the
future reads as `latest`.

Once a partition has a committed offset the start position is never consulted for it again: a
restart, a rebalance and a new instance all resume from the commit. This is why the offset is
committed at assignment. A group that started at `latest` and has read nothing from a partition
would otherwise hold no commit for it, and a restart would move it to the *new* end, past
everything published while it was down.

A committed offset the broker no longer holds, because the group was away for longer than the
topic's retention, reads from the beginning of what remains, as today.

One case the start position cannot tell apart: a partition **added to a topic** after the group
first read it has no committed offset either, so it starts where the source declares. Under
`latest` the messages published to it before the group is assigned it are not read. The topics
page says so.

## Version

A whole number of 1 or more, declared on the component. None declared is version 1.

### A view

The service's database holds a **recorded version** for each topic-sourced view
([data-model.md](../data-model.md)). When the service starts, for each such view:

| Declared against recorded | The instance |
| --- | --- |
| no record | records version 1, without touching the table, then compares as below |
| equal | subscribes under the group for that version |
| higher | **rebuilds**, then subscribes under the group for the declared version |
| lower | is **behind**: does not subscribe, does not write, logs it, and serves the table as it is |

A **rebuild**, in order:

1. Ask the broker for the timestamp of the earliest message it holds on each partition of the
   topic. Until the broker answers, nothing else happens: a view is never emptied while there is
   no broker to refill it from. Retried with the subscriber's backoff.
2. Log the view, the recorded version, the declared version and those timestamps (L2).
3. In one transaction: take the view's exclusive lock; read the recorded version again; if it is
   still lower than the declared one, empty the table and record the declared version.
4. Subscribe under the declared version's group, which has read nothing, so the start position
   applies.

Step 3's second read is what makes a rebuild happen once when several instances start together:
the instance that loses the lock finds the version already recorded and goes straight to step 4. An
instance that starts after the version is recorded never reaches step 1. So L2 may be logged by more
than one instance, and L4 by none; L6 is logged exactly once per rebuild, which is what a test of
"emptied once" counts.

### Every write is checked

A topic-sourced view's row is written, or deleted, in a transaction that first takes the view's
**shared** lock and then writes only if the recorded version equals the writer's own. A write that
finds another version writes nothing, and the instance stops that subscription without committing
the message's offset, logs that it is behind (L3) and marks itself so in its metrics.

The pair of locks is the whole of the guarantee that no row written at an earlier version
survives a rebuild: a rebuild waits for writes in flight, and a write that waited for a rebuild
sees the version the rebuild recorded.

An instance that is behind stays ready and keeps answering queries from the table. A view's
version only goes up: a service rolled back to a lower one leaves the view not updating, and the
way back is the old handler published under a version higher than the recorded one.

### A consumer

A consumer keeps no rows and has no recorded version. Its version changes its group, and with it
where it reads from: a consumer deployed at a higher version reads from its start position under
a group that has read nothing. During a rolling update both versions' groups are live, so a
message published during the roll is delivered under each.

## What the service says

### Log lines

At `INFO` unless noted. Each is one line whose fields are named, so that it can be found by any
of them.

| # | When | Says |
| --- | --- | --- |
| L1 | a topic source subscribes | kind, component id, topic, group, start position, version |
| L2 | a rebuild is about to empty a view | component id, table, recorded version, declared version, and for each partition the timestamp of the earliest retained message, or that it holds none |
| L3 | an instance finds a view behind, at start or on a write (`WARN`) | component id, declared version, recorded version, and that the view is not being updated by this instance |
| L4 | a rebuild found the version already recorded by another instance | component id, version |
| L6 | a rebuild emptied the view (`view emptied for rebuild:`) | component id, the version it replaces, the version it builds |
| L5 | a discovered consumer over a topic declares no start position and its SDK predates 1.7 (`WARN`) | component id, the SDK's protocol version, that it starts at `earliest`, and the version that can declare one |

### Metrics

Two series on the deployed exposition, `GET /ankka/metrics` on the management port, written by
`Metrics.render` from the list of topic sources the runtime holds. Their labels are bounded: a
component id is declared, and there is one group per component.

Who can read them today is narrow, and the topics page says so: the management port admits only
the service's own instances, so until 026-telemetry-export gives an installation's monitoring a
way in, a deployed service's topic sources are read from its log (`services logs`), where L1 to L5
say the same things.

```text
# HELP ankka_topic_source_info A view or consumer reading a topic, and the group it reads under.
# TYPE ankka_topic_source_info gauge
ankka_topic_source_info{kind="view",component="summary",topic="order-changes",group="ankka.shop.orders.view-v2.summary",start="earliest",version="2"} 1

# HELP ankka_topic_source_behind 1 when this instance declares a lower version of a view than the one recorded, and is not updating it.
# TYPE ankka_topic_source_behind gauge
ankka_topic_source_behind{component="summary",declared="1",recorded="2"} 1
```

`start` is `earliest`, `latest` or the time in ISO-8601. `ankka_topic_source_behind` has a series
for every topic-sourced view, `0` when it is not behind. A service with no topic source writes the
two headers and no series.

### A local run

A local run has no management port. The loopback endpoint the local console reads answers
`GET /observability/service` with the service's components; that answer gains `topicSources`, an
array of the same facts:

```json
{"kind":"view","component":"summary","topic":"order-changes","group":"ankka.local.orders.view-v2.summary","start":"earliest","version":2,"recordedVersion":2,"behind":false}
```

One list in the runtime, two exposures, chosen by where the process runs, as the recorder has. The
local console's pages are not changed to show it.
