# Contract: the Kotlin SDK

**Feature**: [spec.md](../spec.md) | **Research**: R5, R3, R8, R11 | **Artifacts**: `com.thinkmorestupidless:ankka-kotlin`, `ankka-kotlin-agent`, `ankka-kotlin-testkit` | **Build**: `sdks/kotlin` (Gradle, Kotlin DSL)

The Kotlin SDK is a library over the Java SDK (`contracts/java-sdk.md`): every Java type is usable
from Kotlin as it is, and the Kotlin SDK adds what Kotlin's own form needs. Package
`com.thinkmorestupidless.ankka.kotlin`. Nothing Kotlin crosses to the wire.

## Declaring components

The Java base classes and annotations are the Kotlin ones; a Kotlin class extends
`EventSourcedEntity<CartState, CartEvent>` and annotates `@Command("add-item")` exactly as Java does.
The Kotlin SDK adds:

```kotlin
// data classes as state and events: the record codec reads the primary constructor (R3, FR-021)
data class CartState(val owner: String, val items: List<LineItem> = emptyList(), val discount: Int? = null)
sealed interface CartEvent { data class ItemAdded(val item: LineItem) : CartEvent; data object CheckedOut : CartEvent }

// a parameter's default is taken for an absent field; no default and absent fails recovery, naming the parameter
// a nullable parameter is the absent form (null written as the encoding's absent); a non-null parameter cannot be given null (compiler)

// the builder DSL, producing the Java effect
@Command("add-item")
fun addItem(item: LineItem): Effect<Done> = effects {
  persist(ItemAdded(item)) thenReply { Done }
}

// a suspending handler (R5, FR-019a): registered as a Java handler whose body runs on the handler's virtual thread
@Step("charge")
suspend fun charge(input: Charge): StepEffect {
  val receipt = componentClient.forEventSourcedEntity(input.account).call(Account::debit).invokeSuspending(input.amount)
  return stepEffects { updateState(state.copy(receipt = receipt)) thenTransitionTo Checkout::ship }
}
```

Rules the Kotlin SDK enforces at start, as `DeclarationProblem`s through the bridge: a `suspend`
handler whose return type is not an effect type; a data class with a `var` property in its primary
constructor used as state (states are values); a `@Query` on a `suspend fun` whose return type is
not `ReadOnlyEffect`.

## Suspension

```kotlin
object AnkkaDispatcher : CoroutineDispatcher()        // runs continuations on the calling handler's virtual thread (runBlocking's event loop)
suspend fun <I, O> Invocation<I, O>.invokeSuspending(input: I): O = invokeAsync(input).await()
suspend fun <O> Invocation<Void, O>.invokeSuspending(): O
suspend fun <Row> ViewClient<Row>.querySuspending(name: String, vararg args: Any): List<Row>
suspend fun <I, O> ServiceClient.callSuspending(path: String, body: I, reply: KClass<O>): O   // the service client, parked not blocked (FR-019a)
fun <Row> ViewClient<Row>.streamFlow(name: String, vararg args: Any): Flow<Row>     // kotlinx Flow over Flow.Publisher
fun <T> Flow<T>.asPublisher(): java.util.concurrent.Flow.Publisher<T>              // for @Sse routes
```

A suspending handler holds no platform thread while it waits: the handler's virtual thread parks
in `runBlocking`'s loop; the scenario in `features/java-kotlin/kotlin.feature` ("no thread of shop
is held while the step waits") is proven by the Kotlin test kit counting platform threads before
and during the wait. Thread-locals (request context, trace, call origin) are read on the handler's
thread, so a continuation that resumes there sees them; the reference page says not to switch
dispatcher inside a handler.

## Nullability

The Java API carries JSpecify `@NullMarked` on its packages, so Kotlin sees every parameter and
return as non-null unless `@Nullable`. Where the Java API returns `Optional<T>`, the Kotlin SDK adds
an extension or overload returning `T?`:

```kotlin
val Request.principalOrNull: Principal?         // Java: Optional<Principal> principal()
fun <Row> ViewClient<Row>.byIdOrNull(id: String): Row?
val EntityContext.callerOrNull: Caller?
```

