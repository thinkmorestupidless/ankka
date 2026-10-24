---
name: pr-review-kotlin
description: The Kotlin rules a code review checks after the general pr-review checklist: structured concurrency and Flow (GlobalScope, swallowed CancellationException, blocking inside suspend code, flow {} context), null safety (!!, platform types, lateinit), sealed hierarchies and data classes (else in an exhaustive when, var in a data class), kotlinx.serialization and Jackson wire contracts, library API compatibility (explicit API mode, .api dumps), coroutine and MockK test hygiene, Gradle Kotlin DSL settings. Use whenever a pull request, diff, branch or file under review contains Kotlin (.kt, .kts, build.gradle.kts, settings.gradle.kts), whether or not pr-review loaded it, and whenever someone asks for feedback on, a check of, or a second opinion about Kotlin code. Not for writing Kotlin or fixing the code under review.
---

# Reviewing Kotlin

Kotlin reviews most often miss what the compiler cannot see: cancellation swallowed by a generic
`catch`, `runBlocking` or JDBC inside a `suspend fun`, a `!!` or `first()` that production data will
fail, an `else` that turns an exhaustive `when` into silent fall-through, and a sealed subclass
serialised under its class name. Apply the groups in this order: coroutines and Flow, null safety,
error handling, classes and sealed hierarchies, collections, serialization, Java interop, API
design, tests, build, then idioms.

## Before reading the code

Detect the flavour from the build files and the paths in the diff; it decides which groups bite.

| Signal in the diff or build | Flavour | What changes for the review |
|---|---|---|
| `kotlin("jvm")` or `org.jetbrains.kotlin.jvm`, `jvmToolchain`, `src/main/kotlin`, Ktor, Spring, Exposed | JVM server or library | every group; `explicitApi()` plus BCV or `abiValidation {}` with `api/*.api` marks a library, where API and compat items are blocking |
| `com.android.application` or `.library`, `android {}`, `androidx.*`, `viewModelScope`, `@Composable` | Android | coroutine rules as written; `Dispatchers.Main` matters here only; Android Lint covers `!!` and nullness ids |
| `kotlin("multiplatform")`, targets inside `kotlin {}`, `src/commonMain`, `expect`/`actual` | KMP | no `java.*` or `@JvmStatic` in `commonMain`; `expect` sealed classes need `else`; sort stability unverified off the JVM |
| `*.kts`, `buildSrc/`, `gradle/libs.versions.toml` | build logic | review as build code (Build group), not as application idioms |

Version: the literal in `kotlin("jvm") version "2.x.y"` or the catalog's `[plugins]`. From 2.2,
`kotlinOptions {}` is a compile error, `jvmDefault` replaces `-Xjvm-default`, guard conditions are
stable and context parameters a preview; from 1.7 the compiler rejects a non-exhaustive `when`; the
2.5 EAP adds name-based destructuring. Read before flagging anything: `.editorconfig` `ktlint_*` keys
or a ktlint plugin; `detekt.yml` and `buildUponDefaultConfig`; `compilerOptions {}` for `explicitApi()`,
`allWarningsAsErrors`, `extraWarnings`, `progressiveMode`, `-X` flags and `-Xjspecify-annotations`;
`api/*.api` files; the test stack (JUnit 5 + MockK + coroutines-test + Turbine, or Kotest).

### Already enforced: do not comment

