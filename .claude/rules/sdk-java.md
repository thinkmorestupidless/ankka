---
paths:
  - "modules/sdk-java/**"
  - "modules/agent-java/**"
  - "modules/testkit-java/**"
  - "sdks/kotlin/**"
  - "cli/src/main/templates/java/**"
  - "cli/src/main/templates/kotlin/**"
---

# The Java and Kotlin SDKs

The Java SDK is a facade over the Scala SDK, hosted in the service's own JVM: `ankka-java`
(`modules/sdk-java`), `ankka-java-agent` (`modules/agent-java`) and `ankka-java-testkit`
(`modules/testkit-java`), sbt modules with no `_3` suffix. The Kotlin SDK is a Gradle build of its own
under `sdks/kotlin` (`ankka-kotlin`, `-agent`, `-testkit`) over the Java one. Each Java component is a
Scala companion built by the bridge (`javasdk.bridge`) from the class's annotations, read by reflection
once when the service starts. `SurfaceParitySuite` (testkit-java's tests) maps every declaration on
`scala-sdk.md` to its Java form, a by-design reason, or nothing; a new Scala declaration fails it until
someone says which.

## Traps

- **The packages are `javasdk`, never `java`** (2026-10-23). A package `com.thinkmorestupidless.ankka.java`
  shadows the root `java` package for every Scala file in an ankka package compiled beside it, so
  `java.util.List` stops resolving across the whole build.
- **Nothing Scala on the public surface.** A public signature naming a Scala type (an `Option`, a
  function, a collection, an interface from the bridge) fails the surface suites, which read the
  published jars' signatures. A Java class that needs something from the bridge reaches it through a
  registry keyed by the Java object (the agent `TestModelProvider` reaches its Scala model through
  `ScalaModels`), not by implementing a bridge type.
- **A method reference is read through its `SerializedLambda`.** Every `*Ref` interface extends
  `Serializable`, and the bridge calls `writeReplace` to learn the class and method a reference names.
  Three shapes reach it: a plain `Type::method` (`implMethodKind` virtual), a reference whose receiver is
  bound, and Kotlin's adapter method (`_init_$x` and similar) for a plain Kotlin function reference,
  which `References.adapted` reads through to the method it calls. A lambda (`x -> x.ask(y)`) names a
  synthetic method and is refused, naming the call site's class. A nested type of a component that is
  not registered can be misread as a call (`References`): treat a refusal naming one as suspect first.
- **The bridge reaches handlers and constructors with `setAccessible(true)`** (`bridge/Declarations`,
  `Reading`), not `MethodHandles.privateLookupIn` as the plan had it. That works on the classpath the
  templates use; a service built as a named module would have to open its package to ankka. Every
  tested handler is public, so a package-private one is not proved.
- **The record codec refuses what the encoding has no form for, at start.** A component of type
  `float`, `short`, `byte` or `char` is refused naming the field; so is a generic varargs parameter on the
  surface, because callers' `-Werror` builds refuse heap pollution warnings (which is why `persist` takes
  one, two or three events and `persistAll` a list). A Kotlin data class is read by
  `RecordShapes.Kotlin`, found by `@kotlin.Metadata`'s name and loaded by name, never through
  `ServiceLoader`: the Java SDK knows the Kotlin reader's class name and nothing else of Kotlin.
- **A `suspend fun` handler is `runBlocking(AnkkaDispatcher)` on the handler's own virtual thread.** It
  parks rather than pins only while nothing on the path holds a monitor: a `synchronized` block around
  an await pins the carrier on JDK 21, and a suite holding the scheduler to one carrier proves it. A
  suspend function reference cannot become a Java SAM, which is why `stepRef(KFunction)` and
  `agentRef(KSuspendFunction…)` exist.
- **javac's `-Werror` is scoped to `Compile / compile`**, never `doc`: the javadoc of a mixed module is
  scaladoc's, and `-parameters` (which binds a handler's parameters by name and which the templates set)
  must stay off the doc task's options too. javac's `this-escape` lint is off, because a base class takes
  its state from a cell the bridge sets while constructing it.
- **Run the Kotlin build through `sdks/kotlin/gradlew-this-commit.sh <task>`** (`just kotlin`). It
  republishes the Java modules to `~/.m2` at this commit's version first; a plain `./gradlew` resolves
  whatever `-PankkaVersion` names, and against an older jar it tests stale code and passes.
- **The Kotlin reference runs in a child-first `URLClassLoader`.** The conformance suite takes
  `-Dankka.conformance.reference=classpath:<listing>:<class>`, a listing written by
  `:samples:reference:conformanceClasspath`: the reference's classes and its runtime classpath less
  every module the Java SDKs bring (the suite already holds those), so ankka's and Scala's classes are
  the suite's. Kotlin's own libraries are always kept in the listing, because the suite's classpath may
  hold an older `kotlin-stdlib`, reached through the Java SDK, that lacks what the compiler emits calls to.
- **cucumber-jvm refuses `@BeforeAll` in a companion object.** The Kotlin cart's hooks are top-level
  functions.
- **A Kotlin `inline` builder is the only kind that can call a `suspend` handler**; the DSL's
  builders (`effects { }`, `routes { }`) are inline for that reason, and the generic types they carry
  are read with `typeOf<B>().javaType` behind one `@PublishedApi` helper, because `javaType` is still
  experimental and an opt-in at every call site would spread.
