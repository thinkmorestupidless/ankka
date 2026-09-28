# Databases

> How the platform provisions a Postgres database for every service with CloudNativePG, why each service must have its own, how data survives deletion, and how to bring your own database instead.

Source: https://docs.ankka.cloud/platform/databases/
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
- **A database authority**: a root certificate and the cert-manager `Issuer` `ankka-database` over it,
  from which each service's client certificate is issued. The cluster accepts it as its client
  authority, and its `pg_hba` admits the members of the role `ankka_tls` by certificate and nothing else.

Per service:

- **A database and a login role**, both named after the service, owned by that role. The role has no
  password and is a member of `ankka_tls`.
- **A client certificate**, `<service>-database`, whose common name is the role. Postgres's certificate
  authentication matches the common name to the role, so the certificate logs in as this service and no
  other. It is valid for a day and renewed every eight hours.
- **A credential Secret**, `<service>-db`, holding where the database is — `ANKKA_DB_HOST`,
  `ANKKA_DB_PORT`, `ANKKA_DB_NAME` and `ANKKA_DB_USER` — and nothing secret, because there is no password.
- **A schema step** in every instance: an init container that applies the runtime's schema before the
  service starts. It is safe to repeat, and it runs under a lock so several instances starting together
  do not race.

The service's container receives the credential Secret's variables, which are the same ones the runtime
reads on a laptop, and four more that tell it to connect over TLS with its certificate:

| Variable | Value |
|---|---|
| `ANKKA_DB_SSL_MODE` | `verify-full`: the server's certificate and name are verified before anything is sent |
| `ANKKA_DB_SSL_ROOT_CERT` | the cluster's server authority, `/var/run/secrets/ankka/database-ca/ca.crt` |
| `ANKKA_DB_SSL_CERT` and `ANKKA_DB_SSL_KEY` | the service's client certificate, under `/var/run/secrets/ankka/database/` |

The key is read when a connection is opened, so a renewed certificate reaches the next connection the pool
opens without a restart. For a Python service all of this goes to the sidecar, which owns the journal; your
process never sees the database.

## A database accepts only its project

A network policy admits connections to a project's Postgres only from that project's ankka workloads, the
database's own instances and the database operator. A workload of another project cannot open a connection
at all, and a workload of this project that is not the service cannot log in as it: it holds no certificate
with the service's name.

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
privilege from `PUBLIC`, so one service's certificate cannot connect to another service's database, even
in the same project's Postgres cluster.

## Data is never destroyed by the platform

Deleting a service deletes its instances, never its database. The platform's operator is not permitted to
delete databases, database roles, Postgres clusters or Secrets; that is a restriction the Kubernetes API
server enforces on the operator's own account, not a promise in its code.

Re-applying the descriptor of a deleted service, under the same name in the same project, reconnects it
to its existing database, and `services get` reports `recovered existing data`. A service name is
therefore a handle on its data. To start a service over with an empty database, the database has to be
removed by someone with the rights to do so, outside ankka.

## A database provisioned before certificates

A service provisioned when the platform still generated passwords moves to certificate authentication on
its next deployment, on its own: the operator issues its certificate, re-applies its role without a
password and adds it to `ankka_tls`. Its old credential Secret is left in place, since nothing the platform
does deletes a Secret, and the password in it no longer logs in. Services not yet redeployed keep logging
in by password in the meantime, so a project moves one service at a time.

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

To connect to a supplied database over TLS, declare `ANKKA_DB_SSL_MODE` (`require`, `verify-ca` or
`verify-full`) and `ANKKA_DB_SSL_ROOT_CERT`, the path of the authority to verify the server with; add
`ANKKA_DB_SSL_CERT` and `ANKKA_DB_SSL_KEY` to authenticate with a client certificate instead of a password.
The files are yours to mount. With no `ANKKA_DB_SSL_MODE` the connection is plain, and its password
crosses the network unencrypted.

Use this for what a provisioned single-instance Postgres cannot yet provide: an existing database with
data to keep, or a durability profile such as replicas or managed backups. Isolation between services is
then whatever the database's owner configured. The platform does not verify it, and the rule that no two
services share a database still holds.
