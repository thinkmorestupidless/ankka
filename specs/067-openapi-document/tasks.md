# Tasks: An OpenAPI document of a service's HTTP endpoints

**Input**: Design documents from `specs/067-openapi-document/`

**Prerequisites**: plan.md, spec.md, research.md (R1 to R13), data-model.md, contracts/document.md,
contracts/scala.md, contracts/java-kotlin.md, contracts/command.md, quickstart.md

**Tests**: required. Every acceptance scenario ends as a test named after it that fails without
the feature (research R12 says which suite holds which). Within a task, write the test first and
see it fail.

**Paths**: `CORE` is `modules/core/src/main/scala/com/thinkmorestupidless/ankka/core`, `HTTP` is
`modules/http/src/main/scala/com/thinkmorestupidless/ankka/http`, `RUNTIME` is
`modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime`, `KIT` is
`modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit`, `CLI` is
`cli/src/main/scala/com/thinkmorestupidless/ankka/cli`, `BRIDGE` is
`modules/sdk-java/src/main/scala/com/thinkmorestupidless/ankka/javasdk/bridge`, `JAVA` is
`modules/sdk-java/src/main/java/com/thinkmorestupidless/ankka/javasdk`; each has a `src/test` twin
written `<NAME>TEST`.

**Messages**: every problem's words are the contract's (`contracts/command.md`,
`contracts/scala.md`); a test asserts them as written there.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: the user story the task serves (US1 to US5)

---

## Phase 1: Setup

- [X] T001 In `project/Dependencies.scala` and `build.sbt`: add `com.networknt:json-schema-validator` (latest 1.5.x) as a compile dependency of `testkit` and `testkitJava` and a `Test` dependency of `core` and `http`; add `io.swagger.parser.v3:swagger-parser` as a `Test` dependency of `http`, `testkit` and `cli`; run `sbt core/Test/compile http/Test/compile testkit/compile` and resolve any Jackson eviction so `-Werror` builds stay clean
- [X] T002 [P] Write `CORETEST/shape/CodecFacts.scala`, a suite that pins what `Codecs.make` does and research R1 relies on, by round trip: a field equal to its default is omitted on write and may be absent on read; `None` is written as `null`; an absent `Option` reads as `None`; a missing collection field's behaviour; a sealed case and a fieldless enumeration case carry `"type"`; `BigDecimal` is a JSON number and `UUID` a string; an unknown field is skipped. Where a fact differs from R1's table, correct the table in `research.md` under `## Found during implementation` before T005
- [X] T003 [P] Verify research R7's open point and record the answer under `## Found during implementation` in `research.md`: in a throwaway service made with each of `sbt new` (the g8), `ankka init --language java` and `--language kotlin`, a `main` that calls `sys.exit(3)` under `sbt run`, `./mvnw -q exec:java` and `./gradlew --no-daemon -q run` ends the build tool with status 3 on JDK 21. Where one does not, name the run command that does (a forked run) for T024 to use
- [X] T004 [P] Verify research R4's open point and record it the same way: `Oidc.authenticate()` constructed with no `ANKKA_AUTH_*` configured. If it throws, change `modules/auth-oidc` so that it answers an authenticator naming no server while `ankka.document` is set, with a case in its suite

---

## Phase 2: Foundational (shapes, and routes that know themselves)

**Blocks every story.**

