# Quickstart: proving web hosting works

How each story is shown to hold, in the order they are built. Commands are run from the repository
root. Contracts are in `contracts/`; shapes in `data-model.md`.

## Before anything: the two spikes

```bash
sbt -Dankka.spikes=on 'proxy/testOnly *ProxyTlsSpike'   # research R2; without the switch it runs nothing
sbt 'http/testOnly *CallerIdentitySuite'   # research R3: the mount identity, and today's parser frozen
```

`ProxyTlsSpike` must pass its five points before `proxy-core` is built on the JDK's server. If it
does not, R2's fallback applies and no contract changes.

`CallerIdentitySuite` must show the frozen copy of today's parser refusing
`ankka://shop/web/mount`. Break it once to see it fail: change the fixture URI to
`ankka://shop/web?mount` and the frozen parser reads it as the service.

## Story 1: deployed with the CLI

Fast, no cluster:

```bash
sbt 'controlPlaneApi/testOnly *HostingSuite *DescriptorSuite'
sbt 'operator/testOnly *WebHostingRenderingSuite *RenderingUnchangedSuite *CrdSchemaSuite'
                                           # *RenderingGoldenSuite, if feature 020's suite is what pins rendering
sbt 'proxy/testOnly *RequestsFeature'      # requests.feature, against the real proxy on loopback
sbt 'controlPlane/testOnly *DescriptorFeatures'
```

On k3s (minutes; run under `caffeinate -i`):

```bash
sbt 'controlPlane/testOnly *WebHostingClusterFeatures'   # deploying.feature; isolation.feature from story 2
```

By hand, on the local installation:

```bash
just up                                    # then create a project, as its last lines print
ankka projects create shop
ankka services apply -f samples/shopping-cart-web/service.json -p shop
ankka services get cart-web -p shop        # hosting web, database none, Ready
ankka services expose cart-web -p shop
curl --cacert ~/.ankka/local-ca.crt https://cart-web-shop.127.0.0.1.sslip.io:8443/
ankka services logs cart-web -p shop
ankka services logs cart-web -p shop --platform
```

Expected: the page; the process's own output; then the proxy's.

Could it pass while false? `Ready` alone could, for a pod whose probe passes for the wrong reason.
The k3s feature therefore asserts on a page fetched through the gateway, and the never-listening
case asserts the detail names the port.

## Story 2: the process calls services as itself

```bash
sbt 'proxy/testOnly *CallingServicesFeature'   # calling-services.feature
```

By hand: the cart's interface shows a line its server read from the cart. Then, from a pod of
another service in the project:

```bash
kubectl -n ankka-shop exec deploy/orders -- curl -s --cert … https://cart-web:9000/
```

Expected: 403 with `X-Ankka-Answered-By: proxy`, because `cart-web` admits no service but itself.

## Story 3: mounts

```bash
sbt 'proxy/testOnly *MountsFeature'        # mounts.feature, several mounts and a shared backend among them
```

By hand, with the cart applied and **not** exposed:

```bash
echo '{"name":"cart","service":{"image":"sample-shopping-cart:latest"}}' | ankka services apply -f - -p shop
ankka services get cart -p shop | grep hostname      # not exposed
curl --cacert ~/.ankka/local-ca.crt -X POST \
  https://cart-web-shop.127.0.0.1.sslip.io:8443/api/cart/carts/c1/items -H 'Content-Type: application/json' -d '{"productId":"p1","name":"Pen","quantity":1}'
ankka services get cart-web -p shop                  # mounts  /api/cart → cart
```

The older-runtime case, which no suite can run because a suite may not name an image by tag:

```bash
# a release from before this feature, as the mounted service
ankka services apply -f - -p shop <<'EOF'
{ "name": "old-cart", "service": { "image": "ghcr.io/thinkmorestupidless/sample-shopping-cart:<previous release>" } }
EOF
# mount it under the interface, then:
curl … https://cart-web-shop.127.0.0.1.sslip.io:8443/api/old-cart/carts/c1
```

Expected: 403 `unrecognised caller certificate`, from the old cart. Never a cart.

## Story 4: on a developer's machine

```bash
sbt 'cli/testOnly *LocalWebSuite'
```

By hand, with nothing in a cluster:

```bash
sbt shoppingCart/run &                                  # the cart, on :9000
cd samples/shopping-cart-web && npm ci
ankka local web --service cart=http://127.0.0.1:9000 -- npm run dev
                                                        # http://127.0.0.1:3000; the process gets a free port.
                                                        # --service is needed only if the cart is not announced as "cart"
curl http://127.0.0.1:3000/api/cart/carts/c1
```

Stop the cart and repeat: 503, naming `cart`, with `X-Ankka-Answered-By: proxy`.

## Story 5: the template, the sample, the documentation

```bash
sbt -Dankka.cluster.tests=off -Dankka.template.tests=web 'cli/testOnly *WebTemplateSuite'
cd samples/shopping-cart-web && npm ci && npm run typecheck && npm test
sbt 'controlPlane/testOnly *SampleDeploymentClusterSuite'
just docs
GRAALVM_HOME=… sbt cli/GraalVMNativeImage/packageBin && cli/native-smoke.sh cli/target/graalvm-native-image/ankka
```

`WebTemplateSuite` renders the project through `Main.run`, runs its type check and tests insisting
none was skipped, builds it, and requests both the mount and the server's call through the real
`ankka local web` against a stand-in backend.

## The success criteria

| Criterion | Shown by |
|---|---|
| SC-001 one tool | the by-hand run of story 1 uses `ankka` and `curl` only |
| SC-002 fifteen minutes | the guide, timed once by someone who did not write it |
| SC-003 ready in thirty seconds | `WebHostingClusterFeatures`, timed from apply with the image on the node |
| SC-004 the cart has no address | `SampleDeploymentClusterSuite`: the cart is not exposed and has no hostname, and every cart operation works through the interface |
| SC-005 no refused request | `deploying.feature`'s outline, 200 requests, at one and at three instances |
| SC-006 the same addresses | `WebTemplateSuite` and the sample use one `service.json` locally and deployed |
| SC-007 under five milliseconds | `ProxyBenchmark` in `proxy`, against a real process on loopback, under `-Dankka.benchmarks` |
| SC-008 every refusal names its field | `DescriptorSuite`, one case per row of the contract's table |
| SC-009 a request cannot choose who it is | `ProxyFeatures`: the two "cannot say" scenarios |
| SC-010 nothing restarts | `RenderingUnchangedSuite` (`unchanged.feature`), and the existing k3s reconcile case |

## The checks that gate a merge

```bash
sbt scalafmtCheckAll scalafmtSbtCheck
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off test
.github/features-check.sh            # from feature 019
just docs
just test-console
```
