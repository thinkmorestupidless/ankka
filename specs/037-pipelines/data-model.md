# Data Model: Pipelines Are Services

## Contract

A name and the fingerprint of a schema document. In `core`:

```scala
final case class Contract(name: String, fingerprint: String)
object Contract:
  def fromSchema(name: String, document: Array[Byte]): Either[String, Contract]   // JCS → SHA-256 → "sha256:<hex>"
  val NameRule = "[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]"                               // the same letters as a topic name, plus "_"
```

- `fingerprint` is `sha256:` and 64 lowercase hex digits.
- Two contracts are the same when both fields are equal.
- The SDKs: `Contract.from_file(path, name=)` (Python), `Contract.fromFile(path, name)` (TypeScript),
  `Contract::from_file(path, name)` (Rust); the Scala SDK reads a classpath resource or a path.

## Declared topic (control plane, `Project.topics`)

```scala
final case class DeclaredTopic(
  partitions: Int,
  declaredAt: Option[Instant] = None,
  compacted: Boolean = false,          // new
  contract: Option[Contract] = None)   // new; the document lives in the project's schema ConfigMap under its fingerprint
```

Rules (`ProjectTopics.problems`): the name rule and `1 ≤ partitions ≤ 1000` as today; a contract
name under `Contract.NameRule`; a schema document that is JSON and at most 65 536 bytes
(`MaxSchemaBytes`). Transitions: partitions never fewer (`Conflict`); `compacted` and `contract`
change freely; a changed contract is a new fingerprint. Events: `ProjectTopicDeclared(name,
partitions, actor, at, compacted = false, contract = None)` (defaults keep old journals decoding;
a wire-form pin is added); `ProjectTopicRemoved` unchanged.

## Declared broker (control plane, `Project.brokers`)

```scala
enum BrokerCredentialShape { case Certificate, Sasl }
final case class DeclaredBroker(bootstrap: String, shape: BrokerCredentialShape, secretName: String, declaredAt: Option[Instant])
```

Rules: `name` under the topic name rule; `bootstrap` a non-empty `host:port[,host:port]`;
`secretName` a project secret the project has recorded, not a reserved form, whose recorded entries
include the shape's keys — `ca.crt`, `tls.crt`, `tls.key` for `Certificate`; `ca.crt`, `username`,
`password` (and optionally `mechanism`: `SCRAM-SHA-512` default, `SCRAM-SHA-256`, `PLAIN`) for `Sasl`.
Events: `ProjectBrokerDeclared(name, bootstrap, shape, secretName, actor, at)`,
`ProjectBrokerRemoved(name, actor, at)`. Removing a broker a running service uses stops nothing;
the service's next start refuses the name.

## `AnkkaProject` (CRD)

```scala
final case class AnkkaProjectSpec(projectId: String = "", topics: List[ProjectTopicEntry] = Nil, brokers: List[ProjectBrokerEntry] = Nil)
final case class ProjectTopicEntry(name: String = "", partitions: Int = 1, declaredAt: String = "",
  compacted: Boolean = false, contractName: Option[String] = None, contractFingerprint: Option[String] = None)
final case class ProjectBrokerEntry(name: String = "", bootstrap: String = "", shape: String = "", secretName: String = "", declaredAt: String = "")
final case class ProjectTopicStatus(name: String, phase: String, partitions: Option[Int], detail: Option[String], compacted: Option[Boolean] = None)
```

Flat fields, so `CrdSchemaSuite` checks each entry type; a new assertion covers `spec.brokers.items`.

## The declarations file (`ankka-project` ConfigMap, key `topics.json`)

Rendered by the operator from the spec; read by the runtime at start from `ANKKA_PROJECT_DECLARATIONS`.

```json
{"project": "shop",
 "topics": [{"name": "orders", "partitions": 3, "compacted": false, "contract": {"name": "order.v1", "fingerprint": "sha256:…"}}],
 "brokers": [{"name": "legacy", "bootstrap": "kafka.legacy:9094", "shape": "sasl"}]}
```

## The schema ConfigMap (`ankka-project-schemas`)

Written by the control plane; one key per fingerprint, the value the document as received. Never
removed (a redeclared contract adds a key); at most 64 KiB per entry; read by `GET …/schema`.

## Component declarations (SDK and remote)