| If the repository runs | then skip |
|---|---|
| ktlint (`.editorconfig` keys, Gradle plugin or hook) | whitespace, indentation, wrapping, blank lines, trailing commas, import order, wildcard and unused imports, semicolons, `Unit` return, modifier order, `"${x}"` braces, file and identifier naming |
| detekt with default config | generic catch and throw, swallowed and rethrown exceptions, `printStackTrace`, throw or return in `finally`; `UnsafeCast`, `UnsafeCallOnNullableType`, `map[k]!!`, `HasPlatformType`, `equals` without `hashCode`, double mutability, implicit locale, `===` on values; `InjectDispatcher`, `RedundantSuspendModifier`, `SleepInsteadOfDelay`, suspend returning `Flow`; magic numbers, return and throw counts, `UseRequire`/`UseCheckOrError`/`UseRequireNotNull`, `SafeCast`, `VarCouldBeVal`, unused privates and parameters, destructuring over 3, `MayBeConstant`, `UnnecessaryApply/Let/Filter`; complexity thresholds; `ArrayPrimitive`, `ForEachOnRange`, `SpreadOperator` |
| detekt, but these rules are inactive by default | nothing: still review by hand unless the config enables `GlobalCoroutineUsage`, `SuspendFunSwallowedCancellation`, `SuspendFunInFinallySection`, `ElseCaseInsteadOfExhaustiveWhen`, `LateinitUsage`, `CastToNullableType`, `NullCheckOnMutableProperty`, `NullableToStringCall`, `DontDowncastCollectionTypes`, `MissingUseCall`, `DataClassShouldBeImmutable`, `NestedScopeFunctions`, `CouldBeSequence`, `NotImplementedDeclaration`, `ObjectExtendsThrowable`; suggest enabling the coroutine and sealed-`when` ones |
| `explicitApi()` or `-Xexplicit-api=strict` | "add `public`/`internal`" and "declare the return type" on that module; still check which visibility was chosen |
| `allWarningsAsErrors = true` | unused variables and parameters, deprecated calls, redundant modifiers, `NOTHING_TO_INLINE`, nullability mismatches under `-Xjspecify-annotations=strict` |
| binary-compatibility validator or `abiValidation {}` | hunting for signature changes; review the `.api` dump diff instead |
| Android Lint (Android modules) | `AvoidUsingNotNullOperator`, `UnknownNullness`, `KotlinPropertyAccess`, `LambdaLast`, `NoHardKeywords`, `CheckResult`, `UnsafeOptInUsageError`, `IgnoreWithoutReason`, `AssertionSideEffect` |
| the compiler, always | non-exhaustive `when` over sealed, enum or Boolean, `val` reassignment, `===` on value classes, `lateinit` on primitives or nullables, public inline touching private API, `@Serializable` referencing a non-serialisable type, `synchronized` around a suspend call in a lambda |

## Checklist

### Coroutines and Flow

- **No `GlobalScope`, no unowned `CoroutineScope(...)`.** Signal: `GlobalScope.`,
  `CoroutineScope(Dispatchers`, `.launch` on a scope nobody cancels. Why: the coroutine outlives the
  request and loses its exceptions; ask who cancels it and when; `coroutineScope {}` or an injected
  scope; the one legitimate case needs `@OptIn(DelicateCoroutinesApi::class)` and a handler.
  [GlobalScope]
- **`runBlocking` only in `main`, tests and non-suspend callbacks.** Signal: `runBlocking` inside a
  `suspend fun` or a server handler. Why: it blocks a dispatcher thread, starves a limited pool and
  deadlocks when nested. [runBlocking]
- **A `suspend fun` never blocks the thread.** Signal: JDBC, `Thread.sleep`, a blocking client or file
  IO inside `suspend fun` with no `withContext(ioDispatcher)`. Why: the function that blocks moves
  itself; `delay` for sleep, `runInterruptible {}` to react to cancellation. [android-coroutines]
- **Dispatchers injected; `Unconfined` never in production.** Signal: `Dispatchers.IO` hard-coded in a
  class, `Dispatchers.Unconfined`, `newSingleThreadContext` never closed. Why: a hard-coded dispatcher
  cannot be a `TestDispatcher`, so `runTest` cannot skip its delays. [dispatchers] [coroutines-test]
- **Never swallow `CancellationException`.** Signal: `catch (e: Exception)`, `catch (e: Throwable)` or
  `runCatching {}` around suspending code with no rethrow of `CancellationException`. Why: the
  coroutine keeps running after cancel and the parent never learns. [cancellation] [detekt-coroutines]
- **Cancellation is cooperative; cleanup runs under `withContext(NonCancellable)`.** Signal: a CPU loop
  with no `ensureActive()`/`yield()`; a suspend call in `finally`; `launch(NonCancellable)`. Why:
  `withTimeout` around a blind loop does nothing, the `finally` call throws at once, and
  `launch(NonCancellable)` severs the parent-child relation. [cancellation] [NonCancellable]
- **Every `async` is awaited; no `fooAsync(): Deferred` functions.** Signal: `async {` whose result is
  dropped; `fun xAsync(): Deferred<`. Why: an unawaited `Deferred` hides its exception until the scope
  ends; `launch` is fire-and-forget; expose `suspend fun` instead. [async] [coroutine-exceptions]
