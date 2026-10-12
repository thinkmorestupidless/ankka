#!/usr/bin/env bash
# Builds the conformance reference (the example with its `conformance` feature) to a module, and
# runs the platform's conformance suite against it in both guest shapes: stateless, then stateful.
# ANKKA_CONFORMANCE_ONLY narrows either run to matching cases, a glob over the full case name
# ('*es.*'). ANKKA_CONFORMANCE_SECRETS=secret-manager runs the secret cases on the Secret Manager
# backend, against the platform's fake. Exits with the first failing run's status. Needs cargo, sbt
# and Docker.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"

(cd "$here" && cargo build -p shopping-cart --release --target wasm32-unknown-unknown --features conformance)
module="$here/target/wasm32-unknown-unknown/release/shopping_cart.wasm"

secrets=()
if [[ -n "${ANKKA_CONFORMANCE_SECRETS:-}" ]]; then
  secrets=(-Dankka.conformance.secrets="$ANKKA_CONFORMANCE_SECRETS")
fi

only=()
if [[ -n "${ANKKA_CONFORMANCE_ONLY:-}" ]]; then
  only=(-- "$ANKKA_CONFORMANCE_ONLY")
fi

for shape in stateless stateful; do
  echo "conformance: the reference module, $shape"
  (cd "$root" && sbt -Dankka.cluster.tests=off -Dankka.template.tests=off \
    -Dankka.conformance.target="wasm:$module" -Dankka.conformance.shape="$shape" \
    ${secrets[@]+"${secrets[@]}"} "sidecar/testOnly *ConformanceSuite ${only[*]:-}")
done
