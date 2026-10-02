# Quickstart: Validating Service Topology

How to prove the feature end to end. The document shapes are in `contracts/`, and the design
reasons are in `research.md`.

## Prerequisites

Docker, sbt, and `ankka` on the PATH (or `sbt 'cli/run …'`). For phase 2: kind, kubectl, and a
cluster created by `just up`.

## 1. Declared topology, local (US1)

```bash
docker compose up -d
ANKKA_CLUSTER_PORT=17355 sbt shoppingCart/run         # terminal 1
ankka local console                                    # terminal 2
```

Open the console, choose the cart service, then the **Topology** tab. Expect, **before sending any
request**:
- the endpoint, the cart entity, each view and consumer of the sample, and their topics, as nodes
  in columns;
- a solid edge from the cart entity to every view and consumer sourced from its events;
- no dashed edges, and the line *"Calls observed in the last 10 minutes … not shown"*.

Raw check:

```bash
curl -s "$(jq -r .address ~/.ankka/running/*.json | head -1)/observability/topology" | jq '.declared'
```

## 2. Observed calls (US2)

```bash
curl -s -XPOST localhost:9000/carts/c1/items -d '{"productId":"p1","name":"Pen","quantity":1}' -H 'content-type: application/json'
curl -s -XPOST localhost:9000/carts/c1/items -d '{"productId":"p1","name":"Pen","quantity":0}' -H 'content-type: application/json'   # refused
```

Within 3 s, expect a dashed edge from the endpoint to the cart, pair
`POST /carts/{cartId}/items → add-item`, with `handled.ok = 1` and `handled.refused = 1`. The edge
carries **no** warning mark, and no `c1` appears anywhere in the document
(`curl …/observability/topology | grep -c c1` prints `0`).

## 3. Polyglot parity (FR-004; the two "in every language" scenarios)

```bash
sbt 'sidecar/testOnly *ConformanceSuite -- *topology*'
cd sdks/python && uv run conformance
cd sdks/typescript && npm run conformance
cd sdks/rust && ./conformance.sh
```

All three report the `topology.declared-sources` and `topology.call-attributed` cases as **passed**,
not ignored. A filter that matches nothing reports green with zero tests, so check the count.

## 4. Unknown caller and bounds

```bash
sbt 'testkit/testOnly *TopologySuite'
sbt -Dankka.benchmarks=on 'testkit/testOnly *ServiceRecordingCostSuite'
```

- `TopologySuite` covers:
  - the thread hand-off case: one edge from `unknown`, none from any component;
  - the thrown-handler case: `handled.failed = 1` and `unanswered.timedOut = 1` on one pair;
  - ten thousand distinct entity ids through the real call path: the edge count is unchanged.
- `ServiceRecordingCostSuite` reports recording plus counting below 1% of a real invocation
  (SC-004).

## 5. Deployed topology (US4)

```bash
just up
ankka apply -f samples/shopping-cart/service.json -p checkout   # with "minInstances": 2
# send a few requests through the gateway, then:
ankka services topology shopping-cart -p checkout
```

Expect *2 of 2 instances*, the same nodes and declared edges as step 1, and call counts summed
across both pods. Then open the console at `https://console.127.0.0.1.sslip.io:8443`, go to the
service, then **Topology**, and see the same.

Isolation (all must fail):

```bash
# a workload's own certificate is refused on observe
just exec-in-pod shopping-cart -- curl --cert /var/run/secrets/ankka/service/tls.crt \
  --key /var/run/secrets/ankka/service/tls.key --cacert /var/run/secrets/ankka/service/ca.crt \
  https://<other-pod-ip>:7628/observability/topology          # TLS alert: certificate unknown
# a pod in another project's namespace cannot connect at all          # connection timed out
```

(`EndToEndClusterSuite` performs both through `InPod.curl`. `just exec-in-pod` is illustrative and is
not a recipe this feature adds.)

## 6. Partial and differences

Roll the service to a build whose registry differs, and read the topology during the rollout.
Expect:
- `differences` naming the node and the pods that have it;
- *partial*, naming any pod that did not answer;
- an exit code of 0 from the CLI.