- **`CoroutineExceptionHandler` only on a root `launch`.** Signal: a handler passed to `async` or a
  child. Why: dead code, the exception still cancels the parent and its siblings; `supervisorScope`
  makes failure one-directional, then each child handles its own. [coroutine-exceptions]
- **`suspend fun` returning `Flow`; `withContext` or `launch` inside `flow {}`.** Signal:
  `suspend fun x(): Flow<`; `withContext` in a builder; `try { emit(x) } catch { emit(fallback) }`.
  Why: flows are cold and the builder owns its context; violations are a runtime
  `IllegalStateException`; `flowOn`, `channelFlow`, `onEach` before `catch`. [flow-guide] [Flow-api]
- **`StateFlow` for state, `SharedFlow` for events, `update {}` for read-modify-write.** Signal:
  `StateFlow<Event>`, a `SharedFlow` emitted before anyone collects, `value = value + 1`, the mutable
  type exposed. Why: `StateFlow` drops equal repeats, a `SharedFlow` with no subscriber drops the
  emission, neither completes; expose `asStateFlow()`. [StateFlow] [SharedFlow]

### Null safety

- **No `!!` in production without the invariant stated; `as?` where a cast may fail.** Signal: `!!`
  outside `src/test`; `map[key]!!`; `x as String?`. Why: a design gap becomes an NPE or
  `ClassCastException` at a distance; `requireNotNull(x) { "..." }` names the failure, `getValue(key)`
  names the key, `as T?` still throws. [null-safety] [typecasts] [detekt-bugs]
- **Platform types never leak into a declaration.** Signal: `val x = javaObj.getFoo()` or
  `fun f() = javaList[0]` with no declared type on anything non-private. Why: `T!` defers nullability
  to every caller; decide `Foo` or `Foo?` once at the Java boundary, and prefer
  `-Xjspecify-annotations=strict` when the library is annotated. [coding-conventions] [java-interop]
- **`lateinit` only for framework, DI and test fixtures.** Signal: `lateinit var` as deferred
  initialisation in ordinary code. Why: `UninitializedPropertyAccessException` at a distance; a
  nullable, `by lazy` or a constructor parameter is the honest model. [properties]
- **`?.let { } ?: run { }` is not if/else.** Signal: `?.let` with side effects followed by `?:`. Why:
  a `let` whose lambda returns null falls into the Elvis branch; write `if`/`else`. [scope-functions]
- **`!!` after a null check on a `var` property: ask for a local copy.** Signal:
  `if (foo != null) use(foo!!)` on a mutable, `open` or custom-getter property. Why: the compiler
  refuses the smart cast because another thread may change it; `val foo = foo ?: return`. [typecasts]
- **`OrNull` variants when empty or invalid is normal.** Signal: `first()`, `single()`, `toInt()`,
  `valueOf()` on data from outside; `List<T>?` where `orEmpty()` fits. Why: `NoSuchElementException`
  waits for production data. [j2k-null] [idioms]
- **`Any?` in a signature is a type-system opt-out.** Signal: `Any?` parameters or returns outside
  logging and serialisation. Why: a sealed type, generic parameter or interface keeps the compiler in
  the loop. [api-readability]

### Error handling

- **No `catch (e: Exception)` or `Throwable` except at a boundary that logs and rethrows.** Signal:
  the generic catch inside domain code. Why: it hides `CancellationException`, `OutOfMemoryError` and
  programming errors. [detekt-exceptions] [cancellation]
- **A caught exception is rethrown, chained as `cause` or logged as an object.** Signal:
  `catch (e: X) { log.warn("failed") }`, `catch (e: X) { return null }`, `onFailure { log }`. Why: the
  cause is gone; `runCatching` is the same swallow with better syntax. [detekt-exceptions]
- **`require`/`check`/`error` with the offending value in the message.** Signal: hand-rolled
  `if (...) throw IllegalArgumentException(...)`, a message with no input, `error(throwable)`. Why:
  `require` for arguments, `check` for state, `error` for unreachable; `error()` stringifies a
  `Throwable`. [exceptions] [detekt-exceptions]
- **`Result<T>` and `runCatching` are not a domain error type.** Signal: `fun load(): Result<User>`;
  `runCatching` around domain code. Why: the KEEP shows it as the anti-example and it catches
  everything, cancellation included; use `T?`, a sealed result or an exception. [KEEP-Result]
