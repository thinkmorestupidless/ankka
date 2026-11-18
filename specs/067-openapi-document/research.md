# Research: an OpenAPI document of a service's HTTP endpoints

Decisions for the plan, each with what was declined. Read from the tree at `ef6f371eb` (main after
066), by three readers and checked where a decision rests on it.

## What the tree already gives

- **Routes are values, without types.** `HttpEndpoint` collects `Route`, `StreamRoute` and
  `SocketRoute` in its constructor (`modules/http/.../HttpEndpoint.scala`). Each holds a method, a
  `PathTemplate`, `needsBody`, a closure and an ACL. The `FromBody[Body]` and `ToResponse[Out]`
  instances are used where the route is declared and not kept. `PathArguments.parsers` keeps each
  path parameter's `FromPath`, whose `name` is `string`, `int`, `long`, `boolean` or `uuid`.
  Routes are also built in `bridge/Routes.scala` (Java), `mcp/McpEndpoint.scala`,
  `mcp/signin/SignInBroker.scala` and `sidecar/RemoteEndpoint.scala`.
- **Endpoints can be built with no service.** `ControlPlaneRoutesReferenceSuite` and
  `AuthorizationMatrixSuite` each build `EndpointClients` over a transport that fails when called
  and a `ViewClient` with a null system, and read `endpoint.routes`. Two copies, in test code.
- **Three partial schema derivations, none reachable from `http`.** `agent.SchemaType` (scalars,
  hand-written, with a decoder), `agent.autonomous.JsonSchema` (products by `Mirror`, no sums, no
  `$ref`, `Option` without `null`, defaults counted as required) and `agent.blueprint.Shape` (a
  checked subset with a value checker). `agent.Json` holds objects in a `Map`, so key order is not
  the declaration's. `http` does not depend on `agent`, by design (`build.sbt`, the comment above
  `mcp`).
- **An ordered JSON tree in `core`.** `core.graph.GraphJson` (`Obj(fields: Vector[(String,
  GraphJson)])`, `Num(BigDecimal)`, a parser and a codec), `private[ankka]`.
- **The wire is `Codecs.make`.** Discriminator `type`, not required first; `None` written as
  `null`; empty collections written; a field equal to its default omitted (jsoniter's
  `transientDefault`, which `make` leaves on; `.claude/rules/control-plane.md` says so, while
  `protocol/ENCODING.md` says every field is written); unknown fields skipped on read; a fieldless
  enumeration case is `{"type":"Ready"}` unless its companion gives a string codec.
- **Java already has a plan of a type.** `javasdk.codec.Planner.plan(Type)` gives a `Plan`
  (record, sum, enumeration, optional, sequence, dictionary, leaves with formats, a reference for
  recursion), and `Schemas.of(Plan)` writes a JSON Schema from it for MCP tool arguments, inlined.
  A Java `RoutePlan` keeps the handler's `Method`, so its body and answer types are recoverable.
  `@QueryParam` parameters are declared. Kotlin's `routes { }` DSL keeps a body's type and no
  answer type (its handler answers `Any?`).
- **No one-shot mode.** Nothing makes `ServiceBuilder.start` do other than serve. `ankka local
  run` passes a system property into whatever JVM the build tool starts through
  `JAVA_TOOL_OPTIONS` (`LocalRun.runningDirectory`), and `SourceCommands.default` knows each
  language's run command and `SourceCommands.language` each service's language.
- **The reserved prefix.** `Router.handle` answers `/_ankka/health` and `/_ankka/storage` before
  any endpoint; `validate` refuses an endpoint under `_ankka`. A local service's observability
  endpoint serves documents the runtime is handed as plain data (`routes`).
- **Tests reach a service over real HTTP in one JVM.** There is no channel from the server to the
  test; `LogCapturing` is the munit shape that fails a test from what was recorded.

## R1. One derivation of a type's shape, in `core`

**Decision**: a new `core.shape` package holds `Shape` (the model: object with ordered properties
and required names, array, dictionary, one-of with a `type` constant, scalar with an optional
format, nullable, a reference by name, raw JSON Schema) and `ShapeOf[A]`, a typeclass derived by a
Scala 3 macro for products, sealed hierarchies and enumerations, with instances for the scalars,
`Option`, the collections, `Map[String, A]`, `Instant`, `Duration`, `LocalDate`, `UUID` and
`BigDecimal`. A named type is emitted once and referred to. It says what `Codecs.make` writes:

