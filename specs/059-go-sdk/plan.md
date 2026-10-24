# Implementation Plan: Go SDK

**Branch**: `059-go-sdk` | **Date**: 2026-10-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/059-go-sdk/spec.md`

## Summary

A Go module, `github.com/thinkmorestupidless/ankka-go`, with which a service is written in Go and
hosted as a process behind the sidecar, speaking protocol 1.22 over gRPC exactly as the Python and
TypeScript SDKs do: every component kind, every effect, the component client, secrets, client keys,
timers, calls to other services and the storage client; a codec that reads the developer's struct
types and `json` tags by reflection once at registration and writes the encoding `protocol/ENCODING.md`
defines; component test kits and a Docker-backed integration kit under `go test`; the shopping cart
and the conformance reference as an example module whose image is a static executable in a
distroless base; `ankka init --language go`; a CI job on Go 1.26 and 1.27; a release job that
subtree-pushes `sdks/go` to the mirror repository `thinkmorestupidless/ankka-go` and tags it, never
moving a tag; a getting-started page, a reference page, an `ankka-go` skill and `## Go differences`
sections. Nothing under `sidecar/`, `modules/`, `operator/`, `controlplane*/`, `crd/` or `protocol/`
changes. The research is in [research.md](./research.md); the API in
[contracts/go-sdk.md](./contracts/go-sdk.md); CI, template and release in
[contracts/ci-and-release.md](./contracts/ci-and-release.md); the proofs in
[quickstart.md](./quickstart.md).

## Technical Context

**Language/Version**: Go 1.26 floor (`go 1.26` in `go.mod`), 1.27 current; CI runs both (R1).
The platform side is unchanged: Scala 3.9.0, JDK 21, sbt 1.12.15, protocol 1.22.

**Primary Dependencies**: `google.golang.org/grpc` v1.84, `google.golang.org/protobuf` v1.36 (the
only runtime dependencies); `github.com/testcontainers/testcontainers-go` v0.44 and its `postgres`
module (the `testkit` package only); `buf`, `protoc-gen-go`, `protoc-gen-go-grpc` as `tool`
directives in a nested `tools/` module; the standard library for JSON tokenising, HTTP, HMAC and
SHA-256 (R2, R3, R7, R11).

**Storage**: none of the SDK's own. The sidecar owns the journal; the integration kit starts
`postgres:17-alpine` with the DDL copied out of the sidecar image; the storage client speaks S3
(hand-signed SigV4), a directory, or memory.

**Testing**: `go test ./...` for the fixtures, the codec, every component test kit, the servicers
through an in-process gRPC client and the client against a fake sidecar; `ANKKA_SLOW=1 go test` for
the cart through the real sidecar, journal portability and the image; `go run ./cmd/conformance` for
the platform's 174-case suite; `GoTemplateSuite` under `-Dankka.template.tests=go`.

**Target Platform**: Linux containers on the installation's Kubernetes (`CGO_ENABLED=0`, distroless
static base, loopback 9010 beside the sidecar); a developer's macOS or Linux with Docker for the kit.

**Project Type**: library (an SDK), with an example application, a CLI template and documentation.

**Performance Goals**: the conformance suite's `bench` case within the bound it applies to every
process SDK; reflection confined to registration so the per-request path is plain field access
(SC-003 quotes the suite; no new numbers).

**Constraints**: `go get` must suffice, so generated code and the protocol copy are committed (R2);
no version placeholder in the tree (R8); the module's tag never moves once fetched (R8); a service
importing only `ankka` must pull no Docker library (V4); the platform does not change (FR-041).

