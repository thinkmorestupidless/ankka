# Data Model: Topic Retention

The shapes the plan adds or extends. Wire forms are in [contracts/](contracts/); this file is the
typed form each side holds. `R` numbers refer to [research.md](research.md).

## Topic settings (`controlplane-api`, R2)

```scala
final case class TopicSettings(
  retention: RetentionTime,            // Bounded(FiniteDuration) | Everything
  retentionSize: RetentionSize,        // Bounded(bytes) | NoLimit, per partition
  cleanup: CleanupPolicy,              // Delete | Compact | CompactDelete
  tombstoneWindow: FiniteDuration,     // delete.retention.ms
  minCompactionLag: FiniteDuration,    // min.compaction.lag.ms
  maxCompactionLag: CompactionLagBound,// Bounded(FiniteDuration) | NoLimit → max.compaction.lag.ms
  copies: Option[Int],                 // None only for a topic declared before this feature ("as the broker holds")
  minInSync: Option[Int])              // likewise; Some for every declaration made since

enum Setting { case Retention, RetentionSize, Cleanup, TombstoneWindow, MinCompactionLag, MaxCompactionLag, Copies, MinInSync }
final case class SettingChange(setting: Setting, from: String, to: String)   // wire-form values
```

- Every value type has a string codec in its companion: `"90d"`, `"everything"`, `"50GiB"`, `"none"`,
  `"compact,delete"`. Durations take `ms`, `s`, `m`, `h`, `d`; sizes `B`, `KiB`, `MiB`, `GiB`, `TiB`.
- `toKafka: Map[String, String]`: `retention.ms` (`-1` for everything), `retention.bytes` (`-1` for
  none), `cleanup.policy`, `delete.retention.ms`, `min.compaction.lag.ms`, `max.compaction.lag.ms`
  (`9223372036854775807` for none), `min.insync.replicas` (absent when `minInSync` is `None`).
- `compacted: Boolean` is derived: `cleanup != Delete`.

### Rules (`ProjectTopics`, R4)

| Rule | Answer |
|---|---|
| a duration or size the parser does not read | 400 naming the field and the accepted units |
| `compacted: true` with `cleanup: "delete"` | 400 `compacted and cleanup disagree` |
| retention time longer than the installation's longest | 400 `retention time 2y is longer than the installation's longest, 1y` |
| retention size larger than the installation's largest | 400 likewise |
| copies above the installation's most, or below 1 | 400 |
| `minInSync` below 1 or above `copies` | 400 `minimum in-sync copies must be between 1 and the copies, 3` |
| a change to `copies` or `minInSync` on a declared topic | 409 `copies and minimum in-sync copies are fixed when a topic is declared`; nothing else applied |
| `copies` or `minInSync` stated for a topic whose copies are the broker's (pre-feature) | 409, the same rule (FR-008) |
| partitions left out on a first declaration | 400 `partitions is required for a new topic` |
| fewer partitions | 409, as today |
| a removal without `removes` | 400 (R5) |
| a removal by a member who is not an owner | 403 (R5) |
| `removes` on a declaration that removes nothing | 400 `this declaration removes nothing` |

`ProjectTopics.fill(request, defaults)` returns a first declaration's settings with every absent field
taken from the installation's defaults and the set of fields so taken; `ProjectTopics.merge(request,
current)` returns a redeclaration's settings with every absent field taken from the current
declaration, and `defaulted` kept for fields not given. `ProjectTopics.changes(before, after)` is the
list of differing settings; `ProjectTopics.removal(changes)` is `Some(text)` when retention shrinks,
the size shrinks, or `compact` is lost.

## Installation topic policy (`controlplane`, R3)

```scala
final case class TopicDefaults(retention: RetentionTime, retentionSize: RetentionSize, cleanup: CleanupPolicy,
  tombstoneWindow: FiniteDuration, minCompactionLag: FiniteDuration, maxCompactionLag: CompactionLagBound,
  copies: Int, minInSync: Int)