- **One failure convention per API.** Signal: siblings where one throws and one returns `null`; a
  third-party exception escaping a library. Why: callers cannot handle what they cannot predict; wrap
  with the original as `cause`. [api-debuggability]
- **Checked exceptions are invisible.** Signal: a Java call declaring `throws IOException` with no
  handling; a Kotlin function Java calls with no `@Throws`. Why: neither is checked. [java-interop]
- **`use {}` for every `Closeable`.** Signal: `close()` in a `finally`, or no close, on a stream,
  statement or client. Why: `use` cannot be forgotten and closes on failure. [exceptions] [idioms]

### Classes, data and sealed hierarchies

- **Data classes are immutable: `val` only.** Signal: `var` in a data class constructor. Why:
  `hashCode` changes after insertion as a key, `StateFlow` dedup breaks, `copy()` lies. [data-classes]
- **No arrays in a data class without hand-written `equals`/`hashCode`.** Signal: `ByteArray` or
  `Array<` as a data class property. Why: generated `equals` compares arrays by reference; `List`, or
  `contentEquals` plus `contentHashCode`. [arrays] [equality]
- **Only primary-constructor properties enter `equals`, `copy` and `toString`; `copy` is shallow.**
  Signal: a property declared in the body; a `MutableList` property. Why: body properties are
  invisible to all of them and copies share the mutable list. [data-classes]
- **No `else` in a `when` over a sealed type or enum.** Signal: `else ->` where the subject is sealed
  or an enum. Why: it defeats exhaustiveness; a new variant falls through silently instead of failing
  to compile. [sealed] [api-predictability]
- **`sealed interface` without shared state; `sealed` over `enum` when variants carry data.** Signal:
  an enum hacking per-variant payloads; a non-sealed direct subclass; a subclass in another package.
  Why: a non-sealed subclass reopens the hierarchy; subclasses share package and module. [sealed]
- **`value class` for ids; mind the boxing boundaries.** Signal: `typealias UserId = String`;
  `List<UserId>` or `UserId?` on a hot path. Why: a typealias accepts any `String`; a value class boxes
  as a generic, interface or nullable; Java needs `@JvmName` or `@JvmExposeBoxed`. [value-classes]
### Collections and sequences

- **Expose read-only types; copy at API boundaries.** Signal: `MutableList`, `MutableMap`,
  `ArrayList`, `MutableStateFlow` in a public signature; a constructor storing a caller's `List` as-is.
  Why: a read-only interface is a view, so the holder of the mutable reference can still change it;
  `toList()` where immutability is relied on. [collections] [api-predictability]
- **`var` holding a mutable collection is double mutability.** Signal: `var items = mutableListOf`.
  Why: choose `val` plus mutable, or `var` plus read-only replaced wholesale. [detekt-bugs]
- **Never downcast `List` to `MutableList`.** Signal: `as MutableList`. Why: works on `ArrayList`,
  throws `UnsupportedOperationException` on `listOf()`, breaks the declared contract. [detekt-bugs]
- **`associateBy`/`toMap` keep the last value; `zip` truncates.** Signal: those calls on keys that can
  repeat or lists that must match. Why: both are silent; `groupBy`, or
  `require(map.size == list.size)`. [transformations]
- **`asSequence()` for long chains over large inputs only.** Signal: `map {}.filter {}.first {}` over
  a large list; `asSequence()` over three elements. Why: each eager step materialises a list;
  sequences cost overhead on small inputs. [sequences]
- **`IntArray` over `Array<Int>`; `contentEquals`; spread copies.** Signal: `Array<Int>`, `==` on
  arrays, `*array` in a hot path. Why: boxing per element, reference equality, a copy per call. [arrays]

### Serialization

- **Every sealed subclass carries `@SerialName`.** Signal: a `@Serializable sealed` hierarchy with
  unannotated subclasses. Why: the default discriminator is the fully qualified class name, so a
  rename or package move breaks every stored document; names are unique per hierarchy. [kxs-poly]
- **Wire data class changes are checked against stored data and other services.** Signal: a new
  property without a default, a removed or renamed field on a `@Serializable` class. Why:
  `MissingFieldException` on old documents, failure on new ones without `ignoreUnknownKeys`.
  [kxs-classes] [api-compat]
