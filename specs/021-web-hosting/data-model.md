# Data Model: Web Hosting

Nothing here is stored anywhere new. The descriptor travels in the control plane's existing
`ServiceApplied` event and entity state; the resource is the existing `AnkkaService`; the proxy
keeps nothing.

## The descriptor (`controlplane-api`, `ServiceSpec`)

Three fields are added, and one value of `hosting`. Every one has a default that means "not used",
so a persisted descriptor decodes unchanged (research R20).

| Field | Type | Default | Meaning |
|---|---|---|---|
| `hosting` | string | `"embedded"` | gains the value `"web"` |
| `mounts` | array of `Mount` | `[]` | paths of this service answered by another service of the project |
| `callers` | array of string | `[]` | the services admitted, beside the internet |
| `processPort` | integer, optional | absent (8080) | the port the process listens on |

`Mount`: `{ "path": string, "service": string }`.

A caller entry is one of `"<service>"`, `"<project>/<service>"` or `"*"`.

```json
{ "name": "web",
  "service": {
    "image": "registry.example.com/acme/shop-web:1.0.0",
    "hosting": "web",
    "processPort": 3000,
    "mounts": [ { "path": "/api/cart", "service": "cart" },
                { "path": "/api/orders", "service": "orders" } ],
    "callers": [ "orders", "billing/invoices" ],
    "env": [ { "name": "SESSION_KEY", "secretKeyRef": { "name": "web-secrets", "key": "session" } } ] } }
```

### Validation (`ServiceSpec.problems`, one implementation for the CLI and the control plane)

Every problem is reported at once. The messages are `contracts/descriptor-and-rendering.md`'s.

When `hosting` is `web`:

- `http` is `true`;
- `protocol` and `runtime` are absent;
- no variable is named `PORT` or `ANKKA_SERVICES_URL`, and none starts with `ANKKA_DB_`; the
  platform's existing reserved names stay refused;
- `processPort`, stated or defaulted, is in 1–65535, is not `port`, and is none of 7626, 7627,
  7628, 7630 and 17355;
- `port` is not 7627 or 7630;
- each mount's `path` starts with `/`, is not `/`, has no empty segment, no trailing `/`, no `?` or
  `#`, and each segment is made of unreserved URL characters;
- no two mounts have the same path, and no mount's path is a segment-wise prefix of another's;
- each mount's `service` is a valid service name and is not this service's own name;
- each caller entry has one of the three shapes, with valid names, and appears once.

When `hosting` is anything else: `mounts` and `callers` are empty and `processPort` is absent.

For every hosting: no variable is taken from a Secret the platform issues: one whose name ends
`-service-tls`, `-mount-tls`, `-cluster-tls` or `-database-tls`, or is `ankka-db` or starts
`ankka-db-`.

Not validated, because the other service is another entity: whether a mount's service exists,
serves HTTP or is paused (see *Mount state*).

## The resource (`crd`, `AnkkaServiceSpec`)

| Field | Type | Default | From |
|---|---|---|---|
| `hosting` | string | `"embedded"` | the descriptor; the CRD's enum gains `web` |
| `mounts` | list of `MountEntry(path, service)` | empty | the descriptor |
| `callers` | list of string | empty | the descriptor |
| `processPort` | optional integer | absent | the descriptor's value, or 8080 for web hosting |
| `provisionDatabase` | boolean | `true` | `false` for web hosting |

Each is declared in `ankkaservice.yaml`; `CrdSchemaSuite` holds the two to each other by name, and
gains the check that the `hosting` enum is the operator's constants.

## The status (`controlplane-api`, `ServiceStatus`)

| Field | Type | Default | Meaning |
|---|---|---|---|
| `mounts` | array of `MountStatus` | `[]` | the descriptor's mounts, each with what is behind it |
| `callers` | array of string | `[]` | the descriptor's |
| `processPort` | integer, optional | absent | the port in force |
| `database` | string, optional | | gains the phrase `none`, for a web-hosted service |

`MountStatus`: `{ "path": string, "service": string, "state": string }`.

### Mount state

Decided when one service is read, from the target's entity, never stored:

| State | When |
|---|---|
| `ok` | the service exists, serves HTTP and is not paused |
| `no service` | no service of that name exists in the project |
| `serves no HTTP` | its descriptor says `"http": false` |
| `paused` | it is paused |

In a listing the state is empty: a row is a projection and cannot ask another entity. A request
under a mount whose state is not `ok` is answered 503 by the proxy, which finds that out for
itself: the status is for people.

## The proxy's settings (the pod's environment, written by the operator)

| Variable | Value | Notes |
|---|---|---|
| `ANKKA_PROXY_PROJECT`, `ANKKA_PROXY_SERVICE` | the service's identity | also read from the certificate; they must agree or the proxy does not start |
| `ANKKA_PROXY_PORT` | the service's `port` | the mutual TLS listener |
| `ANKKA_PROXY_PROCESS_PORT` | `processPort` | where requests are passed on, and what the probe connects to |
| `ANKKA_PROXY_MOUNTS` | `/api/cart=cart,/api/orders=orders` | empty when there are none |
| `ANKKA_PROXY_CALLERS` | `orders,billing/invoices,*` | empty when there are none |
| `ANKKA_PROXY_PUBLIC_AUTHORITY` | `web-shop.example.com` or `…:8443` | absent when the operator has no base domain |
| `ANKKA_NAMESPACE_PREFIX` | as for every workload | to find a service's address |

The certificates are at fixed paths: `/var/run/secrets/ankka/service`, and
`/var/run/secrets/ankka/mount` when there are mounts. A path is not a setting.

The same settings are a value, `ProxySettings`, in `proxy-core`; the CLI's local command builds one
from a descriptor with no certificates and every caller local.

Who sent a request is the proxy's own type, `Sender`: `Internet(stated)`, `Service(project, name)`
or `Local`. `stated` is the address a mounting proxy stated, present only for a request under
another web-hosted service's mount. The internet, the local machine and the service itself are
always admitted; any other service only when the descriptor names it.

## The process's environment (written by the operator)

Every variable of the descriptor, and:

| Variable | Value |
|---|---|
| `PORT` | `processPort` |
| `ANKKA_SERVICES_URL` | `http://127.0.0.1:7630` |

## Identities

| Who | Certificate URI | Read by a callee as |
|---|---|---|
| a web-hosted service, for its process's calls | `ankka://<project>/<service>` | `Caller.Service(project, service)` |
| a web-hosted service, for a request under a mount | `ankka://<project>/<service>/mount` | `Caller.Gateway` by a service of the same project; refused by a service of any other project, and by a runtime from before this feature |
| the gateway | `ankka://gateway` | `Caller.Gateway` |

## Ports in a web-hosted pod

| Port | Name | Listener | Reached by |
|---|---|---|---|
| the descriptor's `port`, 9000 by default | `http` | the proxy, mutual TLS | the gateway and admitted services |
| 7627 | `probe` | the proxy, plain | anything; it answers ready or not |
| `processPort`, 8080 by default | none | the process | the proxy, over loopback; the network admits nothing else |
| 7630 | none | the proxy, bound to loopback | the process |

## Lifecycle

A web-hosted service has the lifecycle every service has. What differs is what makes an instance
ready (the process accepts a connection on its port) and one detail when it never does (research
R8). There is no state of its own.
