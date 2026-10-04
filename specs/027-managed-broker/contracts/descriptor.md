# Contract: the descriptor, its refusals and the status

What a member writes and reads. Held by `features/broker/descriptor.feature`,
`features/broker/topics.feature` and the unit suites named in the plan.

## The descriptor

```json
{
  "name": "wallet",
  "service": {
    "image": "registry.example.com/money/wallet:1.0.0",
    "topics": [
      { "name": "transactions", "partitions": 12 },
      { "name": "wallet-events", "partitions": 3 }
    ]
  }
}
```

`topics` is optional and empty by default. A service declares the topics it publishes to; a service
that only reads a topic of its project declares nothing. A component names a topic exactly as the
descriptor does (`transactions`); the broker holds it as `<project>.<name>` (`money.transactions`).

Naming a broker of one's own is unchanged: any variable starting `ANKKA_KAFKA_` in `env`. The
platform then makes nothing for the service on the installation's broker.

## Refusals from the descriptor's own rules

Printed identically by the CLI before sending and by the control plane on apply. Every problem is
reported at once.

| Problem | Message |
|---|---|
| a name of the wrong shape | `topic '<name>': a name is lower-case letters, digits, "-" and ".", starting and ending with a letter or digit, at most 100 characters` |
| partitions out of range | `topic '<name>': partitions <n> is outside the range 1-1000` |
| the same name twice | `topic '<name>' is declared more than once` |
| web hosting | `topics is meaningful only for a service with components; a web-hosted service declares none` |
| topics beside a broker variable | `topics are declared for the installation's broker, and env var '<name>' names another; remove one` |

## Refusals that need the project's other services

Made by the control plane's endpoint on apply, answered as a conflict, before any resource is
written.

| Problem | Message |
|---|---|
| another service of the project declares the topic with other partitions | `topic '<name>' is declared by '<service>' with <n> partitions; a topic has one count` |
| fewer partitions than this service last applied | `topic '<name>' has <n> partitions and cannot have fewer; <m> was asked` |

## What a member reads

`GET /services/{project}/{name}` and every listing row gain two fields, both omitted when empty:

```json
{ "broker": "provisioned", "topics": ["money.transactions", "money.wallet-events"] }
```

| `broker` | When |
|---|---|
| absent | web-hosted; or the installation has no broker and the service declares no topic; or nothing reported yet |
| `supplied` | the descriptor names its own broker |
| `waiting for broker` | the broker has not yet made the user or a declared topic |
| `provisioned` | the user and every declared topic are ready |
| `recovered existing topics` | as `provisioned`, and they were there before this service was applied |
| `broker provisioning failed` | a problem that will not clear by waiting; the service's `detail` says which |

`ankka services get` prints them after `database`:

```text
broker      provisioned
topics      money.transactions
            money.wallet-events
```

The service's lifecycle (`Ready`, `Failed`, ...) is not changed by the broker's phase: a service
whose topics are waiting or failed is still deployed, and its detail carries the broker's reason.