final case class TopicBounds(longestRetention: RetentionTime, largestRetentionSize: RetentionSize, mostCopies: Int)
final case class TopicPolicy(defaults: TopicDefaults, bounds: TopicBounds, warningThreshold: FiniteDuration)
```

Read once at start from `ankka.controlplane.topics.*`; a default outside its bound, or a value the
parser refuses, fails the start naming the key and the variable (the twelve variables are in R3).

## Declared topic (`controlplane`, extended)

```scala
final case class DeclaredTopic(
  partitions: Int,
  declaredAt: Option[Instant] = None,
  compacted: Boolean = false,                 // 037; kept in the fold, derived from settings when they exist
  contract: Option[Contract] = None,
  settings: Option[TopicSettings] = None,     // None: declared before this feature and not yet filled
  defaulted: Set[Setting] = Set.empty)
```

Transitions:

- **declare** (new name): `settings = Some(filled)`, `defaulted` as filled, `declaredAt` now.
- **declare** (existing): partitions never fewer, and unchanged when left out; `copies`/`minInSync`
  unchanged or refused (stated at all for a topic whose copies are `None` is refused); every other
  setting given replaces the current value and leaves `defaulted`; a setting left out **keeps its
  current value and its mark**. A default reaches an existing topic only when a member declares it.
- **fill** (the sweep): only when `settings` is `None`; `copies`/`minInSync` stay `None`;
  `cleanup = Compact` when `compacted` was true, else the default; everything marked defaulted.
- **remove**: as today.

## Events (`controlplane`, R4, R7)

```scala
case ProjectTopicDeclared(name, partitions, actor, at, compacted = false, contract = None,
  settings: Option[TopicSettings] = None, defaulted: Set[String] = Set.empty, changes: Vector[SettingChange] = Vector.empty)
case ProjectTopicSettingsFilled(name: String, settings: TopicSettings, defaulted: Set[String], at: Option[Instant] = None)
```

- Every new field has a default, so the pre-037 and 037 journals replay; `EventCompatibilitySuite`
  pins the bare form `{type,name,partitions,at}`, the 037 form, and the full form.
- `changes` is empty on a first declaration and on the fill; the fold ignores it (state is `settings`).

## Project history (`controlplane`, R6)

```scala
final case class ProjectHistoryEntry(kind: String,            // topic-declared | topic-changed | topic-filled | topic-removed
  topic: String, actor: Option[HistoryActor], at: Option[Instant], changes: Vector[SettingChange] = Vector.empty)
// Project.history: Vector[ProjectHistoryEntry], newest first, capped at Project.HistoryLimit (the service's cap)
```

## Commands (`controlplane`)

```scala
final case class DeclareTopic(name, partitions: Option[Int], compacted = false, contract = None,
  settings: Option[TopicSettings] = None, defaulted: Set[Setting] = Set.empty)   // settings filled or merged by the endpoint; partitions None keeps them
final case class FillTopicSettings(defaults: TopicDefaults)                      // the sweep's; replies with the names filled
```

The entity computes `changes` against its state and persists it on the event; the endpoint computes
the removal before sending (it needs the old settings to refuse, and the entity cannot see the
organization's roles).

## `AnkkaProject` (`crd`, R8, R9)

```scala
final case class ProjectTopicEntry(name, partitions, declaredAt, compacted = false, contractName, contractFingerprint,
  retentionMs: Option[Long] = None, retentionBytes: Option[Long] = None, cleanupPolicy: Option[String] = None,
  deleteRetentionMs: Option[Long] = None, minCompactionLagMs: Option[Long] = None, maxCompactionLagMs: Option[Long] = None,
  replicas: Option[Int] = None, minInsyncReplicas: Option[Int] = None)     // each Option with @JsonDeserialize(contentAs = …)
final case class ProjectTopicStatus(name, phase, partitions, detail, compacted,
  replicas: Option[Int] = None, config: Option[Map[String, String]] = None)