```scala
final case class TopicOptions(contract: Option[Contract] = None, broker: Option[String] = None, parallel: Boolean = false)
final case class ChangeSource.Topic[Src](topic: String, decoder: Serializer[Src], startFrom: Option[StartFrom] = None, options: TopicOptions = TopicOptions())
final case class Publication(topic: String, contract: Option[Contract] = None, broker: Option[String] = None)
// Consumer: def produces: Option[Publication] = produceTo.map(Publication(_))
enum RemoteSource { case Component(kind, id); case Topic(name: String, startFrom: Option[StartFrom] = None, options: TopicOptions = TopicOptions()) }
final case class RemoteConsumerDescriptor(componentId, source, producesTo: Option[String], startDeclarable: Boolean = true, version: Option[Int] = None, produces: Option[Publication] = None)
enum DeclaredSource { case Events; case State; case Topic(name: String, contract: Option[Contract], broker: Option[String]) }
```

Static rules (`TopicSourceRules`): a `parallel` option on a component source is refused; a
`broker` on a topic view's source is allowed; `produces` and `produceTo` naming different topics
is refused. Start-time rules (`ProjectionRuntime`, with the declarations file): a stated contract
must equal the declared one in name and fingerprint; a topic with a declared contract must be
stated with one; a broker name must be declared and configured.

## Protocol 1.14 (`discovery.proto`)

```proto
message Contract { string name = 1; string fingerprint = 2; }
message Publication { string topic = 1; optional Contract contract = 2; optional string broker = 3; }
message Source { oneof source { ComponentRef component = 1; string topic = 2; } optional StartFrom start_from = 3;
                 optional Contract contract = 4; optional string broker = 5; optional bool parallel = 6; }
message ConsumerDetail { Source source = 1; optional string produces_to = 2; optional uint32 version = 3; optional Publication produces = 4; }
```

`produces` wins over `produces_to` when both are present and name the same topic; different
topics are a discovery problem.

## Topic subscription and status (runtime)

```scala
final case class TopicSubscription(topic: String, group: String, startFrom: StartFrom, parallel: Boolean = false)
final case class TopicSourceStatus(kind, componentId, topic, group, startFrom, version, recordedVersion: Option[Int], behind: Boolean,
  broker: Option[String] = None, contract: Option[String] = None, lag: Option[Long] = None, failing: Option[String] = None)
```

`lag` is the sum over partitions of end offset minus committed offset, `None` until the first poll
or when the broker does not answer; `failing` is the reason of the change being redelivered, cleared
when a change succeeds.

## Service status (control plane API)

```scala
final case class TopicSourceReport(kind, component, topic, group, start, version, recordedVersion, behind, broker, contract, lag, failing)
final case class TopicCheck(topic: String, component: String, direction: "reads" | "publishes", declared: Option[String], stated: Option[String], state: "checked" | "unchecked" | "mismatch")
// ServiceStatus gains: topicSources: Option[Vector[TopicSourceReport]], topicChecks: Option[Vector[TopicCheck]]
```

Both `None` when no instance answered, and on listing rows. `unchecked` is an instance started
before the topic's `declaredAt`.

## `AnkkaService` additions

```scala
processCpuMillis: Int = 100, processMemoryMiB: Int = 128, database: String = "platform"   // "platform" | "supplied" | "none"
```

`provisionDatabase` stays and is `false` for `supplied` and `none`; `Provisioning.decide` maps
`none` to `NotNeeded`. The descriptor: `resources.process: {cpu: "1000m", memory: "1Gi"}` (Kubernetes
quantities, `cpu` ≤ 8, `memory` ≤ 16Gi) and `database: "none"` (absent means today's rule).

## The sink's settings

| Variable | Meaning | Default |
|---|---|---|
| `ANKKA_GRAPH_SINK_TOPIC` | the delta topic, by its declared name | required |
| `ANKKA_GRAPH_SINK_VERSION` | the consumer's version; a higher one re-reads from the start | `1` |
| `ANKKA_GRAPH_SINK_PARALLEL` | read partitions in parallel | `true` |
| `NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, `NEO4J_DATABASE` | the store | required; `neo4j` |
| `ANKKA_GRAPH_SINK_TRANSACTION_TIMEOUT` | per delta | `30s` |

The component (`Neo4jSink(settings)`) takes the same values as a case class. Store shape: nodes
`:Element {id, _version, _deleted}` plus the delta's labels and properties; relationships
`[:<type> {id, _version, _deleted}]`; a placeholder node at `_version = -1`; versions only rise.
