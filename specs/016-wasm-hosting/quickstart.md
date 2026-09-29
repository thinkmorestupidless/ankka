# Quickstart: proving WebAssembly Hosting

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

How each story is shown to work, from the fastest proof to the slowest. Prerequisites: Docker
running, a Rust stable toolchain with the `wasm32-unknown-unknown` target (`rustup target add
wasm32-unknown-unknown`), Java 21 and sbt. On the development Mac, `cargo` is at
`/opt/homebrew/opt/rustup/bin`.

## 1. The host, alone (P1, P3 — FR-001 to FR-013)

```bash
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmHostSuite'
```

Expected: discovery from a test module; a missing export, a foreign import and an `ankka2_` prefix
each refused naming it; a streaming handler refused at discovery; a trap during a command answered
as a fault with the held state unchanged and the next command served by a fresh instance; a
blocking `invoke` from sixty-four instances completing in about one wait; `config` answering a
descriptor variable and refusing every reserved name.

## 2. The crate, natively (P1, P2 — FR-014, FR-015, FR-018)

```bash
cd sdks/rust && cargo test -p ankka
```

Expected: every encoding fixture decodes and re-encodes byte for byte with 0 skipped; a persisting
query fails to compile (`trybuild` case); duplicate wire names and a missing ACL reported together;
the unit testkit shows events, state, retention and replies as values.

## 3. The example through the runtime (P1, P2 — SC-006)

```bash
sbt -Dankka.cluster.tests=off sidecar/docker:publishLocal shoppingCart/docker:publishLocal
cd sdks/rust && cargo build -p shopping-cart --release --target wasm32-unknown-unknown
cargo test -p shopping-cart --features slow
```

Expected: the cart commanded over HTTP through the runtime container, state surviving `restart()`,
and the journal written by the Scala cart read by the Rust cart with equal state, and the reverse.

## 4. Conformance in both shapes (P3 — SC-002)

```bash
cd sdks/rust && ./conformance.sh
```

Expected: `ConformanceSuite` passes with 0 failures against `wasm:<module>` stateless and stateful.
Then break one behaviour in `src/conformance.rs` and rerun: exactly that case fails.

## 5. The spike's number, held (SC-003, SC-005)

```bash
caffeinate -i sbt -Dankka.benchmarks=true -Dankka.cluster.tests=off 'sidecar/testOnly *WasmHostSpike *LoopbackLatencySpike'
```

Expected: the Rust guest's 1 KB `handle` under half the loopback hop's p50; the sixty-four-guest
block within one and a half holds. The spike is the benchmark; SC-003 is recorded from it by T056
rather than asserted, and `WasmHostSuite` carries the SC-005 assertion.

## 6. Locally, as a developer would (P1, P5 — SC-007)

```bash
ankka init orders --language rust && cd orders
cargo test                                   # natively: the entity, the view, the API
cargo module                                 # the module: target/wasm32-unknown-unknown/release/orders.wasm
docker compose up -d runtime                 # Postgres and the runtime with the module mounted
curl -X POST localhost:9000/items/o-1 -H 'content-type: application/json' -d '{"name":"pen","count":2}'
curl localhost:9000/items/o-1
```

Expected: an entity commanded through the runtime with no JVM installed, inside the page's fifteen
minutes; `docker compose restart runtime` and the item is still there. Before the first release
carrying the crate, point the project at this tree's crate (`ankka = { path = "…/sdks/rust/ankka" }`)
and the runtime at this tree's image (`ANKKA_SIDECAR_IMAGE=ankka-sidecar:latest`), as
`RustTemplateSuite` does.

## 7. On the platform (P4 — SC-009, SC-010)

```bash
sbt 'sidecar/testOnly *SidecarClusterSuite'             # the wasm cases, k3s 1.35.1
```

Expected: one container plus one init container; `Ready`; the cart commanded through the gateway;
scale 1→3 leaves the first pod; a restart replaces pods one at a time with 0 refused requests; an
image whose module carries the wrong ABI prefix reported `Failed` with the reason; the reserved
variable invisible from inside the module while a descriptor variable is visible.

By hand, on the local installation:

```bash
cd sdks/rust && cargo build -p shopping-cart --release --target wasm32-unknown-unknown
docker build -f examples/shopping-cart/Dockerfile -t sample-shopping-cart-rust:latest .
kind load docker-image sample-shopping-cart-rust:latest --name ankka
ankka services apply -f examples/shopping-cart/service.json -p checkout && ankka services get cart -p checkout
```

## 8. The template and the release plumbing (P5 — SC-011, SC-012)

```bash
sbt -Dankka.cluster.tests=off -Dankka.template.tests=rust 'cli/testOnly *RustTemplateSuite'
cd sdks/rust && cargo package -p ankka --allow-dirty && ls target/package
```

Expected: the rendered project builds, passes clippy and its tests with 0 skipped against this
tree's crate and runtime image; the packaged crate builds alone for the WebAssembly target. The
release job is proven by the first tag, after the manual first publish in
[contracts/ci-and-release.md](./contracts/ci-and-release.md).

## 9. The documentation (P5 — FR-034 to FR-037)

```bash
just docs-sync && just docs
```

Expected: every included Rust sample current; the new pages in the nav and a skill; `languages:`
tab sets balanced; the limitations page carrying the mode's four lines.
