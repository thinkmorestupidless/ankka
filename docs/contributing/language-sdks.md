---
title: Adding a language SDK
description: What an SDK for a new language must do to host services on ankka, and how the encoding fixtures and the conformance suite prove it compatible without the platform knowing the language exists.
kind: contributing
related: [reference/sidecar-protocol.md, reference/wasm-abi.md, reference/java-sdk.md, reference/kotlin-sdk.md, reference/python-sdk.md, reference/typescript-sdk.md, reference/go-sdk.md, reference/rust-sdk.md, concepts/polyglot.md]
---

# Adding a language SDK

ankka hosts a service in a language off the JVM through the sidecar: the ankka runtime runs beside the
service's process and speaks a gRPC protocol to it. The platform never learns which language is on the other
end. A new language therefore needs no change to the platform, only an SDK, and an SDK is compatible when it
passes two things: the encoding fixtures and the conformance suite. The Python SDK in
[`sdks/python`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/python), the TypeScript SDK in
[`sdks/typescript`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/typescript) and the Go SDK in
[`sdks/go`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/go) are the working examples; the Go
SDK, a compiled language with no code generation at build time, is described in
[The Go SDK as an example](#the-go-sdk-as-an-example).

There is a second route for a language that compiles to WebAssembly: a **guest library**, which builds a
service to a module the runtime loads into its own process instead of running a process beside it. It
answers the same discovery and the same messages, across the module's linear memory rather than over gRPC,
and passes the same fixtures and the same suite. The Rust crate in
[`sdks/rust`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/rust) is the working example; see
[A guest library](#a-guest-library).

There is a third route for a language that runs on the JVM: an **embedded SDK**, a facade over the Scala SDK
that the runtime hosts in its own process, as it hosts a Scala service. There is no sidecar, no protocol and
no module: the facade reads the developer's classes when the service starts and hands the runtime the same
descriptors a Scala service registers. The Java SDK in
[`modules/sdk-java`](https://github.com/thinkmorestupidless/ankka/blob/main/modules/sdk-java) and the Kotlin
SDK over it in [`sdks/kotlin`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/kotlin) are the
working examples; see [An embedded SDK](#an-embedded-sdk).

## What an SDK does

1. **Serves the protocol.** It implements every service in the protocol except `Client`, on loopback at
   `ANKKA_PROCESS_PORT` (9010 by default), and calls the sidecar's `Client` service at
   `ANKKA_SIDECAR_ADDRESS` (`127.0.0.1:9011` by default). The definitions and the rules they do not state are
   in [Sidecar protocol](../reference/sidecar-protocol.md).
2. **Answers discovery.** It describes every registered component and endpoint in a `Spec`: kinds, component
   ids, handler wire names and whether each is read-only or streaming, each kind's details, and the protocol
   version it speaks. It logs whatever the sidecar reports through `ReportError`.
3. **Gives developers the component model in their language's idiom.** Components declared with a stable
   component id and handlers declared with wire names separate from method names; handlers that return
   effects as values rather than performing them; queries that can only return a read-only effect, refused at
   registration otherwise.
4. **Encodes values exactly as the Scala SDK does.** Its default codec produces and accepts the encoding in
   [`protocol/ENCODING.md`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md), so a
   journal written in one language is readable in the other.
5. **Ships testkits.** Unit testkits that run one component with no sidecar and still round-trip every value
   through its codec, and an integration testkit that starts Postgres and the real sidecar image.

## The protocol artifact

[`protocol/`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol) is what an SDK consumes: the
`.proto` files under `src/main/protobuf`, `ENCODING.md`, and `fixtures/`. Copy the whole directory into the
SDK so it builds on its own, and generate stubs from the copy; the Python SDK's `scripts/proto.py`, the
TypeScript SDK's `npm run proto` and the Go SDK's `scripts/proto.sh` do both.
Continuous integration checks that the copy is identical to the original, so a protocol change reaches every
SDK deliberately.

## The encoding fixtures

Each file in [`protocol/fixtures`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/fixtures)
is one encoded value: a manifest, a content type, the bytes in base64, and the value in a language-neutral
JSON form. The fixtures are generated from the Scala codecs, and a build check fails if regenerating them
would change a committed file, so they always describe what the Scala SDK actually writes.

The SDK's own test runner reads every fixture, decodes the bytes with the codec the manifest and content type
select, compares the result with the value, re-encodes it and compares the bytes. A fixture with no matching
codec is a failure, never a skip. The Python SDK's `tests/test_encoding_fixtures.py`, the TypeScript SDK's
`test/encoding-fixtures.test.ts` and the Go SDK's `fixtures_test.go` are the models.

## The conformance suite

`ConformanceSuite`, in
[`sidecar/src/test`](https://github.com/thinkmorestupidless/ankka/blob/main/sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/conformance),
is the platform's definition of a compatible SDK. It drives a reference service through that service's own HTTP
routes and reads the journal directly, so it works against any language. Each behaviour is one test case named
for what it checks, such as `es.refusal-persists-nothing`, so a failure names the behaviour.

It runs in two modes:

```bash
sbt 'sidecar/testOnly *ConformanceSuite'                                              # the Scala reference, in-process
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=127.0.0.1:9010    # a process listening there
```

With a target, the suite starts the sidecar against a throwaway Postgres and points it at the process
listening at that address. Without one, it runs the Scala reference service in-process, so both hosting modes
are held to one definition. Docker is needed for Postgres.

## The reference service

An SDK passes the suite with a reference service of its own: the same components, wire names and routes as
the Scala reference, `ConformanceReference`. They include a shopping cart entity, an entity whose handlers
exercise every effect, a key value entity, a workflow with a pause, a failure and a compensation, a view, a
consumer, a timed action, an agent with a tool and a guardrail, and three endpoints including one that
requires authentication. One route calls another service through the SDK's own client: the suite plays that
other service on loopback, records what it was sent, and reads back what the reference's handler got, so a
call to another service means the same thing in every language. The reference also declares an MCP endpoint
at `/mcp`, admitting client keys and tokens, with three tools: `get_cart`, which answers the cart beside the
principal's subject and claims; `add_item`, whose refusal is a tool error; and `checkout`, declared as a
reference to the cart's command. Two routes, `POST /mcp-keys` and `DELETE /mcp-keys/{id}`, issue and revoke a
client key through the SDK's own client keys, and `GET /mcp-keys` lists them. The `mcp-endpoint.*` cases drive
that endpoint with the raw protocol, so an SDK's MCP endpoint is held to the same answers as the Scala one. The Scala reference, `ConformanceReference`, beside the suite is the full list of
components, wire names and routes, and the suite itself names every behaviour. The behaviours are also
written down, one per case, in the
[conformance contract](https://github.com/thinkmorestupidless/ankka/tree/main/specs/009-polyglot-runtimes/contracts).

The Python reference service is the SDK's shopping cart example plus a conformance entity and endpoint, in
[`examples/shopping_cart`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/python/examples/shopping_cart).
`uv run conformance` in `sdks/python` serves it and runs the suite against it, and exits with the suite's
status. The TypeScript reference is the same shape, in
[`examples/shopping-cart`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/typescript/examples/shopping-cart),
served by `npm run conformance` in `sdks/typescript`, and so is the Go reference, in
[`examples/shopping-cart`](https://github.com/thinkmorestupidless/ankka/blob/main/sdks/go/examples/shopping-cart),
served by `go run ./cmd/conformance` in that directory. `ANKKA_CONFORMANCE_ONLY` narrows any of these runs to
matching behaviours, as a glob over the full case name (`'*es.*'`).

## The Go SDK as an example

The Go SDK is a process beside the sidecar, as the Python and TypeScript SDKs are, in a language that is
compiled, statically typed and fetched as source. The choices it makes are the ones a similar language faces:

- **The generated stubs are committed.** `go get` fetches a module's source and runs no generator, so the
  module carries its stubs in `internal/pb`, generated from its copy of the protocol by `scripts/proto.sh`
  with generators pinned in a nested `tools` module. `scripts/proto.sh --check` regenerates into a
  temporary directory and fails when the committed stubs differ, and continuous integration runs it beside
  the diff of the copy.
- **Components are values, built by generic constructors.** `ankka.EventSourced[State, Event](id, ...)` takes
  the component id, the event cases and the handlers, each declared with its wire name
  (`ankka.Command("add-item", addItem)`), and `ankka.NewService(...)` takes every component and endpoint
  explicitly. Nothing is found by reflection over a package.
- **The read-only rule is a type.** A handler declared with `ankka.Query` must return an
  `ankka.ReadOnlyEffect`, so a query that persists does not compile, and an endpoint with no access control
  does not either. The SDK's tests prove both by type-checking files that must fail
  (`testdata/compilefail`), with a control file that must pass, so the check cannot succeed by failing to run.
- **A refusal is an error.** A handler returns its effect and an `error`; `ankka.Refuse(code, message)` is an
  error the runtime answers with that code, and any other error is a failure.
- **The codec is planned once, from the type.** The default codec reads a type's fields by reflection when
  the component is declared, names each field by its `json` tag or else by its name with the leading capitals
  lowered, and refuses what the encoding cannot describe — an embedded field, an `any`, a tag option, an
  interface with no registered cases — as a problem at start rather than a failure at the first message.
  Sum types are interfaces whose cases are registered with `ankka.Cases`, each under its discriminator.
- **Every problem at once.** `Service.Problems` lists every problem with the declarations together, and
  `Start` refuses with all of them, before the process answers discovery.
- **Slow tests are opt in.** The unit test kits run in `go test ./...` with no Docker; the integration kit,
  which starts Postgres and the sidecar image, runs only under `ANKKA_SLOW=1`.

## A guest library

A guest library does what an SDK does, with the ABI in place of the gRPC services. Where an SDK serves the
protocol on a port, a guest library exports the `ankka1_` functions and calls the `ankka1` imports that
[WebAssembly ABI](../reference/wasm-abi.md) lists, and the language must compile to a WebAssembly core
module with no imports but those — no WASI. The rest is the same list: discovery (returned as a `WasmSpec`),
the component model in the language's idiom, the encoding, and testkits. The differences to design for:

- **The protocol artifact includes `WASM-ABI.md` and `wasm.proto`.** Copy them with the rest; the Rust
  crate's `scripts/proto.sh` does, and continuous integration diffs its copy as it does the SDKs'.
- **Both guest shapes.** A component is stateless unless the module lists it as stateful in `WasmSpec`;
  a stateful component keeps its decoded state between calls until `ankka1_close`. The reference service
  reads its shape from the `ANKKA_CONFORMANCE_SHAPE` variable through the `config` import, so the suite runs
  it in each.
- **No streaming.** A module answers every call whole, so the library offers no streaming handler or route.
- **Faults are traps.** A panic or an exception in a handler should be logged through the `log` import and
  then trap; the runtime discards the instance and answers the caller with a fault.

The suite runs against a module with a `wasm:` target, in either shape:

```bash
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=wasm:/path/to/service.wasm
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.target=wasm:/path/to/service.wasm -Dankka.conformance.shape=stateful
```

The Rust crate's `conformance.sh` builds its reference module and runs both.

## An embedded SDK

An embedded SDK is built in this repository, against the Scala SDK, and is released with it, because it
calls the runtime's own types. The five things an SDK does read differently for it:

1. **Serves no protocol.** The runtime calls the developer's handlers directly, on the same virtual threads
   a Scala handler runs on. A service is one process and one image, with `"hosting"` left at its default.
2. **Answers no discovery.** The facade reads every registered class once when the service starts and
   turns it into the Scala SDK's descriptors, refusing to start with every declaration problem listed. What
   the descriptors carry is what a Scala service's carry, so the runtime cannot tell the languages apart.
3. **Gives developers the component model in their language's idiom.** The Java SDK declares components as
   classes and handlers as annotated methods whose string is the wire name, called by method reference; the
   Kotlin SDK adds data classes, suspending handlers and a DSL over the Java types. The rules are the same:
   a stable component id, wire names apart from method names, effects as values, a query that can only
   return a read-only effect.
4. **Encodes values exactly as the Scala SDK does.** The facade's codec is its own, written for the
   language's types, and is held to the [encoding fixtures](#the-encoding-fixtures) as any SDK's is. The
   Java SDK's `RecordCodecFixturesSuite` reads them; the Kotlin SDK keeps a copy in `sdks/kotlin/fixtures`,
   which `./gradlew checkFixtures` compares with the original.
5. **Ships testkits** over the Scala testkits: unit kits that run one component, and a whole-service kit
   that starts the real runtime against Postgres. No sidecar image is involved.

The conformance suite runs an embedded reference in its own process, as it runs the Scala one, chosen by a
system property:

```bash
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.reference=java
sbt 'sidecar/testOnly *ConformanceSuite' -Dankka.conformance.reference=classpath:<file>:<class>
```

The second form names a file listing a class path and the reference's class on it, for a reference built
outside sbt; `just kotlin-conformance` builds the Kotlin reference with Gradle and runs it that way. A case
the reference cannot run yet is reported skipped, with the reason, never run against another language's
components under this one's name.

## Declaring a service

A service built with the new SDK is deployed like any other, with a descriptor that declares process hosting
and the protocol version its SDK speaks:

```json title="service.json"
{ "name": "cart", "service": { "image": "registry.example.com/acme/cart-rb:1.0.0", "hosting": "process", "protocol": "1.0" } }
```

The image holds only the process. The platform supplies the sidecar.