- [X] T005 Write `CORETEST/shape/ShapeSuite.scala` then `CORE/shape/Shape.scala` and `CORE/shape/ShapeOf.scala`: the `Shape` cases, `TypeName` and `Named` of data-model.md; `ShapeOf[A]` with `shape` and the definitions it reaches; the instances contracts/scala.md lists; `ShapeOf.of(jsonSchema)`; `ShapeOf.Maybe[A]` with a low-priority instance holding none. Cases: each scalar and its format, `Option`, each collection, `Map[String, A]`, a `Raw` that is not a JSON object is reported when asked
- [X] T006 Write `CORE/shape/ShapeMacro.scala`, the macro behind `ShapeOf.derived`, and its `ShapeSuite` cases, one per row of research R1's table: a case class (declaration order; `required` leaves out `Option` fields and fields with a default); nested case classes become `Ref`s with one `Named` each; a sealed trait and a Scala 3 enumeration with fields become `OneOf` with a `type` constant per case; a fieldless enumeration case is an object with a `type` constant; a type whose companion gives a `JsonValueCodec` and no `ShapeOf` derives nothing (so `Maybe` is none) and one whose companion gives both uses the given; a self-referring type terminates; two types of one simple name keep distinct `TypeName`s
- [X] T007 Write `CORETEST/shape/ShapeAgreesSuite.scala`: for a fixture of types covering every row of R1's table, every value `Codecs.make` writes validates against the derived shape (rendered to JSON Schema and checked with the networknt validator), and the same value with a required field removed, or a field given the wrong JSON type, does not. One case is driven by scalacheck over a nested fixture. This suite is FR-009's proof: it must fail when any R1 rule is deliberately broken (break `Option` once, see it fail, restore)
- [X] T008 [P] Write `HTTP/EndpointClients.scala`'s `private[ankka] def unavailable: EndpointClients` (a transport that fails `not called while a document is built`, a `ViewClient` with no system, and the `unavailable` form of each other client), and move `ControlPlaneRoutesReferenceSuite` and `AuthorizationMatrixSuite` in `controlplane/src/test` onto it, deleting their two hand-built copies
- [X] T009 Write `HTTP/openapi/RouteShape.scala` and `HTTP/openapi/Description.scala` (data-model.md; `Description`'s members exactly contracts/scala.md's), then in `HTTP/codecs.scala` give `FromBody` and `ToResponse` a `def shape: Option[Shape] = None` and a fixed-answer member (`fixedStatus: Option[Int]`, none where the handler decides), take a `ShapeOf.Maybe[A]` in `FromBody.json` and `ToResponse.json`, and state the scalar, text, `Done`, `Unit`, `Bytes`, `Streamed` and `Respond` instances' answers. In `HTTP/HttpEndpoint.scala` add `shape: RouteShape = RouteShape.unknown` to `Route` and `StreamRoute` and fill it in `route`, `routeBody`, `routeStreamedBody`, `routeEvents` and `routeEventsBody` from the instances in scope and `PathArguments.parsers`. `HTTPTEST/openapi/RouteShapeSuite.scala`: one case per recorder, and one that `sbt compile` of `samples/shopping-cart`, `controlplane` and `sidecar` is unchanged (they compile in the same build; the case is that no source of theirs is edited by this task)

**Checkpoint**: `sbt 'core/testOnly *ShapeSuite *ShapeAgreesSuite *CodecFacts' 'http/testOnly *RouteShapeSuite' compile` is green.

---

## Phase 3: User Story 1 - A document from a service as it stands (Priority: P1) 🎯 MVP

**Goal**: `OpenApi.build` and its rendering for typed routes, and the command that writes it.

**Independent Test**: `ankka openapi samples/shopping-cart` writes a valid 3.1 document of every route with its schemas, twice the same, with no Docker running.

