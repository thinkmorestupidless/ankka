# Implementation Plan: Java and Kotlin SDKs

**Branch**: `058-java-kotlin-sdk` | **Date**: 2026-10-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/058-java-kotlin-sdk/spec.md`, its six clarifications
of 2026-10-23, the living features under `features/java-kotlin/` and the Java and Kotlin rows added
to about twenty-five existing feature files, and two inventories made for this plan: the Scala SDK's
surface and hooks a facade can use, and the plumbing every added language touches.

## Summary

Two embedded SDKs for the JVM: a Java SDK that is a facade over the Scala SDK, hosted by the same
runtime in the same process, and a Kotlin SDK of its own over the Java one. Nothing of Scala shows
on the Java surface; a handler's wire name is an annotation read once at start, as Akka's Java SDK
does, while a query's inability to persist stays a compile-time fact; a Java record and the Scala
case class of the same shape are written byte for byte alike, so either service recovers what the
other recorded. The Java SDK is three sbt modules (`ankka-java`, `ankka-java-agent`,
`ankka-java-testkit`) published with the JVM artifacts; the Kotlin SDK is a Gradle build under
`sdks/kotlin` with its own CI job, conformance run and release job, offering data classes as state,
nullable types, a builder DSL and suspending handlers and client calls. Each language gets a
template (`ankka init --language java|kotlin`, Maven and Gradle with wrappers, JDK and Docker the
only prerequisites), a template suite in CI, a shopping cart sample that passes the sample's own
features, a reference service that passes the conformance suite in process, a first-service page,
an SDK reference page and a skill. Every place that names the languages names Java and Kotlin; the
limitations page says what the two SDKs lack; the divergences page says where the Java SDK adopts
Akka's annotated handlers and why. The runtime, protocol, sidecar, operator, control plane and CRD
do not change, and a command to a Java or Kotlin entity costs at most a tenth more than to the
Scala one.

## Technical Context

**Language/Version**: Java 21 (the build's `--release 21`; records, sealed interfaces, pattern
matching for switch, virtual threads) for the Java API; Scala 3.9.0 for the bridge, in the same sbt
modules. Kotlin 2.4 (`kotlin("jvm")`, JVM target 21) for the Kotlin SDK, with `kotlin-reflect` for
data classes and `kotlinx-coroutines-core` and `-jdk8` for suspension (versions pinned at
implementation, R5). Gradle 9.6 with the Kotlin DSL and its wrapper; Maven 3.9 with its wrapper for
the Java template.

**Primary Dependencies**: the Java modules depend on `ankka-sdk`, `ankka-runtime`, `ankka-http`,
`ankka-grpc`, `ankka-telemetry-otlp` (and `ankka-agent` for the agent module, `ankka-testkit` for
the test kit module) and add **no** library of their own: the codec is the SDK's (R3), the
annotations are the SDK's, nullability annotations are JSpecify's single small jar. The Kotlin build
depends on the three Java artifacts at the same version, Kotlin's stdlib and reflect, and
kotlinx-coroutines. The templates add JUnit 6.1 (Jupiter) and the Jib plugin for the image (to
verify, R10).

**Storage**: none new. A Java or Kotlin service writes the same journal, state, view and timer
tables through the same hosts; the record codec makes its payloads indistinguishable from the Scala
SDK's (R3), which `features/polyglot/entities.feature`'s cross-language rows prove on one database.

**Testing**: munit from sbt for the Java modules (the bridge's unit tests; the record codec against
`protocol/fixtures`; the surface check, R12; the Java shopping cart through the Java test kits; the
Java reference through the conformance suite in process, R8; `JavaKotlinFeatures`, a `GherkinSuite`
over `features/java-kotlin/`). JUnit under Gradle for the Kotlin SDK (its own tests, the Kotlin
cart, the same feature files run from the repository path, the fixtures from a diffed copy, the
Kotlin reference through `./gradlew conformance`). The CLI's `JavaTemplateSuite` and
`KotlinTemplateSuite` expand a template and run its wrapper's `test`. Three Scala test kits the
Java ones need and the Scala SDK lacks are added to `modules/testkit` first (R9).

**Target Platform**: the JVM, embedded: a Java or Kotlin service is `"hosting": "embedded"` as a
Scala one is, runs from `java -jar` or the template's image locally and on an installation, and
needs no sidecar, protocol version or discovery.

**Project Type**: three new sbt modules and two sbt samples; one new Gradle build outside sbt
(`sdks/kotlin`) with its samples; two CLI templates and their suites; additions to the Scala test
kit; a generalised conformance target; two CI jobs and one release job; six documentation pages,
two skills and about forty one-line edits where languages are enumerated. No runtime, protocol,
sidecar, operator, control plane or CRD change (FR-026).

**Performance Goals**: SC-009: the median of 1,000 commands to a Java or Kotlin cart, measured as
the tracing measures the entity handler's span, at most 10% over the Scala cart's on the same
machine (R13). Start-up adds one reflective pass per component class and per declared type,
once, before the service listens.

**Constraints**: no Scala type on the Java surface, proven by a test (FR-006, SC-006, R12); every
declaration problem reported at start, none at first request (FR-003); `@Query` on a persisting
return type refused at compile time by the type and at start by the bridge (FR-002); a Java `null`
in a value refused with the field named, an empty `Optional` written as the encoding's absent form
(FR-009); the Kotlin SDK's public API in Kotlin's form with suspending handlers and calls
(FR-019, FR-019a); every artifact published only from a tag, Kotlin after the JVM artifacts
(FR-022); the templates build with a JDK and Docker and nothing else installed (FR-014); no
tracked file written by a build; the encoding, fixtures and conformance cases unchanged (FR-026).

**Scale/Scope**: the Scala SDK's public surface is about 30 component and effect types, 10
endpoint types, the two clients, the stores and the test kits; the Java API mirrors it one for one
(expect 6,000 to 8,000 lines of Java, 3,000 to 4,000 of Scala bridge, 2,000 of Kotlin), plus the
two carts (about 600 lines each), the two reference services (about 1,500 each, mirroring
`ConformanceReference.scala`'s thirty components), the templates, six docs pages, two skills and
the edits listed in R11. The riskiest pieces are the record codec's byte identity, the Kotlin
suspension on virtual threads and `Scaffold`'s binary files; the most expensive is the two
reference services; the one that cannot be proven locally is the Kotlin release job's Central
publication.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; `CLAUDE.md` and the rule files govern.

| Principle | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | the Java effect types wrap the Scala builders' values (R4); building one performs no I/O; the hosts reduce them as they reduce Scala's |
| One function reduces an effect, shared by runtime and test kit | the Java test kits unwrap to the same `materialise` the Scala kits call; no Java reduction exists (R4, R9) |
| Registration is explicit; no classpath scanning | a Java class is read by reflection only when handed to `register`; nothing is found by package or annotation scan; every problem is collected and reported before anything listens (R2, FR-003) |
| Wire names are declared separately from method names | the annotation carries the wire name; the method name never reaches the wire; the scenario "a handler's name is declared apart from the function that handles it" renames the method and recovers the entity |
| `query` cannot persist, by construction | `ReadOnlyEffect<R>` is a Java type the persisting builders cannot produce (compile time); the bridge refuses a `@Query` whose return type is not `ReadOnlyEffect` (start time) |
| A macro deriving the wire name was declined | the wire name is still written by hand; what reflection reads is the annotation, not the method name; the divergences page says so (FR-025) |
| Literal route segments outrank parameters | the bridge builds the Scala `Route`s; the server's ordering is unchanged |
| `RequestContext` is thread-local, sound because one request per thread | Java handlers run on the same virtual thread; a Kotlin suspending handler runs under `runBlocking` on that thread, and its continuations resume there (R5); the test kit's thread count scenario proves no thread is held |
| Every endpoint states its ACL | `acl()` is abstract on the Java `HttpEndpoint`; an endpoint that cannot be constructed cannot be registered |
| Anything reading `$HOME` or fixed config must be overridable | the Java SDK reads nothing the Scala SDK does not; the templates' wrappers read their own caches, documented |
| A test that binds a fixed port cannot run beside the documented workflow | every Java and Kotlin suite binds `127.0.0.1:0`; the conformance loopback target is told its address |
| Tests are serialised deliberately | the Java modules join the existing restriction; Gradle's tests run with `maxParallelForks = 1` |
| Forked tests do not inherit `-D` | `ankka.conformance.reference` is forwarded in `Test / javaOptions` with the others (R11) |
| Nothing in the build writes a tracked file | `publishM2` writes `~/.m2`; the release job rewrites `0.0.0` in its checkout only; `docs sync` is run by hand |
| Only a tag publishes | the Java modules go with `ci-release`; the Kotlin job runs after `publish` on the same tag (R11) |
| The honest not-implemented list | `limitations.md` names what each SDK lacks (FR-024); the reference pages promise no further |
| Every `docs/` page stands alone, samples come from tested code | every Java and Kotlin block is an include with `docs:start` markers from a sample, a template or a test (R11) |
| The platform does not change | FR-026; `git diff --stat` over the platform directories is empty at the end, except Scala test kit additions (R9), which the Scala SDK is owed regardless |
| Same conventions across repos | Gradle and Maven templates carry the same `service.json`, compose file, CI workflow, skills and `.mcp.json` as the other templates |

**One tension, named**: `CLAUDE.md` says a macro deriving wire names was declined because the method
name must not be the protocol. The clarification chose annotations read by reflection. These agree:
the annotation's string is the wire name, the method is only where the annotation sits, and renaming
the method changes nothing on the wire. What the Java SDK does adopt from Akka that the Scala SDK
declined is the *method reference at the call site* (R2), which is resolved to the annotation's wire
name through the registry, never to the method's name. The divergences page carries this
reasoning. No violation.

**Post-design re-check** (after Phase 1): the data model and contracts introduce no second effect
reduction, no classpath scan, no Scala type on the Java surface and no platform change beyond the
test kit additions. Gates pass.

## Project Structure

### Documentation (this feature)

```text
specs/058-java-kotlin-sdk/
├── plan.md
├── research.md          # R1–R14 and the "verify at implementation" list
├── data-model.md        # annotations, declaration plan, codec plan, effects, contexts, clients, kits, Kotlin forms
├── quickstart.md        # tiers 1–7 and the reviewer's checklist
├── contracts/
│   ├── java-sdk.md              # the Java API: annotations, components, effects, clients, endpoints, agents, test kits
│   ├── kotlin-sdk.md            # the Kotlin SDK: data classes, nullability, DSL, suspension, its build
│   └── plumbing.md              # templates, ankka init, conformance target, CI and release jobs, docs and skills
└── checklists/requirements.md
```

The protocol, encoding, conformance cases and descriptor contracts are unchanged and referenced.

### Source Code (repository root)

```text
modules/sdk-java/                                   # NEW sbt module, artifact ankka-java, crossPaths := false
├── src/main/java/com/thinkmorestupidless/ankka/java/
│   ├── Ankka.java, AnkkaService.java, ServiceBuilder.java
│   ├── annotations/     Command, Query, Step, Action, Rule, Get, Post, Put, Delete, Patch, Sse, Socket, Stream, Body, Path, QueryParam, Header
│   ├── component/       EventSourcedEntity, KeyValueEntity, View, KeyedView, Consumer, GraphConsumer, Workflow, TimedAction, contexts
│   ├── effect/          Effect, ReadOnlyEffect, PersistBuilder, UpdateBuilder, StepEffect, ConsumerEffect, ViewEffect, TimedActionEffect
│   ├── codec/           Serializer, RecordCodec, Manifest, Json (the value tree the codec reads to), EncodingException
│   ├── client/          ComponentClient, EntityRef, WorkflowRef, ViewClient, Query, ServiceClient, CommandRef (serializable functional interfaces)
│   ├── http/            HttpEndpoint, Acl, Request, Response, Problem, SseEvent, Socket, GrpcEndpoint, McpEndpoint, ToolBuilder
│   ├── secret/          SecretStore;  storage/  ObjectStorage, Bucket
│   ├── timer/           Timers
│   └── problems/        ServiceProblems, DeclarationProblem
├── src/main/scala/com/thinkmorestupidless/ankka/java/bridge/
│   ├── Declarations.scala        # reads a Java class once: annotations → HandlerBinding/StepHandle values, problems collected
│   ├── Adapters.scala            # Java<Kind> extends Scala <Kind>: forwards _setState/_setContext to the Java instance
│   ├── Effects.scala             # wraps/unwraps the Scala effect values behind the Java effect interfaces
│   ├── CodecBridge.scala         # Java Serializer<A> ⇄ Scala Serializer[A]
│   ├── Clients.scala             # ComponentClient over the Scala client; Future → CompletionStage; CommandRef → wire name
│   ├── Routes.scala              # annotated routes → Scala Route values; ToResponse mapping; Acl mapping
│   ├── Grpc.scala, Mcp.scala
│   └── Service.scala             # Ankka.service() → ServiceBuilder → AnkkaService
├── src/test/java/.../java/      # the surface check (R12), codec fixtures, declaration problems, each kind through the kits
├── src/test/java/.../java/reference/   # the Java reference service (R8), mirroring ConformanceReference.scala
└── src/test/scala/.../java/     # JavaKotlinFeatures (GherkinSuite over features/java-kotlin), bridge unit tests

