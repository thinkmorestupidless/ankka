# Contract: a project's topics — contract, schema and compaction

What a member writes and reads. Held by `features/topics/contracts.feature`,
`features/broker/compaction.feature` and the suites named in the plan.

## Declaring

```text
PUT    /projects/{id}/topics/{name}          {"partitions": 3, "compacted": true, "contract": {"name": "order.v1", "schema": {…}}}
GET    /projects/{id}/topics                 every declared topic: name, partitions, compacted, contract name and fingerprint, phase, detail, and its checks
GET    /projects/{id}/topics/{name}/schema   the schema document as declared (application/json)
DELETE /projects/{id}/topics/{name}          as today; the topic and its schema entry are kept
```

```bash
ankka projects topics set orders --partitions 3 --compacted --contract order.v1 --schema schemas/order.v1.json -p shop
ankka projects topics set orders --partitions 3 -p shop                 # a topic with no contract, as today
ankka projects topics schema get orders -p shop > schemas/order.v1.json # fetch it to build against
ankka projects topics list -p shop
```

`compacted` and `contract` are optional; absent means not compacted and no contract, and a
redeclaration without them clears them. `--schema -` reads the document from stdin.

## Refusals (400, before anything is written)

- the name or partitions, as today; fewer partitions than the topic has (409);
- `contract.name` outside `[a-z0-9][a-z0-9._-]{0,98}[a-z0-9]`;
- `contract.schema` that is not a JSON document, or larger than 65 536 bytes;
- `contract` with a `name` and no `schema`, or the reverse.

## Order of writes

The schema document is written to the project's `ankka-project-schemas` ConfigMap under its
fingerprint; then the declaration is recorded; then the trigger writes `AnkkaProject`. A failure
after the first write leaves an unused key, never a declaration without its schema.

## The listing

```text
TOPIC    PARTITIONS  COMPACTED  CONTRACT            PHASE        CHECKS
orders   3           no         order.v1 sha256:3f… Provisioned  wallet reads: checked; relay publishes: mismatch (order.v2); intake reads: unchecked
```

A check is `checked` (an instance started after the declaration states the declared contract),
`mismatch` (it states another, or none), or `unchecked` (every instance started before the
declaration). JSON carries `checks: [{service, component, direction, stated, state}]`.
