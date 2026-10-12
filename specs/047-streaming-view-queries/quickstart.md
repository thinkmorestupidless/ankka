# Quickstart: seeing view streams work

Each step proves one part end to end; run them in order, each is green before the next matters.
Docker is required for everything but the first.

## 1. The pure parts (seconds)

```bash
sbt 'runtime/testOnly *KeyedBufferSuite *QueryCheckSuite'
```

Expect: every overflow strategy case passes (`drop-head keeps the newest keys`, `fail ends unread`);
`QueryCheckSuite`'s watched cases refuse a statement without `row_key`, with a `LIMIT`, with a
`GROUP BY`, each naming its rule, and admit `ORDER BY` and a recursive `WITH`.

## 2. The database (a minute)

```bash
sbt 'testkit/testOnly *DatabaseStreamSuite *ViewAnnounceSuite *ViewListenerSuite'
```

Expect: a 5 000-row stream arrives in order past the whole limit; fifty cancelled streams leave the pool
able to answer a query; a `pg_sleep` statement fails `Timeout`; a stream not read for the statement
timeout fails `Timeout` naming it; every write path raises `ankka_views` with `<table>|<key>`; a rebuild
raises `<table>|!rebuilt`; a dropped listener connection ends the open watches `ListenerLost` and the
next watch reconnects.

## 3. The living features (a few minutes)

```bash
sbt 'testkit/testOnly *RowStreamFeatures *WatchingFeatures *WatchRulesFeatures'
```

Expect: every scenario of `features/view-streams/{row-streams,watching,watch-rules}.feature` green and
none ignored (`GherkinSuite` fails a directory with no scenarios; `ranElsewhere` must name nothing here).
The two-instance scenarios start a peer; the stopping-instance scenario stops the service and reads the
reason.

## 4. Served (a minute)

```bash
sbt 'testkit/testOnly *SseHeartbeatSuite' 'shoppingCart/testOnly *CartGrpcMoreSuite'
sbt 'grpc/test'                       # features/grpc/streaming.feature still passes against the rewritten WatchCart
```

Expect: a quiet SSE stream outlives a 2-second idle timeout; `WatchCart` is sent the cart and again after
it changes, with no `Source.tick` left in `CartStreamsEndpoint`.

## 5. Every language (several minutes; Docker builds the sidecar image)

```bash
sbt sidecar/Docker/publishLocal
sbt 'sidecar/testOnly *ConformanceSuite -- *view.*'          # the Scala reference, in process
cd sdks/python && uv sync && uv run python scripts/proto.py && uv run pytest -q && uv run mypy && uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
sbt 'sidecar/testOnly *WasmHostSuite'                         # the module refusal names "reads a view whole"
```

Expect: `view.stream-all`, `view.stream-named`, `view.watch-named`, `view.watch-row` pass against all
three process references; `view.watched-refused-by-old-runtime` passes; the Rust run skips the streaming
cases (`onlyWhereStreaming`) and `WasmHostSuite` holds the refusal. The glob needs its leading wildcard
or the suite reports green with zero tests.

## 6. By hand, locally

```bash
docker compose up -d
sbt shoppingCart/run
curl -N http://localhost:9000/carts/open          # the open carts, then `event: caught-up`, then live events
# in another shell:
curl -X POST localhost:9000/carts/c9/items -d '{"productId":"p1","name":"x","quantity":1}'
curl -X POST localhost:9000/carts/c9/checkout     # the first shell shows `event: removed` {"key":"c9"}
```

Expect a comment line (`:`) every 15 seconds while nothing changes, and the stream still open after a
minute. The local console (`ankka console`) shows one observed call `carts-endpoint → carts (stream)`
after the `curl` ends.

## 7. Documentation and features

```bash
just docs-sync && just docs && just features
sbt 'testkit/testOnly *ViewStreamsDocumentationSuite'
```

Expect: `docs check` passes with the four new variables described; the checker reports 0 findings; the
documentation suite finds every statement `features/documentation/view-streams.feature` names.

## 8. The platform (CI, not a laptop)

```bash
gh workflow run cluster --ref 047-streaming-view-queries-impl -f suite=SidecarClusterSuite
```

Expect: the sidecar's k3s suite green — it is the one run where the listener connects over the database's
TLS with a client certificate (research V1).