- **Defaults are not encoded by default.** Signal: a consumer needing a field equal to its default with
  no `@EncodeDefault`, `encodeDefaults = true` or `@Required`. Why: the field disappears; `null` into
  a non-nullable property fails even with a default unless `coerceInputValues`. [kxs-json]
- **`ignoreUnknownKeys` is a schema-evolution decision; lenient flags need a reason.** Signal: a
  `Json {}` for inbound data without `ignoreUnknownKeys = true`; `isLenient`, `allowComments`,
  `allowTrailingComma`, `allowSpecialFloatingPointValues`. Why: the default fails on any new field
  from a newer producer; the lenient flags accept non-RFC-8259 JSON. [kxs-json]
- **One `Json` instance in a top-level `val`.** Signal: `Json { }` inside a function. Why: instances
  are immutable, thread-safe and cache per-class metadata. [kxs-json]
- **Open polymorphism needs a `SerializersModule`; prefer `sealed`; the static type decides.** Signal:
  an `abstract` or interface base, `@Polymorphic`, `Any` properties, a subclass missing from the
  module; a value typed as the subclass when encoded. Why: a missing registration is a runtime
  `SerializationException`; a subclass-typed value is written without its discriminator. [kxs-poly]
- **Only backing-field properties serialise; `init` runs on decode; `@Transient` needs a default.**
  Signal: a getter-only property expected on the wire; a side-effecting initialiser. Why: initialisers
  are skipped when the value is present, so `require` in `init` validates wire data. [kxs-classes]
- **Jackson: register `jackson-module-kotlin` and fail on null primitives.** Signal: `ObjectMapper()`
  without `registerKotlinModule()`; no `FAIL_ON_NULL_FOR_PRIMITIVES`; a sealed base without
  `@JsonTypeInfo`. Why: `null` into an `Int` becomes `0` silently; from 2.19 null for a non-null
  reified type throws. [jackson-kotlin]

### Java interop

- **JVM annotations only where Java callers exist.** Signal: `@JvmStatic`, `@JvmField`,
  `@JvmOverloads`, `@file:JvmName`, `@Throws` added or missing. Why: without them Java sees
  `Foo.Companion.bar()`, `getX()`, one arity, `FooKt`; without Java callers, noise. [kotlin-from-java]
- **`@JvmOverloads` does not protect Kotlin callers.** Signal: a defaulted parameter added to a
  library function. Why: the old JVM signature still disappears for Kotlin call sites;
  `@IntroducedAt` or hand-written overloads. [api-compat] [kotlin-from-java]
- **`internal` is public bytecode with a mangled name.** Signal: `internal` relied on as access control
  against Java. Why: Java calls `foo$module`; `@JvmSynthetic` or `private` controls. [kotlin-from-java]
- **Kotlin-only features leaking into a Java-facing API.** Signal: `suspend`, `value class`
  parameters, `Unit` lambdas, default arguments, `Nothing` type arguments, declaration-site variance
  in a class Java calls. Why: Java sees a `Continuation`, mangled names, `Unit.INSTANCE` and raw
  types; offer an overload or a `CompletableFuture` adapter. [kotlin-from-java] [value-classes]
- **Java collections and boxed types arrive as platform types.** Signal: a Java `List<T>` cast to
  `MutableList`; `List<java.lang.Integer>` used as `List<Int>`. Why: Kotlin mutates the first and NPEs
  on `list[0] + 1` for the second; `is List<*>` is the only generic check. [java-interop]
- **Kotlin already null-checks public non-null parameters.** Signal: `?: throw` behind a non-null
  parameter, or the type widened to `T?` for Java callers. Why: that NPE is intended. [kotlin-from-java]

### API design and visibility

- **Everything is `public` by default; ask for `internal` or `private`.** Signal: a new top-level
  declaration or member with no modifier. Why: in a library a compatibility promise, in an application
  clutter; state visibility explicitly. [visibility] [coding-conventions]
- **Libraries: `explicitApi()` and explicit return types on everything public.** Signal:
  `fun x() = ...` on a public library function; no `explicitApi()` in the build. Why: an inferred
  return type changes silently and breaks binary compatibility. [explicit-api] [api-compat]
- **Binary compatibility is read from the `.api` dump, not by eye.** Signal: a library PR with
  signature changes and no `api/*.api` diff, or a changed dump nobody mentions; neither BCV nor
  `abiValidation {}` in the build. Why: widening or narrowing a return type, adding a parameter even
  with a default, adding an abstract member all break something. [BCV] [api-compat] [whatsnew22]