| Type | Shape |
|---|---|
| a case class | an object; properties in declaration order; `required` every field that is neither an `Option` nor has a default; no `additionalProperties` (the reader skips unknown fields) |
| `Option[A]` | `A`'s shape or `null` |
| a sealed hierarchy, an enumeration with fields | one-of its cases, each an object with a required `type` constant of the case's name |
| a fieldless enumeration case | an object with a required `type` constant |
| a type whose companion gives a `JsonValueCodec` | none, unless the companion also gives a `ShapeOf` |
| a self-referring type | a reference to its own name |

`ShapeOf.Maybe[A]` is what a route asks for: the derived shape, or none when the type has no
derivation. A missing shape never fails a compile; it makes the route incomplete.

**Rationale**: it has to be a macro, not a `Mirror` fold, because a `Mirror` does not say which
fields have defaults or what a companion defines, and both change the wire. It has to be in `core`
because `http` must see it and must not see `agent`. The hand-written-codec rule follows the
platform's own convention (the codec lives in the companion, CLAUDE.md) and is the only case where
a derived shape would be confidently wrong.

**Declined**: reusing `agent.autonomous.JsonSchema` (wrong on three of the rules above, and in the
wrong module); moving the three existing derivations onto `ShapeOf` in this feature (worth doing,
and a change to agents' and MCP's tool schemas that is not this feature's to make; noted for a
follow-up); asking jsoniter for a codec's schema (it has none).

**Verify at implementation**: jsoniter's treatment of a defaulted field and of a missing
collection, by fixture, before the derivation is written; `ShapeAgreesSuite` (R10) then holds
every rule in the table to `Codecs.make` by round trip. `protocol/ENCODING.md`'s "every field is
written" is corrected in this feature's documentation task, since the schema rule depends on the
true behaviour.

## R2. A description is a scope around routes

