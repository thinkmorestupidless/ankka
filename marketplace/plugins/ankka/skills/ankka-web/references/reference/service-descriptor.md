# Service descriptor

> Every field of the JSON service descriptor that `ankka services apply` takes, with its type, default and validation rules, and the environment variables the platform reserves.

Source: https://docs.ankka.cloud/reference/service-descriptor/
A service descriptor is the JSON document that states a service's desired state: which image to run,
with what environment, at what size and how many instances. `ankka services apply -f service.json`
sends it to the control plane, which records it and reconciles the cluster towards it. Nothing about
databases, cluster formation or routing appears in it, because the platform provisions those.

A descriptor is validated by the CLI before it is sent and again by the control plane, with the same
rules. Every problem is reported at once.

## A minimal descriptor

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart:1.4.2" } }
```

This runs one small instance of the image, serving HTTP on port 9000, with a database provisioned for
it. It is private until `ankka services expose cart`.

## A complete descriptor

```json title="service.json"
{
  "name": "cart",
  "service": {
    "image": "registry.example.com/acme/cart:1.4.2",
    "runtime": "0.2.0",
    "env": [
      { "name": "LOG_LEVEL", "value": "info" },
      { "name": "ANTHROPIC_API_KEY", "secretKeyRef": { "name": "cart-secrets", "key": "anthropic-api-key" } }
    ],
    "labels": { "team": "checkout" },
    "annotations": { "example.com/owner": "checkout-team" },
    "http": true,
    "port": 9000,
    "resources": {
      "instanceType": "medium",
      "autoscaling": { "minInstances": 3, "maxInstances": 10, "targetCpuPercent": 80 }
    }
  }
}
```

A Scala service that serves gRPC beside HTTP declares it, on the default gRPC port:

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart:1.5.0", "grpc": true } }
```

A service written in Python declares process hosting and the sidecar protocol its SDK speaks:

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart-py:1.0.0", "hosting": "process", "protocol": "1.0" } }
```

A service written in Rust is built to a WebAssembly module, and declares wasm hosting:

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart-module:1.0.0", "hosting": "wasm", "protocol": "1.1" } }
```

## Top level

| Field | Type | Required | Meaning |
|---|---|---|---|
| `name` | string | yes | The service's name within its project. |
| `service` | object | yes | The service's specification, described in [the `service` object](#the-service-object). |

`name` must be a DNS label, because it becomes the name of Kubernetes objects: lowercase letters,
digits and `-`, starting with a letter, ending with a letter or digit, at most 63 characters. An empty
name is refused with `service name must not be empty`; any other invalid name with
`service name '<name>' is invalid: lowercase letters, digits and '-', starting with a letter`.

The project is not part of the descriptor. It comes from `--project`, `ANKKA_PROJECT` or the saved
settings, so one descriptor can be applied to several projects.

## The service object