- **No data classes, arrays, mutable collections or `Boolean` parameters in a public library API.**
  Signal: those in a published artifact's signatures. Why: `copy` and `componentN` change on every
  edit; a `Boolean` argument reads as nothing at the call site. [api-compat] [api-readability]
- **Deprecate before removing.** Signal: a public symbol deleted or renamed without
  `@Deprecated(message, ReplaceWith(...), level)`. Why: WARNING, ERROR, HIDDEN across minors, removal
  in a major; `var old by this::new` keeps a property alive meanwhile. [api-compat] [delegation]
- **Public `inline` bodies are copied into callers.** Signal: a changed body of a `public inline fun`.
  Why: consumers that have not recompiled run the old body; it may only touch public or
  `@PublishedApi` declarations. [inline] [api-compat]

### Tests

- **Coroutine tests use `runTest` and inject the `TestDispatcher`.** Signal: `runBlocking` in a test;
  a hard-coded `Dispatchers.IO` in the code under test. Why: real delays, coroutines invisible to
  `advanceUntilIdle()`, uncaught child exceptions lost. [coroutines-test]
- **`StandardTestDispatcher` queues, `UnconfinedTestDispatcher` runs eagerly; pair `setMain` with
  `resetMain`.** Signal: a test green only under `Unconfined`; `advanceUntilIdle()` with no `Standard`
  dispatcher in play; never-ending producers outside `backgroundScope`. Why: the first hides an
  ordering assumption, the second does nothing. [coroutines-test]
- **Flows are asserted with Turbine or `toList()` under `runTest`.** Signal: `collect` into a mutable
  list plus `delay`; `cancelAndIgnoreRemainingEvents()` at the end. Why: Turbine fails on unconsumed
  events, so ignoring them hides emissions; its 3 s timeout is wall-clock. [turbine]
- **MockK `relaxed = true` hides missing stubs.** Signal: `mockk(relaxed = true)`; `every {}` with no
  `verify {}` or result assertion. Why: the test keeps passing when the code calls something new;
  `relaxUnitFun = true`, `confirmVerified`, `checkUnnecessaryStub()` tighten. [mockk]
- **`mockkStatic`/`mockkObject`/`mockkConstructor` are undone.** Signal: no `unmockkAll()` in
  `@AfterEach` and no scoped block form. Why: they leak into every other test in the JVM. [mockk]
- **Do not mock value types, collections, `File` or `System`; `coEvery` for suspend.** Signal:
  `mockk<List<` or a mocked data class; `every` on a suspend function. Why: MockK restricts the first
  as a design smell; the second is a compile error or a wrong answer. [mockk]
- **Kotest `SingleInstance` shares one spec instance; one style per project.** Signal:
  `val list = mutableListOf()` in a spec body; several spec styles. Why: state leaks between tests;
  `InstancePerRoot` or reset in `beforeTest`; styles have no functional difference. [kotest-isolation]
- **No `Thread.sleep` or system clock in code under test.** Signal: `Thread.sleep`,
  `System.currentTimeMillis()`, `Clock.systemUTC()`. Why: inject a `Clock`/`TimeSource`; `runTest`
  gives `testScheduler.timeSource` and virtual `withTimeout`. [coroutines-test]

### Build and dependencies

- **One literal Kotlin Gradle plugin version, aligned across modules.** Signal: differing
  `kotlin("jvm") version` literals; a stdlib pinned apart from KGP. Why: settings `plugins {}` or the
  catalog's `[plugins]`; `kotlin-bom` aligns transitive stdlib artifacts. [gradle-project]
- **`compilerOptions {}`, not `kotlinOptions {}`.** Signal: `kotlinOptions`; `freeCompilerArgs` for
  something with a typed property. Why: an error from 2.2.0; `optIn.add`, `progressiveMode`,
  `jvmDefault`, `allWarningsAsErrors` are typed. [gradle-compiler] [whatsnew22]
- **`jvmToolchain(N)`; never relax `jvmTargetValidationMode`.** Signal:
  `jvmTargetValidationMode = IGNORE` or `WARNING`; `jvmTarget` and `targetCompatibility` differ. Why:
  the default `jvmTarget` is still 1.8 and the relaxed mode hides the mismatch. [gradle-compiler]
