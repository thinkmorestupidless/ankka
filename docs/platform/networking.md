---
title: Networking and TLS
description: How traffic reaches ankka services — the installation's single gateway and wildcard certificate, per-service routes, in-cluster addresses, the ports an instance uses, readiness, and what is not isolated.
kind: guide
related: [deploy/expose.md, platform/install-cloud.md, concepts/clustering.md]
---

# Networking and TLS

Traffic from outside the cluster reaches ankka through one gateway per installation, which terminates TLS
with one wildcard certificate and routes each exposed service's hostname to that service. Inside the
cluster, every service that serves HTTP has a Kubernetes Service named after it. Instances of one service
also talk to each other directly, to form their cluster.

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

The operator may manage `HTTPRoute`s and nothing else about routing: not gateways, certificates or
Secrets. A deployed service's own identity cannot read routes at all.

The route's status is reported back. A route the gateway has not accepted, or has accepted but cannot
resolve to a backend, shows on `ankka services get` as `route pending` or `route rejected: <reason>` in
the `detail` line. See [Expose a service](../deploy/expose.md).

The gateway routes HTTP/1.1. It forwards no gRPC or HTTP/2 to services, and applies no authentication,
rate limit or header policy of its own: who may call an endpoint is decided by the endpoint's ACL.

## In-cluster addresses

Every service that serves HTTP has a `ClusterIP` Service with the service's name. From the same project it
is reachable at its name; from any other namespace, at its full name:

```text
http://orders:9000
http://orders.ankka-checkout.svc.cluster.local:9000
```

The port is the descriptor's `port`, 9000 by default. From that one value the platform renders the
container port, `ANKKA_HTTP_PORT` for the runtime, and the Service's target, so the three cannot
disagree. A descriptor with `"http": false` gets none of them.

In-cluster traffic is plain HTTP. TLS ends at the gateway.

## The ports an instance uses

| Port | Name | Used for |
|---|---|---|
| 9000, or the descriptor's `port` | `http` | the service's HTTP endpoints |
| 17355 | `remoting` | cluster remoting between the service's own instances |
| 7626 | `management` | readiness, cluster bootstrap, `/ankka/version` and `/ankka/metrics` |

A Python service's pod has two more, on its loopback interface only: the process listens on 9010 for the
sidecar, and the sidecar on 9011 for the process. Neither is reachable from outside the pod.

## Readiness

Each instance's readiness probe is `GET /ready` on the port named `management`, every five seconds. It
answers 200 only when the instance is a member of its service's cluster and, for a service that serves
HTTP, its HTTP server has bound. A Service sends traffic only to ready instances, and a rolling update
waits for each new instance to be ready.

An image that is not an ankka service has no management port, is never ready, and is reported `Failed`
when the deployment's deadline passes. There is deliberately no liveness probe; see
[Scale and roll out](../deploy/scaling-and-rollouts.md#no-liveness-probe).

## What is not isolated

**Projects are not a network boundary.** A `ClusterIP` is reachable from every namespace, so any
project's pods can call any other project's services. Projects separate names and databases, and
database separation is enforced, but not traffic. Isolating traffic would need `NetworkPolicy`, which the
platform does not render.

**Cluster traffic is neither isolated nor encrypted.** Remoting on 17355 and management on 7626 are plain
TCP on the pod network, reachable from any namespace. An instance only chooses peers from its own
service's pods, selected by label, but nothing stops another pod connecting to it. Treat the cluster
network as trusted, or add `NetworkPolicy` of your own.
