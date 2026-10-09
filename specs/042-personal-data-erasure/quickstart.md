# Quickstart: proving personal data erasure

Prerequisites: Docker (OrbStack on the development Mac), `uv`, `node`/`npm`, `cargo` with the
`wasm32-unknown-unknown` target, `kubectl` for the overlay suites; `caffeinate -i` in front of
anything that takes minutes.

## Offline, in minutes

```bash
sbt 'core/testOnly *PersonalCodecSuite *PersonalFixturesSuite'   # the envelope, the decode table, the fixtures
sbt 'keyring/test'                                               # the keyring in-process: wrapping, admission, the channel, replay with one and two copies
sbt 'testkit/testOnly *erasure.*'                                # features/erasure: personal-fields, erasing, agents, restores (template copy), objects (Garage), other-projects (test-kit grants)
sbt 'testkit/testOnly *PersonalReplayBenchmark'                  # SC-007: 1,000 events, ≤ 1.3×
sbt 'testkit/testOnly *PersonalLeakSuite'                        # SC-009: the captured log, the recorder and the local console hold no plaintext
sbt 'controlPlane/testOnly *Erasure* *erasure.*'                 # requests, holds, the sweeper, the log, the CLI, the reference pages
sbt 'operator/testOnly *Rendering* *PlatformBucket*'             # ANKKA_KEYRING_URL, ANKKA_S3_ on the platform container, the platform bucket
sbt 'sidecar/testOnly *ConformanceSuite -- "*personal.*"'        # the Scala reference writes and reads the envelope
sbt 'sidecar/testOnly *WasmHostSuite *WasmImportsSuite'          # the three imports and the export
cd sdks/python && uv run pytest -q && uv run conformance          # Personal in Python; ANKKA_CONFORMANCE_ONLY='*personal.*' for the cases alone
cd sdks/typescript && npm test && npm run conformance
cd sdks/rust && cargo test --workspace && ./conformance.sh
just features && just docs                                       # the living features and the glossary; every page, the reference tables
```

Expected: every suite green with no skip; the conformance runs report the `personal.*` cases ran
(a filter that matches nothing reports green with zero tests — read the count); the benchmark prints
both durations and the ratio.

## On k3s

```bash
caffeinate -i sbt 'set controlPlane / Test / logBuffered := false' 'controlPlane/testOnly *ErasureClusterFeatures'
```

Deploys the keyring component, a players service with personal fields, Garage, and proves: a
channel per instance over mutual TLS; a destroy read as erased by every instance within 60 seconds;
a redacted view; `erase` on Garage; one service rolled during an erasure; the control plane's
identity refused a key. The two restore scenarios are `@ranElsewhere` until 041 lands.

Expected: every scenario passes; the suite's log shows `keyring replay: copies=2 behind=neither`.

## By hand, on compose

```bash
sbt docker:publishLocal                                          # builds ankka-keyring beside the others
ANKKA_KEYRING_SECRET_KEY=$(openssl rand -base64 32) docker compose up -d
ANKKA_KEYRING_URL=http://localhost:9020 sbt shoppingCart/run     # the cart's customer name is a personal field
curl -s localhost:9000/carts/c1/customer -d '{"name":"Ada Byron"}'
docker compose exec postgres psql -U ankka -c "select payload from event_journal" | grep -c 'Ada Byron'   # expected: 0
ankka projects erasures request customer/c1 --keyring http://localhost:9020 -p local
curl -s localhost:9000/carts/c1 | jq .customer                   # expected: {"subject":"customer/c1","project":"local"} — erased
```

Expected: the journal holds the envelope and never the name; after the request the cart reads the
customer as erased and the amounts unchanged; `docker compose logs keyring` shows the destroy and
one `ack` from the cart.

## When a dependency lands

- 040: run `controlPlane/testOnly *AskingFeatures *OtherProjectsClusterFeatures`.
- 041: remove `@ranElsewhere` from the two restore scenarios and run the cluster suite.
- 039: run the GCS suite's `objects` cases.
