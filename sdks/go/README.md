# ankka for Go

The ankka SDK for services written in Go. A service is a program that the ankka runtime hosts beside
its sidecar: the sidecar owns everything durable and distributed, and the program owns the decisions.
This module speaks the sidecar protocol so your code never does.

Documentation: <https://docs.ankka.cloud/>. Start with *Your first service in Go* and the *Go SDK*
reference.

```bash
go get github.com/thinkmorestupidless/ankka-go
```

Go 1.26 or later. A service runs with the standard toolchain, `go run .`, and builds to one static
executable with `CGO_ENABLED=0`. The test kits are the package
`github.com/thinkmorestupidless/ankka-go/testkit`.

This repository is produced from `sdks/go` in
[thinkmorestupidless/ankka](https://github.com/thinkmorestupidless/ankka) by each release, and is
never edited here. Issues and changes go to that repository.

## Developing the SDK

From `sdks/go` in the ankka repository:

```bash
go vet ./...                       # the vet checks CI runs
go test ./...                      # the fast tests, no Docker: fixtures, codec, kits, servicers
ANKKA_SLOW=1 go test ./...         # the Docker-backed tests too (needs the ankka-sidecar image)
scripts/proto.sh                   # copy protocol/ in and regenerate internal/pb
scripts/proto.sh --check           # fail if internal/pb is not what the copy generates
(cd examples/shopping-cart && go run ./cmd/conformance)   # the platform's conformance suite
examples/shopping-cart/scripts/image.sh                   # the example's image
```

`protocol/` is a verbatim copy of the repository's `protocol/`, and `internal/pb/` is generated from
it; both are committed because `go get` runs no generator. CI fails when the copy differs from the
original or when regenerating changes the stubs.
