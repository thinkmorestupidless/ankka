# The shopping cart, in Rust

The cart every ankka SDK carries: an event sourced entity and the HTTP API in front of it, built to
a WebAssembly module the ankka runtime loads and hosts. It stores exactly what the Scala and Python
carts store — the same manifests, the same JSON — so the three can share one journal.

## Build it

From `sdks/rust`:

```bash
cargo build -p shopping-cart --release --target wasm32-unknown-unknown
# → target/wasm32-unknown-unknown/release/shopping_cart.wasm
```

From this directory `cargo build --release` does the same: `.cargo/config.toml` here sets the
target, and the workspace's sets the stack size.

## Run it

The repository's compose file has a `wasm` profile: Postgres, and the runtime image with this
module bind-mounted where it loads it from. The runtime image is built from this checkout once
(`sbt sidecar/Docker/publishLocal` at the repository root).

```bash
docker compose --profile wasm up -d          # from the repository root
curl -XPOST localhost:9000/carts/c1/items -H 'content-type: application/json' \
  -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
```

`ANKKA_WASM_MODULE_PATH` points the profile at another build of a module.

## Test it

Run the tests from `sdks/rust`, not from this directory: here cargo builds for the WebAssembly
target, where a test cannot run.

```bash
cargo test -p shopping-cart                  # the entity and the API, natively; no Docker
cargo test -p shopping-cart --features slow  # the module in the runtime against Postgres (Docker)
```

The fast tests use the unit testkit (`EventSourcedTestKit`, `EndpointTestKit`). The slow ones
build the module, start Postgres and the runtime image, drive the API, restart the runtime and
read the cart back from the journal, and — `journal_portable` — hand one journal back and forth
with the Scala cart's image (`sample-shopping-cart:latest`, from `sbt shoppingCart/Docker/publishLocal`,
or `ANKKA_SCALA_CART_IMAGE`). The runtime image is `ANKKA_SIDECAR_IMAGE` when that is set, else
`ankka-sidecar:latest` for this unreleased checkout. The slow tests are compiled out without the
feature rather than skipped, so a plain `cargo test` reports nothing it did not run.
