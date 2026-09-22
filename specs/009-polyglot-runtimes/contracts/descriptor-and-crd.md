# Contract: the descriptor, the resource, and what the operator renders

**Feature**: [spec.md](../spec.md) | **Research**: R8 | **Data model**: [data-model.md](../data-model.md)

## The descriptor (`ankka services apply -f`)

```json
{
  "name": "cart",
  "service": {
    "image": "my-cart:1.0.0",
    "hosting": "process",
    "protocol": "1.0",
    "http": true,
    "port": 8080,
    "env": [ { "name": "ANTHROPIC_API_KEY", "valueFrom": { "secretKeyRef": { "name": "models", "key": "anthropic" } } } ]
  }
}
```

| field | values | rule |
|---|---|---|
| `hosting` | `embedded` (default), `process` | anything else: `hosting must be "embedded" or "process"` |
| `protocol` | a `MAJOR.MINOR` version | required when `hosting` is `process` (`protocol must be declared for process hosting`); refused when `hosting` is `embedded` (`protocol is meaningful only for process hosting`); parsed by `Version.parse` |
| `runtime` | unchanged | with `hosting = process` it may still be declared and is checked, but describes nothing the developer built; the CLI warns |
| `http`, `port` | unchanged | describe the **app** container under `process` hosting. `http: false` means the app serves no HTTP and the sidecar's own port is the service's HTTP |
| `env` | unchanged | additionally refused: `ANKKA_PROCESS_PORT`, `ANKKA_PROCESS_ADDRESS`, `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_ADDRESS` — `is set by the platform` |

Validation lives in `controlplane-api` and runs at both ends, as today.

**Compatibility**: `Compatibility.supportsProtocol(platform: Version, declared: Version)` is
`platform.major == declared.major && declared.minor <= platform.minor`. The control plane checks it
where it checks `runtime` today, in `ServiceProjector`, and an unsupported version is
`ClusterView.Refused` → `Unavailable` with both versions in the detail, before any resource is
written. The platform's protocol version is a constant in `controlplane-api`
(`Protocol.version`), bumped with the `.proto` files.

## The resource (`AnkkaService`)

```yaml
spec:
  hosting: process          # new; default "embedded"
  image: my-cart:1.0.0      # the app image
  port: 8080                # the app's port, when it serves HTTP
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
| env | `ANKKA_HTTP_PORT=9000`, the five cluster variables, `ANKKA_PROCESS_ADDRESS=127.0.0.1:9010`, `ANKKA_SIDECAR_PORT=9011`, the descriptor's model variables (`ANTHROPIC_*`, `ANKKA_MODEL_*`) | the descriptor's other variables, `ANKKA_PROCESS_PORT=9010`, `ANKKA_SIDECAR_ADDRESS=127.0.0.1:9011` |
| envFrom | the credential secret | none |
| ports | `http` 9000, `management` 7626, `remoting` 17355 | `app` = `spec.port` when `http` |
| readiness | `httpGet /ready` on `management`, as today | TCP on `app` when `http`; none otherwise |
| resources | the descriptor's `resources` (the sidecar is the JVM) | a fixed small request/limit (`100m`/`128Mi`) until the descriptor can say otherwise |

The Kubernetes Service's `http` port targets `app` when `spec.port` is set and `runtime`'s `http`
otherwise; the `HTTPRoute` for an exposed service is unchanged, pointing at the Service. The
contact-point selector and the `formation=bootstrap` label are on the pod, so bootstrap discovers
the sidecar exactly as it discovers a single container.

The schema-init `initContainer` runs before both, as today.

## Refusals and edge cases

- A resource with `hosting: process` and no sidecar image configured on the operator: the
  reconcile reports `Failed: operator has no sidecar image`; nothing is rendered.
- `hosting` changed on an existing service: the Deployment's pod template changes (a second
  container), so it rolls; the selector does not change, so the rollout is a normal one.
- `kubectl describe asvc` shows `hosting` and, from the Deployment, both containers' readiness.
- `ankka services get` prints `hosting: process` and `protocol: 1.0` after `image`.
