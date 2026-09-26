# Design treatment: a TypeScript SDK

**Status**: treatment, fed to `speckit-specify` as feature 013 (`specs/013-typescript-sdk`).
**Date**: 2026-09-25

## The audience

Developers who use Node.js for their backend work. Not "the agents audience" that chose Python;
people who already run services on Node and would take a framework that lets them build larger
systems in a highly structured way, in code they can read and understand, inside a paradigm most of
them have not met: entities, events, effects as values, workflows as durable step machines.

That audience decides more of this design than the protocol does. Three consequences run through
everything below:

- **Readable before clever.** A service written with this SDK must read like ordinary modern Node:
  classes, `async`/`await`, `AsyncIterable` for streams, discriminated unions with a `type` field,
  `Record<string, T>`, `Uint8Array`, `bigint` where a number is genuinely 64 bits. No decorators, no
  code generation from the developer's own code, no dependency container, no `reflect-metadata`.
  Anything the runtime needs is written down once, where a reader can see it.
- **The structure is the product.** The component model is what makes a large project tractable,
  so the SDK enforces it rather than suggesting it: explicit registration, wire names separate
  from method names, queries that cannot persist, endpoints whose ACL must be stated. Where
  TypeScript can make a rule a compile error, it does, and the error names the rule.
- **The paradigm is taught by the docs and the skills.** A Node developer's first contact is a
  getting-started page and, increasingly, a coding agent holding an ankka skill. Both exist for
  Python and are the template. The SDK's job is to make what they teach true in the editor: a
  schema declared once yields the TypeScript type, so nothing is written twice.

## What already lines up

Feature 009 was built so that a third language is a project in `sdks/`, not a platform change,
and it is. Nothing changes in the sidecar, the operator, the control plane, the CRD or the
descriptor. A TypeScript service declares `"hosting": "process", "protocol": "1.0"` exactly as a
Python one does, and the sidecar never learns the language.

The SDK is measured against three artifacts it copies in:

- **The protocol**: `protocol/src/main/protobuf`, eleven files, plus the rules its README states
  that the messages do not (one command in flight per stateful conversation; a workflow's command
  slot and step slot; a plan names, it does not carry).
- **The encoding**: `protocol/ENCODING.md` and its fixtures, which every default codec must decode
  and re-encode byte for byte, with no skips.
- **The conformance suite**: `sidecar`'s `ConformanceSuite`, 49 cases driven from the sidecar's side
  against a reference service. The reference is fully specified by the Scala `ConformanceReference`
  and `specs/009-polyglot-runtimes/contracts/conformance.md`. This is the acceptance test, and it
  exists before a line of TypeScript is written.

The Python SDK is the working example and the yardstick: about 2,900 lines of source, 840 of
testkits, 1,100 of tests and 1,070 of examples. Everything around it has a one-to-one analogue:
the `sdk-python` CI job, the `sdk-python` release job, the compose `polyglot` profile, the
first-service page, the reference page, the `ankka-python` skill and the "Python differences"
sections of the shared skills, and `docs/contributing/language-sdks.md`, which was written for
exactly this moment.

## What does not line up

**No runtime types.** Python infers every codec from dataclass annotations. TypeScript erases its
types, so every state, event, command, reply and row needs a runtime schema. That would be true
of any TypeScript SDK; ankka's encoding makes it sharper than "use a validation library":

- `Int` and `Double` are one JavaScript type. A Scala `Double` of one is written `1.0` and an `Int`
  as `1`; `JSON.stringify` cannot tell them apart. The schema must say which every numeric field is.
- A `Long` beyond 2⁵³ must round-trip losslessly. `JSON.parse` corrupts it. Longs are `bigint`, and
  reading needs a hand-written parser or the reviver's source-text access (Node ≥ 21).
- Doubles render as jsoniter does (`1.0E10`), and an `Instant` keeps 0, 3, 6 or 9 fractional
  digits. The Python SDK wrote its own JSON writer for these reasons; so will this one.

**Path parameters cannot be bound by inspection.** The Python endpoint reads a handler's signature
to bind `{cartId}` by name. TypeScript has no signature at runtime. Template literal types can
type `"/{cartId}/items"` as `{ cartId: string }` at compile time, so the developer still gets typed
parameters, but the schema of any body is declared beside the route.