- [X] T010 [US1] Write `HTTP/openapi/SchemaNames.scala` and its cases in `HTTPTEST/openapi/OpenApiSuite.scala` (created here): simple names; two types of one simple name take the shortest trailing package part that tells them apart; the result is the same whatever order the types are met in; a service type named `AnkkaProblem` is a problem
- [X] T011 [US1] Write `HTTP/openapi/Document.scala` (the model of data-model.md and `render` over `GraphJson` in contracts/document.md's order, two-space indentation, a final newline) and `HTTP/openapi/OpenApi.scala`'s `build(name, version, endpoints, selection)` for derived content only: every `Route` and `StreamRoute` of every endpoint under its whole path; path parameters and their types; request bodies; answers by fixed status, `default` for a handler-decided one, `204` for nothing, `text/event-stream` for events; `operationId`; shared schemas by name; the `default` answer and `AnkkaProblem` (research R6); nothing for socket routes, `ServesRoutes` endpoints (MCP), root routes and any prefix under `_ankka`. `OpenApiSuite` cases, each named after its scenario in `features/openapi-documents/deriving.feature`: holds every route; path parameters; typed body; typed answer; answers nothing; a stream of events; one named shape for two routes; what is left out (an outline, four rows); every rendered document parses with `swagger-parser` as 3.1 with no message
- [X] T012 [US1] Add security and incompleteness to `OpenApi.build`: `security` and `x-ankka-admission` from each route's effective ACL by contracts/document.md's table (`Acl.flatten` finds a `TokenAuthenticator`); `x-ankka-incomplete` per data-model.md's rule, streams and nothing never counted. `OpenApiSuite`: the three-row ACL outline of `deriving.feature`, an `AnyOf` holding tokens, and `completeness.feature`'s "an incomplete route is in the document, marked with what it lacks" and "a body or an answer that is a stream of bytes does not make a route incomplete"
- [X] T013 [US1] Add `def document(kind: String, options: Map[String, String]): Option[Either[Vector[String], String]] = None` to `RuntimeExtension` in `RUNTIME/Ankka.scala`, and answer it in `HTTP/HttpServer.scala` for `openapi`: build the endpoints from `factories` with `EndpointClients.unavailable`, run `validate`, report a factory that throws as contracts/command.md's `the endpoint <class> could not be built: <cause>` (the first frame of the failure that is a constructor of an `HttpEndpoint` subclass), read `strict`, `tags`, `prefixes`, `version` and the service's name from `options`, and answer `OpenApi.build(...).map(_.render)`. `OpenApiSuite`: the endpoint that cannot be built without the running service; a server with two endpoints at one prefix is refused with `validate`'s words
- [X] T014 [US1] In `RUNTIME/Ankka.scala`, at the top of `ServiceBuilder.start` before `DeclaredGrpc.check`: read `ankka.document` and act as contracts/command.md's "The mode in `start`" says (validate the registry; ask each extension; write to a temporary file beside `ankka.document.out` and move it; problems to standard error; exit `0` or `1`; an unknown kind and a service with no HTTP endpoints as worded there). Put the logic in a `private[ankka] object DocumentMode` whose `run(builder, properties): (Int, Vector[String])` a suite can call without exiting. `modules/runtime/src/test/.../DocumentModeSuite.scala`: a document is written whole and no actor system is created (assert no thread named `ankka-`), problems write no file and leave an existing file untouched, the unknown kind, no extension answering
- [X] T015 [US1] Write `CLI/openapi/OpenApiCommand.scala` and add the word to `CLI/Main.scala` (the root command and `builtIn`): options as contracts/command.md; the language from `SourceCommands.language`, refusing with exit `2` and the contract's words for any but Scala, Java and Kotlin; the run command from `SourceCommands.default` or `local.json`'s `run` (or T003's forked form), with the properties in `JAVA_TOOL_OPTIONS` appended to any the environment already has, and nothing else started; on exit `0` move the temporary file to `--out` or print it, and print the `wrote` line with the counts read from the document; on any other exit pass the JVM's problem lines through and exit `1`. `CLITEST/openapi/OpenApiCommandSuite.scala` against a scripted process: each option reaches its property; the default output path; `--out -`; a failing JVM leaves `--out` untouched; each refused language (an outline: python, typescript, go, rust, web)
- [X] T016 [US1] In `samples/shopping-cart`: nothing changes in its endpoints. Add `samples/shopping-cart/src/test/scala/shoppingcart/OpenApiDocumentSuite.scala`, which runs `DocumentMode.run` on the sample's own builder in two separate forked JVMs and asserts the two files are byte-for-byte the same (the scenario "writing the document twice from the same code gives the same document"), that it parses as 3.1, that its route count equals the routes the sample's `HttpServer` lists less sockets, MCP and `_ankka` (SC-003), and that for each typed route every body `ShoppingCartHttpSuite` sends and every answer it gets validates against the document's schema (SC-004)
- [X] T017 [US1] Write `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/OpenApiFeatures.scala`: `class OpenApiFeatures extends GherkinSuite("../../features/openapi-documents") with LogCapturing`, with step definitions for `deriving.feature` over an endpoint built per scenario; "a body the running service accepts for that route fits the shape" and its answer twin run the endpoint under `AnkkaTestKit` and validate what crossed the wire; "with no database, no broker and no network" is `DocumentMode.run` with no kit started. Scenarios of the other five files go in a `lazy val ranElsewhere` for now, each named with the task that binds it, and are removed from it as those tasks land

**Checkpoint**: quickstart step 1 passes by hand; `sbt 'http/testOnly *OpenApiSuite' 'testkit/testOnly *OpenApiFeatures' 'cli/testOnly *OpenApiCommandSuite' 'shoppingCart/testOnly *OpenApiDocumentSuite'` is green.

---

## Phase 4: User Story 2 - Describing what the types do not say (Priority: P2)

**Goal**: the `described` scope, contradictions, refusals, and selection.

**Independent Test**: a route with a raw body, a raw answer, two query parameters and a refusal is complete once described, in at most ten lines.

- [X] T018 [US2] In `HTTP/HttpEndpoint.scala` add `described(describe)(declare)` as `withAcl` is built (a scoped `Vector[Description]`, restored in a `finally`, nesting outermost first) and `protected def description: Description = Description.none`, folded first; each recorder puts the fold into its route's `RouteShape`, and each scope remembers how many routes it held. `RouteShapeSuite`: nesting adds; a route outside a scope carries only the endpoint's; `described` around a `withAcl` and inside one both work; a description changes nothing of how a route answers (the scenario "describing a route changes nothing of how it answers": the same requests, byte-equal answers, with and without)
- [X] T019 [US2] In `OpenApi.build`: write described summaries, body shapes, answers, refusals (a refusal's shape is its own, else the endpoint's `refusalShape`, else `AnkkaProblem`; `default` follows the endpoint's), query parameters and tags; and report every contradiction of data-model.md as a problem naming the route, with no document. `OpenApiSuite` cases named after every scenario of `describing.feature` up to "an endpoint's route tags and refusals are carried by each of its routes", the contradiction outline's three rows, and one each for: a single-route field in a scope holding two routes, a described body on a route with none, a `Raw` that is not a JSON object
- [X] T020 [US2] Add `Selection` to `OpenApi.build` (tags, prefixes; only the schemas the kept operations reach; nothing kept is the problem `no route carries the tag "<t>"` or `no route is under "<p>"`) and bind `describing.feature` in `OpenApiFeatures`. `OpenApiSuite`: the three selection scenarios, and that `AnkkaProblem` is dropped when no kept operation refers to it
- [X] T021 [US2] In `samples/shopping-cart/src/main/scala/shoppingcart/api/`: add one route to `ShoppingCartEndpoint` that takes text and answers bytes it builds (a cart's receipt), reads two query parameters and refuses with `404`, described in at most ten lines between `// docs:start described` and `// docs:end described` (SC-005; assert the line count in `OpenApiDocumentSuite`), and give the endpoint a `description` with a tag

**Checkpoint**: quickstart step 2 passes by hand.

---

## Phase 5: User Story 3 - A document that cannot drift from the service (Priority: P3)

**Goal**: strict, check, and a test that holds a service to its document.

**Independent Test**: an undescribed raw route fails `--strict` by name; a wrong description fails the suite that calls the route, naming the route and what differed.

- [X] T022 [US3] Strict in `OpenApi.build` (every incomplete operation is a problem `<METHOD> <path>: incomplete, lacks <what>`, all together, no document) and `--strict` end to end. `OpenApiSuite` and `OpenApiFeatures`: `completeness.feature`'s two strict scenarios
- [X] T023 [US3] `--check <file>` in `OpenApiCommand`: build to the temporary file, compare bytes; when they differ, parse both and print one line per route added, removed or changed and per schema changed, exit `1`, write nothing. `OpenApiCommandSuite`: current; a route added (the scenario "a kept document that no longer matches the code is found, and nothing is written", the kept file's bytes and modification time unchanged); a schema changed. Commit `samples/shopping-cart/openapi.json` and add a case to `OpenApiDocumentSuite` that it is current
- [X] T024 [US3] Write `HTTP/openapi/DocumentCheck.scala` (the trait of research R10 and a `Checked` record of the operation a route became) and `HttpServer.withDocumentCheck` (`private[ankka]`, written as `withGrants` is). Report from `HttpServer.answer` (every answer with its route, status, content type and body; and, for a route with a body, the request body and the status), and from `QueryParams.raw` and `rawAll` through a per-request recorder that reports only while `RequestScope` holds the handler's context. `HTTPTEST/openapi/DocumentCheckSuite.scala` with a recording check: an answer and a mapped failure are both reported; an ACL's read of a query parameter is not, a handler's is; a route with no check installed runs the same code path as on `main` (no recorder allocated)
- [X] T025 [US3] Write `KIT/HeldToDocument.scala`: the object and the munit trait of contracts/scala.md, its check built over the networknt validator from the server's own document, recording the four violations of data-model.md in contracts/scala.md's words; an incomplete operation is checked only for what it states; a streamed answer's body is not read. `modules/testkit/src/test/.../HeldToDocumentSuite.scala` and the `held-to-document.feature` bindings in `OpenApiFeatures`: one case per scenario of that file, each asserting the violation's words, and one that a test which does not mix the trait in is unaffected
- [X] T026 [US3] Mix `HeldToDocument` into `samples/shopping-cart/src/test/scala/shoppingcart/ShoppingCartHttpSuite.scala` and wrap its server; fix whatever it finds in the sample's descriptions, not in the check. Break the described route's answer once to see the suite fail by name (SC-006), and restore it

**Checkpoint**: quickstart step 3 passes by hand.

---

## Phase 6: User Story 4 - A deployed service serves its document to whom it names (Priority: P4)

**Goal**: `withOpenApi`, the reserved route, and a local service's document.

**Independent Test**: `OpenApiServedSuite` green; `ankka openapi --local cart` prints the running sample's document.

- [X] T027 [US4] `HttpServer.withOpenApi(acl)` in `HTTP/HttpServer.scala`: the document is rendered once in `serve` from the endpoints served; `Router.handle` gains a branch beside health for `GET /_ankka/openapi.json`, admitted by `Admission.decide` for that route with the stated ACL, answering the bytes as `application/json`; without `withOpenApi` the branch does not exist and the path falls through as today; the path joins the reserved paths `validate` refuses to a root route and the startup log's route lines; its authenticators are bound at start as a route's are. `HTTPTEST/openapi/OpenApiServedSuite.scala`: one case per scenario of `serving.feature` but the local one, a request for the path with a query string is answered the whole document (no selection is offered), the served bytes equal to `document("openapi", ...)`'s, and SC-009 as a table of requests answered identically with the feature unused (compare against answers recorded before this task's change, kept in the suite)
- [X] T028 [US4] In `RUNTIME/ObservabilityEndpoint.scala` serve `/observability/openapi` from the service's extensions' `document("openapi", Map.empty)` (`404` when none answers, the problems as text with `500` when it cannot be built); in `cli/.../console/LocalSource.scala` add `openApi(name)`; `ankka openapi --local <service>` in `OpenApiCommand`. Cases in the runtime's observability suite and `OpenApiCommandSuite` (printed; no such local service), and the scenario "a local service's document is given to its developer whether or not the service serves it" bound in `OpenApiFeatures`

**Checkpoint**: quickstart step 4 passes.

---

## Phase 7: User Story 5 - Java and Kotlin services (Priority: P5)

**Goal**: the same document from the bridge, and each language's form of a description.

**Independent Test**: the three shopping carts' documents hold the same operations and schemas for the routes they share.

- [X] T029 [US5] Write `BRIDGE/Shapes.scala`: `Plan` to `core.shape.Shape` under research R1's rules and R3's names (a record's `hasDefault` and optional components out of `required`; `Opt` and `OrNull` nullable; `Sum` and `Enumeration` as Scala's; `Ref` for recursion), opening `Plan`'s cases to the bridge as its other internals are opened and to nothing public. Its suite in `modules/sdk-java/src/test`: each `Plan` case, and that the Java `ShoppingCart` and `LineItem` records' shapes render byte-for-byte as the Scala sample's case classes' do
- [X] T030 [US5] Write the seven annotations of contracts/java-kotlin.md in `JAVA/annotations/` (repeatable ones with their container types), and in `BRIDGE/Routes.scala` fill each `RoutePlan`'s `RouteShape`: path parameters from the binders; the body from the `@Body` parameter's generic type and the answer from the method's generic return type through `Planner.plan` and `Shapes`; every `@QueryParam` as a query parameter; the method's and class's annotations folded into a `Description`; a `RouteRegistrar` declaration's body from its `Type` and no answer. Carry the shape into the Scala `Route` and `StreamRoute` that `scalaRoutes` builds. Cases in the bridge's route suite: each annotation, class and method together, `@ReadsQuery` naming a `@QueryParam` is a contradiction, a `void` handler is nothing, a registrar route is incomplete until described
- [X] T031 [US5] `ServiceBuilder.withOpenApi(Acl)` in `JAVA` and `BRIDGE/Service.scala` (passed to `read.httpServer`); `Settings.heldToDocument()`, `AnkkaTestKit.assertHeldToDocument()` and `documentViolations()` in `modules/testkit-java` (through `ServiceKit.prepare` to `HttpServer.withDocumentCheck` with `KIT`'s check; `close()` asserts). `SurfaceSuite` and `TestkitSurfaceSuite` stay green (no Scala type on a public signature); add contracts/java-kotlin.md's rows to `SurfaceParitySuite`, none pending
- [X] T032 [US5] In `sdks/kotlin/ankka-kotlin/src/main/kotlin/.../dsl/Routes.kt`: `described(describe: RouteDescription.() -> Unit, routes: RouteScope.() -> Unit)` and `RouteDescription` with contracts/java-kotlin.md's members, carried on each `RouteRegistrar.Declaration` made inside (a new optional member of the declaration, read by T030's fill); `Settings.defaults().heldToDocument()` shown in `ankka-kotlin-testkit`'s `Kits.kt` (research, T032). `SurfaceTest.kt` stays green
- [X] T033 [US5] In `samples/shopping-cart-java` and `sdks/kotlin/samples/shopping-cart`: describe the same receipt route as T021 (annotations in Java; the DSL scope in Kotlin's `CartEndpoint`, whose DSL routes also gain `answers<...>()`), each with `// docs:start described` markers; keep an `openapi.json` beside each; hold each sample's HTTP test to its document. Write `modules/testkit-java/src/test/scala/.../OpenApiJavaFeatures.scala` binding `features/openapi-documents/languages.feature`'s Java rows (the Java document equals the Scala sample's kept document in operations, parameters, answers and schemas for the shared routes), and add the Kotlin rows to `sdks/kotlin/ankka-kotlin/src/test/kotlin/.../KotlinFeaturesTest.kt`'s directories and filter; name each in the other suites' `ranElsewhere`. The four "no document yet" rows are `OpenApiCommandSuite`'s (T015): name them in `ranElsewhere` there
- [ ] T034 [US5] Template tests behind `-Dankka.template.tests`: in `cli/src/test/.../ScalaTemplateSuite`, `JavaTemplateSuite` and `KotlinTemplateSuite` (or their equivalents), run the real `ankka openapi` on a freshly made service with Docker not consulted and assert a valid document holding the template's routes; add `ankka-http`'s changed artifacts to nothing new (no new module), and confirm `templateArtifacts` already publishes what the command needs

**Checkpoint**: quickstart steps 5 and 6 pass.

---

## Phase 8: Polish and cross-cutting

- [ ] T035 [P] Write `docs/build/openapi.md` (what is derived, `described`, incomplete routes and strict, the command and its properties, check, served, held to its document, what is left out, the `main`-depends-on-the-environment limit), tabs for Scala, Java and Kotlin included from the samples' `docs:start` markers; add it to `mkdocs.yml` and to `tools/docs/skill/ankka-endpoints/SKILL.md`'s `pages:`; run `just docs-sync` on a clean tree
- [ ] T036 [P] Edit `docs/build/http-endpoints.md` and `docs/build/testing.md` (a short section each, `<!-- languages: scala, java, kotlin; a description travels with a JVM route, and the sidecar's discovery does not carry one yet -->`), `docs/reference/limitations.md` (that reason under "SDKs and releases"; sockets, MCP tools and gRPC left out; no request validation from shapes), `docs/reference/scala-sdk.md`, `java-sdk.md`, `kotlin-sdk.md` (the new declarations, in the sections `SurfaceParitySuite` reads), and `docs/reference/glossary.md` (the nine terms as `GLOSSARY.md` has them)
- [ ] T037 [P] Correct `protocol/ENCODING.md`: a field equal to its default is omitted by the Scala SDK's default codec and may be absent on read, with the fixture from T002 named beside it
- [ ] T038 Run `just docs-reference` and write the hand-written section for `ankka openapi` in the CLI reference so `CliReferenceSuite` is green; add the command to `cli`'s native-image reachability metadata if `cli/native-smoke.sh` needs it, and a line to that script running `ankka openapi --help`
- [ ] T039 SC-002: in `.github/workflows/ci.yml`'s TypeScript job (or a step of `build (rest)` with node present), run `npx openapi-typescript samples/shopping-cart/openapi.json` and compile `sdks/typescript/test/openapi/cart-client.ts`, a typed `fetch` wrapper that adds an item and reads a cart, against the result; run it against `sbt shoppingCart/run` where that job already starts the sample, else compile only and say so in the page. Extend `.github/ci-coverage.py`'s filters if the job now reads `samples/shopping-cart/openapi.json`
- [ ] T040 Empty `ranElsewhere` in `OpenApiFeatures` of everything but the language rows and the command's rows, and confirm by `just features` and by each suite's own stale-name check that all 46 scenarios of `features/openapi-documents/` are run by exactly one suite; add SC-007 as a case of `OpenApiDocumentSuite` (the sample's document in under ten seconds once compiled)
- [ ] T041 Record in `research.md`, under `## Follow-ups`, the two things this feature leaves: `agent.SchemaType`, `agent.autonomous.JsonSchema`, `agent.blueprint.Shape` and Java's `Schemas.java` folded onto `core.shape`; and descriptions for Python, TypeScript, Go and Rust through the sidecar's discovery. Add the rule-file notes this feature teaches to `.claude/rules/runtime.md` (endpoints section): `described` is a scope because routes answer `Unit`; a hand-written codec in a companion needs a `ShapeOf` beside it; `ankka.document` is read before anything starts
- [ ] T042 `sbt scalafmtCheckAll buildAll` with `-Dankka.cluster.tests=off`, `just features`, `just docs`, `just kotlin test`, and quickstart.md end to end

---

## Requirements covered

| Requirements | Tasks |
|---|---|
| FR-001 to FR-003, FR-006 to FR-008, FR-010 | T010, T011 |
| FR-004, SC-008 | T011 (order), T016 (two JVMs) |
| FR-005 | T013, T015 |
| FR-009, SC-004 | T002, T006, T007, T016 |
| FR-011 | T004, T012 |
| FR-012 to FR-015, FR-017, SC-005 | T018, T019, T021 |
| FR-016, SC-009 | T018, T027 |
| FR-018, FR-019 | T012 |
| FR-020 | T022 |
| FR-021, FR-025, SC-001, SC-003, SC-007 | T013, T014, T015, T016, T040 |
| FR-022, FR-023 | T020 |
| FR-024 | T023 |
| FR-026 to FR-029 | T027 |
| FR-030 | T028 |
| FR-031 to FR-033, SC-006 | T024, T025, T026 |
| FR-034, FR-035 | T029 to T033 |
| FR-036 | T015 |
| FR-037 | T009, T034 |
| SC-002 | T039 |

Edge cases: one simple name for two types T010; a self-referring type and a hand-written codec
T006; a status the handler decides T011; streams T012; an endpoint that cannot be built and a
duplicate route T013; a selection that keeps nothing T020; several ACLs T012; the served document
is whole T027.

## Dependencies

- Phase 1 before Phase 2; T002 before T006; T003 before T015; T004 before T012.
- Phase 2 blocks everything: T005 → T006 → T007; T009 needs T005 and T006.
- US1: T010 → T011 → T012 → T013 → T014 → T015; T016 and T017 need T014.
- US2 (T018 to T021) needs T011. US3's T022 and T023 need T019 and T015; T024 → T025 → T026 need T011.
- US4 (T027, T028) needs T013. US5 needs T019, T025 and T027.
- Phase 8 last; T040 after every binding task.

## Parallel examples

- T002, T003, T004 and T008 together.
- After T013: T018 (US2), T024 (US3) and T027 (US4) by three people, touching `HttpEndpoint.scala`, `HttpServer.answer` and `Router.handle`; the last two share `HttpServer.scala` and are merged in that order.
- T035, T036 and T037 together.

## Implementation strategy

US1 is the MVP: after T017 a typed service has a document from one command. US2 is what
ankka-modules needs first and comes straight after. US3's strict and check are small once US2 is
in; the held check is the larger half and can follow a release that carries US1 and US2. US4 and
US5 are independent of each other.

## Languages

Scala, Java and Kotlin, each with a tested sample (T016, T021, T033) and a tab on the page (T035).
Python, TypeScript, Go and Rust are excluded, with the limitation stated (T036) and the command
saying so (T015).