modules/agent-java/                                 # NEW, artifact ankka-java-agent
├── src/main/java/com/thinkmorestupidless/ankka/agent/java/   Agent, AgentEffect, Tools (@Tool), FunctionTool, Guardrail,
│                                                             AutonomousAgent, Task, Judgment, Blueprint, ModelProviders
└── src/main/scala/com/thinkmorestupidless/ankka/agent/java/bridge/   AgentBridge.scala (reaches private[agent])

modules/testkit-java/                               # NEW, artifact ankka-java-testkit
├── src/main/java/com/thinkmorestupidless/ankka/java/testkit/  EventSourcedTestKit, KeyValueEntityTestKit, WorkflowTestKit,
│                                                              ViewTestKit, KeyedViewTestKit, ConsumerTestKit, AgentTestKit,
│                                                              AnkkaTestKit, TimerProbe, TestModelProvider, ScriptedService
└── src/main/scala/.../java/testkit/bridge/         # wraps the Scala kits; unwraps Java effects to materialise

modules/testkit/src/main/scala/.../testkit/         # CHANGED: WorkflowTestKit, ViewTestKit, AgentTestKit added (R9)

samples/shopping-cart-java/                         # NEW sbt sample: the cart in Java, runs the sample's features
sdks/kotlin/                                        # NEW Gradle build, outside sbt
├── settings.gradle.kts, build.gradle.kts, gradle.properties (version=0.0.0), gradlew, gradle/wrapper/
├── ankka-kotlin/        src/main/kotlin/ankka/kotlin/   components (data class support, nullability), dsl/, suspend/, client/
├── ankka-kotlin-agent/
├── ankka-kotlin-testkit/
├── samples/shopping-cart/, samples/reference/      # the Kotlin cart (sample's features) and the Kotlin reference (conformance)
├── fixtures/                                       # copy of protocol/fixtures, diffed in CI
└── features/                                       # none: the Gradle feature tests read ../../features/java-kotlin

