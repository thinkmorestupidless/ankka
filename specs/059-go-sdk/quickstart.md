# Quickstart: Go SDK

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [go-sdk.md](./contracts/go-sdk.md), [ci-and-release.md](./contracts/ci-and-release.md)

How to prove the feature works, cheapest first. Every tier is something CI or a reviewer can run;
the measurements at the end are recorded before the feature is called done. Run from `sdks/go`
unless a tier says otherwise. Needs Go 1.26 or later and, from tier 3, Docker.

## Tier 1 — the codec against the fixtures (seconds, no Docker)

```bash
go test -count=1 -run 'Fixtures' ./...
```

Expected: every file under `protocol/fixtures` (38 today) decoded with the codec its manifest and
content type select, compared with the value, re-encoded and compared byte for byte; 0 skipped. Break
it: change one byte of a fixture's `bytes_base64` in a scratch copy and the test names the file.
Also the Scala double table (`testdata/doubles.txt`, generated from the JVM once, V7) and the
map-order case.

## Tier 2 — the component model, no sidecar (seconds)

```bash
go test -count=1 ./...
```

Expected: the component test kits for every kind; registration refusals (duplicate id, duplicate wire
name, shared prefix, `nil` ACL, unrenderable field, tool without description, topic consumer
without a start position) each named in one refusal; the compile-time refusals as `//go:build ignore`
files the test compiles with `go vet` and asserts fail (a persisting query, an endpoint without the
ACL argument); the servicers through an in-process gRPC client (init, replay, snapshot request, a
mid-step query, a panic recovered, a stream ended both ways); the client against a fake sidecar.

## Tier 3 — the shopping cart through the real sidecar (a few minutes, Docker)

```bash
# from the repository root: the sidecar image from this checkout, and the Scala cart for portability
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off sidecar/docker:publishLocal shoppingCart/docker:publishLocal
cd sdks/go && ANKKA_SLOW=1 go test -count=1 -v ./examples/...
```

Expected: the cart's routes through the sidecar, a restart of the sidecar with the cart read back,
the Scala cart image started beside the Go cart on one Postgres reading what Go wrote and the
reverse (0 differences), the storage routes against the memory and directory stores, and the image
inspection (one executable, no shell). Break it: a wrong manifest in the Go cart's event codec makes
the portability test fail naming the record.

## Tier 4 — the conformance suite against the Go reference (a few minutes, sbt)

```bash
cd examples/shopping-cart && go run ./cmd/conformance
ANKKA_CONFORMANCE_ONLY='*socket.*' go run ./cmd/conformance      # one area
```

Expected: 174 cases, 0 failed (the suite skips the few its own assumptions exclude for a process
target, as it does for the other SDKs). Break one behaviour on purpose, say make `remove-item` of an
absent product reply instead of refuse, and exactly `es.refusal-persists-nothing` (or its sibling)
goes red.

## Tier 5 — the module as the proxy would serve it, on both Go lines (a minute)

```bash
rm -rf /tmp/ankka-go-mirror && cp -R . /tmp/ankka-go-mirror && (cd /tmp/ankka-go-mirror && GOFLAGS=-mod=mod go build ./... && go vet ./...)
mkdir -p /tmp/smoke && cd /tmp/smoke && go mod init smoke && go mod edit -replace github.com/thinkmorestupidless/ankka-go=/tmp/ankka-go-mirror
# a main.go printing ankka.Version(), a x_test.go importing ankka/testkit
go mod tidy && go build ./... && go test -count=1 ./... && go list -deps . | grep -c testcontainers   # expect 0
```

Repeat with `GOTOOLCHAIN=go1.26.10` and `go1.27.3`. Expected: both build; the program prints
`0.0.0`; the service's build pulls no testcontainers package (V4). A `go 1.25` toolchain refuses the
module naming `go 1.26`.

## Tier 6 — the template and the local installation (fifteen minutes, kind)

```bash
# the template
sbt -Dankka.cluster.tests=off -Dankka.template.tests=go 'cli/testOnly *GoTemplateSuite'
# by hand
ankka init cart --language go && cd cart && go vet ./... && ANKKA_SLOW=1 go test ./...
# the installation
sdks/go/examples/shopping-cart/scripts/image.sh        # stages sdks/go and the example into one context; tags sample-shopping-cart-go:latest
kind load docker-image sample-shopping-cart-go:latest --name ankka
ankka services apply -f sdks/go/examples/shopping-cart/service.json -p <project>
ankka services get cart -p <project>          # Ready; sdk ankka-go 0.0.0 (V6)
ankka services expose cart -p <project> && curl …/carts/c1/items
ankka services restart cart -p <project>     # one instance at a time, no request refused (V8)
```

Expected: the template passes with 0 skipped; the deployed service reaches `Ready`, says which SDK
built it, scales, restarts, pauses and resumes with the same words as any service; `git diff --stat
-- sidecar modules operator controlplane controlplane-api crd protocol` names only the two
documentation suites FR-041 carves out (FR-041).

## Documentation

```bash
just docs-sync && just docs && just features
```

Expected: `docs check` passes with the two new pages, the `ankka-go` skill, the `## Go differences`
sections and the language lists; 100% of the Go code blocks are includes; the features check reports
0 findings with the Go rows. `just docs-reference` after the CLI's help changes.

## Measurements to record before calling it done