**Scale/Scope**: about 30 source files in the module and 8 in the test kit; one example module with
31 components and 10 endpoint prefixes; one template of 15 files; two docs pages, one skill, five
differences sections, about 25 files whose language lists gain a word; 3 workflow files and 7 Scala
files touched for the template and CI.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` governs.

| Principle | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | every builder returns a value; the server translates it to the protocol's `Reply`; the sidecar interprets. Building an effect performs no I/O; a reply is a function of the post-event state the server folds |
| One function reduces an effect, shared by runtime and testkit | the component test kits and the server share one `materialise` per kind, as the TypeScript SDK's do, so a test and a deployment cannot disagree |
| Registration is explicit; no classpath scanning | a component exists to the runtime only once passed to `NewService`; Go has no classpath scanning to decline, and `init()` registration is not used; problems are collected and refused together before anything listens (FR-005) |
| Wire names are declared separately from method names | the first argument of `Command`, `Query`, `Step`, `Action`, `Tool`, `Ask` and every route constructor is the wire name; handlers are plain functions, so no function name can reach the wire |
| `query` cannot persist, by construction | `Query` takes a function returning `ReadOnlyEffect[S, R]`, a type no persisting builder produces; registration re-checks the kind; the sidecar refuses events from a read-only handler regardless (R5) |
| Literal route segments outrank parameters | the sidecar's router does this for a declared route; the endpoint test kit matches the same way |
| `RequestContext` is thread-local, sound because one request per thread | the Go equivalent is the `context.Context` every handler receives, read by `ankka.RequestFrom(ctx)`; goroutines the developer starts carry it only if they pass it, and the reference page says so (R6) |
| Work handed to another thread cannot see a thread-local | the same: a context is a parameter, not ambient; a goroutine without it sees nothing, which is the honest Go spelling of the rule |
| SSE payloads must be JSON-encoded | an `SSE` route yields strings; the sidecar frames them; the SDK never writes an SSE line |
| Anything reading `$HOME` or fixed config must be overridable | the SDK reads `ANKKA_PROCESS_PORT`, `ANKKA_SIDECAR_ADDRESS`, the `ANKKA_S3_*` and `ANKKA_STORAGE_*` variables and, in the kit, `ANKKA_SIDECAR_IMAGE` and `ANKKA_SLOW`; nothing under the home directory |
| A test must never name an image by a literal tag | the kit defaults to `ankka-sidecar:latest` only at version `0.0.0` for a developer's laptop; CI sets `ANKKA_SIDECAR_IMAGE` to the tag the same sbt session built; the sidecar-rebuild trap in `testing.md` applies as it does to the other kits |
| A test that binds a fixed port cannot run beside the documented workflow | the SDK's tests bind `127.0.0.1:0`; only `cmd/conformance` uses 9010, because the suite is told that address |
| Two ankka services must never share a Postgres database | each `testkit.Start` gets its own container; `StartBeside` is the deliberate exception for the portability test |
| Nothing in the build may write to a tracked file | `scripts/proto.sh` writes `protocol/` and `internal/pb/`, committed by hand when the protocol changes and diffed in CI; the release job writes nothing: the SDK has no placeholder (R8) |
| Only a tag publishes; no re-tagging | the `sdk-go` job runs on `v*` after `publish`, guards on the tag's existence in the mirror and never forces a tag, because the Go checksum database makes a moved tag a broken module for every consumer (R8) |
| `actions/checkout` must not persist credentials for a push to another repository | the job sets `persist-credentials: false` and pushes with `TEMPLATE_REPO_TOKEN` in the URL, as the template and action jobs do |
| A CLI's `main` should be a one-line wrapper | `cmd/conformance`'s `main` calls a `run` that returns an exit code |
| Every `docs/` page stands alone, samples come from tested code, a new page is in the nav and a skill | R9: every Go code block is an include from `sdks/go/examples` or a test; both pages in the nav; the `ankka-go` skill names its pages; `docs check` is in the quickstart |
| Ask of every check whether it could pass while the thing is false | the proto check regenerates and diffs rather than trusting the copy; the pack smoke asserts the dependency graph, not just a build; the release smoke runs the fetched module and reads its version; `GoTemplateSuite` asserts no `--- SKIP`; the conformance runner exits with sbt's status |
| The platform does not change | FR-041; `git diff --stat` on the platform's directories is empty at the end, except the three language-enumerating tests FR-041 carves out (`ModelProvidersDocumentationSuite`, `McpEndpointsDocumentationSuite`, `InitSuite`), which gain the word `go` and nothing else (quickstart reviewer's checklist) |

**One thing a person must do, named**: the mirror repository `thinkmorestupidless/ankka-go` must
exist and `TEMPLATE_REPO_TOKEN` must be allowed to push to it before the first tag that carries the
SDK, as was done for `ankka.g8`, `ankka-action` and `homebrew-tap`. Unlike npm and crates.io, nothing
is published by hand: the first release goes through the workflow like every later one.

No violations. Re-checked after Phase 1: the contracts introduce nothing beyond the table.

## Project Structure

### Documentation (this feature)

```text
specs/059-go-sdk/
├── plan.md              # This file
├── research.md          # R1 to R13 and the verify-at-implementation table
├── data-model.md        # type plans, declarations, effects, context, client, server state, kits
├── quickstart.md        # tiers 1 to 6, measurements, reviewer's checklist
├── contracts/
│   ├── go-sdk.md        # the public API and the guarantees to the sidecar
│   └── ci-and-release.md # go.mod, the CI job, the template, the release job, the docs touched
└── tasks.md             # /speckit-tasks output, not created here
```

### Source Code (repository root)

```text
sdks/go/                                   # the module; the mirror repository is this directory
├── go.mod, go.sum, go.work                # go.work lists ., tools, examples/shopping-cart
├── LICENSE, README.md                     # the mirror's landing page
├── ankka.go                               # package doc, Version()
├── service.go, spec.go                    # NewService, Start, Problems, discovery, gates
├── codec.go, plan.go, json_write.go, json_read.go, doubles.go, times.go   # R3, R4
├── cases.go                               # Cases, Case, Enumeration
├── effects.go, materialise.go             # every effect kind; one reducer per kind, shared with testkit
├── eventsourced.go, keyvalue.go, workflow.go, view.go, keyedview.go, consumer.go,
│   timedaction.go, agent.go, autonomous.go, endpoint.go, socket.go, mcp.go, graph.go, topic.go
├── context.go                             # RequestFrom, CallerFrom, CommandFrom, TimerFrom, ClientFrom
├── client.go, services.go, secrets.go, clientkeys.go, timers.go   # the sidecar's Client service
├── storage.go, storage_s3.go, storage_dir.go, storage_mem.go, sigv4.go, storage_endpoint.go  # R11
├── contract.go                            # the RFC 8785 fingerprint
├── errors.go                              # RefusalError, ProtocolTooOldError, HttpProblem, ErrorCode
├── internal/pb/                           # generated: ankka/protocol/v1/*.pb.go, *_grpc.pb.go (committed)
├── internal/server/                       # discovery, eventsourced, keyvalue, workflow, stateless,
│                                          # agent, http, mcp, payloads, queue, storage endpoint
├── protocol/                              # the committed copy: src/main/protobuf, fixtures, ENCODING.md, README.md
├── testkit/
│   ├── unit.go, kinds.go, scripted_model.go, slow.go
│   └── integration.go, postgres.go, sidecar.go, ddl.go, http.go, beside.go
├── testdata/doubles.txt                   # the JVM Double.toString table (V7)
├── *_test.go                              # fixtures both ways, codec, kits, servicers, client, refusals, compile-fail cases
├── scripts/proto.sh                       # copy protocol/, go tool buf generate; --check
├── buf.gen.yaml
├── tools/go.mod                           # tool directives: buf, protoc-gen-go, protoc-gen-go-grpc
└── examples/
    ├── shopping-cart/                     # one module, replace => ../..
    │   ├── go.mod, service.json, Dockerfile, .dockerignore, README.md
    │   ├── cart/                          # domain, entity, rows, api  (docs:start markers)
    │   ├── conformance/                   # the 31 components and 10 prefixes of ConformanceReference
    │   ├── cmd/cart/main.go, cmd/conformance/main.go
    │   └── *_test.go                      # unit; slow: sidecar, restart, portability both ways, image
    └── first-service/                     # what first-service-go.md walks through

