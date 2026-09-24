# Deploy a service

> Write a service descriptor, apply it with the ankka CLI, and follow the service from UpdateInProgress to Ready, including environment variables, secrets, version declarations and Python services.

Source: https://docs.ankka.cloud/deploy/deploy-a-service/
A service is deployed by applying a descriptor: a small JSON document naming the service and its image.
`ankka services apply -f service.json` records it as the service's desired state, and the platform then
provisions a database, starts the instances and reports the service `Ready` once every instance has
joined the service's cluster and bound its port. The descriptor says nothing about databases, ports,
peers or namespaces, because the platform decides all of them.

Deploying needs a project to deploy into and a login. See
[Organizations, projects and members](../platform/organizations.md) and
[Identity and machine accounts](../platform/identity.md).

## The smallest descriptor

```json title="service.json"
{
  "name": "orders",
  "service": {
    "image": "orders:latest",
    "runtime": "0.2.0"
  }
}
```

`name` becomes the name of the service's Kubernetes objects, so it follows DNS label rules: lowercase
letters, digits and `-`, starting with a letter, at most 63 characters. `image` is the image the
platform runs; see [Build an image](images.md). `runtime` is the ankka version the image was built
against, described in [Declare the versions](#declare-the-versions). A service created from the
template carries this file with both fields filled in.

Every field, with its default, is in [Service descriptor](../reference/service-descriptor.md).

## Apply it

```bash
ankka services apply -f service.json            # into the configured project
ankka services apply -f service.json -p checkout
cat service.json | ankka services apply -f -    # from standard input
```

The CLI validates the descriptor before sending it, with the same rules the control plane applies when
it arrives, so a typo is reported without a round trip. Every problem is listed at once:

```text
error: invalid descriptor in service.json:
  - service name 'Orders' is invalid: lowercase letters, digits and '-', starting with a letter
  - unknown instanceType 'huge'; one of small, medium, large
```

Applying records the descriptor and returns. It does not wait for anything to start. The reply is the
service's status as the control plane knows it at that moment, usually `UpdateInProgress`.

## Follow it to Ready

```bash
ankka services list
```

```text
NAME    STATUS            INSTANCES  GEN  IMAGE
orders  UpdateInProgress  0/0        1    orders:latest
```

```text
NAME    STATUS  INSTANCES  GEN  IMAGE
orders  Ready   1/1        1    orders:latest
```

`GEN` is the generation: it goes up by one on every apply and every restart, and each status report says
which generation it describes, so a late report about an older deployment can never overwrite a newer
one. `ankka services get orders` shows one service in full, including a `detail` line that says why a
deployment is not yet `Ready`, and a `database` line that says what the platform did about its database.

A service reaches `Ready` only when every desired instance is a member of the service's cluster and, for
a service that serves HTTP, its HTTP server has bound. A deployment that has not got there within ten
minutes is reported `Failed`. The meaning of each state is in
[Service lifecycle states](../reference/lifecycle-states.md).

## What the platform creates

Applying a descriptor to project `checkout` produces, in the namespace `ankka-checkout`:

- **A database of the service's own**, in the project's Postgres cluster, with a generated credential and
  the runtime's schema applied before the service's first instance starts. See
  [Databases](../platform/databases.md).
- **A Deployment** of `minInstances` pods running the image, rolled out without downtime. See
  [Scale and roll out](scaling-and-rollouts.md).
- **A Service** named after the service, so that it is reachable inside the cluster at `orders` from its
  own project and at `orders.ankka-checkout.svc.cluster.local` from anywhere else.
- **A ServiceAccount and a Role** that can list pods in the namespace and nothing else, which is how the
  service's instances find each other.

The service is private: nothing outside the cluster can reach it until you
[expose it](expose.md).

The platform also sets the environment variables that tell each instance where it is running and how to
find its peers. A descriptor may not set them itself: see
[Reserved variables](#reserved-variables).

## Environment variables and secrets

`env` adds variables to the service's container. Each has either a literal `value` or a `secretKeyRef`
naming a key in a Kubernetes Secret in the project's namespace:

```json title="service.json"
{
  "name": "orders",
  "service": {
    "image": "registry.example.com/acme/orders:1.4.2",
    "runtime": "0.2.0",
    "env": [
      { "name": "LOG_LEVEL", "value": "info" },
      { "name": "ANTHROPIC_API_KEY", "secretKeyRef": { "name": "orders-secrets", "key": "anthropic-api-key" } }
    ]
  }
}
```

A variable with both `value` and `secretKeyRef`, or with neither, is refused. The Secret itself is
created outside ankka, in the project's namespace, by whoever administers the cluster:

```bash
kubectl -n ankka-checkout create secret generic orders-secrets --from-literal=anthropic-api-key=sk-ant-...
```

## Reserved variables

Some variables are the platform's to set, and a descriptor that sets one is refused at apply time rather
than having one of two values silently win:

| Variable | Why it is reserved |
|---|---|
| `ANKKA_HTTP_PORT` | The `port` field is the one way to set the port; the platform derives the container port, this variable and the in-cluster Service from it. |
| `ANKKA_CLUSTER_MODE`, `POD_IP`, `ANKKA_CLUSTER_SERVICE`, `ANKKA_CLUSTER_POD_SELECTOR`, `ANKKA_CLUSTER_CONTACT_POINTS` | They tell an instance it is running in Kubernetes and how to find its peers. |
| `ANKKA_PROCESS_PORT`, `ANKKA_PROCESS_ADDRESS`, `ANKKA_SIDECAR_PORT`, `ANKKA_SIDECAR_ADDRESS`, `ANKKA_SIDECAR_BIND` | They tell a Python process and its sidecar where to find each other. |

`ANKKA_DB_*` variables are allowed, and mean something specific: a descriptor that declares any of them
is bringing its own database, and the platform provisions none. See
[Databases](../platform/databases.md#bring-your-own-database).

## Declare the versions

`runtime` declares the ankka version the image was built against. The platform checks it before anything
starts, against its own version:

- the major version must be the same, and
- the minor version must be the platform's or one below it.

A platform at `0.3.x` runs services built against `0.2.x` and `0.3.x`, and nothing else. A declaration
outside that range is reported on `services get` as `Unavailable`, with a detail naming both versions,
and no pod starts. A descriptor with no `runtime` is not checked at all. The declaration is trusted:
the platform does not compare it with the runtime the image actually contains, so keep it in step with
the build. [Upgrade ankka](upgrading.md) covers moving both together.

## Services in another language

A Python service's image is a process, not an ankka runtime, and the descriptor says so with `hosting`
and `protocol`:

```json title="service.json"
{
  "name": "cart",
  "service": {
    "image": "my-cart:1.0.0",
    "hosting": "process",
    "protocol": "1.0"
  }
}
```

`hosting` is `embedded` by default, meaning the image is an ankka runtime. `process` means the platform
runs the ankka sidecar beside your process in the same pod. `protocol` is the sidecar protocol version
your SDK speaks; it is required with `process` hosting and refused without it. The platform accepts a
protocol with its own major version and a minor version no later than its own.

What changes about the pod:

- **Two containers.** The sidecar carries the ports, the readiness probe and the database credential.
  Your container has no ports and no probe, and small fixed resources.
- **The environment is split.** Variables beginning `ANTHROPIC_`, `ANKKA_MODEL_` and `ANKKA_DB_` go to
  the sidecar, which runs the agent loop and owns the journal. Every other variable goes to your process.
  A model key is therefore supplied in `env` exactly as for a Scala service, and your process never sees
  it or the database.
- **Everything else is the same.** Scale, restart, expose, pause and observe a Python service exactly as
  a Scala one.

The descriptor never names the sidecar's image or version. Those belong to the platform, which runs the
sidecar that matches the operator's own release. See
[Services in other languages](../concepts/polyglot.md).

## Change a deployed service

Apply the descriptor again with the change. Each apply is a new generation; the platform rolls the
instances only when something they run has changed, such as the image or the environment. Changing only
`minInstances` adds or removes pods and leaves the others running. Re-applying the descriptor of a
service you deleted recreates it under the same name, and it finds its existing database.