| What | Record |
|---|---|
| `go test ./...` (unit, fixtures, kits) | test count and wall time |
| `ANKKA_SLOW=1 go test ./examples/...` | per-test time: restart, routes, portability |
| `go run ./cmd/conformance` | passed / skipped / failed, suite time |
| the getting-started page from an empty directory to the first `curl` | tool time, and that `go run .` is the one command |
| the example image | size in MB; `docker run --rm --entrypoint sh` fails (no shell) |
| the released module | `go get …@v<version>` in an empty directory: time from tag to first successful fetch |

### Recorded

| When | What | Result |
|---|---|---|
| 2026-10-23, T023 | the example image | 23.3 MB |
| 2026-10-23, T024 | `ANKKA_CONFORMANCE_ONLY='*es.*'`, cart and `conformance` only | 11 passed, 1 skipped (`es.late-reply-dropped`, skipped for every target), 4 failed: the `es.replicated-*` and `es.attribution` cases need `replicated-vehicle` (T039); suite 21 s |
| 2026-10-23, T025 | the US1 loop by hand, compose's sidecar (protocol 1.21) and Postgres | the Go cart's carts read back after `docker compose restart sidecar`; the Scala sample (`sbt shoppingCart/run`, same Postgres) read the Go cart's checked-out cart field for field and refused it 409; the Go cart's image read, totalled and checked out the cart the Scala sample wrote. `es.journal-portable` first passed in T024's run |
| 2026-10-23, T029 | `go test ./...` in the SDK and the example | SDK 1.7 s, test kit 0.3 s, example 1.1 s, Docker not touched |
| 2026-10-23, T029 | `ANKKA_SLOW=1 go test ./examples/...`, sidecar `ankka-sidecar:latest` (1.21, runtime 0.13.0) | every route with a restart 19.0 s; the journal shared with `sample-shopping-cart:latest` 24.5 s. Go reads both carts, Scala reads its own and a Go cart with no snapshot. Scala reading a Go cart past its snapshot skips the event after it: the runtime's in-process host reads a `RemoteStateRecord` without its `snapshotSeq`, and Pekko stores that snapshot one sequence late (the snapshot row of a five-event cart sits at 4, holding the state at 3). The case is skipped while the difference is exactly that one event; it is a runtime defect every process SDK meets, reported with this spec |
| 2026-10-23, T024 | `ANKKA_CONFORMANCE_ONLY='*discovery.*'` | 1 passed (`refuses-wrong-major`), 3 failed on components T039 adds (`lists-every-component`, `read-only-flag`, `workflow-source-needs-1.16`) |
| 2026-10-23, T040 | `go run ./cmd/conformance`, the whole suite against the Go reference, sidecar from this branch | 179 passed, 5 skipped, 0 failed; suite 183 s. The five skips are the ones the suite makes for every target, the TypeScript run's included: `es.late-reply-dropped`, `timer.fires-after-process-restart`, `http.unready-process-is-503`, `agent.session-survives-process-restart` (each `assume(false)`, proven by a Scala suite) and `bench.request-latency` (benchmarks off) |
| 2026-10-23, T040 | the red proof | No conformance case removes an absent product from a cart, so the break T040 names left the suite green (179 passed); the Go cart's own `TestRefusalsChangeNothing` and `TestTheRoutesCallTheCart` went red with it and green after the revert. The suite's refusal case was broken instead: the conformance entity's `refuse` persisting and replying turned exactly `es.refusal-persists-nothing`, `client.error-code-crosses` and `topology.call-attributed` red (176 passed, 3 failed), the three cases that call it; reverted |
| 2026-10-23, T040 | `ANKKA_SLOW=1 go test -run TestTheStorageRoutes ./examples/shopping-cart`, this branch's sidecar | the `/conformance` storage routes through the sidecar, with the unnamed bucket and the descriptor's public `photos`: memory 10.3 s, directory 8.1 s (the directory store's signed URL served at `/_ankka/storage` through the sidecar, a wrong token 403, the objects on disk) |
| 2026-10-23, T043 | `TestTheImageHoldsTheExecutableAndNoRuntime` | the image built by `scripts/image.sh`, created and exported unstarted: 1444 files, `/cart` executable, CA certificates, no shell, no package manager, no Go toolchain; 25.9 MB as `docker images` shows it (5.8 MB compressed); 23.7 s with the build |

## Reviewer's checklist

- [ ] No file under `sidecar/`, `modules/`, `operator/`, `controlplane*/`, `crd/`, `protocol/` changed, except the three language-enumerating tests FR-041 carves out (`ModelProvidersDocumentationSuite`, `McpEndpointsDocumentationSuite`, `InitSuite`), whose diff is the word `go`; the conformance suite and the fixtures are untouched (FR-041, FR-042).
- [ ] The table from T041 (feature file, scenario, the Go test or conformance case behind it) is in `.claude/rules/sdk-go.md` with no empty cell (FR-031).
- [ ] `sdks/go/protocol` equals `protocol/`; `scripts/proto.sh --check` is clean; `internal/pb` is committed.
- [ ] No version placeholder in `sdks/go`; `ankka.Version()` is the build's record; the release job rewrites nothing and never moves a tag.
- [ ] The process binds loopback, `0.0.0.0` only through the kit's option; `/_ankka/storage` declared only at 1.21+.
- [ ] Every Go code block in `docs/` is an include from `sdks/go`; `languages:` carries `go` where a Go sample is shown; every `## Rust differences` has a `## Go differences` beside it.
- [ ] Every language list named in `contracts/ci-and-release.md` names Go; `docs/reference/limitations.md` says five languages.
- [ ] `.claude/rules/sdk-go.md` exists and carries the traps the implementation met, dated.
- [ ] `thinkmorestupidless/ankka-go` exists and the token can push to it before the tag that first carries the SDK.