- **`allWarningsAsErrors` stays on; broad `@Suppress` needs a reason.** Signal: the flag removed or
  false; `@Suppress("DEPRECATION")` on a file. Why: without it the reviewer hunts unused, deprecated
  and platform-type warnings by hand. [gradle-compiler]
- **`api` only for types in public signatures.** Signal: `api(` for logging or a client the public API
  never names; `implementation(` for a `Flow` or `Json` returned publicly. Why: the first leaks, the
  second fails consumers at compile time; `compileOnly` annotations, `runtimeOnly` drivers.
  [gradle-api-impl]
- **kotlinx versions match the Kotlin version.** Signal: a Kotlin bump without `kotlinx-coroutines`,
  `kotlinx-serialization` and its plugin, `kotlinx-datetime`; `kotlinx-coroutines-test` outside
  `testImplementation`. Why: they compile against specific releases. [gradle-project]
- **`kotlin("test")` picks JUnit 4 without `useJUnitPlatform()`.** Signal: JUnit 5 dependencies and no
  `tasks.test { useJUnitPlatform() }`. Why: zero tests run and the build is green. [gradle-project]
- **`-X` flags are previews and compatibility decisions.** Signal: `-Xcontext-parameters`,
  `-Xname-based-destructuring`, `-Xexplicit-api`, `-Xjvm-default` in `freeCompilerArgs`. Why: a
  library's public signatures lock consumers to the flag; prefer `explicitApi()` and `jvmDefault`;
  `complete` destructuring mode changes what `val (a, b) = x` means. [whatsnew22] [gradle-compiler]

### Idioms and language features

- **`TODO()` left in the diff.** Signal: `TODO(` outside tests. Why: it returns `Nothing`, so it
  compiles anywhere and throws `NotImplementedError` at runtime. [idioms] [detekt-exceptions]
- **Defaults over overloads; named arguments for same-type and `Boolean` parameters.** Signal: a new
  overload that only supplies a default; `draw(10, 10, 100, 100, true)`. Why: the call site should
  read `fill = true`; a library should not take a `Boolean` at all. [coding-conventions]
- **Scope functions by the table; never nested.** Signal: `let` inside `apply`, `it` and `this` in one
  lambda; `apply` returning other than its receiver. Why: the current context object is easy to
  mistake, a correctness hazard not taste. [scope-functions]
- **`lazy` mode matches the use.** Signal: `by lazy(LazyThreadSafetyMode.NONE)` on a shared object.
  Why: `NONE` is a race unless init and use are on one thread; `PUBLICATION` for idempotent
  initialisers. [delegation]
- **Extensions: narrow visibility, static dispatch.** Signal: a `public` extension on `String`, `Any`
  or `List<*>` in a shared module; an extension that "overrides" a member. Why: it pollutes every
  caller's completion, dispatches on the declared type, and a member always wins. [extensions]
- **`inline` only for lambda parameters or `reified`.** Signal: `inline fun` with no function-typed
  parameter; a large inline body. Why: `NOTHING_TO_INLINE` noise and bloat at every call site. [inline]
- **Destructuring is positional.** Signal: `val (email, username) = user` on a data class with three or
  more properties, or across a module boundary. Why: reordering the class silently swaps values; `_`
  for ignored parts, name-based destructuring when the project enables it. [destructuring]
- **`Pair`/`Triple` in a signature is a missing data class.** Signal: `: Pair<` as a parameter or
  return type. Why: property names make it readable; fine locally and in `a to b`. [data-classes]

## Severity

**Blocking**
- No `GlobalScope`, no unowned `CoroutineScope(...)`; `launch(NonCancellable)`
- Never swallow `CancellationException`
- `runBlocking` inside a `suspend fun` or on a server pool
- A `suspend fun` that blocks the thread
- `withContext` or `launch` inside `flow {}`; emit from a swallowing catch
- `CoroutineExceptionHandler` on `async` or a child
- `!!`, `map[k]!!`, `first()`, `as` on data production can make null, empty or mismatched
- `else` in a `when` over a sealed type or enum
- `associateBy`/`toMap` on colliding keys; `zip` on lists that must match
- Arrays or `var` in a data class used as a key; `===` on strings or value classes
- Mutable collection or `MutableStateFlow` exposed; `List` downcast to `MutableList`
- Sealed subclass without `@SerialName`; wire change without defaults; Jackson `null` into an `Int`
- A caught exception swallowed (string-only log, `onFailure` that logs)
- `TODO()` in a production path
- Library: public symbol removed without deprecation; data class or `Boolean` parameter added;
  `.api` dump changed unacknowledged; `kotlinOptions` on 2.2+
