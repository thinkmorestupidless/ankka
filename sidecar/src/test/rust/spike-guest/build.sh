#!/usr/bin/env bash
# Builds the spike guest and copies it where WasmHostSpike loads it from. Needs a Rust toolchain
# with the wasm32-unknown-unknown target (`rustup target add wasm32-unknown-unknown`).
set -euo pipefail
cd "$(dirname "$0")"
cargo build --release --target wasm32-unknown-unknown
mkdir -p ../../resources/wasm
cp target/wasm32-unknown-unknown/release/ankka_spike_guest.wasm ../../resources/wasm/spike-guest.wasm
ls -l ../../resources/wasm/spike-guest.wasm
