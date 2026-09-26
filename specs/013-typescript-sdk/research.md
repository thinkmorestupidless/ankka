# Research: TypeScript SDK

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each finding says whether it was verified — against this repository, or against upstream sources
fetched on 2026-09-25 — or is an assumption a named task settles at implementation. The list at the
end collects the latter. The design treatment (`docs/design/typescript-sdk.md`) made the
recommendations; this document checks them.

## R1 — Node 22.22 is the floor, 24 is what the docs show, TypeScript 7

**Verified upstream** (nodejs.org release schedule, `nodejs.org/api/typescript.html`, the TypeScript
blog and npm dist-tags). Today Node 26 is Current and becomes Active LTS on 2026-10-28; Node 24 is
Active LTS until 2026-10-20 and then Maintenance to 2028-04-30; Node 22 is Maintenance LTS to
2027-04-30; Node 20 has been end-of-life since 2026-04-30. Native type stripping is on by default
from 22.18.0 and 23.6.0, without the experimental warning from 22.18.0 and 24.3.0, and *Stable* from
24.12.0 (the 22 line documents it as release candidate). Transform mode (`enum`, namespaces with
values) is flagged on 22 and 24 and **removed** in 26, so erasable syntax is the only portable
TypeScript. A `.ts` file must import other `.ts` files with the explicit extension; type stripping
is refused under `node_modules`. `typescript@latest` is 7.0.2, the Go-native compiler, whose
defaults are `strict`, `module: esnext` and `noUncheckedSideEffectImports`; `erasableSyntaxOnly`
exists since 5.8. The dependencies chosen in R2 and R7 need `>=22` (Connect) and `>=22.22`
(testcontainers).

**Decision**: `engines.node: ">=22.22.0"`; the getting-started page and the CI jobs use Node 24; the
SDK's own CI runs typecheck, unit tests and fixtures on both 22 and 24 so the floor is a fact and
not a claim. TypeScript 7 as a dev dependency. `tsconfig` as the Node docs recommend for
source-run TypeScript: `module: nodenext`, `erasableSyntaxOnly`, `verbatimModuleSyntax`,
`allowImportingTsExtensions`, `rewriteRelativeImportExtensions`, and `noEmit` for the check with
a second config emitting `dist/`. The SDK's sources, tests and examples import with `.ts`
extensions, run directly under `node`, and `tsc` rewrites the extensions when it emits the package.
The runtime check FR-018 asks for is the first statement of the package's entry module, comparing
`process.versions.node` against the floor and throwing a message that names it.

**Alternatives considered**: a Node 26 floor, which would give `Temporal` (R4) and Stable type
stripping, rejected because 26 is not LTS for another month and most of the audience is on 22 or 24
today; a Node 20 floor, which has none of `JSON.parse` source access, type stripping or
`require(esm)`, and is end-of-life. A build step for the developer's own service (`tsc` then
`node dist/main.js`) was rejected as the first-service experience: it is what the audience expects
to have to do, and the point is that they do not.

## R2 — Transport: Connect over HTTP/2, one codegen plugin, grpc-java confirmed by the conformance suite

