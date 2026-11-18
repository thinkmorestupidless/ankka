# Implementation Plan: An OpenAPI document of a service's HTTP endpoints

**Branch**: `067-openapi` | **Date**: 2026-11-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/067-openapi-document/spec.md`

## Summary

A route records what its declaration knows: the shapes of its body and its answer, derived by a
new macro in `core` that says exactly what `Codecs.make` writes, and its path parameters' types.
A `described(...) { }` scope adds what the types do not say. `http` builds one OpenAPI 3.1
document from a service's endpoints, which it can construct with no running service, and renders
it in a fixed order. `ankka openapi` runs the service's own `main` in a mode that writes the
document at the top of `start` and exits; a service that states an ACL for it also serves it at
`/_ankka/openapi.json`. The test kits can hold a service to its document, checking every answer,
every accepted body and every query parameter read against it with a full JSON Schema validator.
Java and Kotlin fill the same route record from the bridge's reflection and describe with
annotations and a DSL scope.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (`core`, `http`, `runtime`, `testkit`, `cli`, the Java
bridge); Java 21 (annotations, `Settings`); Kotlin (the DSL scope); Markdown; YAML (one CI step).

**Primary Dependencies**: jsoniter (already `core`'s); `core.graph.GraphJson` for the rendered
tree; decline (the CLI). New, test kits only: `com.networknt:json-schema-validator`. New, test
scope only: `io.swagger.parser.v3:swagger-parser` (validity), and `openapi-typescript` through
`npx` in the TypeScript CI job (SC-002). Nothing new on a service's production classpath.

**Storage**: none. The document is a file the developer keeps or not.

**Testing**: munit; `GherkinSuite` over `features/openapi-documents/`; `AnkkaTestKit`; the Java
kit and Kotlin's cucumber suite for the language rows; template suites behind
`-Dankka.template.tests`. No k3s suite: nothing here is the cluster's.

**Target Platform**: the JVM a service runs in; the CLI as a JVM and as a native image.

**Project Type**: libraries (`core`, `http`, the test kits, the Java and Kotlin SDKs) and the CLI.

**Languages reached**: Scala, Java and Kotlin. Python, TypeScript, Go and Rust are excluded: their
routes are declared in a process behind the sidecar, and a description would travel in the
sidecar's discovery, which this feature does not change. `docs/reference/limitations.md` states it
and `ankka openapi` says it. Each of the three reached has a tested sample (the shopping carts)
and a tab on every page that documents the feature.

**Performance Goals**: the document for the largest sample in under ten seconds once compiled
(SC-007); building the document at server start adds no measurable time to a service that serves
it and none to one that does not.

**Constraints**: source compatible for every existing service (FR-037); no change to what any
request is answered (FR-016, SC-009); the same bytes on every machine (FR-004); `http` does not
depend on `agent`; `runtime` names nothing of `http`; nothing Scala on the Java and Kotlin public
surfaces.

**Scale/Scope**: one new package in `core`, one in `http`, a field on two route types, one member
on `RuntimeExtension`, one CLI word, two builders, seven Java annotations and a Kotlin scope, two
test kit additions; six feature files with 46 scenarios; one new page and eight edited.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; ankka's `CLAUDE.md` stands in.

| Principle | Holds? | How |
|---|---|---|
| Effects are inert data | yes | a route's shape and a description are values collected at declaration; `OpenApi.build` is pure and answers a document or every problem |
| Module direction | yes | `ShapeOf` in `core`; the document in `http`; `runtime` learns one plain-data member (R3); `agent` and `mcp` untouched |
| Explicit registration, never scanning | yes | the document is built from the endpoints the service hands its `HttpServer`; nothing is found on a classpath |
| Wire names are a versioning boundary | yes | schema names are type names, which are not wire names; the page says a renamed type renames its schema and nothing on the wire |
| No second copy to keep in step | watch | Java's `Schemas.java` stays for MCP while documents use `Shapes.scala` over the same `Plan`; both read one plan, and the follow-up to fold the three Scala derivations onto `ShapeOf` is recorded (R1) |
| Could this check pass while the thing is false? | yes | schemas are held to the codec by round trip (R12); repeatability compares two JVMs; each refusal of the command is asserted by breaking the thing once |
| Tests serialised; no fixed port | yes | every suite binds port 0; the command's suites start no server |
| Compile is warning-free | yes | route declarations still answer `Unit` (R2) |

No gate fails. Re-checked after the design: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/067-openapi-document/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── document.md       # the document's form, order and extensions
│   ├── scala.md          # ShapeOf, described, HttpServer, the test kit
│   ├── java-kotlin.md    # the annotations, the DSL scope, Settings
│   └── command.md        # ankka openapi and the properties behind it
└── tasks.md
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/shape/
├── Shape.scala                 # the model
├── ShapeOf.scala               # the typeclass, its instances, Maybe
└── ShapeMacro.scala            # products, sums, defaults, the companion rule
modules/core/src/test/scala/.../core/shape/
├── ShapeSuite.scala
└── ShapeAgreesSuite.scala

modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/
├── HttpEndpoint.scala          # described, description; route recorders fill RouteShape
├── codecs.scala                # FromBody.json, ToResponse.json take a ShapeOf.Maybe
├── RequestContext.scala        # QueryParams reports a read to the request's check
├── EndpointClients.scala       # unavailable
├── HttpServer.scala            # withOpenApi, withDocumentCheck, the reserved route, document()
└── openapi/
    ├── RouteShape.scala        # what a route knows of itself
    ├── Description.scala       # the builder a described scope takes
    ├── OpenApi.scala           # build: endpoints → Document or problems
    ├── Document.scala          # the model and render
    ├── SchemaNames.scala
    └── DocumentCheck.scala     # the seam the test kits fill
modules/http/src/test/scala/.../http/openapi/
├── OpenApiSuite.scala
└── OpenApiServedSuite.scala

modules/runtime/src/main/scala/.../runtime/
├── Ankka.scala                 # RuntimeExtension.document; the mode at the top of start
└── ObservabilityEndpoint.scala # /observability/openapi

modules/testkit/src/main/scala/.../testkit/HeldToDocument.scala
modules/testkit/src/test/scala/.../testkit/
├── OpenApiFeatures.scala       # binds features/openapi-documents/
└── HeldToDocumentSuite.scala

modules/sdk-java/src/main/java/.../javasdk/annotations/   # Summary, BodyShape, Answers, Refuses,
                                                          # RefusalShape, Query, Tag
modules/sdk-java/src/main/scala/.../javasdk/bridge/
├── Shapes.scala                # Plan → core.shape.Shape
├── Routes.scala                # fills RouteShape from RoutePlan and the annotations
└── Service.scala               # withOpenApi
modules/testkit-java/src/main/java/.../Settings.java, AnkkaTestKit.java
sdks/kotlin/ankka-kotlin/src/main/kotlin/.../dsl/Routes.kt   # described { } { }

cli/src/main/scala/com/thinkmorestupidless/ankka/cli/
├── Main.scala                  # the word, builtIn
└── openapi/OpenApiCommand.scala
cli/src/test/scala/.../cli/openapi/OpenApiCommandSuite.scala

samples/shopping-cart/, samples/shopping-cart-java/, sdks/kotlin/samples/shopping-cart/
                                # one described route each; a kept openapi.json; a held suite
features/openapi-documents/     # written by the spec
docs/build/openapi.md, docs/build/http-endpoints.md, docs/build/testing.md,
docs/reference/{limitations,scala-sdk,java-sdk,kotlin-sdk,glossary,cli}.md, protocol/ENCODING.md,
tools/docs/skill/ankka-endpoints/SKILL.md, mkdocs.yml
```

