# Upgrade ankka

> Move a service to a new ankka version by changing the library version and the descriptor's runtime declaration together, refreshing the local schema, and checking what a deployed instance actually runs.

Source: https://docs.ankka.cloud/deploy/upgrading/
A Scala service upgrades ankka by changing one version in two places: the libraries its build resolves,
and the `runtime` its descriptor declares. The platform checks the declaration against its own version
before it runs anything, so the two must move together. A Python service upgrades its SDK and, when the
SDK speaks a newer protocol, its descriptor's `protocol`.

The platform itself, meaning the operator, control plane, sidecar and CLI, is released as one version from
one tag. Services and the platform are upgraded separately, within the compatibility window described in
[Which versions a platform runs](#which-versions-a-platform-runs).

## Upgrade the operator before or with the control plane

Upgrade the operator before the control plane, or with it, as a release's manifests do. A control plane
accepts every hosting it knows and projects it into the service's resource, and an operator that predates
a hosting refuses to render it rather than guess: such a service reports the operator's problem,
`unknown hosting "<value>"`, until the operator is upgraded. An operator older still renders an unknown
hosting as an embedded service, which for a web-hosted service would be a pod with a database and no
proxy.

## Change the version in both places

In a service created from the template:

```scala
// build.sbt
val ankkaVersion = "0.3.0"
```

```json title="service.json"
{
  "name": "orders",
  "service": {
    "image": "orders:latest",
    "runtime": "0.3.0"
  }
}
```

`ankkaVersion` decides which runtime is compiled into the image. `runtime` is what the platform checks.
The platform trusts the declaration and does not inspect the image, so a descriptor that declares one
version while the image carries another is not caught at deploy time. Keep them equal.

## Refresh the local schema

The runtime's database schema ships inside the `ankka-runtime` library. After changing `ankkaVersion`,
extract it again and recreate the local database, because Postgres applies its initialisation scripts
only to an empty volume:

```bash
sbt schema
docker compose down -v && docker compose up -d
```

This deletes local data. A deployed service needs none of this: the platform applies the schema of the
runtime the platform ships to each service's database every time an instance starts, and that is safe to
repeat.

## Raise a view's version after the upgrade

A view that reads entities may declare a version, and raising it rebuilds the view. While it is rebuilt,
an instance declaring the lower version is told to stop writing to the view, which only an instance of a
release that knows versions of views that read entities can be. Raise a view's version in a deploy after
the one that brought every instance of the service to such a release: an older instance left running beside
the rebuild would go on writing rows, and a row it wrote after the rebuild had read past its entity would
stay until that entity changed again.

## Schema changes are additive

Within a supported range, ankka's schema only ever gains tables and columns. A running service never loses
a table or column it needs, which is what lets services on the older supported minor version keep
running on a platform that has moved to the newer one.

## Names a project secret can no longer take

A project secret whose name ends `-storage` or `-mount-tls` can no longer be set, since those are the names
of a service's storage credential and its mount certificate. One that already exists is kept and listed,
and its entries can be removed; a descriptor that takes a variable from one is refused at its next apply.
Rename it and point the descriptor at the new name.

## Consumer groups are named for the service

Each view or consumer that reads a topic reads under a Kafka consumer group named for the service it
belongs to: `ankka.<project>.<service>.view.<component>` for a deployed service, and the same with
`local` in the project's place for a service run locally that states its name. Releases before this one
named a group for its component alone, `ankka-view-<component>`, so two services on one broker with a
component of the same name shared a group and each received part of the topic.

An upgraded service's groups are therefore new, and hold no offsets: each topic source reads from its
start position, the earliest message the broker holds unless it says otherwise. A view applies its handler
again to rows it already holds. The old groups are left on the broker with their offsets, and the broker
expires them as it does any idle group. See [Broker topics](../build/topics.md#consumer-groups).

**A consumer over a topic must now declare where it starts**, since starting at the earliest message would
put everything the broker holds through its action again. A Scala service declares it when it moves to
this release. A service on an SDK that predates start positions cannot declare one; the platform starts
such a consumer at the earliest message, as it always did, and logs a warning naming it, until its SDK is
upgraded.

A process-hosted service's runtime is the platform's sidecar, so upgrading the platform restarts its
consumers under their new groups before its developer has deployed anything. For a consumer whose actions
must not be repeated, cut over at a moment of your choosing:

1. Pause the service: `ankka services pause <service>`.
2. Upgrade the platform.
3. Upgrade the service's SDK, declare each topic consumer's start position as a time — the moment it was
   paused — and deploy it.
4. Resume it: `ankka services resume <service>`.

The consumer then reads from the moment it stopped. Declaring latest instead would skip whatever was
published while it was paused.

A view's first version bump should follow the runtime upgrade, not ride with it: an instance still on an
older runtime cannot see a view's version, and keeps writing until it stops.

`local` is now a reserved project id, because a project of that name would give its services the same
groups as services run locally. An installation that has a project called `local` can no longer deploy to
it.

## Which versions a platform runs

A platform at version `MAJOR.MINOR.PATCH` runs a service whose declared `runtime` has the same major
version and a minor version equal to the platform's or one below it:

| Platform | Runs services declaring |
|---|---|
| `0.3.1` | `0.2.x`, `0.3.x` |
| `0.3.0` | `0.2.x`, `0.3.x` |
| `1.0.0` | `1.0.x` |

From platform version 0.8.0 a runtime must also be 0.8.0 or later, because an older one cannot speak the
mutual TLS every workload now uses: the "one minor below" rule does not reach across that line.

A declaration outside the range is reported on `ankka services get` as `Unavailable`, with a detail that
names both versions, and no instance starts. A descriptor with no `runtime` is not checked. The window
means a platform can be upgraded one minor version ahead of its services, and each service then has until
the platform's next minor release to follow.

## Installing the broker

An installation that adds the `broker` component to a platform already running services gives every
service with components the installation's broker on the operator's next pass. Each is told where the
broker is, and the certificate it holds is reissued with its name, so each service's instances are
replaced once, as a rolling update that refuses no request. A service whose descriptor names a broker of
its own is left as it is. See [The installation's broker](../platform/broker.md).

## The move to mutual TLS

The first deployment of a service on a runtime that speaks mutual TLS, when its running instances do not,
is not a rolling update. An instance that speaks TLS and one that does not cannot join each other, so the
platform stops every old instance, waits until none is left, and then starts the new ones. The service is
unavailable for as long as that takes — typically well under a minute — once, and `ankka services
history` records it as an update with the detail `moving to mutual TLS: instances restart together,
once`. Every deployment after it rolls as before.

Nothing is asked of the deployer: the platform recognises the old instances and does it. A provisioned
database moves to certificate authentication in the same deployment; see
[Databases](../platform/databases.md#a-database-provisioned-before-certificates).

The control plane is itself an ankka service and makes the same move. `deploy-local.sh` stops its old
instances before applying the new manifest; an installation applied by other means should do the same,
once:

```bash
kubectl -n ankka-controlplane delete deployment ankka-controlplane --wait=true
kubectl apply -k <your overlay> --server-side
```

## Check what an instance runs

Each instance logs its runtime version when it starts. A deployed instance also serves it on its
management port, which answers only a caller holding the service's own certificate — so ask from inside
one of its pods:

```bash
kubectl -n ankka-checkout exec deploy/orders -- curl -s --insecure \
  --cert /var/run/secrets/ankka/cluster/tls.crt --key /var/run/secrets/ankka/cluster/tls.key \
  https://localhost:7626/ankka/version
```

Compare that with the declaration when the two might differ. See
[Runtime endpoints](../reference/runtime-endpoints.md).

## A Python service

A Python service carries no runtime: the platform injects the sidecar at the platform's own version. What
the descriptor declares instead is the sidecar protocol the SDK speaks:

```json title="service.json"
{
  "name": "cart",
  "service": {
    "image": "my-cart:1.1.0",
    "hosting": "process",
    "protocol": "1.0"
  }
}
```

The platform accepts a protocol with its own major version and a minor version no later than its own. A
new minor version of the protocol only ever adds, so an SDK speaking `1.0` keeps working on a platform
speaking `1.1`. An SDK that needs a newer protocol minor needs a platform that speaks it. After upgrading
the SDK in `pyproject.toml`, set `protocol` to the version the new SDK speaks, rebuild the image and
apply the descriptor.