sidecar/src/test/scala/.../conformance/
├── ConformanceTarget.scala                         # CHANGED: InProcess takes an EmbeddedReference; classpath:<file>:<class> loads one
└── ConformanceReference.scala                      # CHANGED: supplies its EmbeddedReference (no case changes)

cli/
├── src/main/scala/.../cli/Init.scala, Main.scala, Scaffold.scala   # CHANGED: Java, Kotlin; --package; binary template entries
├── src/main/templates/java/, kotlin/               # NEW: pom.xml / build.gradle.kts, wrappers, src, test, service.json, compose, workflows, README
├── src/test/scala/.../cli/JavaTemplateSuite.scala, KotlinTemplateSuite.scala   # NEW
├── src/test/scala/.../cli/PolyglotTemplateSuite.scala, InitSuite.scala        # CHANGED: embedded branch, tokens, paths
└── native-smoke.sh                                 # CHANGED

build.sbt, project/Dependencies.scala               # CHANGED: three modules, two samples, aggregate, publishM2 hook, template list
.github/workflows/ci.yml, release.yml, .github/ci-coverage.py   # CHANGED: sdk-kotlin, template-java jobs; sdk-kotlin release job
docs/get-started/first-service-java.md, first-service-kotlin.md  # NEW
docs/reference/java-sdk.md, kotlin-sdk.md           # NEW
docs/reference/{limitations,akka-divergences}.md, docs/contributing/language-sdks.md, docs/index.md, …   # CHANGED (R11 list)
tools/docs/skill/ankka-java/, ankka-kotlin/         # NEW; shared skills gain Java and Kotlin sections
mkdocs.yml, README.md, .claude/rules/build-and-release.md, cli/mcp/AnkkaTools.scala, docs-checking suites   # CHANGED
```

**Structure Decision**: the Java SDK lives in sbt because it is Scala-adjacent code that must see
`private[ankka]` members and must be published with the JVM artifacts in one `ci-release`
(clarification). The Kotlin SDK lives under `sdks/` as the Python, TypeScript and Rust SDKs do,
because it is a foreign build with its own toolchain, CI job, conformance run and release job
(clarification), and because a Kotlin developer's view of it is a Maven artifact, not a module of
this build. The agent facade is its own module to mirror the Scala SDK's split and to reach
`private[agent]`. Samples that sbt builds go under `samples/`; the Kotlin ones go with their build.

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Three Java modules instead of one | mirrors the Scala split (sdk, agent, testkit) so a service without agents carries no provider dependencies, and `private[agent]` is reachable only from package `agent` | one module would make every Java service depend on the agent module and its model providers |
| Three Scala test kits added to `modules/testkit` | the living features require a Java kit that follows a workflow to a pause, and a kit over a view and an agent; the Scala SDK has none to wrap | a Java-only workflow kit would be a second reduction of workflow transitions, against the one-reduction rule |
| A generalised conformance target with a classloader form | the Kotlin reference is built outside sbt and must be driven in process to count as embedded | a loopback protocol target would prove the Kotlin SDK as a process SDK, which it is not; it remains the floor if the classloader form fails (R8) |
| Binary and executable entries in `Scaffold` | the Gradle wrapper is a jar and both wrappers are executable scripts; today every template file is text | shipping templates without wrappers would break "builds with a JDK and no build tool installed" |
