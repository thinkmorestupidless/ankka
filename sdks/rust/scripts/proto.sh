#!/usr/bin/env bash
# Copies the platform's protocol artifact into the crate, verbatim: the .proto files, the fixtures,
# ENCODING.md, WASM-ABI.md and the README. The copy is committed so the published crate carries
# it and CI can diff it against ../../protocol; ankka/build.rs generates the Rust from it.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
source="$here/../../protocol"
copy="$here/ankka/protocol"
if [[ ! -d "$source/src/main/protobuf" ]]; then
  echo "no protocol artifact at $source" >&2
  exit 1
fi
rm -rf "$copy"
mkdir -p "$copy/src/main"
cp -R "$source/src/main/protobuf" "$copy/src/main/protobuf"
cp -R "$source/fixtures" "$copy/fixtures"
for f in ENCODING.md WASM-ABI.md README.md; do cp "$source/$f" "$copy/$f"; done
echo "copied the protocol into $copy"
