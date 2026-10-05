# Quickstart: validating WebAssembly Request and Clock

How to see each user story hold, from this repository, once 025 is on `main` and this branch is
rebased onto it. Docker is required for the conformance and cluster runs; cargo for everything
that builds the Rust reference. Contracts are in [contracts/](contracts/); the scenarios these
runs prove are under `features/wasm/` and `features/documentation/modules.feature`.

## The fast loop

```bash
sbt 'sidecar/testOnly *WasmImportsSuite'
cd sdks/rust && cargo test -p ankka
```

The first needs no cargo and no Docker: guests written by hand, and a stand-in for the client
that records what it is asked. Expect one case per export of the ABI, the permitted ones answering
the stand-in's reply and every other trapping with nothing recorded; the clock read as the value the test fixed; two fills
that differ. The second is the crate natively: `services()` by kind, the scripted services, the
fixed clock and the fixed random.

To see the rule can fail, add `handle` to `CallSite.Permitted` and run the first line again: the
command case goes red, with a request in the stand-in's record.

## User Story 1 — a module calls another service as itself

```bash
cd sdks/rust && ./conformance.sh                                    # both guest shapes
ANKKA_CONFORMANCE_ONLY='*service.*' ./conformance.sh                # only the calls
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmHostSuite'
```

Expect 025's `service.*` cases to run against the module and not report as skipped: read the
count, since a filter that matched nothing is green too. In `WasmHostSuite`'s end-to-end case,
expect the relay consumer's call in the scripted service's record with the method, path and body
the test asked for, and the answer back in `service-asks`.

On a cluster:

```bash
caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'
```

Expect "a module's consumer is admitted by name by a route that admits only its service": the
answer recorded in the module names the module's service and its project, and the same route
asked directly from another service's pod answers 403.

## User Story 2 — a command cannot wait on the network

`WasmImportsSuite` for every export, and in `WasmHostSuite`'s end-to-end case the Rust reference's
`ask-in-command`. Expect the command answered `Internal` with a message holding `request`, the
component and the command's name; the scripted service's record empty; and the next command to
the same entity answered with the state the commands before it left, read by its contents.

## User Story 3 — the time and random bytes

Same two suites. Expect a step's time equal to the fixed clock's value in `WasmImportsSuite`, and
in the end-to-end case between two readings the test takes of the runtime's clock either side of
the step. Expect `ankka.now` on the metadata of every export that carries metadata, the step, the
tool, the guardrail and the result check among them.

The two prebuilt spike guests under `sidecar/src/test/resources/wasm/` were built before this
feature; their cases in `WasmHostSuite` pass unchanged.

What links what:

```bash
cd sdks/rust
cargo build -p shopping-cart --release --target wasm32-unknown-unknown
wasm-objdump -j Import -x target/wasm32-unknown-unknown/release/shopping_cart.wasm | grep -E 'request|now|random'
```

Expect `now` alone. With `--features conformance`, all three.

## User Story 4 — the documentation

```bash
just docs-sync && just docs
just features
```

Expect the ABI reference to list fourteen imports and the table of exports; the Rust SDK reference
to include `service-call` from the reference; the limitations page to say a module cannot be
interrupted and nothing about a module having no clock.

## The whole build

```bash
sbt scalafmtCheckAll compile
sbt -Dankka.cluster.tests=off test
cd sdks/rust && cargo fmt --all --check && cargo clippy --workspace --all-targets --features ankka/testkit -- -D warnings && cargo test --workspace && ./conformance.sh
cd sdks/python && uv run pytest -q          # the protocol copy is refreshed; nothing else changes
cd sdks/typescript && npm test              # likewise
```