final case class AnkkaProjectStatus(topics: List[ProjectTopicStatus] = Nil, brokerNodes: Option[Int] = None)
```

`kustomization/components/crd/ankkaproject.yaml` declares every field; `CrdSchemaSuite` holds both
directions. An entry whose declaration has no settings carries none of the new fields.

## `KafkaTopic` (`operator`, R10)

```scala
final case class KafkaTopicSpec(partitions: Int = 1, replicas: Option[Int] = None, config: Option[Map[String, Object]] = None)
```

- Rendered from an entry with settings: `replicas = entry.replicas`, `config = Some(the seven keys)`.
- Rendered from an entry without: as 037 (`replicas` absent, `config` `None` or the compaction map).
- Observed: `spec.partitions`, `spec.replicas`, `spec.config`; `TopicState` gains `replicas` and `config`.
- `TopicPlan.decide(entry, state, brokerNodes: Option[Int])`: `Failed` when `entry.replicas > nodes`
  (`topic 'p.t' asks for N copies and the broker has M broker nodes`), when the broker is absent, on a
  shrink, or on a permanent Strimzi reason; `Ready` when partitions, replicas (where declared) and
  config equal the declaration; else `Waiting`.

## Broker node count (`operator`, R9)

`KafkaNodePoolResource` (`kafka.strimzi.io/v1`): `spec.replicas: Int`, `spec.roles: List[String]`.
`Executor.brokerNodes(namespace, cluster): Option[Int]` sums `replicas` over pools labelled for the
cluster whose roles include `broker`; `None` when the type is absent.

## Topic configuration cache (`runtime`, R11)

```scala
final case class TopicConfig(cleanup: Set[String], minInSync: Int)      // from cleanup.policy and min.insync.replicas
final class TopicConfigs(connection: KafkaConnection, interval: FiniteDuration = 60.seconds):
  def of(qualifiedTopic: String): Option[TopicConfig]                    // None until first read; refreshed when stale, off the caller's thread
  def describe(qualifiedTopic: String): Future[TopicConfig]              // one Admin.describeConfigs
```

`KeylessPublication(topic: String)` extends `IllegalStateException`: `topic '<t>' is compacted and a
message published to it must carry a key or a subject`.

## Retained and the gap (`runtime`, R12)

```scala
final case class Retained(beginning: Long, end: Long, earliestAt: Option[Instant])     // earliestRetained's answer per partition
final case class PartitionGap(partition: Int, beginning: Long, earliestAt: Option[Instant])
final case class RetentionGap(partitions: Vector[PartitionGap], compacted: Boolean, gone: Boolean, readAt: Instant)
// TopicSourceStatus gains gap: Option[RetentionGap]
```

- `gone` = not compacted and (any `beginning > 0`, or any `earliestAt` after the view's `since`).
- `since` = `ankka_view_versions.recorded_at` for a view (written by `rebuild`; `NULL` for a row
  recorded before this feature, which disables the time rule for it); a consumer has none.
- Reported on subscribe, on rebuild and every `GapInterval` (5 minutes).

`InMemoryBroker` gains `compact(topic)` (keyless publish refused; `compacted` reported) and
`drop(topic, n)` (the oldest `n` entries removed; `beginning` raised by `n`).

## Service status (`controlplane-api`, R13, R14)

```scala
final case class PartitionGapReport(partition: Int, beginning: Long, earliestRetained: Option[Instant])
final case class RetentionGapReport(partitions: Vector[PartitionGapReport], compacted: Boolean, gone: Boolean, readAt: Instant)
// TopicSourceReport gains gap: Option[RetentionGapReport] = None
final case class ServiceWarning(kind: String, component: String, topic: String, message: String)
// ServiceStatus gains warnings: Option[Vector[ServiceWarning]] = None
```

- Merge over instances (`ServiceEndpoint.topicSourcesOf`): partitions by number, first report wins
  per partition; `gone` any; `compacted` all; `readAt` latest.
- A `retention` warning per view topic source whose declared topic keeps a bounded time below the
  threshold and is not compacted; computed on every read.

## Listing row (`controlplane-api`, R1)

```scala
final case class TopicSettingsView(retention: String, retentionSize: String, cleanup: String, tombstoneWindow: String,
  minCompactionLag: String, maxCompactionLag: String, copies: Option[Int], minInSync: Option[Int], defaulted: Vector[String])
// ProjectTopic gains settings: Option[TopicSettingsView] (None until the sweep has filled a pre-feature topic),
//   copiesHeld: Option[Int] (what the broker holds, from the status's replicas), brokerNodes: Option[Int]
```

`copies` absent with `copiesHeld` present reads "copies: as the broker holds (1)"; `copiesHeld` below
the installation's default copies reads "below the installation's default of 3"; `copiesHeld == 1`
reads "a single copy". `replicas` is Strimzi's word and stays on the CRD and the `KafkaTopic` only.
