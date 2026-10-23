# Contract: CI, the template and the release

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Research**: [research.md](../research.md) R8, R12

## `sdks/go/go.mod` (what the module promises)

```
module github.com/thinkmorestupidless/ankka-go

go 1.26

require (
    google.golang.org/grpc     v1.84.x
    google.golang.org/protobuf v1.36.x
    github.com/testcontainers/testcontainers-go                   v0.44.x   // the testkit package only
    github.com/testcontainers/testcontainers-go/modules/postgres  v0.44.x
)
```

- No version placeholder: the version is the mirror's tag, read from the build's record
  (`ankka.Version()`), `0.0.0` for a development build.
- `sdks/go/tools/go.mod` is a nested module holding the `tool` directives for `buf`,
  `protoc-gen-go` and `protoc-gen-go-grpc`; `sdks/go/examples/shopping-cart/go.mod` is a nested
  module with `replace github.com/thinkmorestupidless/ankka-go => ../..`; `sdks/go/go.work` lists
  the three for development. Neither nested module is part of the published one.
- `internal/pb/` is committed generated code; `protocol/` is the committed copy of the protocol
  artifact (protos, fixtures, `ENCODING.md`, `README.md`); `LICENSE` and `README.md` are the mirror's.

## `.github/workflows/ci.yml` — `changes` and `sdk-go`

