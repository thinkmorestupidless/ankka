---
title: Secrets on the platform
description: Where an installation keeps secrets (each service's database or Google Secret Manager), the secret key, project secrets a descriptor takes by secretKeyRef, the record of every read, and moving to Secret Manager.
kind: guide
related: [build/secrets.md, platform/databases.md, platform/identity.md, reference/control-plane-api.md, reference/cli.md]
---

# Secrets on the platform

The platform keeps two kinds of secret for a deployed service, and they do different jobs.

- A **secret key** is what a service's secret store encrypts the values it keeps with. Each service has
  its own, made by the platform, and only the platform's own program in the service's pod holds it.
- A **project secret** is a named set of entries a member sets for a project, such as a payment
  provider's API key the service needs when it starts. A descriptor's variable takes one entry. The
  control plane writes it to the cluster and can never read it back.

A value the service itself is given while it runs, through its own API, belongs in the service's secret
store instead: see [Secrets a service keeps](../build/secrets.md).

Where both are kept — each service's database and the cluster's Secrets, or Google Secret Manager — is
the installation's to choose, once, and every read of a service secret leaves a record a person can find.

## The secret key

When a service is deployed, the operator makes it a secret key: 32 random bytes, in a Secret named
`<service>-secret-key` in the project's namespace, under the entry `key`. The operator creates it once and
never reads, changes or deletes it; a pass that finds it already made leaves it alone. The service's pod is
given it as `ANKKA_SECRET_KEY`, by reference to that Secret.

Only the platform's own program receives it. For a service written in another language, the key is on
the container the platform runs beside your process, not on your process's container; your process asks
the runtime to keep and read secrets, and never holds the key. A WebAssembly module asking for
`ANKKA_SECRET_KEY` is told it is not set.

The key has no owner reference, as a service's database has none. Deleting a service keeps its key;
deploying a service of the same name again names the same key, and reads the secrets it kept before.

### Supplying your own key

A descriptor that sets `ANKKA_SECRET_KEY` itself — as a value, or from a project secret — gives the
service that key, and the operator makes none. This is the same rule as a descriptor that names its own
database: what the descriptor says, the platform does not provide.

```json title="service.json"
{
  "name": "payments",
  "service": {
    "image": "registry.example.com/acme/payments:2.0.0",
    "env": [
      { "name": "ANKKA_SECRET_KEY", "secretKeyRef": { "name": "payments-key", "key": "key" } }
    ]
  }
}
```

The value is the standard base64 of exactly 32 bytes: `openssl rand -base64 32`. A value of any other
form stops the service starting, naming the variable.

### On your own machine

A service run locally has no operator to make it a key. Set `ANKKA_SECRET_KEY` in its environment, or in
the shell before `docker compose up` for a project made by `ankka init`. Without one the service runs,
and keeping or reading a secret fails naming the variable. The test kits generate a key for each test.

A local database created before the secret store existed has no `ankka_secrets` table, because Postgres
runs its initialisation scripts once: the store's error names the file, `40-secrets-postgres.sql`.
Recreate the volume, or apply that file. A deployed service gets the table when its pods next start.

The first time an operator that makes secret keys reconciles an existing service, the service's pods gain
the variable, so every service rolls once, by the platform's usual replace-one-at-a-time.

## Choosing a backend

Where an installation keeps service secrets and project secrets is one setting, the installation's, on the
`ankka-platform` ConfigMap. A service's code, its descriptor and its calls on the store are the same on
either, and a descriptor cannot choose differently.

| Key | Variable | Values |
|---|---|---|
| `secretBackend` | `ANKKA_SECRET_BACKEND` | `postgres` (the default): each service's own database, and the cluster's Secrets. `secret-manager`: Google Secret Manager. |
| `cloudProvider` | `ANKKA_CLOUD_PROVIDER` | `none` (the default) or `gcp`. `secret-manager` needs `gcp`, or the control plane does not start. |
| `cloudAccount` | `ANKKA_CLOUD_ACCOUNT` | The Google Cloud project the secrets are kept in. Required for `secret-manager`. |
| `cloudLocation` | `ANKKA_CLOUD_LOCATION` | A region to keep secrets in. Empty, Secret Manager replicates them automatically. |
| `secretVersionsKept` | `ANKKA_SECRET_VERSIONS_KEPT` | On `secret-manager`, how many versions of a service secret are kept: `2` unless set. |
| `secretRecordRetention` | `ANKKA_SECRET_RECORD_RETENTION` | How long the record of reads is kept: `365d` unless set. |
| `secretMove` | `ANKKA_SECRET_MOVE` | A phase of a move from `postgres` to `secret-manager`: `copy`, `check` or `remove`. Empty: no move. |

The operator gives each service the settings its runtime reads, on the platform's own container only, and
renders nothing for a setting left at its default. A setting the runtime refuses — a backend it does not
know, `secret-manager` with no account — stops the service starting, naming the variable.

## Secret Manager

On the `secret-manager` backend each service secret is a secret in Google Secret Manager, and each read
reads its newest enabled version, every time, with no cache. A value kept on one instance is read by the
next read on every instance; a value kept again adds a version, and the versions beyond
`secretVersionsKept` are destroyed, oldest first, once the new one can be read. The platform never
disables a service secret's version, so the newest can always be read; a version disabled by hand is
skipped, and the record of that read says so. Removing a service secret deletes it and every version. The
service's database holds nothing of a secret kept here.

Secret Manager limits how many reads a Google Cloud project answers a minute. A service that reads a
credential for every request it makes counts against that limit, and a read refused for quota fails as
unavailable: size the quota for it rather than keep a copy.

A secret's id carries the project, the service and the secret's name, and the name itself is kept on the
secret as the annotation `ankka-name`, where an administrator can read it:

| What | Id |
|---|---|
| The service secret `psp/acme/api-key` of `payments` in `spinvibe` | `s_spinvibe_payments_psp_sacme_sapi-key` |
| The entry `STRIPE_KEY` of the project secret `checkout` in `spinvibe` | `p_spinvibe_checkout__STRIPE_uKEY` |

In a name, `_` is written `_u`, `.` is `_p` and `/` is `_s`, so no two names share an id. A name too long
for an id is kept under its prefix and a digest of the name.

### Secret access on Google Cloud

A service reaches Secret Manager as its own Kubernetes ServiceAccount, through Workload Identity
Federation for GKE: no Google service account, no key file, nothing in the pod's environment. What each
identity may do is written as IAM bindings on the Google Cloud project, by the installation's cloud
provider, conditioned on the id's prefix. A condition names a secret by the project's **number**, not its
id: `resource.name.startsWith("projects/123456789012/secrets/s_spinvibe_payments_")`.

| Identity | May | May not |
|---|---|---|
| A service | create a secret; add versions to, read, list the versions of, destroy versions of and delete secrets under `s_<project>_<service>_`; read under `p_<project>_` | list secrets; touch another service's or another project's |
| The control plane | create a secret; add, list and disable versions under `p_` | read any version |
| The cloud provider | read and seed versions under `p_` | touch a service secret |

Google Cloud cannot limit a create by the secret's name, because the secret does not exist when the
create is authorised, so any service may create a secret under any id. That is harmless: a service that
creates a secret under another service's prefix can neither read it, add to it nor delete it, and the
owning service's next keep adds its version to that secret as to its own. The create is in Google Cloud's
audit log, under the creator's identity.

The operator asks the installation's cloud provider for each service's access: a cloud identity for
the service's ServiceAccount, then the access above for that identity. Until the provider has granted
it, the service is not rolled out, and `ankka services get` says it is waiting on the cloud provider for
access to its secrets; access the provider refuses fails the service, with the provider's reason. An
installation with no cloud provider makes these grants itself, and its services roll out without
waiting.

A service whose access has not been written yet is refused by Google Cloud, and its store says so —
naming the prefix its access must admit — never that the secret does not exist.

The power to write these bindings, `setIamPolicy` on the Google Cloud project, can grant anything to
anyone. The operator does not hold it, and nor does the control plane; the installation's cloud provider
does, and nothing else in the installation needs a Google credential.

### Before an installation uses it

- Workload Identity Federation for GKE on the cluster (`--workload-pool=<project>.svc.id.goog`) and on
  every node pool a service may run on (`--workload-metadata=GKE_METADATA`).
- The Secret Manager API enabled on the Google Cloud project.
- Data Access audit logging on for `secretmanager.googleapis.com`, so Google Cloud records each access.
- The installation's cloud provider installed, holding the roles that write the bindings above.

To verify an installation by hand: mint a token for one service's ServiceAccount and call Secret Manager
with it. Reading that service's own secret succeeds; reading another service's, another project's, and
listing the project's secrets are each `PERMISSION_DENIED`.

## The record of reads

Every read, keep and removal of a service secret leaves a record, on either backend: the secret's name,
the project and service, how the service's program is hosted, the outcome, the time, the trace and the
handler's span, and — where the platform ran the handler itself — the component and its kind. Never the
value: a record has nowhere to put one.

A service writes the record to the control plane, as itself, **before** it uses the value, and a read
whose record is not acknowledged within five seconds fails as unavailable. So no value is used without its
record, and a service's reads of secrets depend on the control plane being reachable. The control plane
keeps the records in a database of its own, apart from its journal and from every service's database: a
service cannot remove the record of its own reads, and restoring its database does not rewind them. A
service may write records only of its own reads.

An owner of the project's organization reads them, newest first:

```console
$ ankka projects secret-reads list -p spinvibe --name psp/acme/api-key --from 2026-09-01T00:00:00Z
AT                    SERVICE   COMPONENT  NAME              OPERATION  OUTCOME  TRACE
2026-10-08T12:00:00Z  payments  charge     psp/acme/api-key  get        read     4bf92f35…
```

A member who is not an owner, and a deploy token, are refused. Records older than `secretRecordRetention`
are removed each day. `ankka installation` shows the backend and the retention beside the installation's
cloud, and whether Google Cloud's own access log is on (`unknown` until the cloud provider reports it). On Secret Manager,
Google Cloud's audit log records each access beside the platform's record.

## Moving an installation to Secret Manager

An installation on `postgres` moves service by service, with no person seeing a value. Each step is a
change to the ConfigMap and a rollout; each service performs the phase when its instances next start, and
an instance is not ready until it has.

1. Set `secretBackend` to `secret-manager` and `secretMove` to `copy`, and roll the services. Each
   instance copies every row of its database that Secret Manager does not already hold, decrypting with
   the service's secret key; a secret Secret Manager already holds is never overwritten. The rows stay.
2. Set `secretMove` to `check`. Each instance compares every name's value in its database with Secret
   Manager's newest, by digest, and reports `equal`, `different` or `missing-in-secret-manager` for each.
3. When every service reports every name equal, set `secretMove` to `remove`. Each instance checks
   again, and deletes its rows only when every name is equal; otherwise it leaves them and names the
   differences. Then clear `secretMove`.

`ankka services get` shows each service's backend, the phase its instances ran, the outcome and every
name's state, once an instance is ready. An instance that cannot reach Secret Manager during a phase is
not ready, changes nothing, says why in its readiness probe's answer and its log, and tries again.

Before a service's removal step, setting `secretBackend` back to `postgres` is a rollback: the service
reads its rows again, and names any secret kept since its copy, which Secret Manager alone holds. After
its removal step the database holds nothing, and a service on `postgres` refuses to start, saying its
secrets live only in Secret Manager — so a switch back stops at that service, named, until the setting is
put back.

The secret key is still given to a service on Secret Manager. Through a move it is what decrypts the
rows; after the removal step it is not read, and `ankka services get` says so.

## Project secrets

A project secret is set through the control plane by a member, or by a machine holding a deploy token,
with no cluster credential:

```console
$ ankka projects secrets set checkout STRIPE_KEY=sk_live_... -p shop
project secret 'checkout' in 'shop' has STRIPE_KEY
```

`KEY=-` reads that entry's value from standard input instead, so the value is in no shell history or
process listing:

```console
$ printf '%s' "$STRIPE_KEY" | ankka projects secrets set checkout STRIPE_KEY=- -p shop
```

A descriptor takes a variable from an entry with `secretKeyRef`, and the kubelet gives the pod the value
when it starts:

```json title="service.json"
{
  "name": "billing",
  "service": {
    "image": "registry.example.com/acme/billing:1.0.0",
    "env": [
      { "name": "STRIPE_KEY", "secretKeyRef": { "name": "checkout", "key": "STRIPE_KEY" } }
    ]
  }
}
```

A project secret also holds the credential of a broker the project declares. That is the one case a
project secret reaches a service as files rather than a variable: the platform mounts the secret, read-only,
at `/var/run/secrets/ankka/brokers/<broker>` on the platform's container of every service in the project,
where its entries (`ca.crt`, `tls.crt` and `tls.key`, or `ca.crt`, `username` and `password`) are the
files the runtime reads to connect. A process-hosted service's own container sees nothing of it. See
[A topic on another broker](../build/topics.md#a-topic-on-another-broker).

### Setting, removing, listing

- **set** adds or replaces the entries it names and keeps every other entry of the secret.
- **unset** removes one entry: `ankka projects secrets unset checkout STRIPE_KEY -p shop`. A secret whose
  last entry is removed is no longer listed; its Secret stays in the namespace, empty, and setting an
  entry on that name again brings it back. An entry that was never set is answered not found, and
  nothing is written. An entry a declared broker needs — `ca.crt`, `tls.crt`, `tls.key`, `username` or
  `password` of the secret a broker names — cannot be removed while the broker names it: the removal is
  refused naming the broker, and the broker is removed first.
- **list** shows each secret's name, its entries, and who last set one — never a value:
  `ankka projects secrets list -p shop`.

The console's project page does the same: it lists each secret's entries, sets an entry, and removes one.

A value set again reaches an instance started afterwards; running instances keep the value they started
with, so `ankka services restart` is how a running service picks up a change. A pod started after an
entry it takes was removed — or that names a project secret that does not exist — does not start, and the
service's status shows the reason the cluster gives.

### What the control plane can do with a value

The control plane writes a project secret to the cluster and records, in its own journal, only that the
secret exists, its entries' names, and who set them. Its grant on Secrets is to create and to patch: it
cannot read a Secret back, list Secrets or delete one, and the API server refuses each. A request the
cluster refuses is answered unavailable and records nothing, so the control plane never names an entry the
cluster does not hold.

### On Secret Manager

On the `secret-manager` backend, setting an entry adds a version of that entry's secret in Secret Manager,
written as the control plane's own identity, which may add versions and may not read one; unsetting it
disables its versions. The control plane's record and the listing are unchanged: names, entries and who
set them, never a value.

A service still takes a variable from an entry by `secretKeyRef`, unchanged: the installation's cloud
provider keeps the project's Secret in the cluster in step with Secret Manager, within a minute of a
change. **The value is therefore also held in the cluster's Secret store**, and a pod's read of it at
start is the kubelet's, which Secret Manager does not record per read; the record of reads covers service
secrets. A service of the project that takes a variable from an entry does not start until the provider
has synced that entry: `ankka services get` says it is waiting on the cloud provider to sync the project
secret, or, when the provider could not, says why. A value set again is synced again, though no name
changed. A service that takes nothing from a project secret never waits on one.

### Names

A project secret's name is a Kubernetes Secret name: lowercase letters, digits, `-` and `.`, beginning and
ending with a letter or digit, at most 253 characters. An entry's name is letters, digits, `.`, `_` and
`-`. A value is not empty and at most 64 KiB.

A name of a form the platform uses for its own Secrets in a project is refused: one beginning `ankka-`, or
ending `-db`, `-cluster-tls`, `-service-tls`, `-database-tls`, `-mount-tls`, `-secret-key`,
`-telemetry` or `-storage`. Those hold a service's database location, its certificates, its secret key,
the credential it sends its telemetry with and its storage credential, and the control plane writes a
project secret by
name without being able to look first — a project secret named `payments-secret-key` would otherwise
replace that service's key and make everything it kept unreadable.

A descriptor's variable whose name is one the platform alone sets, such as `ANKKA_HTTP_PORT`, is refused
whether it has a value or takes one from a project secret.
