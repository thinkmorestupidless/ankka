# Contract: the installation

What an installation provides for custom hostnames, in the components and in each overlay.
Decisions are in [research.md](../research.md) (R5, R14, R15, R16, R17).

## The gateway component

`gateway.yaml`:

```yaml
spec:
  allowedListeners:
    namespaces:
      from: Selector
      selector: { matchLabels: { app.kubernetes.io/managed-by: ankka } }
  listeners:
    - name: http          # port 80; routes from managed namespaces too, for cert-manager's solver route
      allowedRoutes: { namespaces: { from: Selector, selector: { matchLabels: { app.kubernetes.io/managed-by: ankka } } } }
    - name: https         # unchanged: *.BASE_DOMAIN, the wildcard
```

The redirect route is unchanged and, having no `hostnames`, redirects a custom hostname in the
clear too. The operator renders nothing against the `http` listener (held by the golden files).

## cert-manager

The component patches the controller Deployment's args with `--enable-gateway-api` and
`--enable-certificate-owner-ref`: the first lets the HTTP-01 solver answer through the Gateway, the
second makes every issued Secret owned by its Certificate, so a custom hostname removed takes its key
with it. `GatewayStack` applies the same patch on k3s.

## The issuer

`ANKKA_HOSTNAME_ISSUER` names a `ClusterIssuer`:

| Overlay | `hostnameIssuer` | The issuer |
|---|---|---|
| local | `ankka-ca` | `local-ca.yaml`: `selfsigned` Issuer and `ankka-root-ca` Certificate in `cert-manager`; `ankka-ca` a `ClusterIssuer` with `ca.secretName: ankka-root-ca`; the wildcard Certificate in `ankka-gateway` names it. `deploy-local.sh` exports the root from `cert-manager` |
| cloud | `letsencrypt-hostnames` | `acme-issuer.yaml` gains: `ClusterIssuer letsencrypt-hostnames`, ACME production, `privateKeySecretRef: letsencrypt-hostnames-account`, one solver `http01.gatewayHTTPRoute.parentRefs: [{name: ankka, namespace: ankka-gateway, kind: Gateway}]`. `letsencrypt-production` (DNS-01, the wildcard) is unchanged |

A Certificate for a custom hostname lives in the project's namespace, which is why the issuer is
cluster-scoped.

## The ConfigMap and the replacements

`platform-configmap.yaml` gains `hostnameIssuer` in both overlays and `gatewayAddress # SET` in
the cloud's (empty in the local's). `replacements` carry `hostnameIssuer` into both Deployments'
`ANKKA_HOSTNAME_ISSUER` and `gatewayAddress` into the control plane's `ANKKA_GATEWAY_ADDRESS`. The
control plane's `ANKKA_DNS_RESOLVER` is unset in both overlays (the cluster's resolver).

## What `RemoteOverlaySuite` asserts

Both overlays render `ANKKA_HOSTNAME_ISSUER` on both Deployments with the overlay's value; the
Gateway carries `allowedListeners` by selector; the local issuer is a `ClusterIssuer` whose CA
Secret is in `cert-manager`; the cloud's `letsencrypt-hostnames` has exactly one solver, HTTP-01,
naming the Gateway; the cert-manager controller's args contain `--enable-gateway-api`.

## The test stack (`AcmeStack`, k3s only)

Namespace `acme-test`: `pebble-challtestsrv` (DNS 8053, management 8055), Pebble (14000 directory,
15000 management, `httpPort: 80`, `-dnsserver` the resolver), `ClusterIssuer pebble`
(`skipTLSVerify: true`, HTTP-01 through the Gateway), and cert-manager's controller patched with
`--acme-http01-solver-nameservers=<resolver ip>:8053`. The suite reads Pebble's root from
`GET https://pebble:15000/roots/0` through a pod and verifies with it.

## A local platform

A developer with a domain creates `_ankka.<hostname> TXT "ankka-project=<project>"` at their
provider and `127.0.0.1 <hostname>` in `/etc/hosts`, then `ankka services hostnames add`. The local
authority issues at once; `https://<hostname>:8443` answers, verified by `~/.ankka/local-ca.crt`.
