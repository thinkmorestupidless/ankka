#!/usr/bin/env bash
# The pack smoke (contracts/ci-and-release.md, step 8): build what a release would push, as a
# stranger's module would see it.
#
#   1. Copy sdks/go to a mirror, the tree `git subtree split` pushes, and build and vet it there
#      with no workspace and modules resolved from the network, as the proxy will.
#   2. In an empty module with a replace to the mirror, build a main that imports ankka and prints
#      Version(), and a test that imports ankka/testkit; run both.
#   3. Refuse when the main's dependencies name any testcontainers package: the test kit's
#      containers must reach a test binary only, never a service's.
#
# Usage: scripts/pack-smoke.sh [work-dir]   (a fresh temporary directory by default)
# GOTOOLCHAIN chooses the Go release, for example GOTOOLCHAIN=go1.27.3 scripts/pack-smoke.sh.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="${1:-$(mktemp -d)}"
mirror="$work/mirror"
smoke="$work/smoke"
rm -rf "$mirror" "$smoke"
mkdir -p "$mirror" "$smoke"

export GOWORK=off
export GOFLAGS=-mod=mod

echo "pack smoke: $(go version) in $work"

# What the release pushes is the whole of sdks/go; the examples are modules of their own, which
# ./... in the mirror does not enter.
cp -R "$here/." "$mirror/"
rm -f "$mirror/go.work" "$mirror/go.work.sum"
(cd "$mirror" && go build ./... && go vet ./...)
echo "pack smoke: the mirror builds and vets"

cat > "$smoke/go.mod" <<EOF
module example.com/smoke

go 1.26

require github.com/thinkmorestupidless/ankka-go v0.0.0

replace github.com/thinkmorestupidless/ankka-go => $mirror
EOF

cat > "$smoke/main.go" <<'EOF'
package main

import (
	"fmt"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

func main() { fmt.Println(ankka.Version()) }
EOF

cat > "$smoke/main_test.go" <<'EOF'
package main

import (
	"testing"

	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheKitIsReachable(t *testing.T) {
	if testkit.SidecarImage() == "" {
		t.Fatal("the test kit names no sidecar image")
	}
}
EOF

cd "$smoke"
go mod tidy
go build -o smoke .
go test -count=1 ./...

printed="$(./smoke)"
if [ "$printed" != "0.0.0" ]; then
  echo "pack smoke: Version() printed '$printed', want 0.0.0 (the placeholder a release rewrites)" >&2
  exit 1
fi

leaked="$(go list -deps . | grep -i testcontainers || true)"
if [ -n "$leaked" ]; then
  echo "pack smoke: a service's binary depends on testcontainers:" >&2
  echo "$leaked" >&2
  exit 1
fi
echo "pack smoke: main has $(go list -deps . | wc -l | tr -d ' ') packages, none of them testcontainers"
echo "pack smoke: Version() printed 0.0.0; passed"