Filter (beside `sdk-typescript`'s, same shape):

```yaml
sdk-go:
  - *workflow
  - *scala-build
  - *templates
  - 'sdks/go/**'
  - 'cli/src/main/templates/go/**'
  - 'cli/src/test/scala/**/cli/GoTemplateSuite.scala'
```

and an output `sdk-go: ${{ steps.filter.outputs.sdk-go == 'true' || github.event_name == 'workflow_dispatch' }}`.
`.github/ci-coverage.py` then claims `sdks/go/**` and the new suite; the `docs` filter already
reads `sdks/**`. The header comment names the Go template suite beside the other three.

Job `sdk-go`, `needs: changes`, `if: needs.changes.outputs.sdk-go == 'true'`, `ubuntu-24.04`,
`strategy.matrix.go: ["1.26", "1.27"]`, `fail-fast: false`, `defaults.run.working-directory: sdks/go`:

1. `actions/checkout` (`fetch-depth: 0`) at the major the `sdk-typescript` job uses; every action
   version below is copied from that job, not from this contract.
2. `actions/setup-go` with `go-version: ${{ matrix.go }}` and the module cache keyed on
   `sdks/go/**/go.sum`.
3. (1.27 only) `actions/setup-java` temurin 21 with the sbt cache, `sbt/setup-sbt`.
4. `diff -r ../../protocol/src/main/protobuf protocol/src/main/protobuf`, `diff -r ../../protocol/fixtures protocol/fixtures`,
   `diff ../../protocol/ENCODING.md protocol/ENCODING.md` — the copy is the original (FR-029).
5. `scripts/proto.sh --check` — regenerating `internal/pb` changes nothing.
6. `go vet ./...` and `gofmt -l . | (! grep .)`.
7. `go test -count=1 ./...` — unit tests, fixtures both ways, servicers through an in-process gRPC
   client, every component test kit, the client against a fake sidecar, registration refusals.
8. **Pack smoke** (FR-035): copy `sdks/go` to `$RUNNER_TEMP/mirror` (what the release would push),
   `go build ./... && go vet ./...` there with `GOFLAGS=-mod=mod`; then in an empty `$RUNNER_TEMP/smoke`
   module with `replace github.com/thinkmorestupidless/ankka-go => $RUNNER_TEMP/mirror`, build a
   `main` importing `ankka` and a `_test.go` importing `ankka/testkit`, and assert `go list -deps` of
   the `main` names no testcontainers package (V4).
9. (1.27) `sbt -Dankka.cluster.tests=off -Dankka.template.tests=off sidecar/docker:publishLocal shoppingCart/docker:publishLocal`
   — the sidecar image from this commit, tagged `:latest`, which the kit chooses at version `0.0.0`.
10. (1.27) `ANKKA_SLOW=1 go test -count=1 ./...` — the cart through the real sidecar with a restart,
    journal portability both ways against `sample-shopping-cart:latest`, the image's filesystem.
11. (1.27) `go run ./cmd/conformance` in `examples/shopping-cart` — the suite against the Go reference.
12. (1.27) `sbt -Dankka.cluster.tests=off -Dankka.template.tests=go 'cli/testOnly *GoTemplateSuite'`.

`cli-native`'s smoke script renders the Go template with the other three.

## The template (`cli/src/main/templates/go/`)

```
go.mod                 module {{module}}; go 1.26; require github.com/thinkmorestupidless/ankka-go v{{ankka_version}}
main.go                NewService(...).Start
domain.go, item_entity.go, item_rows.go, api.go
item_test.go           the component test kits
integration_test.go    testkit.StartT, Slow
Dockerfile             two stages: golang:1.27-alpine CGO_ENABLED=0; distroless static nonroot (no staging script: the project depends on the released module)
.dockerignore, .gitignore, README.md
service.json           {"hosting": "process", "protocol": "{{protocol_version}}"}
.github/workflows/ci.yml, deploy.yml   as the TypeScript template's, with setup-go
```

plus `common/` (`.mcp.json`), `common-service/` (`docker-compose.yml`) and the rendered skills,
added by `build.sbt`'s generator once `"go"` joins its `Seq`. `Language.Go("go")`, parsed from `go`
and `golang`; `--package` and `--template` refused as for TypeScript; the help texts and
`docs/reference/cli.md` regenerated. `GoTemplateSuite(Language.Go, "go")` extends
`PolyglotTemplateSuite`: asserts the pin `v$Version`, appends `replace … => <checkout>/sdks/go`, runs
`go vet ./...` then `ANKKA_SLOW=1 go test -count=1 -v ./...` and asserts the output has no `--- SKIP`
and ends in `ok`. `InitSuite`'s negative case changes its word.

## `.github/workflows/release.yml` — `sdk-go`

`needs: publish`, `if: startsWith(github.ref, 'refs/tags/v')`, `ubuntu-24.04`:

1. `actions/checkout` (the major the `template` job uses) with `fetch-depth: 0` and
   `persist-credentials: false` (the trap in `build-and-release.md`: a persisted credential pushes as
   `github-actions[bot]` and is refused).
2. `version="${GITHUB_REF_NAME#v}"`; `actions/setup-go` 1.27; `go build ./...` in `sdks/go`.
3. **Guard**: `git ls-remote --tags https://x-access-token:${{ secrets.TEMPLATE_REPO_TOKEN }}@github.com/thinkmorestupidless/ankka-go.git "refs/tags/${GITHUB_REF_NAME}"`
   — if the tag exists, log "already released" and stop here with success. A Go tag never moves.
4. `git config user.name "ankka release"`, `user.email "release@ankka.invalid"`.
5. `git subtree split --prefix sdks/go -b ankka-go`.
6. `git push --force "https://x-access-token:…@github.com/thinkmorestupidless/ankka-go.git" ankka-go:main`
   — `main` is replaced, as the template's is; nothing is ever edited in the mirror.
7. `git tag "${GITHUB_REF_NAME}" ankka-go` (no `-f`) and `git push "…ankka-go.git" "refs/tags/${GITHUB_REF_NAME}"` (no `--force`).
8. **Smoke** (FR-035, SC-007): in an empty module under `$RUNNER_TEMP`, with `GOPROXY=https://proxy.golang.org,direct`
   and `GOFLAGS=-mod=mod`, `go get github.com/thinkmorestupidless/ankka-go@${GITHUB_REF_NAME}` with
   up to six attempts a minute apart (the proxy fetches lazily); then build and run a program
   printing `ankka.Version()` and assert it prints `$version`.

Order: after `publish`, like every SDK job, so a release that never reached Maven Central does not
exist on the proxy either. **Before the first release**: create `thinkmorestupidless/ankka-go`
(public, empty, default branch `main`) and grant `TEMPLATE_REPO_TOKEN` push access to it, as for
`ankka.g8`, `ankka-action` and `homebrew-tap`. A broken version is withdrawn by a `retract` directive
in a later release's `go.mod`, never by deleting the tag.

## Documentation and skills touched

- New: `docs/get-started/first-service-go.md`, `docs/reference/go-sdk.md`,
  `tools/docs/skill/ankka-go/SKILL.md`, `.claude/rules/sdk-go.md`.
- `go` in `mkdocs.yml`'s `extra.docs.facets.languages`, the nav ("Your first service in Go"), the
  `site_description`, and `site-links.files` for `sdks/go/README.md`.
- `languages:` gains `go` on every page that has `typescript` and `rust` and shows a Go sample.
- `## Go differences` beside each `## Rust differences` (entities, endpoints, workflows,
  views-consumers, agents); the routing row in `ankka`, the command row in `ankka-inspect`, the
  hosting line in `ankka-deploy`, the language list in `ankka-port`; every skill description that
  lists the languages.
- `docs/reference/limitations.md`: every bullet naming Python and TypeScript names Go (bodies read
  whole, signed-URL bound, no trace on a process's own lines, no gRPC endpoints, no judgments, a
  provider not a model, no blueprints) and "Four languages" becomes five; a Go counterpart of the
  TypeScript autonomous-agent kit limitation if V-tests show it holds.
- Language lists: `docs/index.md`, `concepts/polyglot.md` ("One journal, four languages"),
  `contributing/language-sdks.md` (three worked examples), `get-started/install.md` ("Install the
  Go SDK"), `deploy/images.md`, `README.md` (badge row, prose, `ankka init` line, first-service
  links), `CLAUDE.md` (rules table, commands, template-suite line, CI job list),
  `cli/.../mcp/AnkkaTools.scala` (two descriptions), `McpEndpointsDocumentationSuite`'s regex,
  `ModelProvidersDocumentationSuite`'s heading, `homebrew/README.md`, `marketplace/README.md`,
  `.github/workflows/release.yml`'s comment. `.gitignore` is not touched: Go writes no build output
  into the tree.
- The three language-enumerating tests FR-041 carves out gain `go` and nothing else:
  `ModelProvidersDocumentationSuite`'s heading string, `McpEndpointsDocumentationSuite`'s fence
  regex, `InitSuite`'s negative case.
- `just docs-sync` regenerates the committed skill copies; `just docs-reference` the CLI page.
