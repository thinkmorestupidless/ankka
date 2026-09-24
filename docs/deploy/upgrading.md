---
title: Upgrade ankka
description: Move a service to a new ankka version by changing the library version and the descriptor's runtime declaration together, refreshing the local schema, and checking what a deployed instance actually runs.
kind: guide
languages: [scala, python]
related: [deploy/deploy-a-service.md, deploy/run-locally.md, reference/runtime-endpoints.md]
---

# Upgrade ankka

A Scala service upgrades ankka by changing one version in two places: the libraries its build resolves,
and the `runtime` its descriptor declares. The platform checks the declaration against its own version
before it runs anything, so the two must move together. A Python service upgrades its SDK and, when the
SDK speaks a newer protocol, its descriptor's `protocol`.

The platform itself, meaning the operator, control plane, sidecar and CLI, is released as one version from
one tag. Services and the platform are upgraded separately, within the compatibility window described in
[Which versions a platform runs](#which-versions-a-platform-runs).

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

## Schema changes are additive

Within a supported range, ankka's schema only ever gains tables and columns. A running service never loses
a table or column it needs, which is what lets services on the older supported minor version keep
running on a platform that has moved to the newer one.

## Which versions a platform runs

A platform at version `MAJOR.MINOR.PATCH` runs a service whose declared `runtime` has the same major
version and a minor version equal to the platform's or one below it:

| Platform | Runs services declaring |
|---|---|
| `0.3.1` | `0.2.x`, `0.3.x` |
| `0.3.0` | `0.2.x`, `0.3.x` |
| `1.0.0` | `1.0.x` |

A declaration outside the range is reported on `ankka services get` as `Unavailable`, with a detail that
names both versions, and no instance starts. A descriptor with no `runtime` is not checked. The window
means a platform can be upgraded one minor version ahead of its services, and each service then has until
the platform's next minor release to follow.

## Check what an instance runs

Each instance logs its runtime version when it starts. A deployed instance also serves it on its
management port:

```bash
kubectl -n ankka-checkout port-forward deploy/orders 7626:7626
curl localhost:7626/ankka/version
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
