#!/usr/bin/env bash
# A behaviour broken in the Go reference is named by the conformance suite, and no other is.
# (features/polyglot/conformance.feature, "a behaviour broken in an SDK is named and no other".)
#
# Builds the reference with -tags conformance_broken, under which the cart's `refuse` command persists
# and replies instead of refusing (examples/shopping-cart/conformance/refuse_broken.go), runs the
# whole suite against it, and requires that exactly the cases reading that refusal fail: anything
# else failing, or one of them passing, is this script failing. Needs what `go run ./cmd/conformance`
# needs: Docker and sbt.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

expected=$'client.error-code-crosses\nes.refusal-persists-nothing\ntopology.call-attributed'

cd "$here/examples/shopping-cart"
set +e
go run -tags conformance_broken ./cmd/conformance >"$log" 2>&1
status=$?
set -e

# munit's failure lines, colour removed: "==> X <suite>.<case id>[: its scenario] <time> <reason>".
failed="$(sed -E 's/\x1b\[[0-9;]*m//g' "$log" |
  grep -E '^==> X ' |
  sed -E 's/^==> X [^ ]*ConformanceSuite\.([^ :]+).*/\1/' |
  sort -u)"
totals="$(sed -E 's/\x1b\[[0-9;]*m//g' "$log" | grep -E 'ConformanceSuite finished:' || true)"

if [[ "$status" -eq 0 ]]; then
  echo "conformance-broken: the suite passed against a broken reference" >&2
  tail -40 "$log" >&2
  exit 1
fi
if [[ "$failed" != "$expected" ]]; then
  echo "conformance-broken: expected exactly these cases to fail:" >&2
  echo "$expected" >&2
  echo "and these failed:" >&2
  echo "${failed:-(none: the suite did not run)}" >&2
  tail -40 "$log" >&2
  exit 1
fi
echo "conformance-broken: the broken refusal is named, and no other behaviour"
echo "$totals"
