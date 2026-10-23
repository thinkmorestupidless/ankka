#!/usr/bin/env bash
# Builds sample-shopping-cart-go:latest, the one way the example's image is built: the slow image
# test, the kind deploy and CI all call this. It stages the SDK's sources and this example into one
# temporary build context, so the example's `replace => ../..` resolves inside the build.
set -euo pipefail

example="$(cd "$(dirname "$0")/.." && pwd)"
sdk="$(cd "$example/../.." && pwd)"
tag="${1:-sample-shopping-cart-go:latest}"

context="$(mktemp -d)"
trap 'rm -rf "$context"' EXIT

# The SDK's module: what its build reads, and nothing its tests or tools need.
cp "$sdk/go.mod" "$sdk/go.sum" "$context/"
find "$sdk" -maxdepth 1 -name '*.go' ! -name '*_test.go' -exec cp {} "$context/" \;
mkdir -p "$context/internal"
(cd "$sdk" && find internal -name '*.go' ! -name '*_test.go' -print0 | xargs -0 -I{} sh -c 'mkdir -p "$1/$(dirname "$2")" && cp "$2" "$1/$2"' _ "$context" {})

# The example, without what a local run left behind.
mkdir -p "$context/examples/shopping-cart"
(cd "$example" && tar --exclude='*.test' --exclude='*.out' -cf - .) | tar -xf - -C "$context/examples/shopping-cart"

docker build -t "$tag" -f "$context/examples/shopping-cart/Dockerfile" "$context"
