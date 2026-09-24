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

A service written in Python declares process hosting and the sidecar protocol its SDK speaks:

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart-py:1.0.0", "hosting": "process", "protocol": "1.0" } }
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
| `hosting` | string | `"embedded"` | `embedded` for a Scala service; `process` for a service in another language, run beside the ankka sidecar. |
| `protocol` | string | none | The sidecar protocol version the image's SDK speaks, `MAJOR.MINOR`. Required with `process` hosting. |
| `env` | array of [environment variables](#environment-variables) | `[]` | Environment for the service's containers. |
| `labels` | object of strings | `{}` | Extra labels on the service's Kubernetes objects. |
| `annotations` | object of strings | `{}` | Extra annotations on the service's Kubernetes objects. |
| `http` | boolean | `true` | Whether the service serves HTTP at all. |
| `port` | integer | `9000` | The port the service listens on. Ignored when `http` is `false`. |
| `resources` | object | small, one instance | Size and instance count, described in [resources](#resources). |

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

`hosting` is `embedded` or `process`; anything else is refused with
`hosting must be "embedded" or "process", not "<value>"`.

- `embedded`: the image is an ankka service, and the JVM in it is a cluster node.
- `process`: the image is a process in another language. The platform runs ankka's sidecar in the same
  pod, and the two talk over loopback. The descriptor never names the sidecar's image or version; those
  belong to the platform.

`protocol` is the sidecar protocol version, `MAJOR.MINOR`. It is required with `process` hosting
(`protocol must be declared for process hosting`) and refused with `embedded` hosting
(`protocol is meaningful only for process hosting`). The platform accepts a declaration with the same
major as its own and a minor no later than its own. It currently speaks protocol `1.0`.

### http and port

`port` is the port the workload listens on, from 1 to 65535. From it the platform renders the
container port, sets `ANKKA_HTTP_PORT` so the runtime binds there, and creates a Kubernetes Service
named after the service. A port outside the range is refused with
`service port <n> is outside the range 1-65535`, whether or not `http` is `true`.

Set `"http": false` for a service that serves no HTTP. The platform then renders no port and no
Service, and does not wait for a port to open before reporting the service `Ready`. Omitting `port` is
not the same thing: an omitted port means 9000.

### labels and annotations

Added to the service's Deployment, pods and Service. The platform's own identity labels are applied
after yours, so a label of yours with the same key as one of the platform's is overwritten.

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

### Reserved variables

The platform sets these on every workload, and a descriptor that sets one is refused. Two sources for
one fact is how an address ends up pointing at a port nothing listens on.

| Variable | Set because |
|---|---|
| `ANKKA_HTTP_PORT` | The runtime binds where Kubernetes expects it. Use `port`. |
| `ANKKA_CLUSTER_MODE` | Selects Kubernetes cluster formation. |
| `POD_IP` | The address a node advertises to its peers. |
| `ANKKA_CLUSTER_SERVICE` | The Kubernetes Service peers are discovered through. |
| `ANKKA_CLUSTER_POD_SELECTOR` | The label selector that identifies this service's pods. |
| `ANKKA_CLUSTER_CONTACT_POINTS` | How many peers must be found before a new cluster forms. |
| `ANKKA_PROCESS_PORT`, `ANKKA_PROCESS_ADDRESS` | Where the sidecar finds a process-hosted service. |
| `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_ADDRESS`, `ANKKA_SIDECAR_BIND` | Where a process-hosted service finds its sidecar. |

### Supplying your own database

The platform provisions a database for every service and passes its credentials as `ANKKA_DB_HOST`,
`ANKKA_DB_PORT`, `ANKKA_DB_NAME`, `ANKKA_DB_USER` and `ANKKA_DB_PASSWORD`. A descriptor whose `env`
declares any variable whose name starts with `ANKKA_DB_` is bringing its own database instead: nothing is
provisioned, and `ankka services get` reports the database as `supplied`. The check is on the variable's
name, so a value from a `secretKeyRef` counts.

### Variables in a process-hosted service

With `"hosting": "process"` the pod has two containers, and the platform splits `env` between them by
name:

| Prefix | Goes to | Why |
|---|---|---|
| `ANTHROPIC_` | the sidecar | The sidecar runs the agent loop and calls the model. |
| `ANKKA_MODEL_` | the sidecar | Model configuration, including a scripted model for tests. |
| `ANKKA_DB_` | the sidecar | The sidecar owns the journal; the process never sees the database. |
| anything else | the process | Your own configuration. |

## Resources

| Field | Type | Default | Meaning |
|---|---|---|---|
| `resources.instanceType` | string | `"small"` | The size of each instance. |
| `resources.autoscaling.minInstances` | integer | `1` | The number of instances to run. |
| `resources.autoscaling.maxInstances` | integer | `10` | Validated and stored; not acted on. |
| `resources.autoscaling.targetCpuPercent` | integer | `80` | Validated and stored; not acted on. |

### Instance types

Requests equal limits: an instance is given exactly its size.

| `instanceType` | CPU | Memory |
|---|---|---|
| `small` | 500m | 512Mi |
| `medium` | 1000m | 1024Mi |
| `large` | 2000m | 2048Mi |

Any other value is refused with `unknown instanceType '<value>'; one of small, medium, large`.

### Instance counts

`minInstances` is honoured as a fixed count; there is no autoscaler. The instances form one cluster,
so use an odd number: with two, a network partition leaves neither side a majority and both are
stopped. Changing the count adds or removes pods without restarting the existing ones.

| Problem | Message |
|---|---|
| `minInstances` below 1 | `minInstances must be at least 1` |
| `maxInstances` below `minInstances` | `maxInstances (<max>) is below minInstances (<min>)` |
| `targetCpuPercent` outside 1 to 100 | `targetCpuPercent must be between 1 and 100, was <n>` |

## Fields you will not find

- **A database.** One is provisioned per service. See [Databases](../platform/databases.md).
- **Ports beyond one, or protocols other than HTTP.** A service has one HTTP port.
- **A hostname.** An exposed service's hostname is derived by the platform, and exposure is a command,
  `ankka services expose`, not a field. See [Expose a service](../deploy/expose.md).
- **Paused.** Pausing is a command, `ankka services pause`, so re-applying a descriptor never resumes a
  paused service.
- **YAML.** The descriptor is JSON only.
