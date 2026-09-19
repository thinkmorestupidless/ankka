# Quickstart: Expose Services Outside the Cluster

How to prove the feature works, in tiers, cheapest first. Every tier from 3 on runs against a real
Kubernetes cluster — that is the standard features 002–004 set, and each of them found something
the documentation did not say.

## Tier 1 — Pure (seconds)

```bash
sbt 'crd/testOnly com.thinkmorestupidless.ankka.crd.HostnamesSuite'                 # derivation, the 63-char refusal, api never derivable
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.ServiceEntitySuite'   # expose/unexpose events, idempotence, generation untouched
sbt 'controlPlaneApi/testOnly com.thinkmorestupidless.ankka.controlplane.api.*'     # ServiceStatus.hostname wire round-trip
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.RenderingSuite'       # HTTPRoute shape; absent when unexposed or http:false
sbt 'cli/test'                                              # config set ca; expose/unexpose commands
```

## Tier 2 — Control plane in-process (a minute)

```bash
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.ControlPlaneSuite'
```

Through the real CLI against a real control plane on a throwaway Postgres: expose prints the
hostname; `get`/`list` show it; unexpose clears it; `apply` afterwards does not change it; the three
refusals return their exact messages; `http: false` cannot be exposed.

## Tier 3 — Operator on k3s (minutes)

```bash
sbt 'operator/testOnly com.thinkmorestupidless.ankka.operator.OperatorClusterSuite'
```

Adds: an HTTPRoute is created for an exposed resource and deleted on unexpose; none for `http:
false`; owner reference set; the operator's own token cannot touch `gateways` or `secrets`; a
service's token cannot list `httproutes`.

## Tier 4 — End to end on k3s, from the host (5–8 minutes)

```bash
sbt 'controlPlane/testOnly com.thinkmorestupidless.ankka.controlplane.ExposureClusterSuite'
```

Installs cert-manager and Envoy Gateway into the k3s container, applies the gateway component with
a test base domain, deploys the shopping cart through the CLI, exposes it, and from the **host**
runs `curl --cacert <exported root> --resolve …` against the mapped NodePort:

| Case | Proves |
|---|---|
| private by default | the hostname 404s at the gateway before expose |
| expose, then write and read a cart by hostname with the certificate verified | US1 / SC-001, SC-002 |
| plain HTTP redirects 301 to HTTPS | FR-016 |
| same name in two projects, two hostnames, two states | SC-004 |
| rolling restart under `curl` load by hostname | SC-003 — zero failed requests |
| unexpose: 404 within 30s, service still Ready, same pod UIDs | FR-005 |
| delete the project: no HTTPRoute left in the cluster | SC-008 |
| the control plane's own route: CLI `services list` through `https://api.<base>` with `config set ca` | US3 |

## Tier 5 — The reviewer's checklist

- [ ] `apply` re-applied after `expose` leaves `hostname` unchanged (Tier 2), and `restart` leaves
      the same route UID (Tier 4).
- [ ] `grep -rn "insecure\|-k \|noVerify\|TrustAll" cli/ controlplane/ kustomization/ README.md`
      finds nothing (SC-010).
- [ ] The operator's ClusterRole mentions `httproutes` and no other Gateway API or cert-manager
      resource.
- [ ] `ANKKA_BASE_DOMAIN` appears in the local overlay **once** (the ConfigMap) and reaches every
      consumer by replacement — `kubectl kustomize kustomization/overlays/local | grep sslip` shows
      the same value in the Certificate, the Gateway, both Deployments and the control plane's route.
- [ ] Every existing suite passes; an unexposed service's rendered objects are byte-identical to
      before (SC-009).

## Tier 6 — Manual, on kind

The kind cluster must be created from the config file now — port mappings cannot be added later:

```bash
kind delete cluster --name ankka                           # if it predates this feature
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh
```

The script ends by printing the control plane's address and the root certificate's path:

```bash
ankka config set url https://api.127.0.0.1.sslip.io
ankka config set ca ~/.ankka/local-ca.crt
ankka config set token dev-local-token
ankka services list                                        # no port-forward anywhere

echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}' > cart.json
ankka services apply -f cart.json
ankka services expose cart
#   https://cart-checkout.127.0.0.1.sslip.io

curl --cacert ~/.ankka/local-ca.crt -XPOST https://cart-checkout.127.0.0.1.sslip.io/carts/c1/items \
     -H 'content-type: application/json' -d '{"productId":"p1","name":"Widget","quantity":2}'
curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.127.0.0.1.sslip.io/carts/c1
curl -I http://cart-checkout.127.0.0.1.sslip.io/carts/c1   # 301 to https

ankka services unexpose cart
curl --cacert ~/.ankka/local-ca.crt https://cart-checkout.127.0.0.1.sslip.io/carts/c1   # 404 from the gateway
```

| Action | Expected |
|---|---|
| `deploy-local.sh` on a cluster created with the old one-liner | refuses, names `kustomization/kind.yaml`, says to recreate |
| `dig +short cart-checkout.127.0.0.1.sslip.io` | `127.0.0.1` — if not, the README's hosts-file fallback |
| `services expose` with `ANKKA_BASE_DOMAIN` unset on the control plane | refused, message names the variable |
| `kubectl -n ankka-gateway get gateway ankka` | `PROGRAMMED: True` |
| `kubectl get httproutes -A` after `ankka projects delete` | none for that project |
