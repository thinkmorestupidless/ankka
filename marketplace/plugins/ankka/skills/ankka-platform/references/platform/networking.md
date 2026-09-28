# Networking and TLS

> How traffic reaches ankka services and moves between them — the gateway, mutual TLS on every port, the certificates each workload holds, caller identity, the network policies, readiness, and what a cluster must provide.

Source: https://docs.ankka.cloud/platform/networking/
Traffic from outside the cluster reaches ankka through one gateway per installation, which terminates the
public TLS connection and forwards each request to the service it is for over a second, mutually
authenticated TLS connection. Inside the cluster every connection a workload accepts — from another
service, from its own peers, to its database — is mutual TLS with a certificate the installation issued,
and a network policy decides which sources may open one at all. Nothing is trusted because of where it
is on the network.

## The gateway

An installation has one [Gateway API](https://gateway-api.sigs.k8s.io/) `Gateway`, named `ankka` in the
`ankka-gateway` namespace and implemented by Envoy Gateway. It has two listeners:

- **HTTPS on port 443**, for `*.<base domain>`, serving the wildcard certificate from the Secret
  `ankka-wildcard-tls`. It accepts routes only from namespaces labelled
  `app.kubernetes.io/managed-by: ankka`, which are the namespaces the platform created for projects.
- **HTTP on port 80**, which accepts one route: a redirect of every request to HTTPS. There is no
  HTTP-only mode.

How the gateway reaches the outside world is the installation's choice. A local platform publishes it on
node ports that kind maps to the host's 8080 and 8443; a cloud installation gives it a `LoadBalancer`.

The control plane and the identity provider are routed through the same gateway, at `api.<base domain>`
and `auth.<base domain>`.

## One wildcard certificate

Every hostname the installation serves is one label under the base domain, so one certificate for
`*.<base domain>` covers all of them. A wildcard covers exactly one label, for the certificate and for
the gateway's listener alike, which is why an exposed service's hostname is
`<service>-<project>.<base domain>` rather than `<service>.<project>.<base domain>`.

A wildcard certificate can only be issued over the ACME DNS-01 challenge, so a cloud installation needs a
DNS provider cert-manager can write to. A local platform issues its own from a certificate authority
created in the cluster, and exports the root to `~/.ankka/local-ca.crt`. See
[Install on a cloud cluster](install-cloud.md).

## Routes for exposed services

Exposing a service creates a Gateway API `HTTPRoute` named after the service in its project's namespace,
attached to the installation's gateway, for the service's hostname. Its backend is the service's own
Kubernetes Service, so a request by hostname only ever reaches an instance that is ready.

Beside the route the platform creates a `BackendTLSPolicy` of the same name. It tells the gateway to reach
the service over TLS, to verify the service's certificate against the installation's service authority,
and to require the name `<service>.<namespace>.svc.cluster.local`. The gateway presents a certificate of
its own when it connects, so the service knows the request came through the gateway.

The operator may manage `HTTPRoute`s and `BackendTLSPolicy`s and nothing else about routing: not gateways,
listeners or the certificates the gateway serves. A deployed service's own identity cannot read routes at
all.

The route's status is reported back. A route the gateway has not accepted, or has accepted but cannot
resolve to a backend, shows on `ankka services get` as `route pending` or `route rejected: <reason>` in
the `detail` line. See [Expose a service](../deploy/expose.md).

The gateway routes HTTP/1.1. It forwards no gRPC or HTTP/2 to services, and applies no authentication,
rate limit or header policy of its own: who may call an endpoint is decided by the endpoint's ACL.

## In-cluster addresses

Every service that serves HTTP has a `ClusterIP` Service with the service's name. From the same project it
is reachable at its name; from any other namespace, at its full name:

```text
https://orders:9000
https://orders.ankka-checkout.svc.cluster.local:9000
```

The address is HTTPS and requires a client certificate the installation issued, so the practical way to
call another service is the service client, which presents the calling service's certificate and finds
the port by name. See [HTTP endpoints](../build/http-endpoints.md#call-another-service).

The port is the descriptor's `port`, 9000 by default. From that one value the platform renders the
container port, `ANKKA_HTTP_PORT` for the runtime, and the Service's target, so the three cannot
disagree. A descriptor with `"http": false` gets none of them.

## Every connection is mutual TLS

Each connection a workload accepts is TLS in both directions, with a certificate issued for that workload:

| Connection | Server presents | Client presents | Verified against |
|---|---|---|---|
| Gateway to a service | the service's service certificate | the gateway's certificate | the service authority |
| Service to service | the callee's service certificate | the caller's service certificate | the service authority |
| Between a service's own instances (remoting, management, cluster bootstrap) | the service's cluster certificate | the same certificate | the cluster authority, and the peer must name the same service |
| A service to its database | the database cluster's server certificate | the service's database certificate | the project's own database authorities |

The two installation authorities are separate on purpose: a cluster certificate is worth nothing on a
service's HTTP port, and a service certificate is worth nothing between cluster peers. A service's
instances accept a peer only if its certificate names the same service, so a pod of another service,
issued by the same authority, cannot join.

A connection without a client certificate, or with one from another authority, is refused during the
TLS handshake. No route, ACL or handler runs for it.

## What mutual TLS costs

The gateway and the service client keep their connections open, so a TLS handshake is paid once per
connection and not once per request. What remains is encrypting each request and its reply.

On a request that runs an entity command, writes to the journal and replies, that cost is small but
measurable. Across five runs of the platform's benchmark on one laptop, the median run measured mutual
TLS at about a tenth slower than plain HTTP:

| Measure | Result |
|---|---|
| Median of five runs | 9.6% slower |
| Range across runs | 13.4% faster to 13.6% slower |
| One request, plain HTTP | about 0.5 ms |

The range is wider than the cost, because the journal write dominates a request and varies more than
encryption does. A request that does less work pays a larger share of the same absolute cost.

## The certificates a workload holds

The operator asks cert-manager for each workload's certificates and mounts the Secrets cert-manager
writes. It never reads a private key itself.

| Certificate | Identity | Mounted at | Issued when |
|---|---|---|---|
| `<service>-cluster` | `ankka://<project>/<service>` | `/var/run/secrets/ankka/cluster` | always |
| `<service>-service` | `ankka://<project>/<service>`, and the Service's DNS names | `/var/run/secrets/ankka/service` | always: it is also who the service is when it calls another |
| `<service>-database` | common name `<service>`, the database role | `/var/run/secrets/ankka/database` | the platform provisions its database |

Each directory holds `tls.key`, `tls.crt` and `ca.crt`. A certificate is valid for 24 hours and renewed
every 8, so the one being replaced stays valid for another 16 while every instance picks up its successor.
A running instance reads the renewed files for new connections within a minute and keeps its open ones;
nothing restarts. The paths are fixed: an image needs no configuration to find them and a descriptor
cannot move them.

## Who is calling

Every request that reaches an endpoint carries its caller, read from the client certificate:

- **the gateway**, for a request from outside the cluster — the internet, whoever that was;
- **a service**, named by project and service, for a request from another workload;
- **the local machine**, when the service runs outside a cluster, where there is no certificate to read.

An endpoint names which callers it admits with `Acl.allowCallers`. See
[HTTP endpoints](../build/http-endpoints.md#name-who-may-call). Because the caller comes from a
certificate the platform issued, a request cannot choose who it is: a header claiming to be a service is
evidence of nothing, and no such header is read.

The gateway is one caller for the whole installation. An endpoint that admits the internet admits every
request that arrived through any hostname; deciding which user made it is a bearer token checked by
`Acl.Authenticate`.

## What the network admits

Each workload also gets network policies, which refuse a connection before any TLS begins:

| Port | Admitted from |
|---|---|
| The service's HTTP port | the installation gateway's proxy pods, and any pod of an ankka workload in any ankka namespace |
| 17355 (remoting) and 7626 (management) | the service's own pods only |
| 7627 (readiness) | anywhere |
| 5432 on a project's database | that project's ankka workloads, the database's own instances and the database operator |

Envoy Gateway runs a gateway's proxy pods in its own namespace, `envoy-gateway-system`, not in the
`Gateway`'s. The policy therefore names those pods by the labels Envoy Gateway gives them, for the gateway
`ankka` in `ankka-gateway`. A proxy for any other gateway in the cluster is not admitted.

A project is not a network boundary for HTTP: a service in one project can open a connection to a service
in another. Whether the request is served is the callee's ACL's decision, from the caller's certificate.
That is deliberate — the network decides only that the caller is an ankka workload at all — and a
project's cluster ports and database are closed to every other project either way.

## The ports an instance uses

| Port | Name | Transport | Used for |
|---|---|---|---|
| 9000, or the descriptor's `port` | `http` | mutual TLS | the service's HTTP endpoints |
| 17355 | `remoting` | mutual TLS | cluster remoting between the service's own instances |
| 7626 | `management` | mutual TLS | cluster bootstrap, `/ankka/version` and `/ankka/metrics` |
| 7627 | `probe` | plain HTTP | `GET /ready`, and nothing else |

A Python or TypeScript service's pod has two more, on its loopback interface only: the process listens on
9010 for the sidecar, and the sidecar on 9011 for the process. They carry plain gRPC, because the pod's
loopback interface is shared by the pod's own containers and nothing else; the sidecar holds every
certificate and terminates every connection from outside the pod.

## Readiness

Each instance's readiness probe is `GET /ready` on the port named `probe`, every five seconds. It answers
200 only when the instance is a member of its service's cluster and, for a service that serves HTTP, its
HTTP server has bound. A Service sends traffic only to ready instances, and a rolling update waits for
each new instance to be ready.

Readiness has a port of its own because the kubelet cannot present a client certificate, and a TLS
listener that requires one requires it of every request. The probe port discloses one bit, ready or not,
which is why it admits every source.

An image that is not an ankka service, or whose runtime predates mutual TLS, opens no probe port, is never
ready, and is reported `Failed` when the deployment's deadline passes, with the kubelet's own probe
failure in the detail. There is deliberately no liveness probe; see
[Scale and roll out](../deploy/scaling-and-rollouts.md#no-liveness-probe).

## What a cluster must provide

Two things, beyond Kubernetes 1.32 or later:

- **A network that enforces `NetworkPolicy`.** Every API server accepts a policy; only a network plugin
  that implements them refuses anything. kind 0.24 and later, k3s, GKE Dataplane V2, Calico and Cilium
  enforce them. On a network that does not, every TLS guarantee on this page still holds and none of the
  network ones do. `deploy-local.sh` proves enforcement before it deploys anything; for a cloud cluster,
  see [Install on a cloud cluster](install-cloud.md#check-that-the-network-enforces-policy).
- **cert-manager and trust-manager**, which the platform's overlays install. trust-manager copies the
  service authority's root certificate into every ankka namespace as the ConfigMap `ankka-service-ca`,
  which is where the gateway reads the authority it verifies services with.

## What is not isolated

**Where a workload may connect to is not restricted.** The network policies decide who may connect to a
workload, not where it may connect: a service can open a connection to the internet, to a model provider
or to any address in the cluster. There is no egress policy.

**A project is not a network boundary for HTTP.** As described in
[What the network admits](#what-the-network-admits), a service's HTTP port admits every ankka workload,
and its ACL decides.
