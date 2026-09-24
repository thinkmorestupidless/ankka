---
title: Databases
description: How the platform provisions a Postgres database for every service with CloudNativePG, why each service must have its own, how data survives deletion, and how to bring your own database instead.
kind: guide
related: [deploy/deploy-a-service.md, platform/install-cloud.md, concepts/consistency.md]
---

# Databases

Every deployed service gets a Postgres database of its own, provisioned by the platform when the service
is first applied. The descriptor says nothing about it. The platform creates the database, generates its
credential, applies the runtime's schema before the service's first instance starts, and hands the
connection details to the service's containers. The database holds the service's journal, snapshots,
durable state, view tables, projection offsets and timers.

The platform uses [CloudNativePG](https://cloudnative-pg.io/), a Kubernetes operator for Postgres.

## What is provisioned

Per project, in the project's namespace `ankka-<project>`:

- **One Postgres cluster**, named `ankka-db`, created with the project's first service. It is a single
  Postgres instance with 1Gi of storage. Every service in the project has a database in it.

Per service:

- **A database and a login role**, both named after the service, owned by that role.
- **A credential Secret**, `<service>-db`, holding a generated password and the connection details as
  `ANKKA_DB_HOST`, `ANKKA_DB_PORT`, `ANKKA_DB_NAME`, `ANKKA_DB_USER` and `ANKKA_DB_PASSWORD`. The
  password is generated once, when the Secret does not exist, and never rotated by the platform.
- **A schema step** in every instance: an init container that applies the runtime's schema before the
  service starts. It is safe to repeat, and it runs under a lock so several instances starting together
  do not race.

The service's container receives the credential Secret's variables, which are the same ones the runtime
reads on a laptop. For a Python service they go to the sidecar, which owns the journal; your process
never sees the database.

`ankka services get` reports what happened on its `database` line:

| Phrase | Meaning |
|---|---|
| `waiting for database` | provisioning is in progress |
| `provisioned` | the platform created the database and it is ready |
| `recovered existing data` | the service was re-applied after deletion and found its old database |
| `supplied` | the descriptor declares its own database, and the platform provisioned none |

## One database per service

Each service must have its own database, and this is a correctness requirement, not a convention. The
runtime's timer table has no service column: each service's timer sweeper deletes rows whose component
id it does not recognise, so two services sharing a database delete each other's timers. View tables and
projection offsets are named from component ids alone and collide in the same way.

The platform enforces the separation. Each service's database revokes Postgres's default `CONNECT`
privilege from `PUBLIC`, so one service's credential cannot connect to another service's database, even
in the same project's Postgres cluster.

## Data is never destroyed by the platform

Deleting a service deletes its instances, never its database. The platform's operator is not permitted to
delete databases, database roles, Postgres clusters or Secrets; that is a restriction the Kubernetes API
server enforces on the operator's own account, not a promise in its code.

Re-applying the descriptor of a deleted service, under the same name in the same project, reconnects it
to its existing database, and `services get` reports `recovered existing data`. A service name is
therefore a handle on its data. To start a service over with an empty database, the database has to be
removed by someone with the rights to do so, outside ankka.

## Provisioning takes a minute

A project's first service waits for its Postgres cluster to be created, which takes from twenty seconds
to a minute or more depending on the cluster. Later services in the same project need only a database in
the existing cluster.

Two provisioning errors are transient and clear on their own, and the platform reports them as
`waiting for database`, not as a failure:

- `role "<service>" does not exist`: the database and its role are created together and reconciled
  independently, and the database can be processed first.
- `secrets "<service>-db" is forbidden`: CloudNativePG adds a newly referenced credential Secret to the
  secrets it may read on its own next pass, twenty to forty seconds later.

An error that persists is reported as it is.

## Bring your own database

A descriptor that declares any `ANKKA_DB_` variable in its `env` is bringing its own database, and the
platform provisions nothing for it. `services get` reports `supplied`. Declare all five, taking the
password from a Secret:

```json title="service.json"
{
  "name": "orders",
  "service": {
    "image": "registry.example.com/acme/orders:1.4.2",
    "env": [
      { "name": "ANKKA_DB_HOST", "value": "orders-db.example.internal" },
      { "name": "ANKKA_DB_PORT", "value": "5432" },
      { "name": "ANKKA_DB_NAME", "value": "orders" },
      { "name": "ANKKA_DB_USER", "value": "orders" },
      { "name": "ANKKA_DB_PASSWORD", "secretKeyRef": { "name": "orders-db", "key": "password" } }
    ]
  }
}
```

The check is by variable name, so a value taken from a Secret counts. The supplied database must already
hold the runtime's schema, from the `ankka/ddl` directory of the `ankka-runtime` artifact at the version
the service runs. `sbt schema` in a service created from the template extracts it.

Use this for what a provisioned single-instance Postgres cannot yet provide: an existing database with
data to keep, or a durability profile such as replicas or managed backups. Isolation between services is
then whatever the database's owner configured. The platform does not verify it, and the rule that no two
services share a database still holds.
