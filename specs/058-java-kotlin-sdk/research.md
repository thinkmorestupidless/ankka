# Research: Java and Kotlin SDKs

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

Each entry says whether it was verified against this repository (file and line), against upstream
sources read on 2026-10-23, or is an assumption a named task settles. The list at the end collects
the assumptions. The spec's three clarifications of 2026-10-23 (annotated handlers, the Java SDK in
this build and the Kotlin SDK under `sdks/kotlin`, the 10% cost bound) are decisions already made;
the entries below say how each is kept.

## R1 — The facade is a Java API in front of a Scala bridge, in one sbt module

**Verified in this repository.** Every host reaches a component the same way: `descriptor.create(ctx)`,
then the `private[ankka]` setters (`EventSourcedEntity._setState`/`_setContext`/`_applyEvent`,
`modules/sdk/.../EventSourcedEntity.scala:68-78`; the same on `KeyValueEntity:39`, `Workflow:50`,
`View:45`, `Consumer:32`, `TimedAction`, `Agent.scala:46`), then `binding.decodeAndInvoke(component,
bytes)` on a `HandlerBinding` (`CommandHandle.scala:17`), then the shared reduction
(`EventSourcedEffect.materialise`, `core/effect/EventSourcedEffect.scala:76`; `KeyValueEffect.materialise:43`).
The descriptor case classes are public (`EventSourcedEntityDescriptor`, `EventSourcedEntity.scala:216`;
`KeyValueEntityDescriptor:139`; `WorkflowDescriptor:311`; `ViewDescriptor:137`; `KeyedViewDescriptor:183`;
`ConsumerDescriptor:115`; `TimedActionDescriptor:206`; `AgentDescriptor`, `Agent.scala:193`), but
`HandlerBinding` and `StepHandleLike` are sealed and their concrete classes (`CommandHandle:48`,
`NoArgHandle:73`, `StepHandle`, `NoInputStepHandle`, `RawStepHandle`, `Workflow.scala:128-158`) have
`private[ankka]` constructors. Scala's qualified private compiles to a public JVM member, so code in
package `com.thinkmorestupidless.ankka.*` compiled in this build can call all of it; the `Companion`
classes' handler buffers are truly `private`, so a facade cannot feed an existing companion and must
build the descriptor case classes itself. Agent internals are `private[agent]` (`AgentEffect`,
`AgentEffects`, `StreamHandle`, `FunctionTool`'s constructors), except `StreamHandle.raw`
(`Agent.scala:238`) and `FunctionTool.raw` (`FunctionTool.scala:81`), which are `private[ankka]`.
`HttpEndpoint.routes`/`streamRoutes`/`socketRoutes` are `private[ankka]` overridables
(`HttpEndpoint.scala:346-348`) that `McpEndpoint` already overrides to supply its own `Route`s
(`mcp/McpEndpoint.scala:141`). `ServiceBuilder.register` takes any `ComponentDescriptor`
(`runtime/Ankka.scala:108`) and `host` matches on the descriptor's concrete class (`:424-458`).

**Decision**: one sbt module `modules/sdk-java` (artifact `ankka-java`, `crossPaths := false`) holding
two layers that sbt compiles together in mixed order:

- **The Java API**, in Java, package `com.thinkmorestupidless.ankka.javasdk` and below: the annotations,
  the base classes a developer extends (`EventSourcedEntity<S, E>`, `KeyValueEntity<S>`, `View<Src,
  Row>`, `KeyedView<Row>`, `Consumer<Src, Out>`, `GraphConsumer<Src>`, `Workflow<S>`, `TimedAction`,
  `HttpEndpoint`, `GrpcEndpoint`, `McpEndpoint`), the effect types (`Effect<R>`, `ReadOnlyEffect<R>`,
  `StepEffect`, ...), the contexts, the clients, the serializer interface, `Ankka.service()`. It
  references no Scala type, which is checkable: the Java layer is compiled first, by javac, against
  the Java standard library and ankka's **Java-visible** dependencies only, and SC-006's check is a
  `javap`-based test over the public signatures of the published jar (R12).
- **The Scala bridge**, in Scala, package `com.thinkmorestupidless.ankka.javasdk.bridge` (and
  `com.thinkmorestupidless.ankka.agent.javasdk` for what is `private[agent]`), which reads a Java
  component class once at start (R2), builds the Scala descriptor case class for it with
  `CommandHandle`/`StepHandle` values whose `run` functions call the Java methods through
  `MethodHandle`s, wraps the Scala `*Effects` builders behind the Java effect types, and adapts the
  contexts. A Java component instance is held by a Scala adapter subclass of the Scala abstract class
  (one per kind: `JavaEventSourcedEntity[S, E] extends EventSourcedEntity[S, E]`), which is what the
  hosts call `_setState` on; the adapter forwards state and context to the Java object.

The agent facade lives in a second module `modules/agent-java` (artifact `ankka-java-agent`) because
the agent module's internals are `private[agent]` and because the Scala template already treats
agents as opt-in (model provider dependencies). The test kits are `modules/testkit-java`
(`ankka-java-testkit`). The Kotlin SDK (R5) depends on these three artifacts.

**Alternatives considered**: targeting the remote descriptors (`runtime/remote/RemoteDescriptors.scala:50-167`),
as the sidecar does, over an in-process `Conversation`; rejected because it would route a JVM
service through the byte-level protocol path, lose the in-process hosts' direct dispatch and make
the 10% bound (SC-009) harder, and because it is exactly what "embedded" says a Java service is not.
Writing the whole facade in Scala with Java-friendly signatures; rejected because nothing then
proves the surface is Java-shaped, default arguments and companions leak, and the compile order
would not catch a Scala type on the surface. Making the Java API an annotation processor that
generates the Scala descriptors at compile time; rejected by the clarification (reflection at
start), and because it would put a code generator in every developer's build.

## R2 — Annotated handlers read once at start; call sites use method references

**Decision of the clarification**, mechanics decided here. The Java annotations are
`@Command("wire-name")`, `@Query("wire-name")`, `@Step("name")`, `@Action("name")` (timed actions),
`@Tool("name")`, `@Guardrail`, `@Route(GET, "/carts/{id}")` and the HTTP verbs as their own
annotations, `@Sse`, `@Socket`, `@Stream`, `@Rule` (autonomous result checks), each with
`RetentionPolicy.RUNTIME`. At `register(...)` time the bridge reads a component class with
`Class.getDeclaredMethods` once, builds one `HandlerBinding` per annotated method with a
`MethodHandle` (`MethodHandles.privateLookupIn` so handlers may be package-private, as Akka's are),
and collects every problem into one `ServiceProblems` list before anything listens: a handler with
no wire name, two with one name, a `@Query` on a method whose return type is not `ReadOnlyEffect`,
a `@Command` taking more than one parameter, a parameter or return type with no serializer, a
route template that does not parse, an endpoint with no `acl()`. The reflection is the bridge's
and runs before `ServiceBuilder.register` is called; the Scala registry then re-checks what it
always checks (`ComponentRegistry.from`, `core/ComponentDescriptor.scala:128`).

**Call sites** name a handler by a method reference: `client.forEventSourcedEntity("c1")
.call(ShoppingCart::addItem).invoke(item)`. The reference is typed as a serializable functional
interface (`CommandRef<C, I, O> extends Serializable`), so the bridge reads its `SerializedLambda`
(`writeReplace`) for the implementing class and method name, finds the annotated method and its
wire name in the registry built at start, and caches the result per reference class. This is the
mechanism Akka's Java SDK uses for `Entity::method`; the divergences page said ankka's Scala SDK
avoids it, and now says the Java SDK adopts it, with the reason (clarification of 2026-10-23). A
reference to a method with no annotation is a `CallerError` at the call, not at start: it is a
caller's mistake, not a declaration's, and the registry cannot see call sites.

**Verified upstream**: `SerializedLambda` is specified in `java.lang.invoke` and `writeReplace` on a
serializable lambda has been stable since Java 8; `MethodHandles.privateLookupIn` since 9. Both are
in JDK 21.

**Alternatives considered**: string wire names at call sites (`call("add-item", Item.class, Done.class)`),
kept as the untyped escape hatch for calls across languages and services; typed handles generated
by an annotation processor, rejected with R1; the Scala SDK's companion-declared handles written by
hand in Java (`static final CommandHandle<...> ADD_ITEM = command("add-item", ShoppingCart::addItem)`),
which was option A of the clarification.

## R3 — The Java serializer is the SDK's own record codec, held to the fixtures

**Verified in this repository.** The Scala default is jsoniter-scala's macro derivation
(`Codecs.serializer`, `core/Serializer.scala:53`), unusable from Java. `Serializer[A]` is a plain
trait (`manifest`, `toBytes`, `fromBytes`, `:15`) a Java class can implement. The encoding is
`protocol/ENCODING.md` with fixtures under `protocol/fixtures/` that every SDK reads and writes
byte for byte (`features/polyglot/encoding.feature`); the sidecar SDKs each wrote their own writer
for byte identity (TypeScript's R3, `specs/013-typescript-sdk/research.md:76`). The runtime already
uses Jackson for its own envelopes (`JournalRecord`, `@JsonInclude(NON_ABSENT)`,
`.claude/rules/messaging.md`), not for domain values.

**Decision**: `RecordCodec` in the Java layer: at start it builds, per state, event, input and reply
type, a plan from reflection over Java records (components in declaration order), sealed interfaces
(the `type` discriminator the Scala encoding writes for sum types, the permitted subclasses from
`Class.getPermittedSubclasses`), enums (as their constant's name, matching a fieldless Scala enum
with its string codec), `Optional<T>` (absent when empty, never `null`), `List`/`Set`/`Map`
(`Map<String, V>` as an object, other keys refused), the boxed primitives and `String`,
`java.time.Instant`, `Duration`, `LocalDate`, `LocalDateTime`, `UUID`, `BigDecimal`, `byte[]`
(base64), with number rendering that matches jsoniter's (shortest round-trip for doubles, no
exponent below the thresholds the fixtures show). Reading is a small recursive-descent parser over
the plan, not a general JSON tree. A `null` in a record component is refused when written, naming
the record and the component (FR-009). A developer may implement `Serializer<A>` by hand or adapt
Jackson (`Serializer.of(manifest, toBytes, fromBytes)`); portability is then theirs (FR-010).

**Rationale**: the fixtures demand byte identity, which a general library's defaults do not give
(double rendering, field order, `null` versus absent, discriminator placement), and a wrapper that
forced all of them would be a codec of our own with a dependency attached. Reflection runs once per
type at start, which is the clarification's rule for reflection.

**Alternatives considered**: Jackson with a configured `ObjectMapper` (records are supported; sealed
subtypes need `@JsonSubTypes` or a module, and double rendering differs from jsoniter's for some
values); jsoniter-scala's codecs for Java records (the macro cannot see Java records as products);
Kotlin's `kotlinx.serialization` for the Kotlin SDK (R5 keeps one codec for both so the fixtures
are proven once).

**To verify at implementation** (T-encoding tasks): the exact double and `Instant` rendering rules
in `ENCODING.md` against jsoniter's output for the fixtures, and whether any fixture holds a shape
Java records cannot express (a Scala `Option[Option[A]]`, say); the task fails the fixture run on
any such case rather than skipping it.

## R4 — Effects in Java are the Scala builders behind Java types, reduced by the same functions

**Verified in this repository.** The Scala effect traits are sealed with `private[ankka]` case
classes; only the `*Effects` builders produce them (`EventSourcedEffects`, `core/effect/EventSourcedEffect.scala:102`;
`PersistBuilder:152` with `thenReply(S => R)`; `KeyValueEffects:63`; `WorkflowEffects:80`,
`WorkflowStepEffects:124`; `ConsumerEffects:60`; `KeyedViewEffects:31`; `ViewEffect:17`;
`TimedActionEffect:20`). `materialise` is `private[ankka]` and shared by the hosts and the Scala
test kits.

**Decision**: the Java `Effect<R>` and `ReadOnlyEffect<R>` are Java interfaces with a single
package-private implementation in the bridge that holds the Scala effect. `effects().persist(e)
.thenReply(state -> Done.done())` builds the Scala `PersistBuilder` and wraps a `java.util.function
.Function` as a `scala.Function1` inside the bridge. Varargs (`persist(E first, E... more)`),
`Optional`, `java.util.List`, `java.time.Duration` and Java lambdas are converted at the boundary
and nowhere else. The Java test kits reduce an effect by unwrapping it and calling the same
`materialise` the Scala kits call, so the rule "one function reduces an effect" holds across both
languages without a second reduction. A query's return type `ReadOnlyEffect<R>` is a distinct Java
interface the persisting builders cannot produce, which is the compile-time half of FR-002.

**Alternatives considered**: a Java effect algebra of its own reduced by a Java `materialise`,
rejected because it would be a second place semantics live.

## R5 — The Kotlin SDK: declarations in Kotlin's form, suspending calls over the Java client's futures

**Verified in this repository.** The component client's invocations have `invokeAsync` returning a
Scala `Future` (`sdk/ComponentClient.scala:341,443,466`); `AnkkaExecutors.virtual` runs every
handler on a virtual thread (`CLAUDE.md`, Virtual threads). **Verified upstream** (2026-10-23):
Kotlin 2.4.0 is the current stable line (JetBrains, July 2026; 2.3.20 of March 2026 before it);
Gradle's current release is in the 9.6 line (June 2026); JUnit's stable line is 6.1 (6.1.0 May
2026), requiring Java 17 or later; Maven 3.9.16 (May 2026) is the current Maven 3 and Maven 4 is at
release candidate 6 (July 2026), so the Java template uses Maven 3's wrapper.

**Decision**: `sdks/kotlin` is a Gradle (Kotlin DSL) multi-module build producing `ankka-kotlin`,
`ankka-kotlin-agent` and `ankka-kotlin-testkit`, consuming `ankka-java`, `ankka-java-agent` and
`ankka-java-testkit` at the same version. A Kotlin component is declared in Kotlin's form:

- a component is a class with the same annotations as Java (`@Command` and the rest are Java
  annotations; Kotlin reads them unchanged), so the bridge's reflection sees a Kotlin class exactly
  as a Java one, **plus** a Kotlin declaration form for what Java cannot express: a data class as
  state or event (R3's codec reads Kotlin data classes through `kotlin.reflect`'s primary
  constructor, taking a parameter's default for an absent field and refusing an absent field with
  none, FR-021), nullable types where the Java API has `Optional` (extension properties and
  overloads in the Kotlin SDK map `Optional<T>` to `T?` at the boundary), non-null parameters where
  Java takes an object (the Java API is annotated `@NonNull`/`@Nullable` with JSpecify so Kotlin
  sees platform types as non-null or nullable, FR-020), and a builder DSL for what Java builds with
  chained methods (`effects { persist(ItemAdded(item)) thenReply { Done } }`, routes, tools).
- a handler may be a `suspend fun`; the Kotlin SDK registers it with the bridge as a Java handler
  whose body is `runBlocking(AnkkaDispatcher) { handler(...) }` on the handler's own virtual thread.
  Inside, the Kotlin client's `suspend fun invoke(...)` awaits the Java client's `CompletionStage`
  (the bridge converts the Scala `Future` once, `scala.jdk.javaapi.FutureConverters`) through
  `kotlinx-coroutines-jdk8`'s `await()`, so a wait parks the coroutine and releases the virtual
  thread back to `runBlocking`'s event loop; no platform thread is held, which is what the scenario
  "a handler written as a suspending function calls a component and is answered" asserts through
  the test kit's thread count. A non-suspending Kotlin handler is a Java handler.
- the Kotlin reference service and the Kotlin shopping cart are under `sdks/kotlin/samples/`, and
  the conformance run drives the Kotlin reference in process (R8).

**Alternatives considered**: `kotlinx.serialization` for data classes (a second codec to hold to the
fixtures; rejected, R3); a Kotlin SDK directly over the Scala SDK (loses the Java annotations and
the bridge's checks, and Kotlin reads Scala no better than Java does); `Dispatchers.IO` for
suspending handlers (moves the handler off the virtual thread the runtime attributes calls and
traces to, which `CLAUDE.md` warns about: thread-locals do not follow).

**To verify at implementation**: the exact `kotlinx-coroutines` version (1.10 line expected) and
that `runBlocking` on a virtual thread parks rather than pins (no `synchronized` on the path);
JSpecify annotations' effect on Kotlin's view of the Java API; the Kotlin reflection cost at start
for data classes (once per type, cached).

## R6 — HTTP, gRPC and MCP endpoints in Java

**Verified in this repository.** `HttpEndpoint`'s routes are collected into `private[ankka] Route`
values (`HttpEndpoint.scala:203`) and read through the overridable `routes`, `streamRoutes` and
`socketRoutes` (`:346-348`), as `McpEndpoint` does. Response encoding is the `ToResponse` type class
(`http/codecs.scala:51`), path parameters `FromPath` (`:12`), bodies `FromBody` (`:33`); `Acl` is a
Scala enum (`:86`) with `AllowIf(RequestContext => Boolean)`. `GrpcEndpoint` is already io.grpc
shaped (`grpc/GrpcEndpoint.scala:24`, methods at `:87-121`) with Pekko `Source` for streams;
`McpEndpoint.tool(name)` ends in `ToolBuilder.raw` (`mcp/ToolBuilder.scala:107`), a `private[ankka]`
hook taking `ToolCall => Either[String, ToolAnswer]`.

**Decision**: a Java `HttpEndpoint` declares routes with annotations on methods (`@Get("/carts/{id}")`
with path parameters bound by name and type from the method's parameters, `@Post` with a body
parameter marked `@Body`, `@Sse` returning a `java.util.concurrent.Flow.Publisher<SseEvent>`,
`@Socket` taking a `Socket`, `@Stream` for streamed bodies), an abstract `acl()` returning a Java
`Acl` (static factories `Acl.denyAll()`, `allowAll()`, `allowIf(Predicate<Request>)`,
`authenticate(Function<Request, AuthDecision>)`, `allowCallers(...)`), and reaches its context
through `request()`, `caller()`, `principal()`. The bridge builds the Scala `Route`s itself and
overrides `routes`; literal-over-parameter ordering is the server's and unchanged. Responses:
a handler returns a value serialised by its codec, `Response.of(body).status(201).header(...)`,
`Html`, `Bytes`, or `Done` for 204, mapped onto the Scala `ToResponse` instances in the bridge.
The Java `GrpcEndpoint` takes the generated grpc-java descriptors and `java.util.function` handlers,
with `Flow.Publisher` for streams converted to and from Pekko `Source`. The Java `McpEndpoint` has
`tool("name").describedAs(...).param(String.class, "city", "...").handle((city) -> ...)` and
`.command(ShoppingCart::addItem)`, built on `ToolBuilder.raw` with a JSON Schema derived from the
record codec's plan (R3), which is what `arguments[A]` derives in Scala.

**Alternatives considered**: route declarations as calls in the constructor as Scala does
(`get("/carts/{id}", this::getCart)`); kept as a secondary form the annotations are sugar over, so a
route computed at runtime remains possible.

## R7 — Agents, autonomous agents, judgments and blueprints in Java

**Verified in this repository.** `Agent.Companion.command`/`stream` (`agent/Agent.scala:120,146`);
`AgentEffect`'s builders (`AgentEffect.scala:86-137`) behind a `private[agent]` constructor;
`FunctionTool.raw(spec, approval, origin)(Json => Either[String, String])` (`FunctionTool.scala:81`);
`Guardrail` is a trait of three `Either`-returning checks (`AgentEffect.scala:196`); autonomous
agents' `Task.named`, `rule(name)(R => Result)` (`autonomous/Task.scala:161,39`) and the `tasks`,
`forTask`, `forAutonomousAgent` extensions on `ComponentClient` (`autonomous/Calls.scala:323-340`);
judgments' `Question.choice`/`score`/`yesNo` (`judgment/Question.scala:47-73`); blueprints are
plain case-class builders (`blueprint/Blueprint.scala:219`). `forAgent(SessionId)` is a top-level
extension (`AgentRuntime.scala:1194`). `TestModelProvider` is in the agent module.

**Decision**: `modules/agent-java` holds the Java agent API and its bridge in package
`com.thinkmorestupidless.ankka.agent.javasdk` so the `private[agent]` constructors are reachable.
`@Command` and `@Stream` handlers return `AgentEffect<R>`; tools are `@Tool("name")` methods on a
`Tools` class (the schema from the record codec's plan over the parameter record, the decoder from
the same plan: "one schema and one decoder" as the Scala `FunctionTool` builder gives) or built
with `FunctionTool.named(...)`; guardrails implement a Java `Guardrail` interface whose checks
return `Optional<String>` (a refusal) rather than `Either`; `requiresApproval(Duration)`. The Java
autonomous agent declares `@Rule` result checks and `Task.named(...).describedAs(...)`; judgments
through `Judgment.choice(...)`, `score`, `yesNo` with Java enums for choices; blueprints through the
same builders with Java collections. Providers: `ModelProviders.installation()`, `Anthropic.fromEnv()`,
`OpenAi.fromEnv()`, presets by name, all thin wrappers; `TestModelProvider` wrapped for the Java test
kit. The client reaches agents through `client.forAgent(sessionId)`, `client.tasks()`,
`client.forAutonomousAgent(id)` as Java methods on `ComponentClient` (not extensions).

## R8 — The reference services and the conformance suite, in process

**Verified in this repository.** The conformance suite drives a reference service through
`ConformanceTarget` (`sidecar/src/test/.../conformance/ConformanceTarget.scala`); the Scala reference
runs in process (`ConformanceReference.scala`), and a process target is chosen with
`-Dankka.conformance.target=host:port`. The polyglot conformance feature now carries Java and Kotlin
rows with an empty shape column, as Scala's (`features/polyglot/conformance.feature`).

**Decision**: a Java reference service (`modules/sdk-java/src/test/java/.../reference/`) declaring
the same components, wire names and routes as the Scala reference, and run by the conformance suite
through a new in-process target kind, `ConformanceTarget.Embedded(descriptors, extensions)`, which
the Scala reference also moves onto so the two cannot diverge in how they are driven; a system
property `ankka.conformance.reference=java` selects it in `sbt 'sidecarJava/testOnly *ConformanceSuite'`
or the suite runs both. The Kotlin reference lives in `sdks/kotlin/samples/reference` and is run by
`./gradlew conformance`, which starts the Kotlin reference in process **with the conformance suite
on the classpath** from the published `ankka-sidecar` test artifact, or, failing that, over the
loopback protocol target. Which of the two is settled by the task that builds it; the loopback
form is known to work today for every other SDK, so it is the floor.

**To verify at implementation**: whether the conformance suite's test classes are published or
publishable as a test-jar the Gradle build can depend on; otherwise the Kotlin conformance run
starts the Kotlin reference as a loopback target and runs `sbt` from Gradle, as the TypeScript
`npm run conformance` runs it from npm (`specs/013-typescript-sdk/research.md:227`).

## R9 — The test kits, and the one the Scala SDK lacks

**Verified in this repository.** `EventSourcedTestKit.of` (`testkit/EventSourcedTestKit.scala:246`)
drives an entity through `_setState`, `decodeAndInvoke` and `materialise` (`:178-200`);
`KeyValueEntityTestKit:86`, `ConsumerTestKit:131`, `KeyedViewTestKit:117`, `AnkkaTestKit.start:326`
with `restartService`, `eventually`, `TimerProbe`, `ScriptedService`, `InMemorySecretStore`,
`InMemoryObjectStorage`, `TestSocket`. **There is no `WorkflowTestKit`, no single-source
`ViewTestKit` and no `AgentTestKit`** in the Scala test kit, yet the living features require a Java
component test kit that "follows a workflow to where it waits for a command"
(`features/polyglot/testing.feature`, Java and Kotlin rows) as the sidecar SDKs' kits do.

**Decision**: the Java test kits are wrappers over the Scala ones where they exist, sharing their
reduction. Where the Scala kit does not exist, the kit is **added to the Scala test kit first** and
then wrapped: a `WorkflowTestKit` that runs a workflow's commands and steps through the same
transitions the engine applies (`WorkflowEngine`'s step dispatch at `runtime/WorkflowEngine.scala:298,355`),
stopping where it pauses and reporting each step moved to; a `ViewTestKit` for `View` beside the
keyed one; an `AgentTestKit` that runs one turn against `TestModelProvider`. These are additions
to `modules/testkit`, not changes to the runtime, so FR-026 holds; they are owed to Scala
developers regardless. `AnkkaTestKit` is wrapped as `AnkkaTestKit.start(Ankka.service()...)` in
Java with `restartService()`, `componentClient()`, `eventually(Duration, Runnable)`.
Neither kit depends on a test framework; the templates use JUnit 6.1 (Jupiter) and the SDK's own
tests run under munit from sbt for the Java module and JUnit under Gradle for Kotlin.

## R10 — Templates: Maven for Java, Gradle for Kotlin, rendered by the CLI's `Scaffold`

**Decision of the clarification.** The Java template is a Maven project with the Maven wrapper
(`mvnw`, `.mvn/wrapper`), `pom.xml` pinned to `ankka-java` at the CLI's version, JUnit 6.1, the
`maven-surefire-plugin`, `jib` or `docker-maven-plugin` for the image (the task settles which, with
Jib preferred since it needs no Docker daemon to build and the Scala template's `sbt-native-packager`
likewise builds without a Dockerfile), the compose file, `service.json`, GitHub workflows, the
skills, and a `README.md` saying how to run each. The Kotlin template is a Gradle Kotlin DSL
project with the Gradle wrapper, `build.gradle.kts` pinned to `ankka-kotlin`, `kotlin("jvm")` at the
Kotlin version the SDK was built with, `kotlinx-coroutines`, JUnit 6.1, and the Jib Gradle plugin.
Both are rendered by the CLI's existing `Scaffold` from resources under
`cli/src/main/resources/templates/{java,kotlin}/`, as Python, TypeScript and Rust are, and each
gets a template suite (`JavaTemplateSuite`, `KotlinTemplateSuite`) behind `-Dankka.template.tests=java|kotlin`
that expands the template, builds it with the wrapper and runs its tests; CI runs each in a
`template-java` and `template-kotlin` job when a pull request touches what they read. Details of
the plumbing are in R11 once the inventory of `Scaffold`, the template suites and the CI filters
is in hand.

## R11 — The plumbing an added language touches

**Verified in this repository** (2026-10-23), each with what this feature does about it.

- **Build.** A module is `project.in(file("modules/x")).dependsOn(...).settings(commonSettings)`
  (`build.sbt:338-345`, `:406-412`) and must be added to `root.aggregate` (`:1100-1136`).
  `commonSettings` sets `-Werror` for scalac only, scoped to `compile` (`:159-160`), and
  `javacOptions ++= Seq("--release", "21")` (`:161`), no javac lint; the Java modules add
  `Compile / compile / javacOptions ++= Seq("-Xlint:all", "-Werror")` scoped so javadoc is spared.
  `crossPaths` and `autoScalaLibrary` appear nowhere; the three Java modules set `crossPaths := false`
  so their artifacts are `ankka-java`, `ankka-java-agent`, `ankka-java-testkit` with no `_3`
  (they still carry `scala-library`, since the bridge is Scala). `Test / javaOptions` is a hand-kept
  list of forwarded `-D` keys (`:174-222`): `ankka.conformance.reference` and anything else new is
  added there. `templateArtifacts` (`:982-1008`) `publishLocal`s eight modules when
  `-Dankka.template.tests` names `scala`; the Java and Kotlin templates resolve from Maven, so the
  same hook gains `publishM2` of the Java modules and their dependencies when it names `java` or
  `kotlin` (nothing in the build publishes to `~/.m2` today). The template resource generator's
  language list (`:954`) gains `java` and `kotlin`, which, like `web`, skip `common-service` (the
  sidecar compose file) and carry an embedded compose file of their own (Postgres only, as the
  Scala template's). `project/Dependencies.scala` gains nothing for Java (the codec is the SDK's
  own, R3) and nothing for Kotlin (the Gradle build pins its own versions). The rule file
  `.claude/rules/build-and-release.md` names the published modules by count ("twelve") and must
  change.
- **Release.** `release.yml`'s `publish` job runs `sbt ci-release` (`:80-91`) and publishes every
  module not marked `publish / skip`, so the Java modules need nothing there. A new `sdk-kotlin`
  job modelled on `sdk-rust` (`:691-767`) runs after `publish`: it checks out the tag, runs
  `sbt publishM2` for the Java modules so the Kotlin build resolves the exact artifacts of this tag
  from `mavenLocal` rather than waiting for Central's asynchronous publication, writes the version
  over `0.0.0` in `sdks/kotlin/gradle.properties`, runs `./gradlew check conformance`, and
  publishes with Gradle's signing and the Central Portal upload under the existing `PGP_*` and
  `SONATYPE_*` secrets, guarded by an "already published?" probe as the others are. The `template`
  release job (`:250-287`) is the g8 template's only; the Java and Kotlin templates ship inside the
  CLI and need no release job.
- **CI.** `ci.yml`'s `changes` filters (`:55-216`): `scala-build` already claims `modules/**` and
  `samples/**`, so `modules/sdk-java` and an sbt Java sample run in `build` and trigger every SDK job
  as any module change does. New filters, each landing in the same commit as a file it matches
  (`.github/ci-coverage.py` fails on a pattern that matches nothing and on a file no filter claims):
  `sdk-kotlin` (`sdks/kotlin/**`, the Kotlin template dir and suite, modelled on `sdk-rust` at
  `:153-159`), `template-java` (`cli/src/main/templates/java/**`, `JavaTemplateSuite`, and the
  `template-scala` filter's CLI entries at `:171-180`). Jobs: `sdk-kotlin` (setup-java 21, the
  Gradle wrapper, `sbt publishM2`, `./gradlew check conformance`, then `sbt -Dankka.template.tests=kotlin
  'cli/testOnly *KotlinTemplateSuite'`); `template-java` (`sbt -Dankka.template.tests=java 'cli/testOnly
  *JavaTemplateSuite'`). The `build` matrix (`:228-264`) is `testkit`, `sidecar`, `rest` with `rest`
  defined by subtraction; the Java modules' suites run in `rest` unlisted unless they prove slow,
  in which case a `java` entry is added and subtracted from `rest`. `cli/native-smoke.sh:53-60`
  renders the four templates from the native binary and gains `java` and `kotlin`.
- **`ankka init`.** `Init.Language` (`cli/.../Init.scala:9-25`) gains `Java` and `Kotlin` with
  aliases `java`, `kotlin`, `kt`; `Init.problems` (`:74-96`) gains cases: `--package` applies to Java
  and Kotlin (a dotted package, validated as Java identifiers and not keywords, the model being
  `Scaffold.moduleProblems:50-57`). `Main.initCommand` (`Main.scala:1416-1476`) gains two
  `Scaffold.render` branches with next steps (`./mvnw test`, `./gradlew test`). `Scaffold`
  (`Scaffold.scala`) replaces exact tokens (`:59-65`) in paths and contents and **reads and writes
  every file as UTF-8 text with no permissions** (`:74-90`), which would corrupt
  `gradle-wrapper.jar` and strip `gradlew`'s and `mvnw`'s executable bit; it gains binary entries
  (an `index.txt` line form marking a file binary and executable), and two tokens `{{package}}` and
  `{{package_path}}`, added to `PolyglotTemplateSuite`'s leftover-token check (`:65-83`).
  `PolyglotTemplateSuite` also hard-codes the sidecar compose shape (two `ANKKA_SERVICE_NAME` lines),
  the repository paths list (`:124`) and the hosting branch (`:130-141`: module for Rust, else
  process); it gains an embedded branch (no `protocol`, a `runtime` pin as the Scala
  `TemplateSuite` checks). `JavaTemplateSuite` and `KotlinTemplateSuite` subclass it with tools
  `java` (the wrappers bring the build tools), rewrite the SDK pin to resolve from `mavenLocal`,
  and run the wrapper's `test`. `TemplateSwitch` needs no change.
- **Docs tool.** `mkdocs.yml:85` `extra.docs.facets.languages` gains `java`, `kotlin` (the vocabulary
  is the repo's, not the tool's: `tools/docs` is shared with satisfactory and ankka-flow and is not
  changed). Sample inclusion (`snippets.py`) already takes any file under the repo root with
  `docs:start`/`docs:end` in any comment syntax, so `.java`, `.kt` and `.kts` samples include as
  they are. Skills: `tools/docs/skill/ankka-java` and `ankka-kotlin` modelled on `ankka-rust`
  (Rules, Development loop, Mistakes to check for); per-language sections in `ankka-agents`
  (`:106-129`), `ankka-endpoints:85`, `ankka-entities:120`, `ankka-views-consumers:150`,
  `ankka-workflows:102` gain Java and Kotlin; the umbrella `ankka` skill's description names the
  languages. Nav entries at `mkdocs.yml:157` and `:251`. New pages: `get-started/first-service-java.md`,
  `first-service-kotlin.md`, `reference/java-sdk.md`, `reference/kotlin-sdk.md`, each in a skill's
  `pages:`. `docs/contributing/language-sdks.md` assumes a sidecar or module SDK (`:8-24`) and gains
  the embedded route as a third. `akka-divergences.md` gains a section on the Java SDK (annotated
  handlers adopted, method references at call sites, what still differs); `limitations.md`'s "Four
  languages" bullet (`:458`) becomes six and names what the two SDKs lack.
- **Hand-kept language lists** to update, each a task: `cli/mcp/AnkkaTools.scala:364,531`;
  `PolyglotTemplateSuite.scala:124,138-140`; `InitSuite.scala:67-71`; the docs-checking suites
  `McpEndpointsDocumentationSuite:32,60`, `ServiceCallsDocumentationSuite:34-37`,
  `ViewStreamsDocumentationSuite:47`, `ModelProvidersDocumentationSuite:41,69`,
  `AwaitingWorkflowsDocumentationSuite:39-42`; `ReplicationSteps.scala:1460-1466` and
  `StorageClientClusterFeatures.scala:101-112` (conformance commands per language);
  `AwaitSteps.languageRow:724-729` (its skip message names `sdks/<language>`, wrong for Java);
  `README.md:13,20,120,140`; `docs/index.md:19,26,129,180`; the pages the inventory lists
  (`concepts/polyglot.md`, `get-started/install.md`, `build/{testing,secrets,timers,calling-services,views,consumers,graph}.md`,
  `deploy/{images,local-models}.md`, `operate/telemetry.md`, `reference/model-providers.md`).
  The descriptor needs no change: `ServiceSpec.hosting` is `embedded` by default
  (`controlplane-api/.../descriptors.scala:216-310`); discovery's `SdkInfo` is for process and
  module SDKs only.
- **Conformance.** `ConformanceTarget.fromProperty` (`ConformanceTarget.scala:365-374`) gives
  `ModuleTarget` for `wasm:<path>`, `Sidecar(address)` for an address, `InProcess(model)` otherwise;
  `InProcess` (`:429-520`) hard-codes the Scala reference's descriptors, extensions, endpoints,
  `componentIds` and `readOnlyHandlers`. It is generalised to take an `EmbeddedReference` (descriptors,
  extensions, endpoint factories, component ids, read-only handlers) that the Scala reference, the
  Java reference (`sidecar.dependsOn(sdkJava % Test)`, selected by `-Dankka.conformance.reference=java`)
  and the Kotlin reference supply. The Kotlin reference is not on sbt's classpath: `./gradlew
  conformance` writes the Kotlin reference's runtime classpath minus the ankka artifacts to a file
  and runs `sbt -Dankka.conformance.reference=classpath:<file>:<entry class>`; the suite loads it in
  a `URLClassLoader` whose parent is the suite's own loader, so ankka's and Scala's classes are
  shared and only Kotlin's come from the child, and asks the entry class (implementing the Java
  interface `ConformanceEntry` in `ankka-java`) for its `EmbeddedReference`. The new property is
  forwarded in `Test / javaOptions`. The Scala reference's outline (`ConformanceReference.scala:1482-1634`:
  about thirty components and ten endpoints) is what the Java and Kotlin references reproduce.
- **Encoding fixtures.** `protocol/ENCODING.md:12-37` (records write every field; a sum type carries
  `"type"` first; fieldless cases as `{"type":"X"}`; longs exact; doubles shortest round-trip;
  `Instant` as `Instant.toString`; readers lenient on unknown fields, strict on a missing required
  field or unknown `type`), fixtures under `protocol/fixtures/*.json` with subdirectories
  `autonomous/`, `contracts/`, `graph-deltas/`, `secrets/`, `storage/`, `topics/`, generated by
  `EncodingFixturesSuite`. The Java module reads `protocol/fixtures` directly from the repository
  (its test runs in sbt); the Kotlin build keeps a copy under `sdks/kotlin/fixtures` that CI
  `diff -r`s against `protocol/fixtures`, as the other `sdks/` do. Note from `ENCODING.md`: an absent
  option is written as `null`, not omitted; R3's "absent" means that `null`, and a Java `null` in a
  non-`Optional` component is what is refused.
- **Living features.** `GherkinSuite` (`testkit/GherkinSuite.scala:45`) takes a directory or file;
  `ranElsewhere`/`ranOutside` (`:68,79`) name scenarios run by another suite or outside; a
  `<language>` in an outline's name makes a row its own test. `features/polyglot/*` is run by no
  suite (owned by conformance cases and SDK tests); `features/java-kotlin/` gets a runner,
  `JavaKotlinFeatures` in `modules/testkit-java`'s tests, with the Kotlin rows `ranElsewhere` in the
  Gradle build's feature tests, which run the same files from the repository path.

## R12 — Proving "nothing Scala on the surface"

**Decision**: a test in `modules/sdk-java` lists, through `java.lang.reflect` over every public class
of the packaged jar's `com.thinkmorestupidless.ankka.javasdk` packages (excluding `.bridge`), each
public method's parameter and return types, each public field's type, each supertype and each
annotation's element types, and fails on any whose package starts with `scala.`,
`org.apache.pekko.`, `com.github.plokhotnyuk.` or `com.thinkmorestupidless.ankka.` outside the
`java` packages. It is the executable form of SC-006 and the scenario "every type a Java developer
writes, reads or is given is a Java type". The same test runs over `ankka-java-agent` and
`ankka-java-testkit`, and the Kotlin build runs the same list over its public API with
`kotlinx-coroutines` and `kotlin.` allowed.

## R13 — Measuring the facade's cost

**Decision**: the quickstart's measurement runs the Scala, Java and Kotlin shopping carts in turn
under `AnkkaTestKit`, sends 1,000 `add-item` commands to each through the component client, and
reads the median of the `Server` spans the recorder holds for the entity handler (the trace window,
`.claude/rules/observability.md`), so the measurement is the one the tracing makes. The result is
recorded in this file's Measurements section and SC-009 is checked against it; it is not a CI
assertion, since CI's machines vary, but it is a reviewer's checklist item.

## R14 — What does not change

- The runtime, the protocol, the sidecar, the operator, the control plane and the CRD: `git diff
  --stat` over `modules/runtime`, `protocol/`, `sidecar/src/main`, `operator/`, `controlplane*/`,
  `crd/` is empty at the end, except for one allowed kind of change: a Scala SDK type that must
  gain a Java-callable form (FR-026), expected to be none, since the bridge sits inside the package.
- The Scala SDK's public surface, the conformance suite's cases, the encoding and its fixtures.
- The descriptor: a Java or Kotlin service is `"hosting": "embedded"` (the default) exactly as a
  Scala one; the operator and control plane see no new field.

## Measurements

*Recorded at implementation (R13).*

**Java, 2026-10-23** (`FacadeCostSuite`, `samples/shopping-cart-java`). Machine: Apple M5, 10 cores,
OpenJDK 25.0.3, on a laptop doing other work. The Scala and Java carts were each started under
`AnkkaTestKit`, and each took 200 `add-item` commands that were not counted, then 1,000 that were.
The figure is the median `durationNanos` of the recorder's `shopping-cart`/`add-item` spans. Each run
measures the two carts twice and counts only the second round: the first round gave whichever cart
went first a colder JVM, and in a single round the Java cart measured at half the Scala one.

| Run | Scala median | Java median | Java / Scala |
|---|---|---|---|
| 1 | 6.0 µs | 6.0 µs | 1.000 |
| 2 | 11.0 µs | 9.0 µs | 0.822 |
| 3 | 14.6 µs | 9.7 µs | 0.664 |

SC-009's 10% bound holds in every run. The spread between runs (6 to 15 µs for the same cart) is
larger than any difference between the languages. The span covers the handler and its effect inside
the entity host, not the round trip. At this size the facade's reflective call and effect unwrapping
are not measurable apart from the machine's noise, which is why the bound is asserted only under
`-Dankka.cost.assert=true`.

## Verify at implementation

1. R3: the fixtures' double and `Instant` renderings against the record codec; any fixture shape a
   Java record cannot hold.
2. R5: the `kotlinx-coroutines` version; `runBlocking` on a virtual thread parks and does not pin;
   JSpecify nullability as Kotlin sees it.
3. R8: whether the conformance suite can be a test artifact the Gradle build depends on, or the
   Kotlin conformance run is a loopback target that invokes `sbt`.
4. R10: Jib versus a Dockerfile for the two templates; Maven wrapper and Gradle wrapper versions.
5. R2: `MethodHandles.privateLookupIn` on a module-less classpath (the templates are classpath
   builds; a developer who adds `module-info.java` needs `opens`, which the reference page says).
6. R9: the Scala `WorkflowTestKit`'s fidelity to the engine's pause and timeout semantics without
   a timer: it reports a pause and lets the test send the next command, as the Python kit does.
7. R11: `URLClassLoader` with the suite's loader as parent for the Kotlin reference: that no ankka
   or Scala class is duplicated in the child (the classpath file excludes them) and that Kotlin
   reflection finds `kotlin-reflect` there.
8. R11: `sbt publishM2` resolves for a `crossPaths := false` module with `_3` dependencies, and the
   Maven and Gradle templates resolve the result from `mavenLocal` before Central.
9. R10: the Maven wrapper's script-only distribution type (no jar) works where the Gradle wrapper
   needs its jar; `Scaffold`'s binary entries carry the jar and the executable bits through the
   native image's resource glob.

## Answers at implementation

- **The package is `javasdk`, not `java`** (found before any Java was written). A package
  `com.thinkmorestupidless.ankka.java` shadows the root `java` package for every Scala file in an
  ankka package compiled beside it: `java.util.List` inside `com.thinkmorestupidless.ankka.sdk`
  would resolve to `com.thinkmorestupidless.ankka.java.util`. Akka's Java SDK is `akka.javasdk` for
  the same reason. The surface is `com.thinkmorestupidless.ankka.javasdk`, the agents'
  `com.thinkmorestupidless.ankka.agent.javasdk`; Kotlin keeps `com.thinkmorestupidless.ankka.kotlin`.
- **The MCP facade is in `agent-java`**, not `ankka-java`: `mcp` depends on `agent`.
- **1 (R3).** Every payload fixture at the top of `protocol/fixtures` decodes and re-encodes byte for
  byte through the Java codec (`RecordCodecFixturesSuite`, 24 cases), with `Double.toString` as the
  double rendering and `Instant.toString` as the instant's. Each fixture shape is a Java record,
  sealed interface or enum; the one-case `service-event` is a sealed interface with one record. The
  subdirectories (`autonomous`, `contracts`, `graph-deltas`, `secrets`, `storage`, `topics`) are not
  payloads of a developer's types, which is why no other SDK's encoding suite reads them either;
  each is read by the suite of the feature it belongs to. Breaking the enum writer turns two cases red.
- **2 (R5).** `runBlocking` on a virtual thread, awaiting a `CompletableFuture` through
  `kotlinx.coroutines.future.await`, parks the thread and frees its carrier. The spike
  (`sdks/kotlin`, deleted after) ran 50 such waiters on a scheduler held to one carrier
  (`jdk.virtualThreadScheduler.parallelism=1`, `maxPoolSize=1`), JDK 21 toolchain. All 50 reached
  their await before any future completed, which one pinned waiter would have prevented, and all 50
  finished once the futures completed. The same test with `synchronized { future.get() }`, which pins
  on JDK 21, failed at the first assertion. R5 stands: a suspending handler's body is
  `runBlocking(AnkkaDispatcher)` on the handler's virtual thread. kotlinx-coroutines is 1.11.0;
  `kotlinx-coroutines-jdk8` has been part of core since 1.7, so the build names core only.
- **The Kotlin reader is found by name, not by `ServiceLoader`** (T053). Phase 2 had left
  `RecordShapes` loading readers through `ServiceLoader`, and the task said explicit registration
  (`RecordCodec.withExtension`). Neither is quite right. Registration would leave a Kotlin service
  started through the Java `Ankka.service`, which the contract allows, unable to read its own data
  classes. Classpath discovery is what the family does not do. `RecordShapes.Kotlin` checks whether
  a class carries `@kotlin.Metadata`, read by annotation name so no Kotlin class is loaded, and only
  then loads `com.thinkmorestupidless.ankka.kotlin.codec.DataClassShapes` by that name, once. Without
  `ankka-kotlin` the planner is told to add it. The Java SDK knows the reader's name and nothing
  else of Kotlin.
- **8 (R11).** `sdkJava/publishM2` with `crossPaths := false` publishes
  `com.thinkmorestupidless:ankka-java:<v>` whose pom names `ankka-core_3`, `ankka-sdk_3`,
  `ankka-runtime_3`, `ankka-http_3`, `ankka-grpc_3`, `ankka-telemetry-otlp_3`, `scala3-library_3` and
  `jspecify` at compile scope and the test-only modules (`ankka-testkit_3`, the unpublished
  `ankka-grpc-fixtures_3`) at test scope, which Maven does not resolve for a consumer. No
  `pomPostProcess` is needed. A Maven or Gradle build of an unreleased version therefore needs every
  ankka module in `~/.m2`, so the template suites run `publishM2` over the published aggregate, not
  over the Java modules alone. Javadoc of a mixed module is scaladoc's, and `-parameters` must stay
  off the doc task's `javacOptions`.
- **What the code changed in the contract** (Phase 2). `persist` takes one, two or three events and
  `persistAll` a list, not varargs: a generic varargs parameter makes every caller's compiler warn of
  heap pollution, which a `-Werror` build refuses. `withSerializer(Class<A>, Serializer<A>)` names
  the type it writes, which a bare `Serializer<?>` cannot. An `EntityContext` has no `services`,
  `secrets` or `storage`: the platform never offers them to an entity (`.claude/rules/secrets.md`).
  The untyped call is `call(componentId, wireName, in, out)`: an `EntityRef` from
  `forEventSourcedEntity(id)` knows no component until a method reference names one. A rejection
  reaches a caller as `CommandException`, whose `problem()` is the `Problem` the handler returned
  (status, title, detail). `@Manifest` lives in `javasdk.codec` beside `Serializer`, not in
  `annotations`. The Java base classes take their state from a cell the bridge sets while
  constructing them, so the Java surface has no setter; javac's `this-escape` lint is off for the
  same reason.
- **What the code changed in the contract** (US1). `ServiceBuilder.withHttp(host, port)` says where
  the endpoints are served (port 0 for a free one) and `AnkkaService.httpAddress()` names it; the
  contract had the address and no way to choose it. `Response.ok`, `created` and `redirect` sit
  beside `status`, `noContent`, `html`, `bytes` and `problem`; `Response.of` became `ok`, the word
  the status has. `Acl.allowLocal` is not written: no Scala matcher admits only the local machine,
  and locally every caller is `Local` already. A route parameter named as a template segment is
  bound by its name (javac `-parameters`, which the templates set); `@Path` binds one named
  otherwise. A throwing handler is answered as a Scala one is (the entity stops; the caller's wait
  ends at `ankka.ask-timeout`), which FR-007 asks and `.claude/rules/runtime.md` already records;
  the documentation steers to `effects().error(...)`.
- **An unregistered component is found in the class file** (FR-003). A method reference such as
  `ShoppingCart::addItem` is a constant of the class that writes it, so `References` reads the
  constant pool of every registered component and endpoint once, at start, and names each
  `@ComponentId` class there the service does not register. No bytecode library: the pool's layout
  is fixed by the JVM specification. A call by component id and wire name is a string, not found
  until it is made; the reference page says so.