cli/src/main/templates/go/                 # the template (contracts/ci-and-release.md)
cli/src/main/scala/.../cli/Init.scala      # Language.Go
cli/src/main/scala/.../cli/Main.scala      # help and next steps
cli/src/main/scala/.../cli/mcp/AnkkaTools.scala   # two descriptions
cli/src/test/scala/.../cli/GoTemplateSuite.scala  # new; InitSuite's negative case changes
cli/native-smoke.sh                        # renders go
build.sbt                                  # "go" in the template resource generator's Seq

.github/workflows/ci.yml                   # changes filter + output, sdk-go job, header comment
.github/workflows/release.yml              # sdk-go job
.github/ci-coverage.py                     # claims sdks/go/** and the suite

docs/get-started/first-service-go.md       # new
docs/reference/go-sdk.md                   # new
docs/get-started/install.md, concepts/polyglot.md, contributing/language-sdks.md,
docs/deploy/images.md, docs/reference/limitations.md, docs/index.md   # language lists, Go sections
docs/**/*.md                               # `go` in languages: where a Go sample is shown
mkdocs.yml                                 # facet, nav, site_description, site-links
tools/docs/skill/ankka-go/SKILL.md         # new
tools/docs/skill/{ankka,ankka-entities,ankka-endpoints,ankka-workflows,ankka-views-consumers,
                  ankka-agents,ankka-inspect,ankka-deploy,ankka-port}/SKILL.md   # Go differences, lists
