# Contract: what the operator renders, and what the runtime is told

Held by the operator's rendering suites (offline) and by the broker's k3s suite.

## The operator's settings

| Variable | Meaning |
|---|---|
| `ANKKA_BROKER_BOOTSTRAP` | where services connect: `<cluster>-kafka-bootstrap.<namespace>.svc:9093` |
| `ANKKA_BROKER_NAMESPACE` | the namespace `KafkaTopic` and `KafkaUser` resources are written to |
| `ANKKA_BROKER_CLUSTER` | the `strimzi.io/cluster` label those resources carry |

All three, or none. None: the installation has no broker and nothing below is rendered. One or two:
the operator refuses to start, naming the missing ones.

## Per service, when the installation has a broker

For a service that is not web-hosted and whose `provisionBroker` is true.

**In the broker's namespace**, no owner reference, labelled managed-by ankka, never removed:

```yaml
apiVersion: kafka.strimzi.io/v1
kind: KafkaUser
metadata:
  name: money.wallet                      # <project>.<service>
  labels: { strimzi.io/cluster: ankka }
spec:
  authentication: { type: tls-external }
  authorization:
    type: simple
    acls:
      - resource: { type: topic, name: "money.", patternType: prefix }
        operations: [Read, Write, Describe]
      - resource: { type: group, name: "ankka.money.wallet.", patternType: prefix }
        operations: [Read]
---
apiVersion: kafka.strimzi.io/v1
kind: KafkaTopic                          # one per declared topic
metadata:
  name: money.transactions                # <project>.<name>
  labels: { strimzi.io/cluster: ankka }
spec:
  partitions: 12                          # never fewer than the topic has
```

**In the project's namespace**:

- the service `Certificate` gains `commonName: <project>.<service>`; nothing else of it changes;
- the platform's container, and for process hosting the process's too, gains

  | Variable | Value |
  |---|---|
  | `ANKKA_KAFKA_BOOTSTRAP_SERVERS` | the operator's `ANKKA_BROKER_BOOTSTRAP` |
  | `ANKKA_KAFKA_TLS_DIRECTORY` | `/var/run/secrets/ankka/service` |
  | `ANKKA_KAFKA_TOPIC_PREFIX` | `<project>.` |

No volume, mount, Secret or `Certificate` is added.

## What is rendered for whom

| Service | User | Topics | Variables | Common name | Status `broker` |
|---|---|---|---|---|---|
| web-hosted | no | — (refused) | no | no | absent |
| names its own broker | no | — (refused) | its own, to both programs | no | `Supplied` |
| no broker in the installation, no topic | no | no | no | no | absent |
| no broker in the installation, a topic | no | no | no | no | `Failed`, "the installation has no broker" |
| a broker, no topic declared | yes | no | yes | yes | `Waiting`, then `Provisioned` |
| a broker, topics declared | yes | yes | yes | yes | `Waiting`, then `Provisioned` or `Recovered` |

A service of an installation with no broker, declaring no topic, renders byte for byte what it did
before this feature; `RenderingGoldenSuite` and `RenderingUnchangedSuite` hold that with no
fixture rewritten.

## The operator's grant

A `Role` and `RoleBinding` in the broker's namespace, part of the broker component: `kafka.strimzi.io`
`kafkatopics` and `kafkausers`, verbs `get`, `list`, `watch`, `create`, `patch`. No `delete`. The
k3s suite mints a token for the operator's ServiceAccount and shows the API server refuses a delete.

## What the runtime does with the variables

| Variable | Absent | Present |
|---|---|---|
| `ANKKA_KAFKA_BOOTSTRAP_SERVERS` | no broker: a component that needs one is refused at startup, as today | connect there, on first use |
| `ANKKA_KAFKA_TLS_DIRECTORY` | plaintext, as today | TLS, presenting the certificate in that directory, re-read as it is renewed, with the broker's name checked |
| `ANKKA_KAFKA_TOPIC_PREFIX` | topics by their declared names, as today | each topic handed to the broker as prefix + declared name |

A descriptor may give `ANKKA_KAFKA_BOOTSTRAP_SERVERS` and no other is documented for it; the other
two are written by the operator.