**A test that types `.ts` needs no build, on a new enough Node.** Node strips types natively from
22.18 (unflagged in 23.6), for *erasable* syntax only: no `enum`, no parameter properties, no
decorators. That is one more reason the SDK uses none of them, and it lets the first-service page
run `node main.ts` with no compiler configuration, which is the first thing a Node developer will
judge the framework by.

## The shape

1. **Components are classes with a static declaration.** The same picture the entities, endpoints
   and agents skills already draw for Scala and Python, so their "differences" sections stay short.
   Identity and codecs are static fields; handlers are declared in a static table with the wire name
   separate from the method, which is the repository's versioning rule stated directly:

   ```ts
   export class ShoppingCartEntity extends EventSourcedEntity<Cart, CartEvent> {
     static readonly componentId = "shopping-cart"
     static readonly state = jsonCodec(Cart, "shopping-cart")
     static readonly events = jsonCodec(CartEvent, "shopping-cart-event")
     static readonly handlers = {
       addItem: command("add-item", LineItem, Done, (cart, item) => cart.addItem(item)),
       getCart: query("get-cart", Cart, (cart) => cart.getCart()),
     }

     emptyState(): Cart { return { cartId: this.entityId, items: [], checkedOut: false } }

     addItem(item: LineItem) {
       if (this.state.checkedOut) return this.effects.error("cart is checked out", ErrorCode.Conflict)
       return this.effects.persist({ type: "ItemAdded", item }).thenReply(() => done)
     }

     getCart() { return this.effects.reply(this.state) }

     applyEvent(state: Cart, event: CartEvent): Cart { switch (event.type) { … } }
   }
   ```

   `query` accepts only a handler returning `ReadOnlyEffect`, a branded type distinct from
   `EventSourcedEffect`, so "a query cannot persist" is a compile error here, as in Scala. The spec
   for 009 assumed a second language could not have that; this one can.

2. **A small schema builder, not a validation library.** `record`, `sumType`, `enumeration`, `int`,
   `long`, `double`, `boolean`, `string`, `instant`, `duration`, `option`, `list`, `stringMap`,
   `bytes`, `recursive`. `Infer<typeof Cart>` yields the TypeScript type, so the developer writes
   the shape once. It reads like the libraries a Node developer already knows and does three
   things they cannot: distinguishes `int` from `double`, carries `long` as `bigint`, and renders
   every value exactly as the Scala codecs do. It also emits JSON Schema, which is what an agent
   tool's input needs and what Python derives from a dataclass. A sum type on the wire
   (`{"type":"ItemAdded", …}`) *is* a TypeScript discriminated union, so the value a developer
   handles is the JSON in the journal; nothing is translated. A zod adapter is a possible follow-on,
   never the foundation: the encoding is the platform's contract, not a library's.

3. **Effects are fluent builders over frozen values**, spelled as in Scala with JavaScript casing:
   `persist(e).thenReply(s => …)`, `updateState(s).thenTransitionTo("charge", input)`,
   `thenPause({ after, onTimeout })`, `updateRow`, `produce`, `systemMessage(…).tools(…).thenReply()`.
   Every handler may be `async`. The component client is `await client.forEventSourcedEntity(id,
   key).call("add-item").invoke(item, Done)`; a token stream is an `AsyncIterable<string>`. Sequential
   code, which is the reading virtual threads give Scala and asyncio gives Python.

4. **Endpoints declare routes with their schemas and a mandatory ACL.** `get("/{cartId}", Cart,
   (req) => …)`, `post("/{cartId}/items", LineItem, Done, (req, item) => …)`, `sse(…)` returning an
   `AsyncIterable<string>`. `req.params.cartId` is typed from the template. `acl` has no default; an
   unstated one is a decision nobody made.

5. **Transport: Connect over HTTP/2, speaking gRPC.** `@connectrpc/connect-node` with
   `@bufbuild/protobuf`. Its server API for a bidirectional stream is an async generator over an
   `AsyncIterable` of requests, which is the per-instance conversation exactly as the Python server
   writes it with asyncio, and it reads as ordinary code. `@grpc/grpc-js` with `ts-proto` is the
   conventional fallback if the spike finds a fault against grpc-java. Stubs are generated at build
   time into a gitignored directory and shipped in the package, as the Python wheel does. Only
   loopback (or `0.0.0.0` for the testkit) is ever bound.