marketplace/plugins/ankka/skills/, ankka.g8/src/main/g8/.claude/skills/   # regenerated by just docs-sync
.claude/rules/sdk-go.md                    # new rule file, paths: sdks/go/**, cli/src/main/templates/go/**
CLAUDE.md, README.md, homebrew/README.md, marketplace/README.md, .gitignore   # language lists
modules/*/src/test/.../{McpEndpointsDocumentationSuite,ModelProvidersDocumentationSuite}.scala  # regex, heading
```

**Structure Decision**: the SDK lives in this repository under `sdks/` beside the other three, for
the reason they do: the protocol, the fixtures, the conformance suite and the SDK must move together
and a tag must prove them consistent. It is **one Go module at `sdks/go`** with the test kit as a
package of it, not a nested module, because Go fetches only the packages a build imports (V4
measures it) and a second module would need a second tag and a pin between the two. The two nested
modules that do exist, `tools/` and `examples/shopping-cart/`, exist to keep generator and example
dependencies out of the published module's graph, and `go.work` joins the three for development. The
mirror repository is this directory as it stands, so everything the mirror needs (`LICENSE`,
`README.md`, generated code, the protocol copy) is in it, and nothing in it names a path outside it.
`materialise.go` is one file on purpose, as the TypeScript SDK's is: the server and the kits import
the same reducers. The template is in `cli/src/main/templates/go/` because that is where
`Scaffold` reads templates from and `build.sbt` packs them, and its suite in `cli`'s tests because
that is where the other three are.

## Complexity Tracking

No constitution violations to justify. Four additions that could look like scope and are not:

| Addition | Why it is in this feature |
|---|---|
| A JSON writer and reader of the SDK's own (R3) | the encoding forces it: declared field order, every field written, `1.0` for whole doubles, `long` never through `float64`, a discriminator injected from a registered case, a missing required field refused. `encoding/json` v1 and v2 cannot be told to do these, and a wrapper doing them is the writer |
| Committed generated code and a `tools/` module (R2) | `go get` runs no generator, so the mirror must carry the stubs; the generators are Go programs and `tool` directives in a nested module pin them without putting buf in every consumer's graph |
| A release guard on the tag and no `--force` (R8) | the Go checksum database turns a moved tag into a checksum mismatch for every consumer, so the other mirrors' force-push pattern cannot be copied for the tag; the guard is what makes a re-run of the job safe |
| A CI matrix on Go 1.26 and 1.27 for the fast tests (R1) | a declared floor CI never runs is a claim; the matrix costs one more minute and makes it a fact |
