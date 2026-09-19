# Contract: `controlplane-api` Wire Changes

**Satisfies**: FR-030, FR-031

One field, plus a validation rule. Both defaulted or additive so nothing existing breaks.

## `ServiceStatus`

```scala
final case class ServiceStatus(
    name: String,
    projectId: String,
    lifecycle: ServiceLifecycle,
    generation: Long,
    image: String,
    readyInstances: Int,
    desiredInstances: Int,
    detail: Option[String] = None,
    confirmed: Boolean = true      // NEW
)
```

`confirmed = false` means one of two things, distinguished by `detail`:

| Cause | `detail` |
|---|---|
| The control plane cannot reach the cluster | `"could not reach the cluster: <message>"` |
| The resource exists but nothing has reported on it (FR-031) | `"no operator has reported on this service"` |

The second matters more than it looks: it is how an operator discovers that the ankka operator is
not installed, is crash-looping, or is watching a different namespace. Without it that situation is
indistinguishable from a slow rollout, which is exactly the silence this whole feature exists to
remove.

Defaulted to `true` so the JSON in `README.md`, existing fixtures and stored rows decode unchanged.

## Project id validation

New shared rule beside `ServiceDescriptor.ValidName`, applied by `ProjectEndpoint` on create:

```
lowercase letter, then lowercase letters / digits / '-', ending alphanumeric
length ≤ 63 - (namespace-prefix length + 1)    // default prefix "ankka" → 57
```

The id becomes part of a namespace name, so a value that cannot be expressed in Kubernetes must be
refused when the project is created, not when its first service fails to deploy. In
`controlplane-api` so the CLI rejects it before the round trip — the reason that module holds
validation rules at all.

**Behaviour change**: `POST /projects/{projectId}` previously accepted any path segment. Existing
ids are not migrated; projection reports a failure for a non-compliant one rather than crashing.
Existing tests use ids like `checkout`, which comply.

## HTTP surface

| Route | Change |
|---|---|
| `GET /services/{projectId}` | response objects gain `confirmed` |
| `GET /services/{projectId}/{name}` | same |
| `PUT /services/{projectId}/{name}` | response gains `confirmed`; request unchanged |
| `POST /projects/{projectId}` | now `400` for an id that is not a valid DNS label |

**No new route.** Status reaches the control plane by watching the custom resource, not over HTTP.
This is the concrete payoff of choosing a resource over a private protocol: there is no second API
to design, authenticate, version or expose.

## CLI

`services list` prints `NAME STATUS INSTANCES GEN IMAGE` and drops `detail` — which is why
`confirmed` is a field rather than detail text. Unconfirmed renders in the STATUS column:

```
NAME  STATUS               INSTANCES  GEN  IMAGE
cart  Ready                1/1        4    registry.example.com/acme/cart:1.4.2
mail  Ready (unconfirmed)  1/1        7    registry.example.com/acme/mail:0.9.1
```

`services get` adds a `confirmed` field; `-o json` carries it verbatim. The CLI still depends only
on `controlplane-api` and gains no dependency — in particular it gains nothing from `crd`, and
cannot see the custom resource at all.
