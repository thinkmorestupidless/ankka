# Implementation Plan: TypeScript SDK

**Branch**: `013-typescript-sdk` | **Date**: 2026-09-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-typescript-sdk/spec.md`, the design treatment it
was written from, `docs/design/typescript-sdk.md`, and the audience it records: developers who use
Node.js for their backend work, offered a highly structured way to build larger systems in code they
can read, inside a paradigm most of them have not met.

## Summary

Add a third SDK, for TypeScript on Node.js, in `sdks/typescript`, on the seam feature 009 built:
the process side of the sidecar protocol, the encoding in `protocol/ENCODING.md`, and the
conformance suite as the definition of compatible. The platform does not change; a TypeScript
service is `"hosting": "process", "protocol": "1.0"` exactly as a Python one is.

The SDK's shape is the Scala SDK's shape in the language's own idiom: components are classes with
static declarations; handlers are declared in a table with the wire name separate from the method;
effects are inert values; a query cannot persist, and here that is a compile error again. Because
TypeScript erases its types, every value that crosses the protocol is declared once as a schema
from which both the static type and the codec are derived, and the codec does what no validation
library does for this encoding: distinguishes `int` from `double`, carries `long` as `bigint`, and
renders doubles and instants byte for byte as the Scala codecs do. The transport is Connect over
HTTP/2 speaking gRPC to the sidecar, chosen for an async-generator handler shape that is the Python
server's shape and reads as ordinary code. Two testkits, the shopping cart port, the conformance
reference, npm publishing from a tag, CI and release jobs mirroring the Python SDK's, and
documentation and a skill on the Python trail complete it.

## Technical Context

**Language/Version**: TypeScript 7.0 (the Go-native compiler; `erasableSyntaxOnly`,
`verbatimModuleSyntax`, `module: nodenext`), on Node.js `>=22.22.0`; the docs and CI use Node 24
(R1). Sources, tests and examples are `.ts` run directly by Node's type stripping; the package
ships `dist/` emitted by `tsc`.

**Primary Dependencies**: runtime — `@connectrpc/connect` 2.2, `@connectrpc/connect-node` 2.2,
`@bufbuild/protobuf` 2.15 (R2). Dev — `typescript` 7, `@bufbuild/buf` 1.73 and
`@bufbuild/protoc-gen-es` 2.15 for codegen. The `testkit` entry point additionally needs
`testcontainers` 12.1 and `@testcontainers/postgresql` 12.1 (R7), declared as `peerDependencies`
marked optional so the SDK alone installs nothing Docker-shaped. No schema library, no test-runner
dependency, no HTTP client library (`fetch` is built in).

**Storage**: none of the SDK's own. The sidecar writes the same tables; the SDK's codec makes its
payloads indistinguishable from the Scala and Python SDKs' (R3), and the integration testkit puts the
platform's DDL, copied out of the sidecar image, into a throwaway Postgres.

**Testing**: `node --test` for the SDK's own tests and the examples' (R7): unit tests of every kind
through the unit testkit, the encoding fixtures both ways, the servicers through an in-process
Connect client, and Docker-backed integration tests (`test:slow`) including journal portability
against the Scala cart image. The platform's `ConformanceSuite` against the TypeScript reference via
`npm run conformance` (R10). The testkits depend on no runner; the reference page says vitest works.

**Target Platform**: Node.js on Linux and macOS, as a process beside the `ankka-sidecar` image
locally (compose `polyglot` profile) or as the app container of a process-hosted service on the
platform. Not browsers, Deno or Bun.

**Project Type**: one new directory outside sbt (`sdks/typescript`), one new CI job, one new release
job, documentation pages and a skill, and one-line language mentions in `mkdocs.yml`, `docs/`,
`AnkkaTools.scala`, `README.md` and `CLAUDE.md`. No sbt project changes; no platform code changes
(FR-033).

**Performance Goals**: none new. The sidecar's SC-003 from feature 009 (a process-hosted command
within 2× in-process) is a property of the sidecar and the loopback hop, not the SDK; the SDK adds a
codec on each side of each hop. Recorded as a measurement in the quickstart, not a criterion.

**Constraints**: every value's shape declared once (FR-008); the default codec passes every fixture
with none skipped (FR-009, SC-001); a persisting query, an unstated ACL and a missing static are
compile errors (FR-002, SC-005); no decorators, reflection metadata, parameter properties, enums or
code generation from the developer's code (FR-007, R1); loopback only, `0.0.0.0` for the testkit
(FR-013); the conformance suite and fixtures unchanged (FR-034); no platform file changed (FR-033);
no tracked file written by a build (FR-027); the first npm publish is manual (R8).

**Scale/Scope**: the Python SDK is the yardstick — about 2,900 lines of source, 840 of testkits,
1,100 of tests and 1,070 of examples; TypeScript is a little more verbose with schemas declared
beside types, so expect 4,000–5,000 lines of source and testkits and a similar amount of tests and
examples, plus two docs pages, a skill and about ten small edits elsewhere. The riskiest piece is the
codec's byte-identity against the fixtures; the most expensive is the reference service; the piece
that cannot be tested locally is the trusted-publisher handshake.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` governs.