**Decision** (the person's): `described(_.summary(...).body[A].answers[B].refuses(404, "not
found").query[Int]("page").tag("player")) { ...routes... }` on `HttpEndpoint`, built as `withAcl`
is: a scope that the routes declared inside it carry. Scopes nest and add. A scope may hold
several routes for what they share (tags, refusals, the refusal shape, query parameters); a
summary, a body shape or an answer in a scope that holds more than one route is refused when the
document is built, naming the routes. An endpoint gives what all its routes share by wrapping its
whole body or by overriding `description`.

Route declarations keep answering `Unit`. `Route` and `StreamRoute` gain one field with a default,
`shape: RouteShape`, filled where the route is recorded from the `ShapeOf.Maybe` of its body and
answer, the `FromPath` names of its path parameters, the `ToResponse`'s fixed status and content
type, and the scope's description. The other four places that build a `Route` compile unchanged.

`FromBody.json` and `ToResponse.json` each take one more `using`, a `ShapeOf.Maybe[A]`. Source
compatible; a service compiled against the previous release must be recompiled, as for any minor
release before 1.0 (`versionScheme` is `early-semver`, there is no MiMa).

**Declined**: a chained `.describe` on the route (route methods would answer a value, which
`-Wvalue-discard` refuses as the last statement of every `withAcl` block in the tree, and is a
binary break); a statement before the route (paired by position); annotations on Scala routes
(routes are values, not methods).

## R3. The document is built in `http`, from endpoints built with no service

**Decision**: `http.openapi` holds `OpenApi.build(serviceName, version, endpoints, selection):
Either[Vector[String], Document]` and `Document.render: String`. No new module. `EndpointClients
.unavailable` (new, `private[ankka]`) is the stand-in the two reference suites build by hand
today; they move onto it. An endpoint whose factory throws is reported by the first frame of the
failure that is a constructor of an `HttpEndpoint` subclass, with the cause.

`RuntimeExtension` gains `def document(kind: String, options: Map[String, String]):
Option[Either[Vector[String], String]] = None`, plain data as `routes` is, so `runtime` still
names nothing of `http`. `HttpServer` answers kind `openapi`.

**Rendering**: over `GraphJson`, pretty-printed with two spaces and a final newline. Order is
fixed and written down in `contracts/document.md`: `openapi`, `info`, `paths`, `components`; paths
sorted; methods in a fixed order; properties in declaration order; schemas sorted by name. Nothing
in it comes from a clock, the environment or a hash order.

**Schema names**: the type's simple name; two distinct types with one simple name each take the
shortest trailing part of their package that tells them apart (`players.Profile`,
`reporting.Profile`), decided over the whole document so the names are the same on every run.

**Declined**: a module `ankka-openapi` (everything it needs is `http`'s, and a module is a
published artifact to keep); `agent.Json` for the tree (unordered); YAML (a second renderer).

## R4. What a route's ACL says

**Decision** (the person's, for issuers): `Acl.AllowAll` gives `security: []`. An ACL whose tree
holds a `TokenAuthenticator` gives the one scheme `bearer` (`type: http, scheme: bearer`) and names
no issuer. Any other ACL gives `x-ankka-admission: "not-open"` and no scheme. An `AnyOf` holding a
token authenticator among others is `bearer`.

**Verify at implementation**: that `Oidc.authenticate()` can be constructed with no issuers
configured, since the document is built on machines that configure none. If it refuses, it is
made to answer an authenticator that names none while a document is being built.

## R5. Incomplete routes and the strict switch

**Decision**: an operation that lacks a shape carries `x-ankka-incomplete`, a list of what is
missing (`request body`, `answer 200`). `Streamed`, `Bytes` taken as a stream, `Done` and `Unit`
never count. Strict is an option of the build (`strict=true`): the build answers the list of
problems and no document.

## R6. Refusals

**Decision** (the person's): a refusal named with no shape has the endpoint's refusal shape, else
the platform's, `AnkkaProblem` (`status`, `error`, optional `details` of strings), written once
under the document's schemas. Every operation also carries a `default` answer of the same shape,
which is how the refusals the platform gives before a handler runs are described once.

## R7. The command

**Decision**: `ankka openapi [<service directory>]`, a built-in word.

| Option | Meaning |
|---|---|
| `--out <file>` | where to write; `-` for standard output; default `openapi.json` in the service's directory |
| `--strict` | fail on any incomplete route |
| `--tag <t>`, `--prefix <p>` | the selection; each may repeat |
| `--api-version <v>` | `info.version`; default `0.0.0` |
| `--check <file>` | compare with a kept document, print what differs, write nothing |
| `--local <service>` | read the document of a service running on this machine (R9) and print it |

It refuses a service that `SourceCommands.language` does not find to be Scala, Java or Kotlin,
naming the language. Otherwise it runs that language's run command as `ankka local run` does, with
`-Dankka.document=openapi -Dankka.document.out=<temporary file>` and the options, in
`JAVA_TOOL_OPTIONS`, and no database or broker. At the top of `ServiceBuilder.start`, before
`DeclaredGrpc.check` and before any actor system, the property is read: the registry is validated,
each extension is asked for the document, the file is written whole (a temporary file, then a
move) and the JVM exits `0`, or the problems are written to standard error and it exits `1`. The
CLI moves, prints or compares the temporary file and exits as the JVM did.

A developer without the CLI passes the same properties to their build's run task; the pages say
so. Java and Kotlin need nothing more, because `JavaServiceBuilder.start` ends in the same call.

**Known limit**: the service's own `main` runs up to `start`, so a `main` that adds endpoints
only under some environment gives a document that depends on it. The page says so.

**Verify at implementation**: that `sbt <service>/run`, `./mvnw exec:java` and `./gradlew run`
each end with the JVM's exit status when the service's `main` calls `sys.exit` inside them, on
JDK 21. If sbt's in-process `run` does not, the Scala run command for this mode forks.

**Declined**: a task in each build tool (three implementations of one thing, and a fourth for
every project made before it); reading endpoints statically (Scala's are written inline in
`main`); a `--describe` argument to the service's `main` (templates' mains take none).

## R8. Served

**Decision**: `HttpServer.withOpenApi(acl: Acl)`, and `ServiceBuilder.withOpenApi(Acl)` in Java
and Kotlin. Then `GET /_ankka/openapi.json` is answered, admitted by `Admission.decide` for that
route, with the whole document rendered once when the server starts from the endpoints it serves.
Without it the path falls through as today. The path is added to the reserved paths `validate`
refuses to a root route, and to the startup log. The document is not in the document.

## R9. A local service's document

**Decision**: `ObservabilityEndpoint` serves `/observability/openapi` from
`service.extensions`' `document("openapi", ...)`, and `LocalSource` reads it for `ankka openapi
--local <service>`. The local console's pages and an MCP tool for it are not in this feature.

## R10. Held to its document

**Decision**: `http` defines `private[ankka] trait DocumentCheck` with three calls: an answer
(route, status, content type, body), an accepted request body (route, body, the status it was
answered with), a query parameter read by a handler (route, name). `HttpServer.withDocumentCheck`
(`private[ankka]`) installs one. The answer and the accepted body are reported from
`HttpServer.answer`; the read from `QueryParams.raw` and `rawAll`, only while the handler's
`RequestScope` is set, so an ACL's read of the same parameters is not counted. A check never
changes an answer.

`ankka-testkit` implements it over `com.networknt:json-schema-validator` (2020-12) and offers:
`HeldToDocument.server(server): HttpServer`, `violations`, `assertHeld()`, and a munit trait
`HeldToDocument` that asserts after each test as `LogCapturing` does. `ankka-java-testkit` offers
`Settings.heldToDocument()` and `kit.assertHeldToDocument()`, also called by `close()`; Kotlin
uses the Java kit. An incomplete route is checked only for what it states.

**Declined** (the person's): growing the in-house argument checker, which would pass a keyword it
does not know in a schema a developer wrote by hand.

## R11. Java and Kotlin

**Decision**: the bridge fills each route's `RouteShape` from its `RoutePlan`: the `Method`'s
generic return and body types through `Planner.plan`, converted to `core.shape.Shape` by a new
`bridge/Shapes.scala` (so the names, the references and the rules are R1's and R3's, not
`Schemas.java`'s), and every `@QueryParam` as a query parameter with its type and whether it is
`Optional`. The description is annotations on the handler method or the class, in
`javasdk.annotations`: `@Summary`, `@BodyShape(Class)`, `@Answers(status, shape)`, `@Refuses(status,
reason)`, `@RefusalShape(Class)`, `@Query(name, type, required)` for a parameter read through
`Request`, `@Tag`, each repeatable where it can repeat, plus `@Shape("...json schema...")` forms.
Kotlin's `routes { }` DSL gains `described { ... } { routes }` with the same members; a DSL route
has no answer type, so it is incomplete until described, and the page says so.

`Schemas.java` keeps writing MCP's argument schemas as it does.

## R12. Proof

- `core`: `ShapeSuite` (each rule of R1), `ShapeAgreesSuite` (for a fixture of types, every value
  `Codecs.make` writes is valid against the derived shape, and a value with a required field
  removed or mistyped is not; the validator is a test dependency of `core`).
- `http`: `OpenApiSuite` (derivation, description, contradictions, selection, strict, order and
  repeatability, names), `OpenApiServedSuite`, and the features through a `GherkinSuite` in
  `testkit`, since `http`'s tests cannot see it.
- `testkit`: `OpenApiFeatures` binding `features/openapi-documents/`, `HeldToDocumentSuite`.
- `testkit-java` and Kotlin's cucumber suite: the language rows, named in `ranElsewhere`.
- `cli`: `OpenApiCommandSuite` against a scripted process, and a template test per language behind
  `-Dankka.template.tests` that runs the real command on a fresh service with no Docker.
- SC-002: the shopping cart's document is given to `openapi-typescript` in the TypeScript job, and
  a typed call compiled against the result.
- Each validity assertion parses the document with `swagger-parser` (test scope) as OpenAPI 3.1.

Could each pass while the thing is false? The agreement suite is the one that answers it for
schemas: it is driven by the codec, not by the derivation's own idea of the wire. The repeatability
test compares two JVMs' output, not two calls in one.

## R13. Documentation

A new page `docs/build/openapi.md` (tabs for Scala, Java and Kotlin, each sample from a tested
file), a section in `docs/build/http-endpoints.md` and `docs/build/testing.md` marked `<!--
languages: scala, java, kotlin; ... -->`, the limitation stated in `docs/reference/limitations.md`
(the four other languages; sockets, MCP tools and gRPC left out; a `main` that depends on the
environment), `docs/reference/scala-sdk.md`, `java-sdk.md` and `kotlin-sdk.md`, the CLI reference
regenerated, `SurfaceParitySuite` rows, the `ankka-endpoints` skill, `mkdocs.yml`, and the
correction to `protocol/ENCODING.md`. `docs/reference/glossary.md` gains the nine terms.

## Declined alternatives, in one place

- Serving the document from every service by default: a disclosure nobody asked for (spec).
- Describing the sidecar languages now: the description would travel in discovery, which is a
  protocol change with four SDKs' conformance behind it.
- Request validation at run time from described shapes: changes what a service accepts.
- A client generator: the document is the product; generators exist.

## Analysis

2026-11-18, one round. Findings and what was done:

- MEDIUM: the spec's edge case of a path parameter taking the rest of a path names something
  `PathTemplate` does not have. Removed from the spec.
- MEDIUM: the task list named few requirements. A coverage table was added to `tasks.md`; every
  requirement, success criterion and edge case has a task.
- LOW: the edge case "the served document asked for with a selection" had no test. Added to T027.
- LOW: the spec's prose says schema and label where the features say shape and route tag. Recorded
  under the spec's Clarifications; left as it is.

## Design finished

2026-11-18: analyze reported nothing above LOW.

## Found during implementation

- **T002, `Codecs.make` by round trip** (`core/shape/CodecFacts.scala`): every row of R1's table
  holds. One fact R1 did not state: an empty collection (a `List`, a `Map`) is written, and a
  missing one reads as empty, so a collection field is never in `required`, like an `Option` and a
  field with a default. A missing field of any other type, and a field of the wrong JSON type, are
  refused on read.
- **T003, R7's open point** (JDK 21, minimal projects in a scratch directory): a `main` that calls
  `sys.exit(3)` ends `sbt -batch run` (sbt 1.12, in-process run) with status 3 and
  `./mvnw -q exec:java` (exec-maven-plugin 3.6.4, the Java template's) with status 3.
  `./gradlew --no-daemon -q run` forks the JVM and ends with status **1**, printing Gradle's own
  `FAILURE: ... finished with non-zero exit value 3` to standard error; a `main` that exits `0`
  ends it with `0`. The command needs only zero against non-zero, which all three keep, so no
  forked form is needed: `ankka openapi` reads success from status `0` and the document from the
  file, treats any other status as failure, and passes the build's standard error through.
- **T004, R4's open point**: `Oidc.authenticate()` with no `ANKKA_AUTH_ISSUERS` threw `no issuer
  is configured`, so a service whose endpoint names it could not be built on a machine that writes
  the document. It now answers an authenticator that names no issuer (and answers `Unavailable`
  if ever asked) while `ankka.document` is set, and throws as before otherwise; a set that is
  misconfigured is refused in both cases (`OidcConfigSuite`).
- **A companion's codec, asked during T005** (Trevor, 2026-11-18): the tree mostly puts plain
  `Codecs.make` codecs in companions (the shopping cart's `PlayerRead`, `RegisterPlayer`), which
  R1's rule makes shapeless. A macro cannot tell `Codecs.make` from a hand-written codec
  deterministically (a given's source tree is visible only when both compile in one run), so R1
  stands as written, and a companion opts in with one line beside its codec:
  `given ShapeOf[T] = ShapeOf.derived`. `derived` expanded inside the type's own companion ignores
  the companion's codec; anywhere else, a type whose companion gives a codec and no shape is
  refused by `derived` (a compile error naming the fix) and has none under `Maybe`.
- **T016, the registry a document's check reads**: the sample's MCP endpoint is refused by the
  HTTP server's `validate` when its tools name components a registry does not hold, and the
  seam of T013 gave the server none. `document` takes a third parameter, the registry
  `ServiceBuilder.validate` answered, defaulting to the empty one, so `document(kind, options)`
  still reads as the contract states it and a check that reads components sees the service's own.
- **T018, contracts/scala.md's overloads**: `refuses(status, reason)` beside
  `refuses[A](status, reason)(using ShapeOf[A])`, and `answers(status)` beside
  `answers[A](status)(using ShapeOf[A])`, are ambiguous to Scala 3 at every call that names no
  type, which is the contract's own example. Each pair is one `inline` method: a type left out
  (inferred `Any`) carries no shape, a type named is summoned its `ShapeOf` and refused at
  compile time without one. Every call the contract shows is written as it shows it.
