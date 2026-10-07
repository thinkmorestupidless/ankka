# Contract: what a component states, and the check at start

Held by `features/topics/contracts.feature` (scenarios 3–6), `features/topics/brokers.feature`
and `features/topics/parallelism.feature`; the SDK shapes by the conformance suite.

## Stating a contract, a broker and parallelism

Scala:

```scala
val orders = Contract.fromSchema("order.v1", getClass.getResourceAsStream("/schemas/order.v1.json").readAllBytes())
object Relay extends Consumer.Companion[Relay, Event](
  "relay", ChangeSource.fromTopic("events", eventSerializer, StartFrom.Earliest, TopicOptions(broker = Some("legacy"), parallel = true)))
class Relay extends Consumer[Event, Order]:
  override def produces = Some(Publication("orders", contract = Some(orders)))
```

Python:

```python
ORDERS = Contract.from_file("schemas/order.v1.json", name="order.v1")

@consumer("relay", source=topic("events", start=StartFrom.EARLIEST, broker="legacy", parallel=True),
          produces_to=Publication("orders", contract=ORDERS))
```

TypeScript and Rust follow the same names (`Contract.fromFile`, `Contract::from_file`,
`producesTo`/`produces_to` taking a topic or a `Publication`, `broker`, `parallel`). A view's
topic source takes `contract` and `broker`; `parallel` on a view is accepted and means the same.

## Discovery (protocol 1.14)

`Source.contract`, `Source.broker`, `Source.parallel`, `ConsumerDetail.produces`. A Spec that
declares 1.13 or earlier states none of them. The sidecar accepts both.

## The fingerprint

`sha256:` + hex(SHA-256(JCS(document))). `protocol/fixtures/contracts/fingerprints.json` rows are
`{"name", "schema", "fingerprint"}`; every SDK's tests fingerprint each row's `schema` and compare.

## The check at start

With `ANKKA_PROJECT_DECLARATIONS` set to the project's declarations file, after the components
are known and before any subscribes or publishes:

| Component states | Declaration has | Result |
|---|---|---|
| contract `C` | contract `C` | accepted |
| contract `C` | contract `D` (name or fingerprint differs) | refused |
| none | contract `C` | refused |
| contract `C` | none | accepted (and reported as `unchecked`-free: the project declares nothing) |
| anything | topic not in the file | accepted; the topic is undeclared, as today |
| broker `b` | broker `b` declared | accepted |
| broker `b` | not declared | refused |

Without the variable (a local run, a test), nothing is checked.

The refusal is one line per problem, written to the container's termination message and the log,
and the service exits:

```text
cannot start ankka projections:
 - consumer 'relay' publishes to 'orders' as 'order.v2' (sha256:9c…); project 'shop' declares 'order.v1' (sha256:3f…)
 - view 'orders-by-day' reads 'orders' with no contract; project 'shop' declares 'order.v1' (sha256:3f…)
```

`ankka services get` shows the first line as the service's detail, with lifecycle `Failed`.

## On the wire

A message published to a topic whose publication states a contract carries `ce-type: <name>`;
otherwise `ce-type: message`, as today. Nothing is checked on read.
