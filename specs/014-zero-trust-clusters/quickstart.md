# Quickstart: validating zero trust in the service clusters

Each scenario names the suite that automates it and the by-hand check that matches what a
developer's machine does. Docker is required throughout; the k3s suites need
`-Dankka.cluster.tests` left on (the default) and take minutes each. Run long suites under
`caffeinate -i`.

## Prerequisites

```bash
sbt compile                                   # warning-free
sbt docker:publishLocal                       # operator, control plane, sidecar, shopping cart
kind version                                  # ≥ 0.24 for a local platform (network policy)
```

## 1. Offline: the http module speaks mutual TLS and names the caller

```bash
sbt 'http/testOnly *TlsServerSuite *CallerAclSuite *RotatingTlsSuite'
```

Expected: a client presenting a certificate with URI `ankka://p/x` is served with
`caller == Service("p","x")`; without a certificate the connection is refused (no request reaches
the router, the recorder has no span); with a certificate lacking an `ankka://` SAN the answer is
403 recorded as a refusal; after the server's certificate files are replaced, a new connection
sees the new certificate within the reload interval and open connections continue.

## 2. Offline: rendering

```bash
sbt 'operator/testOnly *RenderingSuite *ServiceRenderingSuite *CnpgRenderingSuite *TransitionSuite'
```

Expected: the objects and rules in `contracts/rendering.md`; the Deployment selector unchanged;
`Transition` emitted only for a template without the transport label; the credential Secret has no
password.

## 3. Offline: the SDKs and the sidecar agree

```bash
sbt 'sidecar/testOnly *ConformanceSuite -- *ep.caller*'
cd sdks/python && uv run pytest -q && uv run mypy && uv run conformance
cd sdks/typescript && npm run typecheck && npm test && npm run conformance
```

Expected: the five `ep.caller-*` cases pass on all three targets.

## 4. In a real cluster: the whole feature (k3s)

```bash
sbt 'controlPlane/testOnly *ZeroTrustClusterSuite'      # new; deploys the shopping cart + a second sample
sbt 'controlPlane/testOnly *MultiNodeClusterSuite'      # existing, now over TLS: crash, partition, handoff
sbt 'operator/testOnly *OperatorClusterSuite'           # RBAC negatives for the new kinds; CNPG fields accepted
```

`ZeroTrustClusterSuite` asserts, in order:

1. three instances form one cluster; `kubectl exec` on a pod in another namespace to 17355, 7626
   and 5432 → connection refused (SC-001, network);
2. a pod carrying the service's identity labels but a certificate for another service (minted by
   the suite through the `ankka-cluster` issuer) is refused at the handshake; membership unchanged
   (SC-001, TLS);
3. `pg_stat_ssl` joined to `pg_stat_activity` for the service's role: every row `ssl = true`,
   `client_dn` = the role; the credential Secret has no `password` key (SC-005);
4. a second service in the same project calls the first through `ServiceClient`: 200 and the
   handler reports `Service(<project>, <second>)`; a third service in another project: 403; the
   same route through the gateway with `curl --cacert --resolve`: 200 and `Gateway`; `curl -k`
   to the pod IP with no client certificate: TLS handshake failure (SC-002);
5. renewal on demand — `kubectl patch certificate <svc>-cluster --subresource=status --type=merge
   -p '{"status":{"conditions":[{"type":"Issuing","status":"True","reason":"ManuallyTriggered","message":"suite"}]}}'`
   for all three certificates — under a continuous request load: zero failed requests, zero
   restarts, zero membership changes, and the pod's mounted `tls.crt` changes within two minutes
   (SC-003);
6. rolling replacement under load loses no requests (SC-004, existing measure);
7. the transition: deploy an image built from the previous release (the suite tags the current
   sample image with the pre-feature rendering by deploying it *through a pre-feature operator
   image* first, `ghcr.io/…/ankka-operator:0.7.1` via the Artifact Registry cache), then swap the
   operator: exactly one `Deploying` observation with the transition detail, then `Ready`, one
   cluster, journal intact (SC-007);
8. a descriptor declaring `"runtime": "0.7.1"` is refused with the compatibility detail; a
   `pause` image with `"http": false` is `Failed` with a detail quoting the kubelet's probe failure
   (FR-031).

## 5. The local platform

```bash
kind create cluster --name ankka --config kustomization/kind.yaml
./kustomization/deploy-local.sh        # now proves network policy enforcement before deploying
ankka config set url https://api.127.0.0.1.sslip.io:8443 && ankka config set ca ~/.ankka/local-ca.crt
ankka login                            # dev / dev
ankka organizations create acme --name Acme && ankka projects create checkout --name Checkout -O acme
echo '{"name":"shopping-cart","service":{"image":"sample-shopping-cart:latest"}}' > cart.json
ankka services apply -f cart.json -p checkout        # the deploy script already loaded the image
ankka services get shopping-cart -p checkout          # Ready 1/1, database provisioned
ankka services expose shopping-cart -p checkout
kubectl -n ankka-checkout get certificate,networkpolicy,backendtlspolicy
kubectl -n ankka-checkout exec deploy/shopping-cart -- ls /var/run/secrets/ankka/cluster   # ca.crt tls.crt tls.key
curl --cacert ~/.ankka/local-ca.crt https://shopping-cart-checkout.127.0.0.1.sslip.io:8443/callers/whoami
```

Expected: five certificates `Ready` (the project's two database authorities and the service's
cluster, service and database certificates), three network policies, and an accepted
`BackendTLSPolicy`; `whoami` answers `the internet, through the gateway`; and `kubectl run -n
default probe --image=busybox:1.36 --rm -i --restart=Never -- wget -T3 -qO-
http://shopping-cart.ankka-checkout.svc:9000/carts/c1` times out.

## 6. Docs

```bash
just docs-sync && just docs
sbt 'cli/testOnly *CliReferenceSuite' 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite'
sbt 'controlPlaneApi/testOnly *DocumentationDescriptorsSuite'
```

Expected: green; `limitations.md` no longer contains the four quoted statements;
`networking.md`'s port table matches `contracts/configuration.md`.

## 7. Overhead (SC-006)

```bash
sbt 'controlPlane/testOnly *VerificationOverheadBenchmark'     # extended with a TLS-on/off pair on the same path
```

Expected: median within 10%; the numbers are written into `docs/platform/networking.md`.
