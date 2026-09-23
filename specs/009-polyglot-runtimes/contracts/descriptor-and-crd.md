# Contract: the descriptor, the resource, and what the operator renders

**Feature**: [spec.md](../spec.md) | **Research**: R7, R8 | **Data model**: [data-model.md](../data-model.md)

## The descriptor (`ankka services apply -f`)

```json
{
  "name": "cart",
  "service": {
    "image": "my-cart:1.0.0",
    "hosting": "process",
    "protocol": "1.0",
    "env": [ { "name": "ANTHROPIC_API_KEY", "valueFrom": { "secretKeyRef": { "name": "models", "key": "anthropic" } } } ]
  }
}
```

| field | values | rule |
|---|---|---|
| `hosting` | `embedded` (default), `process` | anything else: `hosting must be "embedded" or "process"` |
| `protocol` | a `MAJOR.MINOR` version | required when `hosting` is `process` (`protocol must be declared for process hosting`); refused when `hosting` is `embedded` (`protocol is meaningful only for process hosting`); parsed by `Version.parse` |
| `runtime` | unchanged | with `hosting = process` it may still be declared and is checked, but describes nothing the developer built; the CLI warns |
| `http`, `port` | unchanged | describe the service's HTTP, which under `process` hosting is the **sidecar's** (it serves the routes the process declares). `http: false` means the service serves no HTTP and a `Spec` declaring endpoints is refused by the sidecar at startup |
| `env` | unchanged | additionally refused: `ANKKA_PROCESS_PORT`, `ANKKA_PROCESS_ADDRESS`, `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_ADDRESS` — `is set by the platform` |

Validation lives in `controlplane-api` and runs at both ends, as today.

**Compatibility**: `Compatibility.supportsProtocol(platform: Version, declared: Version)` is
`platform.major == declared.major && declared.minor <= platform.minor`. The control plane checks it
where it checks `runtime` today, in `ServiceProjector`, and an unsupported version is
`ClusterView.Refused` → `Unavailable` with both versions in the detail, before any resource is
written. The platform's protocol version is a constant in `controlplane-api`
(`Protocol.version`), bumped with the `protocol/` directory.

## The resource (`AnkkaService`)

```yaml
spec:
  hosting: process          # new; default "embedded"
  image: my-cart:1.0.0      # the app image
  port: 9000                # the sidecar's HTTP port, as for any service
  env: [...]                # the descriptor's env, unsplit — the operator splits it
```

The resource carries no sidecar image and no protocol version: the sidecar image is the operator's
(`ANKKA_SIDECAR_IMAGE` on its Deployment, default `ankka-sidecar:<its own version>`), and the
protocol version was checked before the resource was written. `status` is unchanged.

## What the operator renders for `hosting: process`

One Deployment, same selector, strategy, `restarts` counter, `preStop` sleep and identity labels
as today, with two containers:

| | `runtime` (sidecar) | `app` |
|---|---|---|
| image | `ANKKA_SIDECAR_IMAGE`, `IfNotPresent` | `spec.image`, `IfNotPresent` |
| env | `ANKKA_HTTP_PORT`, the five cluster variables, `ANKKA_PROCESS_ADDRESS=127.0.0.1:9010`, `ANKKA_SIDECAR_PORT=9011`, the descriptor's model variables (`ANTHROPIC_*`, `ANKKA_MODEL_*`) | the descriptor's other variables, `ANKKA_PROCESS_PORT=9010`, `ANKKA_SIDECAR_ADDRESS=127.0.0.1:9011` |
| envFrom | the credential secret | none |
| ports | `http` (`spec.port`) when `http`, `management` 7626, `remoting` 17355 | none |
| readiness | `httpGet /ready` on `management`, as today — which now folds discovery and the process's reachability | none; the sidecar's readiness is its opinion |
| `preStop` | `sleep 5s`, as today | `sleep 5s`, so the process outlives the sidecar's drain and answers forwarded requests through it |
| resources | the descriptor's `resources` (the sidecar is the JVM) | a fixed small request/limit (`100m`/`128Mi`) until the descriptor can say otherwise |

The Kubernetes Service, the `HTTPRoute` for an exposed service, the contact-point selector and the
`formation=bootstrap` label are unchanged: they all point at the `runtime` container, exactly as
they point at the single container today. The schema-init `initContainer` runs before both.

## Refusals and edge cases

- A resource with `hosting: process` and no sidecar image configured on the operator: the
  reconcile reports `Failed: operator has no sidecar image`; nothing is rendered.
- `hosting` changed on an existing service: the Deployment's pod template changes (a second
  container), so it rolls; the selector does not change, so the rollout is a normal one.
- `kubectl describe asvc` shows `hosting` and, from the Deployment, both containers' state.
- `ankka services get` prints `hosting: process` and `protocol: 1.0` after `image`.
