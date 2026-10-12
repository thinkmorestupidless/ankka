# Quickstart: proving awaiting workflows works

Each feature file has one suite that runs it whole. Before trusting a green run, break the thing
once and watch it go red: the last column says how.

## Prerequisites

Docker (OrbStack on the development Mac) for the shared Postgres; `uv`, `node`/`npm` and `cargo`
for the SDK halves; the sidecar image built from this branch for the Python and TypeScript kits:

```bash
sbt docker:publishLocal                         # builds ankka-sidecar:<commit tag>
export ANKKA_SIDECAR_IMAGE=ankka-sidecar:$(git rev-parse --short HEAD)   # never :latest for a branch test
```

## The living features

| Feature file | Suite | Run | Red when |
|---|---|---|---|
| `features/awaiting-workflows/awaiting.feature` | `testkit/…/awaiting/AwaitingFeatures` | `sbt 'testkit/testOnly *AwaitingFeatures'` | make `answerWaiters` a no-op: every "answered with the state" scenario times out; make the engine poll the lifecycle: "not by asking again and again" fails on the recorder's count |
| `composition.feature` | `CompositionFeatures` | `sbt 'testkit/testOnly *CompositionFeatures'` | give `verify` a 2s timeout and `decision` 20s: the step must fail as timed out, and `kyc` must still reach its end |
| `instances.feature` | `InstancesFeatures` | `sbt 'testkit/testOnly *InstancesFeatures'` | remove the `PostStop` answer: the caller is still answered, but only after an ask timeout; remove the re-ask on `TimeoutException`: the caller gets `Timeout` |
| `serving.feature` | `ServingFeatures` (idle timeout 3s) | `sbt 'testkit/testOnly *ServingFeatures'` | drop the heartbeat: the SSE connection is cut before `ended`; the whole-answer scenario must be cut, so a passing SSE case with a passing whole-answer case means the idle timeout was not applied |
| `languages.feature` (Scala rows) | `LanguagesFeatures` | `sbt 'testkit/testOnly *LanguagesFeatures'` | Python and TypeScript rows report `ranElsewhere`; a run that shows them passed here has bound them to nothing |
| `languages.feature` (Python rows, and the old-runtime scenario) | `sdks/python/examples/shopping_cart/test_await.py`, `tests/test_await_old_runtime.py` | `cd sdks/python && uv run pytest -q examples/shopping_cart/test_await.py tests/test_await_old_runtime.py` | point `ANKKA_SIDECAR_IMAGE` at a 1.14 image: `await_end` must raise naming 1.15 |
| `languages.feature` (TypeScript rows) | `sdks/typescript/examples/shopping-cart/await.test.ts` | `cd sdks/typescript && npm run test:slow -- examples/shopping-cart/await.test.ts` | likewise |
| `features/documentation/awaiting-workflows.feature` | `testkit/…/AwaitingWorkflowsDocumentationSuite` | `sbt 'testkit/testOnly *AwaitingWorkflowsDocumentationSuite'` | delete the "Waiting for the end" section from `docs/build/workflows.md` |

The whole directory in one go:

```bash
caffeinate -i sbt 'testkit/testOnly *awaiting.*'
```

munit's filter matches the full name: a glob without the leading `*` matches nothing and reports
green with zero tests. Check the count.

## The topology scenario

"a wait is one call in the topology" reads the service's topology through the local observability
endpoint after one await. Expected: one observed call from the caller to `quote` with
`handled.ok = 1` and `unanswered.timedOut = 0`. Red when: count `handled` per `NotYet` (the count
grows with the wait's length) or count `unanswered` per attempt.

SC-002's independence from the wait's length is by construction (the answer is the `thenRun` after
the persist, not a poll) and is checked at two lengths, a 5-second `offer` in `awaiting.feature` and
a 20-second `margin` in `instances.feature`; no suite runs ten minutes.

## Conformance

```bash
sbt 'sidecar/testOnly *ConformanceSuite' -- '*wf.*'              # the Scala reference, in-process
cd sdks/python && uv run conformance                              # the Python reference
cd sdks/typescript && npm run conformance                         # the TypeScript reference
cd sdks/rust && ./conformance.sh                                  # the Rust module, both shapes
```

Expected: `wf.start-and-await` and `wf.await-failed` pass for every reference. Red when: a
reference answers the start's own reply instead of the end (the state lacks the last step).

## The protocol pins

```bash
sbt 'sidecar/testOnly *ProtocolSuite' 'controlPlaneApi/testOnly *CompatibilitySuite'
cd sdks/typescript && npm test -- test/contracts.test.ts
cd sdks/rust && cargo test -p ankka --test contracts_discovery
cd sdks/python && uv run pytest -q tests/test_topic_sources.py
diff -r protocol/src/main/protobuf sdks/python/proto/src/main/protobuf && diff -r protocol/src/main/protobuf sdks/typescript/proto/src/main/protobuf && diff -r protocol/src/main/protobuf sdks/rust/ankka/protocol/src/main/protobuf
```

Expected: every pin says 1.15; the three copies are identical.

## The error code

```bash
sbt 'grpc/testOnly *GrpcStatusSuite *GrpcClientsSuite' http/test
```

Expected: `GrpcStatus.codes == ErrorCode.values`; `HttpProblem.from(CommandError("x", WorkflowFailed, Map("step" -> "margin")))` is 424 with the details in the body.

## The sample

```bash
sbt shoppingCart/test
```

Expected: `CheckoutWorkflowSuite` has no `eventually` and asserts the state `awaitEnd` returned.

## The documentation

```bash
just docs-sync && just docs && just features
```

Expected: `docs check` passes with the new includes resolved, the skills re-rendered under
`marketplace/` and `ankka.g8/`, and the features check reports 0 findings over 49 specs. Red when:
a page mentions a feature number or a spec (`docs check` refuses it), or a new page is missing
from `mkdocs.yml`'s `nav` or every skill's `pages:`.

## Running it by hand

```bash
docker compose up -d
sbt shoppingCart/run                                                            # HTTP on :9000
curl -s -X POST localhost:9000/carts/c1/items -d '{"productId":"p1","name":"pen","quantity":1}'
curl -s -X POST localhost:9000/carts/c1/checkout-and-wait                      # answers the checkout's final state in one request
```

The local console (`ankka console`) shows one call from the endpoint to `checkout`, open for the
wait and recorded whole when it ends.
