# ankka for Rust

The Rust guest library for ankka: a service written in Rust is built to a WebAssembly module
(`wasm32-unknown-unknown`), and the ankka runtime loads it in-process and hosts every component it
declares. `ankka/` is the crate published to crates.io as `ankka`; `examples/shopping-cart` is the
shopping cart every SDK carries, and the conformance reference.

```bash
cargo test -p ankka                        # the library: codec, fixtures, testkits
cargo build -p shopping-cart --release --target wasm32-unknown-unknown   # the example as a module
./scripts/proto.sh                         # refresh the copy of the protocol from ../../protocol
./conformance.sh                           # the platform's conformance suite against the example, both shapes
ANKKA_CONFORMANCE_SECRETS=secret-manager ./conformance.sh   # its secret cases on the Secret Manager backend
```

The guide is the documentation site's *Your first service in Rust* and *Rust SDK* reference pages
(`docs/get-started/first-service-rust.md`, `docs/reference/rust-sdk.md`); the ABI a module speaks is
`protocol/WASM-ABI.md`.