**Verified upstream** (connectrpc.com docs, connect-es and protobuf-es releases and sources, the
Connect conformance runner, grpc-node issues). `@connectrpc/connect-node` 2.2.0 (2026-09-07,
`engines.node >=22`) serves the gRPC protocol over `node:http2`, including bidirectional streams;
over HTTP/1.1 it does not, so the sidecar-facing server is `http2.createServer(connectNodeAdapter({
routes }))`, plaintext, on loopback. A bidi handler's type is `(requests: AsyncIterable<In>, ctx)
=> AsyncIterable<Out>`, an async generator, and `router.service(EventSourced, impl)` checks every
method of the generated descriptor at compile time. `createGrpcTransport` from the same package is
the client for the sidecar's `Client` service (unary and server-streaming, HTTP/2 only). Protobuf-ES
2 (`@bufbuild/protobuf` 2.15.0, `@bufbuild/protoc-gen-es` 2.15.0) generates messages *and* service
descriptors from one plugin; `protoc-gen-connect-es` is the v1 line and no longer needed. `@bufbuild/buf`
1.73.0 on npm carries the `buf` binary as an optional platform dependency. Connect-node runs the
Connect conformance suite in server and client mode against the reference *and* an official grpc-go
peer, across unary, client-, server- and both bidi stream shapes. `@grpc/grpc-js` 1.14.5 is the
official implementation but its bidi server API is the Node `Duplex` event API, and the
`for await` shape hangs until the deadline (grpc-node #1839, open since 2021); `nice-grpc` 2.1.17 puts
an async-generator layer over it.

**Decision**: Connect. The handler shape is the Python server's asyncio shape and reads as ordinary
code, one plugin generates everything, and the interop evidence is a real gRPC peer. Codegen is
`buf generate` with `protoc-gen-es`, `target=ts`, `import_extension=ts` (R1: the SDK runs its
sources), into `src/_proto/` (gitignored, shipped in `dist/`). Client keepalive pings stay off over
loopback (a 2.1.2 defect threw on a destroyed session); `readMaxBytes` is set deliberately; the
`http2` server is created by the SDK so `maxSessionMemory` is its to set. The first task is still a
spike of the event sourced conversation against the sidecar image, because the evidence is grpc-go
and the peer is grpc-java; it confirms rather than decides. **Fallback** if the spike finds a
grpc-java defect Connect will not fix: `nice-grpc` over `@grpc/grpc-js` with `ts-proto`, which keeps
the async-generator handler shape and changes only the transport layer and the codegen.

**Alternatives considered**: grpc-js directly (rejected for the bidi ergonomics above and a 4 MB
default receive limit to remember); `@grpc/proto-loader` (untyped); Connect protocol instead of gRPC
(the sidecar speaks grpc-java, and nothing else).

## R3 — The codec is schema-directed; reading uses `JSON.parse` with source access, writing is the SDK's own

**Verified in this repository**: `protocol/ENCODING.md` and the 24 fixtures. Every field written
including `null` and `[]`; a `Long` past 2⁵³ must round-trip; doubles render as jsoniter does
(`1.0`, `1.0E10`); an `Instant` keeps 0, 3, 6 or 9 fractional digits; a fieldless enumeration as a
field is `{"type":"Ready"}`; the fixtures compare bytes, so field *order* matters and is the Scala
case class's declared order. The Python SDK wrote its own writer (`write_json`) and renders doubles
as Scala does (`_scala_double`). **Verified upstream**: `JSON.parse`'s reviver receives a third
`context` argument with `context.source`, and `JSON.rawJSON` exists, in V8 from Chrome 114 — Node
21.0.0 and every later line, so on 22, 24 and 26 unflagged.

**Decision**: a `Schema<T>` is the single declaration; the codec is derived from it. **Writing** is
the SDK's own writer walking the schema: fields in declaration order, every field written, integers
checked whole, `bigint` printed as digits, doubles through a Scala-`toString` renderer, instants and
durations through the SDK's value classes. **Reading** is `JSON.parse` with a reviver that turns any
integer-valued number outside the safe range into `BigInt(context.source)` (a double that large is
converted back by the schema), followed by a schema-directed decode that converts, checks required
fields and the `type` discriminator, and reports the path of a refusal. Unknown fields are ignored.

**Alternatives considered**: zod, valibot, ArkType, Effect Schema — none distinguishes an `int`
field from a `double` field in a way that survives to the writer, none renders doubles or instants
as the Scala codecs do, and all would sit beneath a wrapper that does; the wrapper *is* the schema
builder. A hand-written JSON parser (rejected: `JSON.parse` with source access is correct, fast and
already there). `JSON.stringify` with `rawJSON` for longs (rejected: it writes insertion order, not
schema order, and cannot render `1.0`).

## R4 — Time values are the SDK's own small classes, not `Date` or `Temporal`

**Verified upstream**: `Temporal` is a global only in Node 26.0.0 (V8 14.6); Node 22 and 24 do not
have it unflagged. `Date` has millisecond precision. **Verified in this repository**: the fixtures
carry instants with 6 fractional digits and the encoding allows 9; a `Duration` is ISO-8601
(`PT1.5S`); `FiniteDuration` at top level is `duration-millis` text.

**Decision**: `Instant` (epoch seconds and nanoseconds; `now()`, `parse()`, `fromDate()`,
`toDate()`, `toString()` with 0/3/6/9 digits preserved), `Duration` (`ofMillis`, `ofSeconds`,
`parse`, `toMillis`, `toString`), `LocalDate` and `LocalDateTime` as thin string-backed values. A
`Date` is accepted wherever an `Instant` is expected on write. When the floor reaches 26, `Temporal`
adapters are additive.

## R5 — What the type checker refuses, and how

**Verified in this repository**: the Scala `query` accepts only a `ReadOnlyEffect`, the Python
SDK refuses at registration, the sidecar refuses events from a read-only handler. **Verified
upstream**: TypeScript template literal types can parse `"/{cartId}/items"` into `{ cartId: string }`;
a discriminated literal field (`kind: "read-only"`) makes two otherwise-compatible object types
non-assignable; a generic constraint on `register<C extends EndpointClass>` refuses a class missing
a required static.

**Decision**: three compile-time guarantees, each backed at runtime. (1) `query(name, reply, run)`
types `run` as returning `ReadOnlyEffect`; `persist` builders produce `PersistEffect` with `kind:
"persist"`; registration checks `kind` again for JavaScript callers. (2) `register` constrains the
class type per kind, so a missing `componentId`, codec, `handlers` or `acl` is an error at the
registration line; the registry re-checks and reports every problem at once. (3) `req.params` is
typed from the template; the body and reply schemas are named beside the route. None of this needs
decorators, `reflect-metadata` or parameter properties, which type stripping cannot run (R1).

## R6 — The server holds one async loop per stream and two slots per workflow

**Verified in this repository**: `sdks/python/src/ankka/server.py` and the protocol README. One
command in flight per stateful stream, replies in order with the sidecar's id; a reply computed from
the state *after* its events, so the process folds them locally; snapshot included exactly when
requested; a workflow answers commands mid-step from the pre-step state and refuses a second
concurrent step; stream end releases state and cannot be told from passivation; a thrown handler is
a `Failure`, never a silent no-reply. The Python endpoint holds the request in a `ContextVar`.

**Decision**: each bidi handler is one async generator whose locals are the instance's state, and
commands are awaited in order inside it, which makes one-in-flight true without a lock. The
workflow generator spawns a step as a separate promise on a fresh instance and merges replies
through one queue. `this.request` is an `AsyncLocalStorage` store, the Node equivalent of the
`ContextVar`, with the same rule: endpoint instances are shared, per-request state lives on the
request. Every handler invocation is wrapped so a rejection becomes the request's `Failure`; the
process installs no global `unhandledRejection` handler that swallows, because a rejection that
escapes the wrapper is a defect to surface, not to hide. Connect's `context.signal` and the
server's `shutdownSignal` end streams cleanly on stop.

## R7 — Testkits: `node:test` for the SDK's own tests, testcontainers 12 for integration, DDL out of the image

**Verified upstream**: `node --test` includes `**/*.test.{ts,mts,cts}` by default on 22.18+, 24 and
26 and runs them through type stripping with no loader; the `.ts` default glob was narrowed once
(nodejs/node#57359) and the current docs and source disagree on its exact shape, so the scripts pass
explicit globs. vitest 5.0.2 runs TS tests with zero config and needs `^22.12 || ^24 || >=26`.
`testcontainers` 12.1.0 and `@testcontainers/postgresql` 12.1.0 (2026-08-04, `engines.node
>=22.22`): `withExtraHosts([{ host: "host.docker.internal", ipAddress: "host-gateway" }])` renders
Docker's `--add-host`; `withCopyDirectoriesToContainer` copies before start, root-owned with the mode
given; `PostgreSqlContainer(image)` takes the image and has no init-script API of its own;
`Wait.forHttp(path, port).forStatusCode(200)`; `restart()` re-runs the wait strategy. The library
never sets `host.docker.internal`; its own mechanism is `host.testcontainers.internal` via an sshd
side-container. Copying out of an image without running it has no public high-level API; it is
`getContainerRuntimeClient()` → `container.create` → `fetchArchive` (dockerode) → untar, or `docker
create` + `docker cp` from a shell. **Verified in this repository**: the Python testkit shells out
to `docker create` and `docker cp` to take `/opt/docker/ddl` out of the sidecar image, `chmod`s the
directory 0755 (the Linux bind-mount trap), sets the extra host unconditionally, and waits on
`/_ankka/health`.

**Decision**: the SDK's own tests and the examples' tests run under `node --test` with explicit
globs (zero dependencies, and it is what the docs will show); the testkits depend on no runner and
the reference page says vitest works. The integration testkit uses testcontainers for Postgres and
the sidecar, copies the DDL out of the sidecar image with `docker create`/`docker cp` as Python does
(no tar dependency, one known-good shape), and puts it into Postgres with
`withCopyDirectoriesToContainer` rather than a bind mount, which sidesteps the permission trap by
construction — *verify at implementation* that the copied directory itself is world-readable for the
entrypoint's `ls`. The extra host is set unconditionally.

## R8 — Publishing: npm trusted publishing needs the package to exist, so the first publish is manual

**Verified upstream** (docs.npmjs.com trusted-publishers, staged-publishing, provenance; npm/cli
#8544). Trusted publishing needs npm ≥ 11.5.1; Node 22 bundles npm 10.9.9 and Node 24 bundles
11.19.0, so the release job runs on Node 24. A trusted publisher is configured on an *existing*
package's settings; there is no pending-publisher shape, and the issue asking for one (#8544) is
open. Since 2026-09-03 a new trusted-publisher configuration defaults to stage-only, and direct
`npm publish` is an opt-in per configuration. Provenance is automatic under trusted publishing;
`repository.url` must match the repository, with `directory` for a package in a subdirectory.
"Require 2FA and disallow tokens" leaves trusted publishers working. `files` in `package.json`
overrides `.gitignore` for packing (confirmed by experiment), so gitignored generated code ships
when it is under a listed directory. `engines` is advisory unless the installer sets
`engine-strict`.

**Decision**: the first release that includes this SDK publishes the package **once by hand**, from
a laptop with 2FA, at the tag's version, after `sonaRelease` has succeeded for the same tag; the
trusted publisher is then attached (repository `ankka`, workflow `release.yml`, environment `npm`,
`npm publish` allowed) and every later tag publishes from the `sdk-typescript` job with no token.
The job sets the version from the tag into the one placeholder (`0.0.0` in `package.json`, mirrored
into `src/version.ts` by the build), runs `npm run proto`, `npm run build`, `npm pack`, installs the
tarball into an empty directory and imports it, then `npm publish` — after `publish`, like the
Python job, and refusing to run if the package already has that version. `package.json` carries
`"type": "module"`, `files: ["dist"]`, `exports` for `.` and `./testkit` with `types` first,
`repository` with `directory: "sdks/typescript"`, `engines.node` from R1, and no `.npmignore`.

**Alternatives considered**: publishing 0.0.0 as a placeholder to create the package (rejected:
a version on npm can never be removed, and a placeholder is a permanent broken install for anyone
who finds it); a granular token in a repository secret (rejected: every other artifact publishes
without a stored credential, and the one-time manual publish achieves the same end state).

## R9 — The documentation is on the Python trail, and the tooling needs nothing new

**Verified in this repository**: `tools/docs/src/ankka_docs/snippets.py` recognises `docs:start`
/ `docs:end` in any comment syntax; `pages.py` excludes only `design/`; `mkdocs.yml` `nav` and every
skill's `pages:` list must name a new page or `docs check` fails; the `ankka-python` skill names
seven pages; the agents, endpoints and entities skills each carry a "Python differences" section;
the pages that say "Scala or Python" are `docs/index.md`, `concepts/polyglot.md`,
`contributing/language-sdks.md`, `get-started/install.md`, `deploy/images.md`, the site description
in `mkdocs.yml` and the CLI's MCP tool descriptions (`AnkkaTools.scala`). Reference pages pin the
released version (`ankka==0.4.0`).

**Decision**: `get-started/first-service-typescript.md` and `reference/typescript-sdk.md` with the
Python pages' section structure; a `tools/docs/skill/ankka-typescript/SKILL.md` naming the same kinds
of pages; a "TypeScript differences" section beside each "Python differences" section; the language
lists updated. The pages pin the *next* release's version, the same rule the Python pages followed
when 0.4.0 was the first version on PyPI. Every code block on the TypeScript pages is an include
from `sdks/typescript/examples` or `test`.

## R10 — The reference service and the two portability proofs

**Verified in this repository**: `specs/009-polyglot-runtimes/contracts/conformance.md` names the
eight components, three endpoints and forty-nine behaviours; the Python reference is the cart plus
`conformance.py` (`Conformance`, `Profile`, `CheckoutRecorder`, `Reminder`, `ConformanceAssistant`,
`ConformanceEndpoint` with `/problems`, `PrivateEndpoint`); `ConformanceTarget.scala` reads
`-Dankka.conformance.target`; `uv run conformance` serves the reference in a thread and runs sbt
with that property, `-Dankka.cluster.tests=off -Dankka.template.tests=off`, and `ANKKA_CONFORMANCE_ONLY`
narrowing; `test_journal_portable.py` uses `start_beside` with the `sample-shopping-cart` image.

**Decision**: the TypeScript reference is the shopping cart example plus a `conformance.ts` with the
same components, wire names and routes, and `npm run conformance` does what `uv run conformance`
does. The journal portability test starts the Scala cart image beside the TypeScript cart on one
Postgres, in both directions. A three-way proof with the Python cart is not needed: two SDKs each
proven against Scala are proven against each other through the fixtures.

## Measurements (2026-09-26, a MacBook, Docker Desktop, Node 24.15)

| What | Measured |
|---|---|
| `npm test` (124 tests: fixtures both ways, servicers through an in-process Connect client, every unit kit, the client against a fake sidecar, registration) | about 4 s of test time; the whole command about 6 s |
| `npm run test:slow` (the cart through the real sidecar with a restart, every route, journal portability both ways with the Scala image) | 8 tests in 48 s: restart case 13.7 s, routes 5.2 s, portability 14.0 s |
| `npm run conformance` (all 49 behaviours through the in-JVM sidecar) | 44 passed, 5 skipped by the suite's own assumptions, 0 failed; 17 s of suite time after sbt's compile |
| The getting-started page from an empty directory: `npm init`, install the SDK and the dev dependencies, copy the four files, `tsc --noEmit`, `node --test` | 16 s of tool time; then `node main.ts` beside the compose sidecar answered the first `curl` within 5 s of the sidecar reporting healthy. SC-004's fifteen minutes is a reading-and-typing budget; the machinery is under a minute |
| Packaging: `npm run build`, `npm pack`, install into an empty project, import both entry points | Node 24.15 and 22.22 import; Node 20.20 is refused with `ankka needs Node.js 22.22.0 or later` |
| The codec's cost against the Python cart through one sidecar | not measured: the two SDKs were not run against one sidecar in one session. The conformance suite's `obs.one-span-per-invocation` and the restart cases hold for both, and the sidecar's SC-003 from feature 009 is a property of the loopback hop the SDK does not change |

## Verify at implementation

| # | Assumption | Settled by |
|---|---|---|
| V1 | Connect's bidi stream against **grpc-java** (the sidecar) behaves as it does against grpc-go: init, replay, commands, snapshot request, clean close on passivation, abort on sidecar restart | **Answered 2026-09-26 (T004)**, against `ankka-sidecar:latest` from compose: discovery completed; `POST /spike/e1` forwarded over `Http.Handle`, the process called `Client.Invoke` back, the sidecar opened the entity stream with `Init` and journaled three events with manifest `JournalRecord`; `snapshot_requested` arrived on command 2 (`snapshotEvery = 2`) and the snapshot row sat at sequence 3; after `docker compose restart sidecar` the new stream began with `Init(snapshot seq 2)` and replayed event 3; a graceful sidecar stop ended the generator **cleanly** (indistinguishable from passivation, as Python found), `docker kill` surfaced as a thrown `Premature close`. Connect it is; no fallback needed. One thing the spike found that the plan did not: protoc-gen-es emits TypeScript `enum`s unless `erasable_syntax=true`, and type stripping refuses them — the option is set in `buf.gen.yaml` |
| V2 | `withCopyDirectoriesToContainer` leaves the initdb directory readable by the `postgres` user on Linux (the 0700 trap in the other direction) | **Open**: passes on macOS (Docker Desktop, 2026-09-26); the `sdk-typescript` CI job on `ubuntu-latest` is the first Linux run. The testkit also `chmod`s the copied directory 0755 and its files 0644 before the copy, so the Python fix is in place either way |
| V3 | `node --test` with explicit globs collects only `*.test.ts` and no `.d.ts` under 22.22 and 24 | **Answered (T005)**: with `'test/**/*.test.ts'` an `other.test.d.ts` beside `a.test.ts` is not collected on 22.22.2 or 24.15.0 (1 test, 1 pass, both) |
| V4 | `await using` (explicit resource management) is available on Node 24 and not 22; the testkit exposes `Symbol.asyncDispose` and `stop()` and the docs use `try`/`finally` | **Answered (T005)**: runs on 24.15.0 (`using ok`, `disposed`), a `SyntaxError` on 22.22.2. The testkit offers both; the docs show `try`/`finally` |
| V5 | `rewriteRelativeImportExtensions` rewrites the generated `_pb.ts` files' `.ts` imports on emit, so `dist/` resolves | **Answered (T006)**: `tsc -p tsconfig.build.json` emits `dist/_proto/**/*_pb.js` with `from "./payload_pb.js"`, and `import("./dist/_proto/ankka/protocol/v1/discovery_pb.js")` loads (`Kind.AGENT = 6`) |
| V6 | The trusted-publisher configuration accepts `npm publish` from a job with `environment: npm` and provenance appears on the package page | **Open**: the second tagged release after the manual first publish; the job is written (`release.yml`, `sdk-typescript`) and its packaging half is proven locally on Node 22.22 and 24.15 (tarball into an empty project, both entry points import, Node 20 refused with the floor message) |
| V7 | The sidecar's `SdkInfo.name` for this SDK, `ankka-typescript`, appears in the console and in `services get` without any platform change | **Open**: the in-JVM sidecar of the conformance suite reports `sdk` from discovery and all 49 behaviours pass with it; the kind deployment (quickstart tier 6) was not run on 2026-09-26 because this machine has no `ankka` kind cluster and its host port 8080 is held by another kind cluster, so `kind create cluster --config kustomization/kind.yaml` cannot bind. The sample image builds and starts (`sample-shopping-cart-typescript:latest`, 7 classes registered) |
| V8 | `Http2SessionManager` defaults (no pings, 15-minute idle) keep the callback channel healthy across a sidecar that is idle for longer than that, or the client reconnects transparently | **Answered by design, not by a timed test**: Connect's `Http2SessionManager` reopens a closed session on the next request and `createGrpcTransport` is created once per connection, so an idle close costs one reconnect. The sidecar restart cases in the conformance suite (`es.recover-after-restart`, `wf.survives-restart-mid-step`) exercise the client after its peer went away and came back, and pass. A test that idles past fifteen minutes would take fifteen minutes and was not written |