6. **Two testkits, runner-agnostic.** A unit testkit that runs one component with no sidecar and
   round-trips every value through its codecs, so a shape the codec cannot express fails in a unit
   test. An integration testkit on `testcontainers` for Node that starts Postgres, copies the DDL
   out of the sidecar image, starts `ankka-sidecar:latest`, and offers `restart()` so a test proves
   durability rather than caching. Neither depends on a test runner; the docs use `node --test`
   because every Node developer has it, and say that vitest works.

7. **The reference service, the shopping cart port, and the proof.** `examples/shopping-cart` with
   the same components, wire names and routes as the Scala reference, a `conformance` script that
   serves it and runs the sbt suite against it, a journal portability test in both directions
   against the Scala image, and a Dockerfile on `node:22-slim`. The `sdk-typescript` CI job mirrors
   `sdk-python` line for line: diff the protocol copy, build the sidecar image, generate, typecheck,
   test, pack, install the tarball into an empty directory and import it, run conformance.

8. **Published to npm from a tag.** Version `0.0.0` in `package.json`, rewritten from the tag as the
   other three placeholders are; OIDC trusted publishing with provenance, the shape of the PyPI
   job; after `publish`, for the same reason. The version the package reports to the sidecar in
   discovery is the one on npm. `ankka` and `@ankka/sdk` were both unclaimed on 2026-09-25.

9. **Docs on the Python trail.** `get-started/first-service-typescript.md`,
   `reference/typescript-sdk.md`, an `ankka-typescript` skill, a "TypeScript differences" section in
   each shared skill, and edits wherever the tree says "Scala or Python": the polyglot concept, the
   language-SDKs contributing page, install, images, the site description and the MCP tools'
   description. Samples are included from tested code with `// docs:start` markers, which the
   snippet extractor already accepts in any comment syntax.

## Decisions for the spec

1. **Package name.** `ankka`, unscoped, mirroring PyPI, unless a scope is wanted for a family of
   packages later. npm's trusted publishing may need the package to exist before a publisher can be
   attached, so the first release may be a one-time manual publish; verify during planning.
2. **Node floor.** 22 LTS, and 22.18 or later so `.ts` runs without a build. ESM only.
3. **Transport.** Connect, confirmed by a spike of the event sourced conversation against the real
   sidecar before the plan commits.
4. **Schema.** The SDK's own builder, as argued. Whether a zod adapter is in scope: recommend no.
5. **Scope.** Every component kind, endpoints and agents in one feature. The conformance suite is
   the definition of compatible and its reference needs all of them.
6. **`ankka init`.** Out of scope, as for Python; the first-service page starts from `npm init`.
7. **Where it lives.** `sdks/typescript`. The package targets Node, but the language is what the
   developer chooses and what the docs name.

## The cost, stated once

The treatment for 009 quoted the lineage's warning about SDKs that never earned their upkeep. This
is where it starts to apply: every protocol minor now touches three SDKs, every shared skill gains a
third differences section, and the fixtures have a third codec to keep byte-identical. The audience
above is the reason on record. The conformance suite is what keeps the cost bounded: an SDK is
compatible when it passes, and the platform never has to know it exists.

## Order of work

1. The spike: one event sourced entity over Connect against the running sidecar image, with the
   compose `polyglot` profile. Proves the transport and the stream shape; nothing else depends on
   more than this.
2. The codec and the fixtures. The writer, the long-safe reader, and `test_encoding_fixtures`'
   analogue passing every fixture both ways. Everything downstream carries values through it.
3. Event sourced entity, the server, discovery, the component client, the unit testkit. The cart
   passes the `es.*` and `discovery.*` cases.
4. Key value, view, consumer, timed action, workflow with its two slots; then endpoints; then the
   agent conversation. The conformance families in that order.
5. The integration testkit, the reference service complete, `conformance` green, journal
   portability both ways.
6. Packaging, the CI and release jobs, the docs and the skill.
