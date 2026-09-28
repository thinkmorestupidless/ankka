#!/usr/bin/env bash
# Builds the Go spike guest with TinyGo and copies it where WasmHostSpike loads it from. Needs
# tinygo (0.42 or later) and go on PATH; `wasm-unknown` is the target with no imports at all.
set -euo pipefail
cd "$(dirname "$0")"
# Homebrew's binaryen is keg-only, and TinyGo needs its wasm-opt on PATH or named here.
export WASMOPT="${WASMOPT:-$(command -v wasm-opt || echo /opt/homebrew/opt/binaryen/bin/wasm-opt)}"
go mod tidy
# wasm-unknown defaults to a leaking collector and a 4 KB stack: a long-lived guest needs neither.
tinygo build -o spike-guest-go.wasm -target=wasm-unknown -opt=2 -no-debug -gc=precise -stack-size=64kb .
mkdir -p ../../resources/wasm
mv spike-guest-go.wasm ../../resources/wasm/spike-guest-go.wasm
ls -l ../../resources/wasm/spike-guest-go.wasm
