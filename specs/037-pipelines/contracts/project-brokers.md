# Contract: a project's declared brokers

Held by `features/topics/brokers.feature`.

```text
PUT    /projects/{id}/brokers/{name}   {"bootstrap": "kafka.legacy:9094", "shape": "sasl", "secret": "legacy-credential"}
DELETE /projects/{id}/brokers/{name}
GET    /projects/{id}/brokers          name, bootstrap, shape, secret, declaredAt
```

```bash
ankka projects secrets set legacy-credential ca.crt=- username=ingest password=- -p shop
ankka projects brokers set legacy --bootstrap kafka.legacy:9094 --shape sasl --secret legacy-credential -p shop
ankka projects brokers unset legacy -p shop
ankka projects brokers list -p shop
```

## The secret's shapes

| Shape | Entries the project secret must hold | Optional |
|---|---|---|
| `certificate` | `ca.crt`, `tls.crt`, `tls.key` (PEM) | |
| `sasl` | `ca.crt`, `username`, `password` | `mechanism`: `SCRAM-SHA-512` (default), `SCRAM-SHA-256`, `PLAIN` |

Every shape is over TLS; the broker's authority is `ca.crt`. There is no plaintext shape.

## Refusals (400)

- a name outside the topic name rule; an empty or malformed `bootstrap`;
- a `shape` other than the two; a `secret` that is not a project secret the project has set, or
  a reserved form;
- a secret whose recorded entries lack one the shape needs, naming it:
  `project secret 'legacy-credential' lacks 'ca.crt', which shape 'sasl' needs`.

## What a service sees

Every service of the project has, on its platform container only:

```text
ANKKA_TOPIC_BROKER_LEGACY_BOOTSTRAP_SERVERS=kafka.legacy:9094
ANKKA_TOPIC_BROKER_LEGACY_SHAPE=sasl
ANKKA_TOPIC_BROKER_LEGACY_SECRET_DIRECTORY=/var/run/secrets/ankka/brokers/legacy   # the project secret, mounted read-only
```

The name is upper-cased with `-` as `_`. A process container has none of these and no mount. A
component that names a broker the project has not declared is refused at start:
`consumer 'intake' reads 'events' from broker 'legacy', which project 'shop' does not declare`.
A topic on a declared broker has no prefix, is never created by the platform, and is not an
undeclared topic. Its consumer group is the service's usual one.
