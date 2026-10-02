# Quickstart: validating the graph delta publisher

The runs that show the feature works, cheapest first. Each tier names what it proves. Commands are
from the repository root unless a directory is given. Docker is needed from tier 2.

Contracts: [protocol](contracts/protocol.md), [builder](contracts/graph-builder.md),
[key value deletion](contracts/key-value-deletion.md), and one per language under `contracts/`.

## Tier 0 — the fixtures exist in ankka-flow

In the ankka-flow repository: `protocol/fixtures/graph-deltas/deltas.json` is read by the sink's
`DeltasSuite` and by the Python SDK's `test_graph.py`, and both pass. Then here:

```bash
diff <ankka-flow>/protocol/fixtures/graph-deltas/keys.json   protocol/fixtures/graph-deltas/keys.json
diff <ankka-flow>/protocol/fixtures/graph-deltas/deltas.json protocol/fixtures/graph-deltas/deltas.json
```

No output. `protocol/fixtures/graph-deltas/SOURCE.md` names the ankka-flow tag.

## Tier 1 — pure suites, seconds

```bash
sbt core/test sdk/test
```

Proves: the delta codec and reader against the three fixture files; the builder's refusals;
element keys; `Outgoing` and `ProduceAll` as data.

## Tier 2 — the runtime, against Postgres and the in-memory broker

```bash
sbt -Dankka.cluster.tests=off testkit/test sidecar/test
```

Proves: several messages in order under their keys with the subject unchanged; the default key;
the empty list; redelivery when a publication fails, for all three kinds of source; the 4 MiB
bound; a single `produce` unchanged; `ConsumerTestKit` and its graph form; a key value consumer
handed the revision; a key value deletion as a recorded state, reaching a view and a consumer,
with a write afterwards; the stored form pinned; the remote path (`RemoteProjectionSuite`) and
the WebAssembly host (`WasmHostSuite`) carrying `produce_all` and stamping `ankka.protocol`.

## Tier 3 — a real broker

```bash
sbt 'testkit/testOnly *KafkaSuite'
```

Proves: the record key on Kafka is the named key, and `ce-subject` is still the entity's id.

## Tier 4 — the sample

```bash
sbt shoppingCart/test
```

Proves: `CartGraph` publishes the cart graph for a scripted history, with no key, version or JSON
written in the sample (SC-004); its unit tests start nothing.

## Tier 5 — every SDK, and conformance

```bash
cd sdks/python     && uv sync && uv run python scripts/proto.py && uv run mypy && uv run pytest -q && uv run conformance
cd sdks/typescript && npm ci && npm run proto && npm run typecheck && npm test && npm run conformance
cd sdks/rust       && ./scripts/proto.sh && cargo test --workspace && cargo test -p shopping-cart --features slow && ./conformance.sh
sbt 'sidecar/testOnly *ConformanceSuite'
```

Proves: each SDK's builder against the fixtures; each SDK's guard on `ankka.protocol`; each test
kit; and, through the conformance cases of [contracts/protocol.md](contracts/protocol.md), that
the four publish the same records for the same history (SC-003a).

Check before trusting a green run: `ANKKA_CONFORMANCE_ONLY='consumer.graph-deltas'` runs exactly
one case, and that case fails against a reference with `cart-graph` removed.

## Tier 6 — documentation

```bash
just docs-sync && just docs
```

Proves: the new guide and the changed pages build; every included sample matches its tested
source; the skills are current.

## Tier 7 — a graph in the database, with no mapper

On the local cluster with ankka and ankka-flow (0.3.0 or later) deployed, and ankka-flow's
development graph database:

1. Deploy the pipeline in `samples/shopping-cart/graph/` — the built-in merge sink alone, reading
   the managed topic `cart-graph` — and confirm the topic exists and is compacted.
2. Deploy the shopping cart with `ANKKA_KAFKA_BOOTSTRAP_SERVERS` set, so `CartGraph` is
   registered.
3. Run the script of creations, changes, checkouts and discards. The graph holds the expected
   nodes and edges at the expected versions; discarded carts are marked deleted; the sink has not
   stalled (SC-005).
4. Read the topic: every record's key is its element key.
5. Restart the service's pods mid-run and compare with an uninterrupted run (US4.4).
6. Replay: reset the consumer's offset and let it run again. The sink counts every delta stale and
   writes nothing (SC-006).
7. Rebuild: empty the database, reset the sink alone, compare (SC-007), following ankka-flow's
   guide to rebuilding a graph.
8. Discard a cart and add to it again: its node is live, above the tombstone (SC-008).
9. Repeat steps 2–4 with the Python example behind a sidecar and with the Rust module, each in
   place of the Scala service, into an emptied database. The graph is the same.

Record what was run, and the result, in the sample's README.

## Reviewer's checklist

- [ ] `consumer.proto`'s `Produce` is byte for byte what it was; `git diff` of the message shows
      only additions around it
- [ ] the three SDK copies of `protocol/` are identical to the canonical one
- [ ] `protocol/fixtures/` at its top level is untouched; `EncodingFixturesSuite` passes
- [ ] no module gained a dependency; `runtime` and `sidecar` contain no reference to graphs
- [ ] the suite pinning `StateRecord` was committed before the hosts changed
- [ ] each new conformance case was seen to fail without the feature
- [ ] `sbt compile` is warning-free; `scalafmtCheckAll`; `cargo clippy -D warnings`; `mypy`; `tsc`
- [ ] the sample and the three examples contain no literal `node:` or `edge:` key and no version
- [ ] the guide states the writer's rules, who creates the topic, and what expiry does not do
- [ ] release notes: protocol 1.3; key value deletion now a recorded change; Rust's new enum variant
