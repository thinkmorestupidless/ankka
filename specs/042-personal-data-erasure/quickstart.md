# Quickstart: validating personal data erasure

How to prove the feature works, from the fastest checks to the cluster. Every acceptance scenario in
`features/erasure/` (and the four changed or added in `features/installation/facts.feature`) is run
by one of the suites below; `GherkinSuite` names each one it delegates elsewhere in `ranElsewhere`.

## Prerequisites

Docker (OrbStack), `uv`, Node ≥ 22.22, Go, `cargo` with `wasm32-unknown-unknown`, and for the
cluster suites the `cluster` workflow (`gh workflow run cluster --ref 042-personal-data-erasure-design`).

## 1. The codec and the fixtures (seconds)

```bash
sbt 'core/testOnly *Personal*'              # envelope fixtures, subjects, AD binding, toString
cd sdks/python && uv run pytest -q -k personal
cd sdks/typescript && npm test -- personal
cd sdks/go && go test ./... -run Personal
cd sdks/rust && cargo test -p ankka personal
sbt 'sdkJava/testOnly *Personal*'           # record codec and Kotlin
```

Expect: every SDK writes `protocol/fixtures/personal/envelopes.json` byte for byte, opens it, and
reports the spliced envelope as corrupt, not erased.

## 2. In-process features (about a minute)

```bash
sbt 'testkit/testOnly *ErasureFeatures'     # GherkinSuite over features/erasure/ (in-process scenarios)
sbt 'testkit/testOnly *PersonalLeakSuite'   # SC-009: no plain value in any log or span
sbt 'testkit/testOnly *KeyringOutageSuite'  # SC-010 with kit.keyring.outage
```

Expect: `personal-fields`, `erasing`, `holds` (with a test clock), `agents`, `asking`, `places` (CLI
and MCP), the in-process half of `other-projects` and `replication` (two kits, the test relay) green;
`kit.assertNoPersonalValue` passes after an erasure and fails when the codec is bypassed (break it
once to see it go red).

## 3. Recovery cost (SC-007)

```bash
sbt 'testkit/testOnly *PersonalRecoveryBenchmark'
```

Expect: the ratio printed is ≤ 1.3 for 1,000 events of one subject, against a local keyring.

## 4. Polyglot conformance (SC-008)

```bash
sbt 'sidecar/testOnly *ConformanceSuite'
cd sdks/python && uv run conformance
cd sdks/typescript && npm run conformance
cd sdks/go/examples/shopping-cart && go run ./cmd/conformance
cd sdks/rust && ./conformance.sh
```

Expect: the `personal.*` and `erasure.*` cases green for every reference, including
`personal.sidecar-reads-nothing` (the sidecar's payload path given an envelope it cannot parse).

## 5. The keyring and the control plane (offline)

```bash
sbt keyring/test                            # store, wrap, destroy+vacuum, tombstone, admission, log replay
sbt 'controlPlane/testOnly *Erasure*'       # entity, routes, sweep, certificate, facts
just test-console                           # the Erasures section against the fake control plane
```

## 6. Cluster (k3s, via CI)

```bash
gh workflow run cluster --ref 042-personal-data-erasure-design -f suite=ErasureClusterFeatures
gh workflow run cluster --ref 042-personal-data-erasure-design -f suite=ErasureRestoreClusterFeatures
gh workflow run cluster --ref 042-personal-data-erasure-design -f suite=ObjectStorageGcsClusterFeatures
```

Expect: the keyring component deployed with two instances; story 2 across two services; story 3's
two restore cases (project database, keyring database); objects on Garage; the outside machine's
`Decrypt` through the gateway; the descriptor refused with erasure off.

## 7. Docs and features

```bash
just features                               # 0 findings
just docs                                   # every page checks; samples from tested code
```

## By hand, once

On a laptop: `sbt shoppingCart/run` with a personal field added to the sample, `ankka local erasures
file cart/1`, then read the cart in the local console: the field shows `Personal(cart/1)` and the
recorder shows no value.