**Structure Decision**: no new module. The shape of a type is `core`'s because both `http` and,
later, `agent` and `mcp` need it; the document is `http`'s because only `http` knows a route.

## Phases for the task list

1. **Shapes** (`core`): the model, the instances, the macro, the two suites. Nothing else depends
   on anything but this.
2. **Routes that know themselves** (`http`): `RouteShape`, the recorders, `ShapeOf.Maybe` on the
   two JSON instances, `EndpointClients.unavailable`, the reference suites moved onto it.
3. **The document** (`http`): build and render, names, security, incompleteness. Ends US1 in
   process.
4. **Descriptions** (`http`): the scope, the builder, contradictions, selection, refusals. US2.
5. **The command** (`runtime`, `cli`): the mode in `start`, `ankka openapi`, strict and check.
   Ends US1 and the first half of US3 from a terminal.
6. **Held to its document** (`http`, `testkit`): the seam, the validator, the trait. US3.
7. **Served** (`http`, `runtime`, `cli`): the reserved route, the local document. US4.
8. **Java and Kotlin**: `Shapes.scala`, the annotations, the scope, `Settings`. US5.
9. **Samples, pages, skills, parity rows, the TypeScript proof.**

Phases 4, 6 and 7 depend on 3 and not on each other; 8 depends on 3, 4 and 6.

## Complexity Tracking

No violation to justify.