| Principle | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | every builder returns a frozen value; the server translates it to the protocol's `Reply`; the sidecar interprets. A handler performs no I/O on the platform's behalf; even the reply is a function of the post-event state the server folds |
| One function reduces an effect, shared by runtime and testkit | the unit testkit and the server share `materialise` for each kind, so a test and a deployment cannot disagree about what an effect means — the same shape the Scala testkit shares with the runtime |
| Registration is explicit; no classpath scanning | a class exists to the runtime only once `register`ed; nothing is found by import or directory scan; problems are collected and reported together before anything listens |
| Wire names are declared separately from method names | the first argument of `command`, `query`, `step`, `action`, `stream`, `tool` and the route functions is the wire name; the method name never reaches the wire, and the handler table makes the two visibly distinct |
| `query` cannot persist, by construction | `query` types its `run` as returning `ReadOnlyEffect`, a discriminated type a persist builder cannot produce; registration re-checks `kind`; the sidecar refuses events from a read-only handler regardless (R5) |
| Literal route segments outrank parameters | the sidecar's router does this for a declared route; the endpoint unit testkit matches the same way so a test cannot pass on declaration order |
| `RequestContext` is thread-local, sound because one request per thread | the Node equivalent is `AsyncLocalStorage`, one store per request, read as `this.request`; endpoint instances are shared and hold no per-request state (R6) |
| SSE payloads must be JSON-encoded | an `sse` route yields strings; the sidecar encodes each as a frame; the SDK never writes an SSE line |
| Anything reading `$HOME` or fixed config must be overridable | the SDK reads only `ANKKA_PROCESS_PORT`, `ANKKA_SIDECAR_ADDRESS` and, in the testkit, `ANKKA_SIDECAR_IMAGE`; nothing under the home directory |
| A test must never name an image by a literal tag | the integration testkit defaults to `ankka-sidecar:latest` for a developer's laptop, and CI sets `ANKKA_SIDECAR_IMAGE` to the tag the same sbt session built, as the Python job does |
| A test that binds a fixed port cannot run beside the documented workflow | the SDK's own tests bind `127.0.0.1:0`; only `npm run conformance` uses 9010, because the suite is told that address |
| Two ankka services must never share a Postgres database | the integration testkit gives each start its own container; `startBeside` is the deliberate exception for the portability test, on one journal, as the Python test does |
| Nothing in the build may write to a tracked file | `npm run proto` writes `proto/` (committed by hand when the protocol changes) and `src/_proto/` (ignored); the release job rewrites `package.json`'s version in the job's checkout only (R8) |
| A CLI's `main` should be a one-line wrapper | `npm run conformance` is `bin/conformance.ts` whose `main` returns an exit code and the wrapper calls `process.exit` on it, so the test of the runner does not exit the test process |
| Every `docs/` page stands alone, samples come from tested code, a new page is in the nav and a skill | R9; every TypeScript code block is an include with `// docs:start` markers from `sdks/typescript/examples` or `test`, and `docs check` is in the quickstart |
| The platform does not change | FR-033; `git diff --stat` on `sidecar/`, `modules/`, `operator/`, `controlplane*/`, `crd/`, `protocol/` is empty at the end (quickstart reviewer's checklist) |

**One tension, named**: `CLAUDE.md` says the first publish of anything goes through the workflow
so the workflow is proven. npm's trusted publishing cannot create a package (R8), so this SDK's first
publish is the one artifact published by hand, at the tag's version, after the tag's `sonaRelease`
succeeded. The workflow is still proven from the second tag on, and the manual step is written down
in `CLAUDE.md`'s Publishing section as the exception it is.

No violations.

## Project Structure

### Documentation (this feature)

```text
specs/013-typescript-sdk/
├── plan.md
├── research.md          # R1–R10 and the "verify at implementation" list
├── data-model.md        # schemas, codecs, components, effects, server state, client, testkits, package
├── quickstart.md        # tiers 1–6 and the reviewer's checklist
├── contracts/
│   ├── typescript-sdk.md      # the package's public API, the two testkits, the scripts
│   └── ci-and-release.md      # the sdk-typescript jobs, package.json, the one-time manual publish
└── checklists/requirements.md
```

The protocol, sidecar, descriptor and conformance contracts are feature 009's and are unchanged.

### Source Code (repository root)

```text
sdks/typescript/                            # NEW, outside sbt; the third SDK
├── package.json                            # name ankka, version 0.0.0, type module, engines >=22.22, files [dist],
│                                           #   exports . and ./testkit, repository.directory, scripts (contract)
├── tsconfig.json                           # noEmit check: nodenext, erasableSyntaxOnly, verbatimModuleSyntax,
│                                           #   allowImportingTsExtensions, rewriteRelativeImportExtensions
├── tsconfig.build.json                     # emits dist/ (JS + d.ts) from src/
├── buf.gen.yaml                            # protoc-gen-es: target=ts, import_extension=ts → src/_proto
├── proto/                                  # copied from protocol/ by `npm run proto`; committed; CI diffs
├── scripts/proto.ts                        # the copy and `buf generate`
├── bin/conformance.ts                      # serves examples/shopping-cart's reference; runs the sbt suite
├── src/
│   ├── index.ts                            # public exports; the Node floor check is its first statement (FR-018)
│   ├── version.ts                          # VERSION, the one placeholder (0.0.0)
│   ├── schema.ts                           # s.*, Infer, validation at declaration; JSON Schema derivation
│   ├── json.ts                             # the schema-directed writer; the source-access reviver (R3)
│   ├── codec.ts                            # Codec, jsonCodec, text and binary codecs, defaultCodecFor, codecForManifest
│   ├── time.ts                             # Instant, Duration, LocalDate, LocalDateTime (R4)
│   ├── handlers.ts                         # command, query, step, action, stream, tool, guardrail; HandlerRef types
│   ├── effects/{common,eventSourced,keyValue,workflow,view,consumer,timedAction,agent}.ts
│   ├── eventSourcedEntity.ts, keyValueEntity.ts, workflow.ts, view.ts, consumer.ts,
│   │   timedAction.ts, agent.ts, endpoint.ts # base classes; static shape types register() constrains (R5)
│   ├── routes.ts                           # get/post/put/delete/patch/sse; template → params type; Request, Acl, HttpProblem
│   ├── context.ts                          # CommandContext; AsyncLocalStorage for the request (R6)
│   ├── materialise.ts                      # one reduction per kind, shared by server and unit testkit
│   ├── service.ts                          # Ankka.service(), ServiceBuilder, Registry, problems, spec()
│   ├── server/
│   │   ├── server.ts                       # http2.createServer + connectNodeAdapter; loopback rule; shutdownSignal
│   │   ├── discovery.ts, eventSourced.ts, keyValue.ts, workflow.ts, stateless.ts, http.ts, agent.ts
│   │   └── payloads.ts                     # Payload ↔ value through a codec; Metadata ↔ Record
│   ├── client.ts                           # ComponentClient over createGrpcTransport; typed and by-name calls; views; timers
│   ├── testkit/
│   │   ├── index.ts                        # the ./testkit entry
│   │   ├── unit.ts                         # EventSourced/KeyValue/Workflow/View/Consumer/TimedAction/Endpoint/Agent kits, ScriptedModel
│   │   └── integration.ts                  # AnkkaTestKit: Postgres + sidecar via testcontainers; DDL out of the image
│   └── _proto/                             # generated; gitignored; emitted into dist/
├── test/                                   # node --test 'test/**/*.test.ts'
│   ├── encoding-fixtures.test.ts           # every fixture both ways; a missing codec fails
│   ├── schema.test.ts, json.test.ts, time.test.ts
│   ├── event-sourced-entity.test.ts, other-kinds.test.ts, endpoint.test.ts, agent.test.ts
│   ├── server-stream.test.ts               # the servicers through an in-process Connect client
│   ├── stream-close.test.ts                # clean close and abort both release state
│   ├── registration.test.ts                # every problem reported at once; compile-time refusals via @ts-expect-error
│   └── journal-portable.test.ts            # slow: Scala writes, TypeScript reads, and the reverse
└── examples/shopping-cart/
    ├── domain.ts, entity.ts, cartRows.ts, checkoutNotifier.ts, checkoutLog.ts, checkoutWorkflow.ts,
    │   assistant.ts, endpoint.ts, main.ts, conformance.ts
    ├── cart.test.ts                        # unit, and slow integration through the sidecar
    ├── service.json                        # {"image": "sample-shopping-cart-typescript:latest", "hosting": "process", "protocol": "1.0"}
    └── Dockerfile                          # node:24-slim; npm ci; build; CMD node dist/examples/shopping-cart/main.js

.gitignore                                  # sdks/typescript/src/_proto/, sdks/typescript/dist/, node_modules
.github/workflows/ci.yml                    # + sdk-typescript (contracts/ci-and-release.md)
.github/workflows/release.yml               # + sdk-typescript, needs: publish, environment npm
docker-compose.yml                          # comment: the profile serves any process on 9010, TypeScript included

docs/get-started/first-service-typescript.md, docs/reference/typescript-sdk.md     # NEW
docs/{index,concepts/polyglot,contributing/language-sdks,get-started/install,deploy/images}.md, mkdocs.yml
tools/docs/skill/ankka-typescript/SKILL.md                                          # NEW; committed copies regenerated
tools/docs/skill/{ankka-agents,ankka-endpoints,ankka-entities,ankka-workflows,ankka-views-consumers}/SKILL.md  # TypeScript differences
cli/src/main/scala/.../mcp/AnkkaTools.scala # "Scala, Python or TypeScript"
README.md, CLAUDE.md                        # commands; the manual first publish; the SDK in the module map
```

**Structure Decision**: the SDK lives in this repository under `sdks/` beside Python, for the reason
Python does: the protocol, the fixtures, the conformance suite and the SDK must move together, and a
tag must prove them consistent. It is one npm package with two entry points, `ankka` and
`ankka/testkit`, because a service's runtime dependencies must not include Docker tooling, and a
second package would double the release surface for no consumer benefit. Sources run directly under
Node (R1), so `src/`, `test/` and `examples/` share one `tsconfig` and one convention (`.ts`
imports); `dist/` exists only for the package. `materialise.ts` is one file on purpose: the server
and the unit testkit both call it, which is the shared-reduction rule from `CLAUDE.md` and the reason
the Scala testkit cannot disagree with the runtime. The reference service lives inside the example
rather than beside it, as Python's does, so the conformance run exercises the same code a reader
sees in the docs.

## Complexity Tracking

No constitution violations to justify. Three additions that could look like scope and are not:

| Addition | Why it is in this feature |
|---|---|
| A schema builder of the SDK's own (R3) | the encoding forces it: `int` versus `double`, `long` as `bigint`, byte-identical doubles and instants, declaration-order fields. A library would sit under a wrapper that does all of this; the wrapper is the builder. It is also what yields the types and the tools' JSON Schema, so one declaration serves three uses |
| Own `Instant` and `Duration` classes (R4) | `Date` cannot hold the fixtures' precision and `Temporal` is Node 26 only; four small value classes are cheaper than a wrong floor |
| A CI matrix on Node 22 and 24 for the fast tests (R1) | a declared floor that CI never runs is a claim; the matrix costs one more minute and makes it a fact |
