#!/usr/bin/env bash
# Drives the shopping cart through the carts the README's expected graph describes.
#
#   ./drive.sh https://shopping-cart-checkout.127.0.0.1.sslip.io:8443 [prefix]
#
# `prefix` (default `cg`) names the carts, so a second run with another prefix adds a second set.
set -euo pipefail
base="${1:?give the base URL of the service}"
prefix="${2:-cg}"
ca="${ANKKA_CA:-$HOME/.ankka/local-ca.crt}"

call() { # method path [json]
  local method="$1" path="$2" body="${3:-}"
  local args=(-s -o /dev/null -w '%{http_code}' --cacert "$ca" -X "$method" "$base$path")
  [[ -n "$body" ]] && args+=(-H 'content-type: application/json' -d "$body")
  local status
  status="$(curl "${args[@]}")"
  [[ "$status" =~ ^2 ]] || { echo "$method $path answered $status" >&2; exit 1; }
}
add()      { call POST   "/carts/$1/items" "{\"productId\":\"$2\",\"name\":\"$3\",\"quantity\":$4}"; }
remove()   { call DELETE "/carts/$1/items/$2"; }
checkout() { call POST   "/carts/$1/checkout"; }
discard()  { call DELETE "/carts/$1"; }

# 1: filled, an item taken out again, checked out.
add "$prefix-1" p1 Pen 2; add "$prefix-1" p2 Ink 1; remove "$prefix-1" p2; checkout "$prefix-1"
# 2: checked out as soon as it had something in it.
add "$prefix-2" p1 Pen 1; checkout "$prefix-2"
# 3: discarded, and left that way.
add "$prefix-3" p1 Pen 1; discard "$prefix-3"
# 4: discarded, then started again under the same id.
add "$prefix-4" p1 Pen 1; discard "$prefix-4"; add "$prefix-4" p9 Cap 3
# 5: still open.
add "$prefix-5" p1 Pen 4
echo "drove carts ${prefix}-1 to ${prefix}-5"
