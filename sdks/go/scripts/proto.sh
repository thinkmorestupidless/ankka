#!/usr/bin/env bash
# Copies the platform's protocol artifact into the SDK, verbatim (the .proto files, the fixtures,
# ENCODING.md and the README), and regenerates internal/pb from the copy. Both are committed: a Go
# module is fetched as source and `go get` runs no generator, so the module must carry its stubs.
#
#   scripts/proto.sh           copy the protocol in and regenerate
#   scripts/proto.sh --check   regenerate into a temporary directory and fail if internal/pb differs
#
# The generators come from tools/go.mod (a nested module), so nothing needs installing.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
source="$here/../../protocol"

bin="$(mktemp -d)"
trap 'rm -rf "$bin"' EXIT
(cd "$here/tools" && go build -o "$bin/" \
  github.com/bufbuild/buf/cmd/buf \
  google.golang.org/protobuf/cmd/protoc-gen-go \
  google.golang.org/grpc/cmd/protoc-gen-go-grpc)

generate() {
  local into="$1"
  mkdir -p "$into/internal/pb"
  (cd "$here" && PATH="$bin:$PATH" buf generate --template buf.gen.yaml -o "$into")
  # The protocol has a message McpServer (an agent's MCP server, discovery.proto) and a service Mcp,
  # whose generated server interface is also McpServer, in one Go package. protoc-gen-go-grpc has no
  # option to rename it, so the service's server identifiers in its one file become McpService*.
  perl -pi -e 's/\b(Unimplemented|Unsafe|Register)?McpServer\b/$1McpServiceServer/g' \
    "$into/internal/pb/mcp_grpc.pb.go"
}

if [[ "${1:-}" == "--check" ]]; then
  out="$(mktemp -d)"
  generate "$out"
  if ! diff -r "$out/internal/pb" "$here/internal/pb"; then
    echo "internal/pb is not what the protocol copy generates; run scripts/proto.sh" >&2
    rm -rf "$out"
    exit 1
  fi
  rm -rf "$out"
  echo "internal/pb matches the protocol copy"
  exit 0
fi

if [[ ! -d "$source/src/main/protobuf" ]]; then
  echo "no protocol artifact at $source" >&2
  exit 1
fi
rm -rf "$here/protocol"
mkdir -p "$here/protocol/src/main"
cp -R "$source/src/main/protobuf" "$here/protocol/src/main/protobuf"
cp -R "$source/fixtures" "$here/protocol/fixtures"
for f in ENCODING.md README.md; do cp "$source/$f" "$here/protocol/$f"; done
echo "copied the protocol into $here/protocol"

rm -rf "$here/internal/pb"
generate "$here"
echo "generated $here/internal/pb"
