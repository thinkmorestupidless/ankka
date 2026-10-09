# Contract: what the runtime refuses, from the broker's own configuration

What a developer's service sees. Held by `features/broker/cleanup-policy.feature` (the keyless
outline), `features/broker/copies.feature` (the in-sync outline) and `KafkaSuite`,
`InMemoryBrokerSuite`, `StartRefusalSuite`.

## A topic's configuration, read from the broker

The runtime reads `cleanup.policy` and `min.insync.replicas` of a topic from the broker (one
`describeConfigs`) the first time it publishes to it, and again when the reading is older than a
minute, off the publishing thread. It never reads them from the project's declarations file or a
platform variable, so a change applied in place reaches a running service within a minute and no
restart. A read that fails keeps the last reading and logs once; a topic the broker does not know is
treated as having no configuration (the publish waits for the topic as today).

## A keyless message to a compacted topic

A record's key is the named key, else the message's subject, else nothing. When the topic's cleanup
policy contains `compact` and the key would be nothing, the publish fails in the service before
anything is sent:

```text
topic 'deltas' is compacted and a message published to it must carry a key or a subject
```

The failure is the publish's: a consumer's change is handed to it again (a topic source does not
commit; an entity source retries with its backoff), and the log names the topic. The same rule holds
in the in-memory broker once a test calls `broker.compact("deltas")`.

A topic declared compacted after the service started refuses keyless messages within a minute. A
client outside ankka that writes a keyless record is refused by the broker itself.

## A producer whose `acks` is below `all`

Kafka's producer default is `acks=all` and the runtime does not change it; a service's own
configuration can (`pekko.kafka.producer.kafka-clients.acks`). At start, after the declarations check,
the runtime reads the producer's effective `acks`; when it is `0` or `1` and any topic the service
publishes to has a minimum in-sync copies above one, the service refuses to start:

```text
cannot start ankka projections:
  - the producer's acks is 1 and topic 'transactions' needs 2 in-sync copies; set acks=all
```

The refusal is written to the termination log and reaches `ankka services get` as the detail; the
pod never becomes ready. A topic the broker does not know yet is not checked.

## Publishing when a broker node is down

With three copies and a minimum of two in sync, a publication to a topic with one node stopped is
acknowledged once two copies hold it; with a minimum of three it is refused by the broker
(`NotEnoughReplicasException`) until the node returns, and the service's log names the topic and the
reason, as any refused publish is logged (`could not publish to topic '{}' ({} on the broker)`).
