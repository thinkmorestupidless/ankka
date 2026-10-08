# Secrets on the platform

> The secret key the platform makes for each deployed service, and project secrets — values a member sets for a project, with no cluster credential, that a descriptor's variable takes by secretKeyRef.

Source: https://docs.ankka.cloud/platform/secrets/
The platform keeps two kinds of secret for a deployed service, and they do different jobs.

- A **secret key** is what a service's secret store encrypts the values it keeps with. Each service has
  its own, made by the platform, and only the platform's own program in the service's pod holds it.
- A **project secret** is a named set of entries a member sets for a project, such as a payment
  provider's API key the service needs when it starts. A descriptor's variable takes one entry. The
  control plane writes it to the cluster and can never read it back.

A value the service itself is given while it runs, through its own API, belongs in the service's secret
store instead: see [Secrets a service keeps](../build/secrets.md).

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
