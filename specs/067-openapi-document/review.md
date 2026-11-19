# Review: feat(067): an OpenAPI document of a service's HTTP endpoints

**Verdict:** Request changes. One blocking finding: `--local` and the local console give a Python, TypeScript, Go or Rust service a document, though FR-036 and the limitations page say it gets none. The should-fix items are expected before merge or answered with a reason.

**What it does:** It derives an OpenAPI 3.1 document from a JVM service's recorded routes. Routes carry `ShapeOf` shapes derived to match what `Codecs.make` writes, and for Java and Kotlin what the record codec plan writes. A route adds what its types do not say through `described` scopes, Java annotations or Kotlin's `described({ }) { }`. The document is written by `ankka openapi`, which runs the service's own build under `ankka.document`. It can also be served at `/_ankka/openapi.json` behind an ACL, checked with `--check`, and used to hold tests through `HeldToDocument`.

**Should it exist:** Yes. ankka-modules needs a generated client for about twenty services, and the spec shows the type-only approach would describe 215 of its 230 body routes as bare paths. The design builds on the route model rather than adding a second registry: shapes are recorded where the `FromBody` and `ToResponse` instances are already summoned. That is the right layer.

**Read:**
- Read in full: `core/shape/{Shape,ShapeMacro,ShapeOf}.scala`, `http/openapi/{OpenApi,Document,Description,RouteShape,SchemaNames,DocumentCheck}.scala`, the `HttpServer`, `HttpEndpoint`, `codecs`, `responses` and `RequestContext` diffs, `runtime/DocumentMode.scala`, the `Ankka.scala`, `ObservabilityEndpoint` and `Oidc.scala` diffs, `cli/openapi/OpenApiCommand.scala`, `testkit/HeldToDocument.scala`, the Java bridge (`Routes.scala` diff, `Described.scala`, `Shapes.scala`), the `testkit-java` `ServiceKit` diff, Kotlin `dsl/Routes.kt`, `docs/build/openapi.md`, and the `http-endpoints.md`, `testing.md`, `limitations.md`, SDK reference and `ENCODING.md` diffs. Also `spec.md`, `tasks.md`, `REVIEW.md`, `.claude/rules/docs.md` and the endpoint sections of `.claude/rules/runtime.md`.
- Skimmed: the three committed `openapi.json` files, which I inspected with a script for paths, markers and schema names. The spec-kit artifacts other than spec and tasks. The feature files. The large test suites (`OpenApiFeatures`, `OpenApiSuite`), read for intent rather than line by line. The rendered skill copies under `marketplace/` and `ankka.g8/`, which are generated.
- Opened outside the diff: `sidecar/.../RemoteEndpoint.scala`, `sidecar/.../Main.scala` (how a process service's routes reach `HttpServer`), `cli/.../console/LocalSource.scala`, `QueryParams`, `reference.conf` (`ankka.service.name`) and `.githooks/commit-msg`.
- Not reviewed line by line: the Kotlin tests, the TypeScript client stub, and the Java test fixtures. No Kotlin or TypeScript language skill was loaded, because their hand-written code is small.

**Size:** 298 files, +18271 −601. Hand-written: 261 files, +14822 −407, about half of it tests and specs.

This is well past the size at which a reviewer finds defects reliably. A split I would accept:
1. `core/shape` and its suites.
2. Route shapes, `OpenApi.build` and the command.
3. `withOpenApi` and `HeldToDocument`.
4. Java and Kotlin.
5. Docs.

The 45 per-task commits make reviewing commit by commit possible, so this is not a blocker.

## Blocking

- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/ObservabilityEndpoint.scala:135` and `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/openapi/OpenApiCommand.scala:48` — issue (blocking): a process or module service gets a document, though the platform says it has none.
  - **What happens.** `HttpServer.document` answers for every `HttpServer`, and that includes the sidecar's (`sidecar/.../Main.scala:207`). The sidecar's `RemoteEndpoint.routes` builds `Route(...)` with the default `shape = RouteShape.unknown`. So `/observability/openapi` on a local Python, TypeScript, Go or Rust service answers 200. `ankka openapi --local <service>` then prints that document and exits 0, with no language check on that path.
  - **What the document holds.** Every route is marked incomplete, and no path parameters are declared, because `RouteShape.unknown` has none. A path like `/{cartId}` with no `in: path` parameter is not a valid OpenAPI 3.1 operation. I have not run this against a live sidecar.
  - **What it contradicts.** FR-036 ("For a service in any other language the command MUST fail"). The limitations entry added here ("has no document: `ankka openapi` refuses each by its language"). `docs/build/openapi.md`'s "What is left out". REVIEW.md's rule that the limitations page is the honest list.
  - **Suggestion.** Make `HttpServer.document` answer `None`, or a refusal naming the language, when any served endpoint is a remote one. A marker on `RemoteEndpoint`, or the sidecar handing `HttpServer` a flag, would do. Then add a case to `OpenApiCommandSuite`'s refused-language outline for `--local` against a stub answering as a sidecar would.
  - Resolved: the sidecar's server is `HttpServer.undocumented`, so `document` answers none and `--local` and the console say the service has no document; `SidecarDocumentSuite` holds it, 899e6f248

## Should fix

- `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/HttpServer.scala:237` — issue (non-blocking): the served document can differ from the written one, which FR-029 forbids.
  - The served document, and the local console's, are built with version `""`, which renders as `0.0.0`. The command writes `ankka.document.version` from `--api-version`.
  - A team that writes with `--api-version 1.4.0` and then compares the served bytes with the file, which the spec's independent test for US4 does, gets a difference.
  - The title comes from a different source too: `ankka.service.name`, else `system.name` here, against the directory or descriptor name the CLI passes.
  - Suggestion: read the same `ankka.document.version` and `ankka.service.name` properties, or a builder setting, on both paths. Add a served-equals-written case that sets a version.
  - Resolved: `DocumentMode.title` and `.version` are the one rule for the written, served and console documents; `OpenApiServedSuite` serves with a version and compares with the written one, 899e6f248
- `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/openapi/OpenApi.scala:57` — issue (non-blocking): paths are written as `endpoint.prefix + template`, not normalised.
  - The Scala sample's document holds `/mcp-keys/` and `/open-carts/`, while the Java and Kotlin documents hold `/mcp-keys`.
  - The server matches both forms because it routes by non-empty segments. A generated client calls the trailing-slash form, and `languages.feature`'s "the same service has the same document in every language" cannot hold for a route declared as `"/"`.
  - A prefix ending in `/` would give `//` in a path.
  - Suggestion: normalise as `HttpServer.normalised` does (`/` + non-empty segments), or keep a trailing slash only for the bare root `/`. Regenerate the kept document.
  - Resolved: `OpenApi.joined` writes `/` and the non-empty segments, the kept cart document is regenerated (`/mcp-keys`, `/open-carts`), and `HeldToDocument` looks paths up the same way, 2063f996d
- `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/HeldToDocument.scala:26` — issue (non-blocking): violations sit in one JVM-wide queue, shared by every checked server.
  - Two held kits open in one test lose or misattribute each other's violations, because the first `close()` clears the second's. Examples are a Java test using two services, or `installations(...)`.
  - The Java kit's `start` also clears whatever a Scala suite left in the queue.
  - Suggestion: give each `Check` its own queue, reachable from the server it wraps. The munit trait and the Java kit then read the queue of the server they hold.
  - Resolved: `HeldToDocument.Violations` is a record a checked server writes to; the Java kit keeps its own, and a suite case shows the shared record is left alone, 3db4bbd6d
- `modules/testkit/src/main/scala/com/thinkmorestupidless/ankka/testkit/HeldToDocument.scala:115` — issue (non-blocking): when the handler decides the status (a `Respond[A]` or a Java `Response` with no described answers), the body is never checked.
  - Such an operation has only `default`, so `named.isEmpty` admits any status. `responses.path(status.toString)` is then missing, so `schemaOf` finds nothing.
  - FR-031's "an answer body its schema refuses" therefore never fires for those routes.
  - Suggestion: when the status is not named, validate against `default`'s content.
  - Resolved: a success whose status the handler decides is validated against `default`; a suite case with a codec that lies is recorded, 3db4bbd6d
- `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/HttpEndpoint.scala:340` and `codecs.scala:49,90` — question: what does this change break in compiled code?
  - **The two signatures.** `FromBody.json` and `ToResponse.json` now take a second `using` parameter (`ShapeOf.Maybe[A]`). Every call site compiled against 0.17 calls `json(codec)`.
  - **Libraries.** A library of endpoints compiled before this change and run on the new `ankka-http` (ankka-contrib, a shared module in ankka-modules) fails at link time with `NoSuchMethodError`.
  - **Source.** The new `protected def description` and `described` members break an endpoint subclass that already has a member named `description`, for example `val description: String`. None exists in this repository.
  - **What the spec says.** FR-037 promises source compatibility only. The repository runs no MiMa, and the commits carry no `!` marker.
  - **What would settle it.** Either a line in the PR description saying binary compatibility is not kept within 0.x and naming what to recompile, or less common member names (`routeDescription`) plus a kept one-argument forwarder for the two `json` givens.
  - Answered: binary compatibility is not kept within 0.x, and the repository runs no MiMa. The pull request says so: a library of endpoints compiled against 0.17 is recompiled, and an endpoint with a member named `description` renames it. FR-037's source compatibility holds for every endpoint in the tree.
- `.claude/rules/runtime.md:42` — issue (non-blocking): the new trap says a hand-written companion codec makes the macro "refuse such a type at compile time … The refusal reaches every route whose body or answer contains the type".
  - That is not what the code does. `ShapeOf.Maybe` (`ShapeMacro.maybe`, `root = false`) answers `Maybe.none` for such a type, and also for a case class containing one, so the route compiles and is marked incomplete.
  - Only an explicit `ShapeOf.derived`, or a `described(_.answers[A])` / `.body[A]` naming the type, fails the compile.
  - A rule file is what the next session plans from, and this wording predicts a compile break that FR-037 forbids.
  - Suggestion: say that the route is incomplete, and that the compile error comes only where a shape is asked for by name.
  - Resolved: the rule now says the route compiles and is marked incomplete, and the compile error comes only where a shape is named, 8b8de324f

## Nits

- `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/openapi/Document.scala:36` — nitpick (non-blocking): the comment says `operationId` is "unique because method and path are". The id is not unique: `/carts/{cartId}` and `/carts/cartId` collide, and so do `/a-b` and `/a_b`. OpenAPI requires unique ids and generators use them as function names. Either detect a collision in `build` and report it as a problem, or soften the comment.
  - Resolved: `OpenApi.build` refuses operations that share an id, naming both; the comment says so, 2063f996d
- `modules/sdk-java/src/main/scala/com/thinkmorestupidless/ankka/javasdk/bridge/Routes.scala:359` — nitpick (non-blocking): two consecutive scaladoc blocks sit above `codecShape`. The first is a leftover draft; remove it.
  - Resolved: the leftover block is removed, 8b8de324f
- `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/openapi/Document.scala:114` — nitpick (non-blocking): every derived answer has `"description": ""`. It is valid 3.1, but several generators and linters warn on an empty description. Write a status phrase (`OK`, `No Content`) when nothing was described.
  - Left: nit. A status phrase would change every kept document; valid 3.1 as it stands.
- `docs/build/openapi.md:104` — nitpick (non-blocking): the page says "`described { }` wraps the routes of a `routes { }` block". The Kotlin form is `described({ … }) { … }`, as the tab above shows.
  - Resolved: the page says `described({ … }) { … }`, 8b8de324f
- `modules/testkit-java/src/main/scala/com/thinkmorestupidless/ankka/javasdk/testkit/bridge/ServiceKit.scala:192` — nitpick (non-blocking): `finally if held then assertHeldToDocument()` throws an `AssertionError` that hides an exception thrown by `kit.stop()`. Attach the stop failure as suppressed, or assert only when the stop succeeded.
  - Resolved: `close` asserts only once the kit stopped, so a stop failure is what is thrown, 3db4bbd6d
- `docs/build/testing.md` around line 1470 — nitpick (non-blocking): the general "HTTP server in an integration test" Scala tab is now the held-to-document form. A reader of that section sees `HeldToDocument.server` before the section that explains it, and the other six tabs do not show it. Consider including a plain region there.
  - Left: nit.
- Commit hygiene — nitpick (non-blocking): the diff moves every `0.16.2` in `install.md`, the SDK references, `images.md` and other pages to `0.17.0`. This is `docs sync` catching up with the v0.17.0 tag and is unrelated to the feature. It would be cleaner as its own commit, or left to `main`.
  - Declined: `docs check` refuses a tree whose release forms are behind the newest tag, and `docs sync` is what it asks for; the move is in the docs commit that ran it, and `main` would make the same move on its next docs change.

## Questions

- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/openapi/OpenApiCommand.scala:73,85` — question: exit code `2` is also used for "cannot tell the language", "cannot tell how to run" and every `locate` refusal (several services, not a service's directory). The page documents `2` only for "a language that has no document yet". Should those be `1`, or should the page list them?
  - Answered: `2` means the directory is not one service the command can run, which all of those are; the page and the reference now list them, 8b8de324f
- `cli/src/main/scala/com/thinkmorestupidless/ankka/cli/openapi/OpenApiCommand.scala:181` — question: a value holding whitespace is passed through `JAVA_TOOL_OPTIONS` wrapped in double quotes. Does JDK 21's `JAVA_TOOL_OPTIONS` parser honour quotes? A `--tag "a b"` or a version with a space would show whether it does, and a case in `OpenApiCommandSuite` that runs a real JVM would settle it. Today's temporary path has no space, so only user-given values are at risk.
  - Answered: it does. On JDK 21, `JAVA_TOOL_OPTIONS='-Dq="a b" -Dx=1'` reads `q` as `a b`.
- `protocol/ENCODING.md` — thought: the corrected text says "a reader in another SDK must accept a defaulted field's absence". That is a cross-SDK contract statement. Is it proven for the Python, TypeScript, Go and Rust readers (their fixture suites), or is it a new obligation that needs a follow-up issue?
  - Answered: the Scala writer has always omitted such a field, so the text now describes what every reader has been handed; whether each SDK's fixture suite holds its reader to it is not shown here, and is a follow-up in research.md.

## Praise

- `modules/core/src/test/scala/com/thinkmorestupidless/ankka/core/shape/CodecFacts.scala` and `ShapeAgreesSuite.scala` — praise: the derivation is held to the codec by round trip and by validation, scalacheck included, rather than to a second reading of the rules. The suite found and corrected a real error in `ENCODING.md`: the default codec omits a field equal to its default.
- `modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/openapi/DocumentCheck.scala` — praise: the check costs one thread-local read when nothing listens. It sits inside the handler's own scope, so an ACL's reads are not counted. It reports from `answer` before the response leaves, so a test cannot see its answer before the check has seen it.
- `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/DocumentMode.scala` — praise: the temporary file plus atomic move, the run before any environment check, and `run` kept separate from `sys.exit` together make FR-021 and FR-025 directly testable, and the rule file records why the order matters.
- Docs — praise: `docs check` passes (114 pages, no problems). The page's `languages: [scala, java, kotlin]` matches what the feature reaches. The two `<!-- languages: … -->` reasons in `http-endpoints.md` and `testing.md` restate a limitation the page now states. Every tab is included from a tested sample in its own SDK's tree. The docs side of REVIEW.md's every-language rule is met, and the one gap is the behaviour in the blocking finding.

## Out of scope

- note: `EndpointClients.unavailable` builds `ViewClient(null, …)(using null)`. This is deliberate and documented. The view client would be worth a proper `unavailable` form when the follow-up in `research.md` folds the agent schema types onto `core.shape`.

## Checks applied

- **General checklist.** Applied: Correctness, Design and scope, Error handling, Concurrency and state (the thread-local check, the global queue, the volatile served endpoints), Security (the served path's ACL and its refusal shapes, the reserved path, `JAVA_TOOL_OPTIONS` propagation), Tests, Observability (the startup route line, problems to stderr), API and compatibility, Dependencies and build, Documentation, and Commit and PR hygiene. Skipped: Data and migrations, since there is no schema or journal change, and Performance beyond the hot-path check of `DocumentCheck.read` and the compile-time macro at every `json` summon.
- **Language skills.** `pr-review-scala`, with the concurrency, serialization, types, error handling, tests, build and API groups applied. Kotlin, Java and TypeScript were read against the general checklist only, because their hand-written changes are small.
- **Project rules read.** `CLAUDE.md`, `REVIEW.md`, `.claude/rules/docs.md`, `runtime.md` (endpoints section and the new notes), `testing.md`, `build-and-release.md` (the template note), and `control-plane.md` and `sidecar.md` for the CLI and the sidecar path.
- **Enforced by tooling, so not reviewed:** formatting (scalafmt hook), unused code and warnings (`-Werror`, `-Wunused`), and the Conventional Commit header form (`.githooks/commit-msg`). All 45 headers are 72 characters or fewer and carry the attribution.
- **Tests run:**
  - `sbt core/testOnly *ShapeSuite *ShapeAgreesSuite`: 29 passed.
  - `http/testOnly *OpenApiSuite *RouteShapeSuite *DocumentCheckSuite`: 79 passed.
  - `cli/testOnly *OpenApiCommandSuite`: 24 passed.
  - `uv run --project tools/docs docs check`: no problems.
- **Not run:** the testkit, sample, Java and Kotlin suites (Docker), and the full build, as instructed. The blocking finding was confirmed by reading the code, not by running a sidecar.