- Tests: `relaxed = true` with no verification; `cancelAndIgnoreRemainingEvents()` hiding
  emissions; mocks never unmocked; `useJUnitPlatform()` missing

**Should fix**
- Hard-coded `Dispatchers.*`; `Dispatchers.Unconfined` in production
- `async` never awaited; `fooAsync(): Deferred`; `suspend fun` returning `Flow`
- Long loops without `ensureActive()`; suspending cleanup in `finally` without `NonCancellable`
- `StateFlow` for events, `SharedFlow` for state, `value = value + 1` instead of `update {}`
- `lateinit` outside DI and test setup; `?.let { } ?: run { }` control flow; `Any?` in a signature
- Platform types inferred into `internal` or `public` declarations
- Public-by-default declarations where `internal` was meant
- `Pair`/`Triple` in signatures; positional destructuring of three or more
- `Result<T>` as a domain return type; mixed throw-versus-null conventions in one API
- Nested scope functions; `inline` with no lambda
- Long eager chains over large inputs; `Array<Int>`; spread in a hot path
- `Json { }` per call; `ignoreUnknownKeys` unset on inbound data
- `api` where `implementation` suffices; kotlinx versions behind Kotlin; `jvmTargetValidationMode`
  relaxed; `allWarningsAsErrors` turned off
- Kotest `SingleInstance` with mutable spec state; `Thread.sleep` in tests

**Nit**
- `if` versus `when`; expression bodies; `..<`; `for` versus `forEach`; `"${name}"` braces; `const val`;
  `data object` versus `object`; `entries` versus `values()`; `@JvmStatic` with no Java callers

## Where the sources disagree

- `!!` with a justifying comment or banned outright: follow the repository's convention; if none,
  flag and accept a comment stating the invariant and why `requireNotNull` with a message is not better.
- `else -> error("unreachable")` or no `else` over sealed types: follow the repository's convention;
  if none, prefer no `else` because the compiler already rejects a non-exhaustive `when` (1.7+), so
  the `else` only hides new variants; allow it in `commonMain` for `expect` sealed classes.
- `catch (e: CancellationException) { throw e }` or `ensureActive()` in the catch: either is fine; a
  bare generic catch with neither is the defect.
- Stdlib `Result<T>` or a typed result (Arrow `Either`, kotlin-result): follow the repository's
  convention; if none, treat stdlib `Result` as should-fix and third-party types as a project choice.
- MockK `relaxed = true`: the vendor documents it neutrally and the concern is practitioner
  consensus, so keep it at should-fix unless no verification follows at all.
- Sort stability: the JVM delegates to a stable sort; in `commonMain`, Native or JS it is unverified.
- One `Dispatchers.IO` or `limitedParallelism` views per blocking resource: not a defect; raise it as
  a scaling question when a PR introduces a new blocking resource.
- Data classes in application APIs: follow the repository's convention; if none, allow them inside a
  repo and apply "no data class in public API" only to artifacts consumed outside it.
- Name-based destructuring: stable only in `only-syntax` mode (2.5 EAP); review a flag change here as
  a behaviour change, not style.
- `kotlinOptions`: an error from 2.2.0, a warning on 2.0 and 2.1; severity follows the project version.
- The ktlint rule inventory and JUnit Kotlin guidance were not fetched; items marked unverified rest
  on general knowledge, so phrase them as questions when the project disagrees.

## Reference

Open `references/kotlin-review-research.md` for an item's full signal list or the detekt rule that
would catch it, for a finding's source (every tag above resolves to a URL in section 1), for the
complete tooling table, or when a severity call is contested. Its sections: 1. Sources; 2. Detecting
the language and flavour; 3. Checklist (Null safety; Idioms and language features; Classes, data and
sealed hierarchies; Collections and sequences; Coroutines and Flow; Error handling; Java interop;
Serialization; API design and visibility; Tests; Build and dependencies); 4. Severity hints;
5. Already enforced by tooling; 6. Open questions / disagreements.
