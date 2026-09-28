# Contract: the descriptor's `wasm` hosting and what the operator renders

## The descriptor

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart-rs:1.0.0", "hosting": "wasm", "protocol": "1.0" } }
```

| rule | message |
|---|---|
| `hosting` is one of three | `hosting must be "embedded", "process" or "wasm", not "<value>"` |
| `protocol` required with `wasm` | `protocol must be declared for wasm hosting` (the process message, generalised: `protocol must be declared for process or wasm hosting`) |
| `protocol` meaningless with `embedded` | unchanged |
| reserved variables (`ANKKA_CLUSTER_*`, `POD_IP`, `ANKKA_HTTP_PORT`, `ANKKA_WASM_MODULE`, `ANKKA_WASM_*`) | refused naming the variable, as today, with the two new names added to the list |
| the runtime's image cannot be named | unchanged: the sidecar image is the operator's setting |
| ports | a wasm descriptor declares no port; `"http": false` is refused: the runtime serves HTTP |

`Compatibility` applies unchanged: the declared `protocol` against the platform's, same major,
minor no higher.

The CRD's `spec.hosting` enum becomes `[embedded, process, wasm]`; `CrdSchemaSuite` proves it and
`AnkkaServiceSpec.hosting` stays a `String`.

## The pod

For `hosting: wasm` the operator renders, on the Deployment every service gets:

```yaml
initContainers:
  - name: <service>-module
    image: <the descriptor's image>
    imagePullPolicy: IfNotPresent
    volumeMounts: [{ name: ankka-module, mountPath: /ankka/module }]
    resources: { requests: { cpu: 10m, memory: 16Mi }, limits: { memory: 64Mi } }
containers:
  - name: <service>                      # the node: exactly a Scala service's container from the sidecar image
    image: <the operator's sidecar image>
    env:
      - { name: ANKKA_WASM_MODULE, value: /ankka/module/service.wasm }
      - <every cluster variable, the database credential, ANKKA_BASE_DOMAIN, …, as for embedded>
      - <every descriptor variable, unsplit>
    ports: [http 9000, management 7626, remoting 17355]   # by name, as today
    readinessProbe: httpGet /ready on management
    lifecycle: { preStop: { sleep: { seconds: 5 } } }
    volumeMounts: [{ name: ankka-module, mountPath: /ankka/module, readOnly: true }]
volumes:
  - name: ankka-module
    emptyDir: { sizeLimit: 64Mi }
imagePullSecrets: [<the project's registry Secret, when configured>]
```

- No second app container. `RenderingSuite` pins `containers.size == 1` and the init container's
  image and mount.
- The environment is not split: with one process there is nothing to split at render time. The
  host's `config` import is what withholds the reserved names from the module.
- The `SchemaInit` init container, when the database is provisioned, precedes the module's; order is
  not significant.
- The Deployment's selector, the generation annotation, the `restarts` counter, `RollingUpdate`
  with `maxSurge: 1, maxUnavailable: 0`, the formation label and everything else are exactly as for
  every service.

## The module image contract

A module image, run with `/ankka/module` mounted read-write, writes exactly one file,
`/ankka/module/service.wasm`, and exits 0. The template's Dockerfile:

```dockerfile
FROM busybox:1.37
COPY target/wasm32-unknown-unknown/release/<crate>.wasm /service.wasm
CMD ["cp", "/service.wasm", "/ankka/module/service.wasm"]
```

An image that exits non-zero, or writes nothing, fails the pod's init; the operator reports the
init container's state as it reports any waiting container, and `services get` shows it. The
runtime container never starts, so a missing module is never "discovery failed".

## What the runtime container reports

| condition | effect |
|---|---|
| module missing at the path, unparseable, uncompilable | the runtime exits 1 naming the path and reason; the pod restarts; `Failed` at the rollout deadline with the container's last log line |
| wrong ABI prefix, missing export, foreign import | the same, naming the export or import and the versions |
| discovery refused | the same, every problem listed |
| loaded and discovered | `/ready` true once the cluster has formed and HTTP is bound |

## Local

The template's compose file bind-mounts the built module and sets the same variable:

```yaml
services:
  runtime:
    image: ghcr.io/thinkmorestupidless/ankka-sidecar:<version>
    environment: { ANKKA_WASM_MODULE: /module/service.wasm, ANKKA_HTTP_PORT: "9000", ANKKA_DB_HOST: postgres, … }
    volumes: ["./target/wasm32-unknown-unknown/release/<crate>.wasm:/module/service.wasm:ro"]
    ports: ["127.0.0.1:9000:9000"]
```

No process port, no `host.docker.internal`, no callback port: the runtime container has nothing to
reach back to.