A `null` passed where the Java API takes a value is a compile error in Kotlin (FR-020, scenario "a
null given where the Java SDK takes a value is refused before build").

## The DSL (`...kotlin.dsl`)

```kotlin
fun <S, E> EventSourcedEntity<S, E>.effects(build: EventSourcedEffectScope<S, E>.() -> Effect<*>): Effect<*>   // typed overloads per reply type
class EventSourcedEffectScope<S, E> { fun persist(vararg events: E): PersistBuilder<S, E>; fun <R> reply(r: R): ReadOnlyEffect<R>; fun error(message: String): ReadOnlyEffect<Nothing> }
infix fun <S, E, R> PersistBuilder<S, E>.thenReply(reply: (S) -> R): Effect<R>
fun <S> Workflow<S>.stepEffects(build: StepEffectScope<S>.() -> StepEffect): StepEffect
infix fun <S> StepBuilder<S>.thenTransitionTo(step: KFunction<*>): StepEffect      // a Kotlin function reference to a @Step
fun HttpEndpoint.routes(build: RouteScope.() -> Unit)                              // the programmatic route form; annotations remain primary
class RouteScope { fun get(template: String, handler: suspend (Request) -> Any) ... }
fun mcpTools(build: McpToolScope.() -> Unit): List<McpTool>
fun agentEffects(...)                                                                // agent module
```

A Kotlin function reference (`Checkout::ship`, `Cart::addItem`) is accepted wherever Java takes a
`CommandRef` or `StepRef`: the Kotlin SDK converts a `KFunction` to the serializable Java
reference, resolved through the same registry.

## Test kits (`...kotlin.testkit`, artifact `ankka-kotlin-testkit`)

The Java kits as they are, plus:

```kotlin
fun ankkaTestKit(service: ServiceBuilder, block: AnkkaTestKit.() -> Unit)        // start, run, close
suspend fun AnkkaTestKit.eventually(within: Duration = 10.seconds, assertion: suspend () -> Unit)
fun <S, E> eventSourcedTestKit(entity: KClass<out EventSourcedEntity<S, E>>): EventSourcedTestKit<S, E>
fun <S> WorkflowTestKit<S>.runUntilPause(): StepResult                           // the Java one; here for discoverability
val AnkkaTestKit.platformThreads: Int                                             // for the suspension scenario
```

## The build (`sdks/kotlin`)

```
settings.gradle.kts      rootProject.name = "ankka-kotlin"; include("ankka-kotlin", "ankka-kotlin-agent", "ankka-kotlin-testkit", "samples:shopping-cart", "samples:reference")
gradle.properties        version=0.0.0 (the one placeholder, rewritten by the release job); ankkaVersion=<same>; kotlin.code.style=official
build.gradle.kts         plugins { kotlin("jvm") version "<pinned>"; `maven-publish`; signing }; repositories { mavenLocal(); mavenCentral() }
                         kotlin { jvmToolchain(21) }; tasks.test { useJUnitPlatform(); maxParallelForks = 1 }
ankka-kotlin/build.gradle.kts    api("com.thinkmorestupidless:ankka-java:$ankkaVersion"); implementation(kotlin("reflect")); api("org.jetbrains.kotlinx:kotlinx-coroutines-core:<pinned>"); implementation("...:kotlinx-coroutines-jdk8:<pinned>")
ankka-kotlin-agent/              api(project(":ankka-kotlin")); api("...:ankka-java-agent:$ankkaVersion")
ankka-kotlin-testkit/            api(project(":ankka-kotlin")); api("...:ankka-java-testkit:$ankkaVersion")
samples/shopping-cart/           the cart in Kotlin; its tests run the sample's features from ../../../../samples/shopping-cart/features
samples/reference/               the Kotlin reference service; `./gradlew conformance` (R8, R11)
fixtures/                        copy of protocol/fixtures; `./gradlew checkFixtures` diffs it; CI runs `diff -r`
```

Tasks: `./gradlew check` (compile, tests, the API surface check with `kotlin.*`, `kotlinx.*` and
the Java SDK's packages allowed), `./gradlew conformance` (writes the reference's runtime classpath
minus ankka artifacts to `build/conformance-classpath.txt` and runs
`sbt -Dankka.conformance.reference=classpath:<file>:com...reference.KotlinReference 'sidecar/testOnly *ConformanceSuite'`
from the repository root), `./gradlew publishToMavenLocal`, `./gradlew publish` (release job only,
signed, to the Central Portal's staging through the `maven-publish` plugin's repository block with
credentials from the environment).

Resolution of `ankka-java` at version `0.0.0` during development comes from `mavenLocal` after
`sbt publishM2` (the `templateArtifacts`-style hook runs it for `-Dankka.template.tests=kotlin`;
`just kotlin` runs it by hand). The release job does the same against the tag's version before
Central has it (R11).

## What the limitations page says the Kotlin SDK lacks

Kotlin multiplatform and Kotlin/Native (JVM only); `kotlinx.serialization` as the codec (the record
codec reads data classes by reflection); suspending view queries that stream (`streamFlow` is
cold and complete per call); Kotlin-specific gRPC stubs (grpc-java's are used).