| Field | Type | Default | Meaning |
|---|---|---|---|
| `image` | string | required | The container image to run. |
| `runtime` | string | none | The ankka version the image was built against, `MAJOR.MINOR.PATCH`. |
| `hosting` | string | `"embedded"` | `embedded` for a Scala service; `process` for a service in another language, run beside the ankka sidecar; `wasm` for a service built to a WebAssembly module, loaded into the ankka runtime; `web` for any program that serves HTTP, run beside the platform's proxy. |
| `protocol` | string | none | The protocol version the image's SDK speaks, `MAJOR.MINOR`. Required with `process` or `wasm` hosting. |
| `env` | array of [environment variables](#environment-variables) | `[]` | Environment for the service's containers. |
| `labels` | object of strings | `{}` | Extra labels on the service's Kubernetes objects. |
| `annotations` | object of strings | `{}` | Extra annotations on the service's Kubernetes objects. |
| `http` | boolean | `true` | Whether the service serves HTTP at all. |
| `port` | integer | `9000` | The port the service listens on. Ignored when `http` is `false`. |
| `grpc` | boolean | `false` | Whether the service serves gRPC. Embedded hosting only. |
| `grpcPort` | integer | `9090` | The port the service serves gRPC on. Ignored when `grpc` is `false`. |
| `resources` | object | small, one instance | Size and instance count, described in [resources](#resources). |
| `mounts` | array of `{ "path", "service" }` | `[]` | With `web` hosting only: paths answered by another service of the project. See [web hosting](#web-hosting). |
| `callers` | array of strings | `[]` | With `web` hosting only: the services admitted beside the internet. |
| `processPort` | integer | `8080` | With `web` hosting only: the port the process listens on, told to it as `PORT`. |
| `provisionObjectStorage` | boolean | `false` | Whether the platform gives the service a bucket and a credential that reaches it. See [object storage](#object-storage). |
| `exposeObjectStorage` | boolean | `false` | Whether that bucket is reachable from the internet, for URLs the service signs. Only with `provisionObjectStorage`. |

### image

Any image reference the cluster can pull. A local cluster loaded with `kind load docker-image` uses the
image straight from the node, because the platform renders every workload with
`imagePullPolicy: IfNotPresent`. An empty image is refused with `service image must not be empty`.

### runtime

The ankka version the image was built against, as `MAJOR.MINOR.PATCH`, optionally followed by a
`+build` or `-SNAPSHOT` suffix. The service template writes it from the same value as the build's ankka
version. When declared, the control plane compares it with its own version before anything starts: the
major must be equal, and the minor equal to the platform's or one below. A declaration outside that
range makes the service `Unavailable`, with both versions in the detail. An undeclared runtime is not
checked. A malformed one is refused with
`runtime version '<text>' is not MAJOR.MINOR.PATCH (optionally followed by +build or -SNAPSHOT)`.

The declaration is trusted: the platform does not compare it with the version the running image
reports.

### hosting and protocol

`hosting` is `embedded`, `process`, `wasm` or `web`; anything else is refused with
`hosting must be "embedded", "process", "wasm" or "web", not "<value>"`.

- `embedded`: the image is an ankka service, and the JVM in it is a cluster node.
- `process`: the image is a process in another language. The platform runs ankka's sidecar in the same
  pod, and the two talk over loopback. The descriptor never names the sidecar's image or version; those
  belong to the platform.
- `wasm`: the image carries a WebAssembly module. The platform runs the image once, as an init
  container, to copy the module into a volume the pod shares, and then runs its own runtime image with
  the module loaded into it — one container. The image's contract is exactly that: run with
  `/ankka/module` mounted, write `service.wasm` there, and exit 0; any other exit fails the pod's start,
  and the service reports it. A wasm service always serves HTTP, since the runtime serves the module's
  routes, so `"http": false` is refused with `a wasm service's runtime serves HTTP`.
- `web`: the image is any program that serves HTTP, such as a user interface. The platform runs its
  proxy beside it in the same pod; the program needs no certificate and no ankka library, and is given
  no database. Its own rules are in [web hosting](#web-hosting), and what the program is told is in
  [the web hosting reference](web-hosting.md).

`protocol` is the protocol version, `MAJOR.MINOR`. It is required with `process` or `wasm` hosting
(`protocol must be declared for process hosting`, and the same for `wasm`) and refused with `embedded`
hosting (`protocol is meaningful only for process or wasm hosting`). The platform accepts a declaration with the same
major as its own and a minor no later than its own. It currently speaks protocol `1.4`.

### http and port

`port` is the port the workload listens on, from 1 to 65535. From it the platform renders the
container port, sets `ANKKA_HTTP_PORT` so the runtime binds there, and creates a Kubernetes Service
named after the service. A port outside the range is refused with
`service port <n> is outside the range 1-65535`, whether or not `http` is `true`.

Set `"http": false` for a service that serves no HTTP. The platform then renders no port and no
Service, and does not wait for a port to open before reporting the service `Ready`. Omitting `port` is
not the same thing: an omitted port means 9000.

### grpc and grpcPort

Set `"grpc": true` for a service that serves [gRPC endpoints](../build/grpc-endpoints.md). The platform
then renders a container port named `grpc`, sets `ANKKA_GRPC_PORT` so the runtime binds there, adds a
`grpc` port to the service's Kubernetes Service, adds a headless Service named `<name>-grpc-peers` that
other services balance their calls across, and admits workloads of the installation to the port. The
service is not `Ready` until it can answer gRPC calls. A descriptor that says nothing about gRPC serves
none, and nothing about gRPC is rendered for it.

`grpcPort` is the port it serves gRPC on, `9090` unless set. A service may serve gRPC and no HTTP. These
are refused when the descriptor is applied, each naming the reason:

| The descriptor | The refusal |
|---|---|
| `grpcPort` outside 1 to 65535, whether or not `grpc` is `true` | `service grpcPort <n> is outside the range 1-65535` |
| `grpc` and `http`, with `grpcPort` equal to `port` | `grpcPort <n> is also the service port; gRPC and HTTP are served on different ports` |
| `grpc` with `process` or `wasm` hosting | `only an embedded service serves gRPC; remove "grpc" or use embedded hosting` |
| `grpc`, and a name longer than 52 characters | `service name '<name>' is <n> characters; a service that serves gRPC has a name of at most 52` |
| `grpc`, and a declared `runtime` older than the first that serves gRPC | `runtime <version> does not serve gRPC; it is served from <version>` |

A service whose descriptor declares gRPC and that registers no `GrpcServer` refuses to start, and the
platform reports it `Failed` with that reason.

### labels and annotations

Added to the service's Deployment, pods and Service. The platform's own identity labels are applied
after yours, so a label of yours with the same key as one of the platform's is overwritten.

## Web hosting

A web-hosted service's descriptor says `"hosting": "web"`, and may name its mounts, the services it
admits and its process's port:

```json
{
  "name": "web",
  "service": {
    "image": "registry.example.com/acme/shop-web:1.0.0",
    "hosting": "web",
    "processPort": 3000,
    "mounts": [
      { "path": "/api/cart", "service": "cart" },
      { "path": "/api/orders", "service": "orders" }
    ],
    "callers": ["orders", "billing/invoices"]
  }
}
```

- `mounts`: each `path` is answered by `service`, a service of the same project, which receives the
  request with the path removed. A path is whole segments: `/api/cart` matches `/api/cart` and
  `/api/cart/carts/c1`, never `/api/cartoons`.
- `callers`: `"<service>"` for a service of this project, `"<project>/<service>"` for another
  project's, `"*"` for every service of this project. The internet and the service itself are always
  admitted, and are not written.
- `processPort`: the port the process listens on, from 1 to 65535, told to it as `PORT`. The service's
  own `port` is where the proxy listens.

The rules for a web-hosted service, with the messages the CLI and control plane print. Every problem is
reported at once.

| Problem | Message |
|---|---|
| `"http": false` | `a web-hosted service's proxy serves HTTP; remove "http": false` |
| `protocol` declared | `protocol is meaningful only for process or wasm hosting` |
| `runtime` declared | `runtime is meaningful only for a service built on ankka; a web-hosted service declares none` |
| `PORT` or `ANKKA_SERVICES_URL` in `env` | `env var '<name>' is set by the platform and cannot be declared` |
| a variable starting `ANKKA_DB_` | `env var '<name>' supplies a database, and a web-hosted service has none` |
| `processPort` out of range | `processPort <n> is outside the range 1-65535` |
| `processPort` equal to `port` | `processPort <n> is the service's own port; the process and the proxy cannot both listen on it` |
| `processPort` 7626, 7627, 7628, 7630 or 17355 | `processPort <n> is used by the platform` |
| `port` 7627 or 7630 | `service port <n> is used by the platform's proxy` |
| a mount path with no leading `/` | `mount '<path>': a path starts with "/"` |
| a mount at `/` | `mount '/': a mount cannot be every path; the process serves what no mount does` |
| a malformed mount path | `mount '<path>': a path is whole segments of letters, digits, "-", ".", "_" and "~", with no trailing "/"` |
| the same path twice | `mount '<path>' is declared more than once` |
| one path inside another | `mount '<inner>' is inside mount '<outer>'` |
| a mount's service not a name | `mount '<path>': '<service>' is not a service name` |
| a mount of the service itself | `mount '<path>': a web-hosted service cannot mount itself` |
| a caller entry of no known shape | `caller '<entry>' is not "<service>", "<project>/<service>" or "*"` |
| a caller entry twice | `caller '<entry>' is declared more than once` |
| `mounts`, `callers` or `processPort` without web hosting | `<field> is meaningful only for web hosting` |

Whether a mount's service exists, serves HTTP or is paused is not a rule of the descriptor, because the
other service may be applied later: `ankka services get` shows what is behind each mount, and a request
under a mount with nothing behind it is answered `503`.

## Environment variables

Each entry in `env` has a `name` and exactly one of `value` or `secretKeyRef`.

| Field | Type | Meaning |
|---|---|---|
| `name` | string | The variable's name. |
| `value` | string | A literal value. |
| `secretKeyRef` | object | A value read from a Kubernetes Secret in the service's namespace: `{ "name": "<secret>", "key": "<key>" }`. |

The rules, with the messages the CLI and control plane print:

| Problem | Message |
|---|---|
| An empty name | `env var name must not be empty` |
| Both `value` and `secretKeyRef` | `env var '<name>' sets both value and secretKeyRef` |
| Neither | `env var '<name>' sets neither value nor secretKeyRef` |
| `ANKKA_HTTP_PORT` | `env var 'ANKKA_HTTP_PORT' conflicts with the service port; declare the port instead` |
| A variable the platform sets | `env var '<name>' is set by the platform and cannot be declared` |
| A variable from a Secret the platform issues, in any hosting | `env var '<name>': secret '<secret>' is issued by the platform and cannot be read by a service` |

A Secret the platform issues is one whose name ends `-service-tls`, `-mount-tls`, `-cluster-tls` or
`-database-tls`, or is the project's database cluster's own: `ankka-db`, or any name starting
`ankka-db-`. They hold certificates and their keys, which identify a service to others; a variable
taken from one would hand a process an identity that is the platform's to hold.

### Reserved variables

The platform sets these on every workload, and a descriptor that sets one is refused. Two sources for
one fact is how an address ends up pointing at a port nothing listens on.

| Variable | Set because |
|---|---|
| `ANKKA_HTTP_PORT` | The runtime binds where Kubernetes expects it. Use `port`. |
| `ANKKA_GRPC_PORT` | The runtime serves gRPC where Kubernetes expects it. Use `grpcPort`. |
| `ANKKA_CLUSTER_MODE` | Selects Kubernetes cluster formation. |
| `POD_IP` | The address a node advertises to its peers. |
| `ANKKA_CLUSTER_SERVICE` | The Kubernetes Service peers are discovered through. |
| `ANKKA_CLUSTER_POD_SELECTOR` | The label selector that identifies this service's pods. |
| `ANKKA_CLUSTER_CONTACT_POINTS` | How many peers must be found before a new cluster forms. |
| `ANKKA_PROCESS_PORT`, `ANKKA_PROCESS_ADDRESS` | Where the sidecar finds a process-hosted service. |
| `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_ADDRESS`, `ANKKA_SIDECAR_BIND` | Where a process-hosted service finds its sidecar. |
| `ANKKA_WASM_MODULE`, `ANKKA_WASM_INSTANCES`, `ANKKA_WASM_MAX_MEMORY_PAGES` | Where the runtime finds a wasm service's module, and how it sizes the instances that run it. |
| `PORT`, `ANKKA_SERVICES_URL` | With web hosting only: where the process listens, and where it calls services. |
| `ANKKA_OTLP_ENDPOINT`, `ANKKA_OTLP_HEADERS` | Where the installation's telemetry goes and what is sent with it: the installation's to say, once. See [Telemetry](../operate/telemetry.md). |

### Supplying your own database

The platform provisions a database for every service and passes its credentials as `ANKKA_DB_HOST`,
`ANKKA_DB_PORT`, `ANKKA_DB_NAME`, `ANKKA_DB_USER` and `ANKKA_DB_PASSWORD`. A descriptor whose `env`
declares any variable whose name starts with `ANKKA_DB_` is bringing its own database instead: nothing is
provisioned, and `ankka services get` reports the database as `supplied`. The check is on the variable's
name, so a value from a `secretKeyRef` counts.

### Object storage

`provisionObjectStorage` gives the service a bucket of its own and five variables any S3 client needs:
`ANKKA_S3_ENDPOINT`, `ANKKA_S3_REGION`, `ANKKA_S3_BUCKET`, `ANKKA_S3_ACCESS_KEY` and `ANKKA_S3_SECRET_KEY`,
on the developer's own program in every hosting. `exposeObjectStorage` adds `ANKKA_S3_PUBLIC_ENDPOINT`, the
address URLs for a browser are signed against. A descriptor whose `env` declares any variable whose name
starts with `ANKKA_S3_` has an object store of its own instead: nothing is provisioned, and `ankka services
get` reports `supplied`. See [Object storage](../platform/object-storage.md).

| The descriptor | Refused with |
|---|---|
| sets `provisionObjectStorage` and declares `ANKKA_S3_X` | `provisionObjectStorage cannot be combined with env var 'ANKKA_S3_X', which supplies an object store of the service's own` |
| sets `exposeObjectStorage` without `provisionObjectStorage` | `exposeObjectStorage needs provisionObjectStorage: only a bucket the platform made can be reached from outside the cluster` |
| asks for a bucket whose name, `<project>.<service>`, would be over 63 characters | `bucket name '<name>' is N characters, over the 63 character limit for a bucket's name; a shorter service name or project id is the only fix` |
| takes a variable from a Secret named `<service>-storage` | `env var '<name>': secret '<secret>' is issued by the platform and cannot be read by a service` |

```json title="service.json"
{
  "name": "reports",
  "service": {
    "image": "registry.example.com/acme/reports:1.0.0",
    "provisionObjectStorage": true,
    "exposeObjectStorage": true
  }
}
```

### Variables in a process-hosted service

With `"hosting": "process"` the pod has two containers, and the platform splits `env` between them by
name:

| Prefix | Goes to | Why |
|---|---|---|
| `ANTHROPIC_` | the sidecar | The sidecar runs the agent loop and calls the model. |
| `ANKKA_MODEL_` | the sidecar | Model configuration, including a scripted model for tests. |
| `ANKKA_DB_` | the sidecar | The sidecar owns the journal; the process never sees the database. |
| anything else | the process | Your own configuration. |

### Variables in a wasm-hosted service

With `"hosting": "wasm"` the pod has one container, so every variable in `env` is in the runtime's
environment. The module reads them through the runtime, which answers a name that starts `ANTHROPIC_`,
`ANKKA_MODEL_` or `ANKKA_DB_` — or is one of the platform's own — as unset. A model key supplied in the
descriptor therefore reaches the runtime, which runs the agent loop, and never the module.

## Resources

| Field | Type | Default | Meaning |
|---|---|---|---|
| `resources.instanceType` | string | `"small"` | The size of each instance. |
| `resources.autoscaling.minInstances` | integer | `1` | The number of instances to run. |
| `resources.autoscaling.maxInstances` | integer | `10` | Validated and stored; not acted on. |
| `resources.autoscaling.targetCpuPercent` | integer | `80` | Validated and stored; not acted on. |
| `resources.process.cpu` | string | `"100m"` | The process container's CPU, as a Kubernetes quantity, for a process-hosted service. |
| `resources.process.memory` | string | `"128Mi"` | The process container's memory, as a Kubernetes quantity. |

### Instance types

Requests equal limits: an instance is given exactly its size.

| `instanceType` | CPU | Memory |
|---|---|---|
| `small` | 500m | 512Mi |
| `medium` | 1000m | 1024Mi |
| `large` | 2000m | 2048Mi |

Any other value is refused with `unknown instanceType '<value>'; one of small, medium, large`.

### The process container

For a process-hosted service the instance type sizes the sidecar, which runs the runtime; the developer's
own container is sized by `resources.process`, requests equal to limits, and is small unless the
descriptor says otherwise:

```json
{ "resources": { "process": { "cpu": "1000m", "memory": "1Gi" } } }
```

`cpu` is millicores (`500m`) or CPUs (`0.5`, `1`), at most `8`; `memory` is a binary quantity (`512Mi`,
`1Gi`), at most `16Gi`. Absent, the container is given 100m and 128Mi.

| Problem | Message |
|---|---|
| `cpu` that does not parse | `process cpu '<value>': …` |
| `cpu` above 8 | `process cpu '<value>' is more than 8` |
| `memory` without a binary unit | `process memory '<value>': needs a binary unit: Ki, Mi, Gi or Ti` |
| `memory` above 16Gi | `process memory '<value>' is more than 16Gi` |

### Instance counts

`minInstances` is honoured as a fixed count; there is no autoscaler. The instances form one cluster,
so use an odd number: with two, a network partition leaves neither side a majority and both are
stopped. Changing the count adds or removes pods without restarting the existing ones.

| Problem | Message |
|---|---|
| `minInstances` below 1 | `minInstances must be at least 1` |
| `maxInstances` below `minInstances` | `maxInstances (<max>) is below minInstances (<min>)` |
| `targetCpuPercent` outside 1 to 100 | `targetCpuPercent must be between 1 and 100, was <n>` |

## Database

| Field | Type | Default | Meaning |
|---|---|---|---|
| `database` | string, optional | absent | `"none"` for a service with no database at all. |

A service has the platform's database unless its `env` supplies one through `ANKKA_DB_*` variables, and
neither needs saying. `"database": "none"` declares a service that has none: one made of consumers,
endpoints and agents, such as a stage of a streaming pipeline. Nothing is provisioned for it, no
credential or certificate reaches it, and its runtime is told so with `ANKKA_DATABASE=none`: an entity, a
view, a workflow or a timed action registered in such a service refuses the start, naming itself —
`this service declares no database: - view 'orders-by-day' needs a database, and this service declares
none (ANKKA_DATABASE=none)` — and the timer scheduler refuses every timer. Any other value is refused
with `database '<value>' is not a choice; leave it out, or say "none" for a service with no database`,
and `"none"` beside an `ANKKA_DB_*` variable with `database "none" and an ANKKA_DB_ variable: a service
with no database supplies none`.

## Fields you will not find

- **A database's location.** One is provisioned per service, except a web-hosted one, which has none,
  and one that declares `"database": "none"`. See [Databases](../platform/databases.md).
- **A bucket's name or address.** A bucket is named from the project and the service, and reached at the
  store's hostname; neither is chosen. See [Object storage](../platform/object-storage.md).
- **A route, a rewrite or a header rule.** A web-hosted service's mounts pass whole paths to a service of
  its project; the platform has no other routing.
- **Ports beyond one, or protocols other than HTTP.** A service has one HTTP port.
- **A hostname.** An exposed service's hostname is derived by the platform, and exposure is a command,
  `ankka services expose`, not a field. See [Expose a service](../deploy/expose.md).
- **Paused.** Pausing is a command, `ankka services pause`, so re-applying a descriptor never resumes a
  paused service.
- **YAML.** The descriptor is JSON only.
