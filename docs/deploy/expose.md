---
title: Expose a service
description: Make a deployed service reachable from outside the cluster at its platform-derived HTTPS hostname, understand why the hostname has the shape it does, and remove the route again.
kind: guide
related: [platform/networking.md, build/http-endpoints.md, deploy/deploy-a-service.md]
---

# Expose a service

A deployed service is private: it answers at its in-cluster address and nowhere else. `ankka services
expose` gives it a public HTTPS address, and `ankka services unexpose` takes that away again. Exposure is
a decision made after deploying, by a command, so applying a descriptor never changes it.

```bash
ankka services expose cart
```

```text
https://cart-checkout.127.0.0.1.sslip.io:8443
```

```bash
curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.127.0.0.1.sslip.io:8443/carts/c1
ankka services unexpose cart    # removes the route; the service keeps running
```

`ankka services get cart` shows the address on its `hostname` line, or `not exposed`.

## Decide the ACL first

**Exposure changes who can reach an endpoint, not who is allowed to call it.** Every HTTP endpoint
declares an `acl`, and that is the only check on who may call it: the gateway does no authentication of
its own. An endpoint whose ACL is `AllowAll` on an exposed service on a real installation is on the
internet. Choose the ACL before running `expose`. See [HTTP endpoints](../build/http-endpoints.md).

## The hostname

The platform derives the hostname; you do not choose it:

```text
<service>-<project>.<base domain>
```

Service `cart` in project `checkout` on an installation whose base domain is `example.com` answers at
`https://cart-checkout.example.com`. The control plane itself answers at `api.<base domain>`.

The name is one label under the base domain, `cart-checkout`, rather than the two-label
`cart.checkout.example.com`, because a wildcard covers exactly one label. The platform serves every
exposed service under a single wildcard certificate for `*.<base domain>`, and the gateway's listener
matches the same wildcard. `*.example.com` covers `cart-checkout.example.com` and does not cover
`cart.checkout.example.com`.

Two services with the same name in different projects never collide, because the project is part of the
hostname.

## When expose is refused

`expose` refuses rather than produce a hostname that would not work:

| Refusal | Why | Fix |
|---|---|---|
| hostname label over 63 characters | a DNS label is at most 63 characters, and `<service>-<project>` is one label | a shorter service name or project id |
| hostname already exposed by another service | `a-b` in project `c` and `a` in project `b-c` both derive `a-b-c` | unexpose the other, or rename one |
| service serves no HTTP | the descriptor says `"http": false`, so there is nothing to route to | none needed |
| no base domain configured | the control plane was started without `ANKKA_BASE_DOMAIN` | configure the installation's base domain |

## TLS, always

Every exposed service is served over HTTPS, and plain HTTP to the same hostname is redirected to HTTPS.
There is no HTTP-only mode and no option anywhere in the CLI to skip certificate verification.

On a local platform the certificate is issued by a certificate authority created inside the cluster, and
its root is exported to `~/.ankka/local-ca.crt`. Nothing on your machine is asked to trust it: pass it to
each client that needs it, as `curl --cacert` and `ankka config set ca` do. On a real installation the
wildcard certificate comes from a public certificate authority, and clients need nothing. See
[Networking and TLS](../platform/networking.md).

## Local DNS

A local platform's base domain is `127.0.0.1.sslip.io`. sslip.io is a public DNS service that resolves
any name ending in an embedded address to that address, so `cart-checkout.127.0.0.1.sslip.io` resolves to
`127.0.0.1` with nothing configured on your machine. The ports are 8443 and 8080 rather than 443 and 80
because a developer's machine often has those taken; the port is part of the URL and of nothing else.

If your resolver blocks sslip.io, use a hosts-file name instead and redeploy the local platform with a
matching base domain:

```bash
echo '127.0.0.1  api.ankka.local cart-checkout.ankka.local' | sudo tee -a /etc/hosts
ANKKA_BASE_DOMAIN=ankka.local ./kustomization/deploy-local.sh
```

A hosts file has no wildcards, so each exposed service needs its own line.

## When the hostname does not answer

The route to an exposed service is only as healthy as the gateway says it is. When the gateway has not
accepted the route, or has accepted it and cannot reach the service, `services get` says so in its
`detail` line:

```text
detail      route rejected: RefNotPermitted
```

`route pending` means the gateway has not yet processed the route, which is normal for a few seconds
after `expose`. `route rejected: <reason>` carries the gateway's own reason. See
[Troubleshooting](../operate/troubleshooting.md).

A request by hostname only ever reaches an instance that is ready: the route's backend is the service's
in-cluster address, so an instance that has not joined the service's cluster or bound its port receives
nothing.

## Unexpose

```bash
ankka services unexpose cart
```

The route is removed and the hostname stops answering. Nothing else changes: the service keeps running
and stays reachable inside the cluster. Deleting a service also removes its route, and a service
re-applied after deletion starts private again.
