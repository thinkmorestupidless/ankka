**Contents**

- 1. Sources
- 2. Detecting the language and version
- 3. Checklist (3.1 Modern idioms; 3.2 Types, nulls and immutability; 3.3 Equality, hashing and value
  semantics; 3.4 Collections and generics; 3.5 Error handling and resources; 3.6 Concurrency;
  3.7 Security; 3.8 Serialization and interop; 3.9 API design and encapsulation; 3.10 Tests;
  3.11 Build and dependencies)
- 4. Severity hints
- 5. Already enforced by tooling
- 6. Open questions / disagreements
- Appendix: Effective Java, 3rd edition, the 90 items

# Java review rules (Java 17 and 21 LTS, with awareness of 25)

A checklist an AI reviewer applies to a pull request *after* the language-agnostic checks. Researched
2026-10-23 from primary sources where they could be fetched; each item names its source. "EJ3 Item n"
is *Effective Java*, 3rd edition (full item list in the appendix). "EP" is Error Prone, "SB" is
SpotBugs, "CS" is Checkstyle, "SCG" is Oracle's Secure Coding Guidelines for Java SE, "GJS" is the
Google Java Style Guide, "JLS" is the Java Language Specification (SE 21), "JD21" is the Java SE 21
API Javadoc.

## 1. Sources

URLs actually read, one line each with what it contributed. Nothing below was cited without being
fetched; the few pages that could not be fetched are named in section 6.

### Effective Java
- https://www.informit.com/store/effective-java-9780134686097 — the publisher's table of contents: all 90 item numbers and exact titles of the 3rd edition (appendix).
- https://github.com/david-sauvage/effective-java-summary — a 3rd-edition summary; cross-checked the 90 titles and chapter grouping.
- https://pv168.pages.fi.muni.cz/literature/effective-java — the chapter-to-item ranges (1–9, 10–14, 15–25, 26–33, 34–41, 42–48, 49–56, 57–68, 69–77, 78–84, 85–90).
- https://github.com/HugoMatilla/Effective-JAVA-Summary — a *2nd*-edition summary (78 items); used only to confirm that 2nd-edition numbering differs, so a reviewer must cite 3rd-edition numbers.
- https://github.com/recepinanc/effective-java-notes — confirms the 90-item count only.

### Style guides
- https://google.github.io/styleguide/javaguide.html — the non-formatting rules: naming, `@Override` always, no ignored exceptions, static members by class name, no finalizers, one top-level class, no wildcard imports, overload grouping, modifier order, `L` suffix, Javadoc requirements and summary fragments.
- https://www.oracle.com/java/technologies/javase/codeconventions-programmingpractices.html — Oracle's 1999 Code Conventions, section 10 Programming Practices (page marked "Archive Purposes Only", last revised 1999): no public instance variables without reason, class variables via class name, no magic numbers other than -1/0/1, no chained or embedded assignments, parentheses in mixed-operator expressions, `return booleanExpression`.
- https://github.com/google/google-java-format — what the formatter does (whitespace, line breaks, import sorting and unused-import removal, Javadoc formatting) and that its algorithm has no configuration.
- https://github.com/diffplug/spotless/blob/main/plugin-gradle/README.md — Spotless Java steps (`googleJavaFormat`, `palantirJavaFormat`, `eclipse`, `removeUnusedImports`, `importOrder`, `formatAnnotations`, `licenseHeader`) and `ratchetFrom`.

### Security
- https://www.oracle.com/java/technologies/javase/seccodeguide.html — Secure Coding Guidelines for Java SE (v11.0, June 2025, fetched with a browser user agent after a 403): every guideline title in sections 0–9 and the text of INJECT-1..5, INPUT-1..3, MUTABLE-1..12, OBJECT-1..5, SERIAL-1..6, DOS-2, DOS-5, CONFIDENTIAL-1..3.
- https://cmu-sei.github.io/secure-coding-standards/sei-cert-oracle-coding-standard-for-java/ — the SEI CERT Oracle Coding Standard for Java: the 19 rule categories (IDS, DCL, EXP, NUM, STR, OBJ, MET, ERR, VNA, LCK, THI, TPS, TSM, FIO, SER, SEC, ENV, JNI, MSC); individual rule pages were not read.
- https://openjdk.org/jeps/290 — serialization filtering (`ObjectInputFilter`, `jdk.serialFilter`, pattern syntax), JDK 9.
- https://docs.oracle.com/en/java/javase/21/core/serialization-filtering1.html — the JDK 21 serialization-filtering guide: allow-lists over reject-lists, filter factories (JDK 17), built-in RMI/JMX filters.

### Static analysis
- https://errorprone.info/bugpatterns — the full list of ERROR-level and WARNING-level bug patterns on by default.
- https://errorprone.info/bugpattern/DoubleCheckedLocking — why non-volatile DCL is broken; volatile or holder idiom.
- https://errorprone.info/bugpattern/FutureReturnValueIgnored — a dropped `Future` hides its exception.
- https://errorprone.info/bugpattern/StreamResourceLeak — `Files.lines/list/walk/find` wrap resources and need try-with-resources.
- https://errorprone.info/bugpattern/ThreadLocalUsage — `ThreadLocal` fields should be static.
- https://errorprone.info/bugpattern/InterruptedExceptionSwallowed — broad `catch (Exception|Throwable)` swallows interruption.
- https://errorprone.info/bugpattern/Var — the opt-in "final by default, `@Var` to opt out" style.
- https://spotbugs.readthedocs.io/en/latest/bugDescriptions.html — all categories: Bad practice, Correctness, Multithreaded correctness, Malicious code, Performance, I18N, Security, Dodgy code, with pattern codes.
- https://checkstyle.sourceforge.io/checks.html — every check by group; used to separate formatting checks from semantic ones.
- https://jspecify.dev/docs/user-guide/ — `@Nullable`, `@NonNull`, `@NullMarked`, `@NullUnmarked`; type-use placement; type parameters and arrays.
- https://github.com/uber/NullAway — what NullAway checks, `AnnotatedPackages`/`OnlyNullMarked`, JSpecify mode, Lombok and unannotated code exclusions, Error Prone integration.
- https://community.sonarsource.com/t/java-rule-s2095-bug-reported-for-java-util-concurexecutorservice-even-though-sonar-java-source-is-17/109431 — Sonar S2095 (resources should be closed) now fires on an un-closed `ExecutorService` because it is `AutoCloseable` since JDK 19; SonarSource's confirmation.
- https://assertj.github.io/doc/ — AssertJ guide: the "forgotten assertion" trap and the tools that catch it (SpotBugs `RV_RETURN_VALUE_IGNORED_INFERRED`, Sonar S2970), `as()` before the assertion, recursive comparison, `containsExactly` vs `containsExactlyInAnyOrder`, exception assertions.

### Concurrency
- https://openjdk.org/jeps/444 — virtual threads (JDK 21): do not pool, thread-locals cost, pinning on `synchronized` and native frames, `jdk.VirtualThreadPinned`, `jdk.tracePinnedThreads`, not for CPU-bound work.
- https://openjdk.org/jeps/491 — JDK 24: `synchronized` no longer pins; what still pins; `ReentrantLock` migration no longer needed; keep lock scopes small and no I/O under a lock.
- https://openjdk.org/jeps/505 — structured concurrency, fifth preview in JDK 25: `StructuredTaskScope.open`, `fork`, `join`, `Joiner`, enforced rules, the problems with unstructured `ExecutorService`/`Future`.
- https://openjdk.org/jeps/506 — scoped values, final in JDK 25: `ScopedValue.where(...).run/call`, immutability, bounded lifetime, cheap inheritance; when to migrate from `ThreadLocal` and when not.
- https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html — JLS §17.5 final-field semantics and the `this`-must-not-escape condition; §17.7 non-atomic `long`/`double`; §17.4.5 happens-before edges.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html — default executor (common pool), non-async vs `*Async` thread, `CompletionException` wrapping, `join` vs `get`, `orTimeout`/`completeOnTimeout`, `obtrudeValue`.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html — the two-phase `shutdown`/`awaitTermination`/`shutdownNow` idiom, `close()` since 19, "an unused ExecutorService should be shut down".
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentHashMap.html — `compute*`/`merge` atomicity and the "short, simple, must not update other mappings" rule; no nulls; weakly consistent iterators.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ThreadLocal.html — lifetime tied to the thread; `remove()`; `withInitial`.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html — virtual threads are daemon, fixed priority, unnamed by default; `interrupt()` semantics; `stop/suspend/resume` throw.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Object.html — `equals`/`hashCode` contracts, `clone` shallow copy, `finalize` deprecated for removal, `wait` in a loop.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/HashMap.html — not synchronized; fail-fast iterators are best effort only.
- https://jcip.net/ and https://jcip.net/annotations/doc/net/jcip/annotations/package-summary.html — *Java Concurrency in Practice* home page and the `@ThreadSafe`, `@NotThreadSafe`, `@Immutable`, `@GuardedBy` annotations.

### Language and library
- https://openjdk.org/jeps/395 — records (JDK 16): restrictions, compact constructors, derived `equals`/`hashCode`/`toString`, serialization.
- https://openjdk.org/jeps/409 — sealed classes (JDK 17): `permits`, `final`/`sealed`/`non-sealed`, same module/package, exhaustive switch.
- https://openjdk.org/jeps/441 — pattern matching for `switch` (JDK 21): exhaustiveness, `MatchException`, guards, dominance, `case null`, prefer no `default` over a sealed hierarchy.
- https://openjdk.org/jeps/431 — sequenced collections (JDK 21): `getFirst/getLast/reversed`, retrofitted types, `SortedSet` positional ops throw.
- https://openjdk.org/jeps/465 — string templates: Closed/Withdrawn (June 2024) after previews in 21 and 22; no finalisation.
- https://openjdk.org/jeps/513 — flexible constructor bodies (JDK 25): statements before `super()`, the prologue may not use `this`; helps the this-escape problem.
- https://openjdk.org/jeps/512 — compact source files and instance `main` (JDK 25): intended for beginners, scripts and prototypes.
- https://openjdk.org/projects/jdk/17/, https://openjdk.org/projects/jdk/21/, https://openjdk.org/projects/jdk/25/ — JEP lists and GA dates (2021-09-14, 2023-09-19, 2025-09-16).
- https://docs.oracle.com/javase/specs/jls/se21/html/jls-5.html — JLS §5.1.7: boxed `==` identity guaranteed only for constant expressions in -128..127 (and `boolean`, `char` ≤ 0x7f).
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Optional.html — API note: intended as a return type; an `Optional` variable must never be `null`; value-based.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/doc-files/ValueBased.html — value-based classes: no `==`, identity hash or synchronization; "synchronization may fail".
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html — `equals` compares scale, `compareTo` does not; `new BigDecimal(double)` is unpredictable; `divide` without scale/`MathContext` throws on non-terminating expansions.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/SimpleDateFormat.html — "Date formats are not synchronized"; API note recommends `DateTimeFormatter`.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Random.html — not cryptographically secure; use `SecureRandom`; `ThreadLocalRandom` under contention.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html — `*Exact` methods; `Math.abs(Integer.MIN_VALUE)` is negative; `floorMod` vs `%`.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/stream/Stream.html — `toList()` is unmodifiable (JDK 16); non-interference and statelessness; `forEach` vs `forEachOrdered` on parallel streams.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/List.html — `List.of/copyOf`: unmodifiable, reject nulls, value-based, `copyOf` is a snapshot.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Map.html — mutable keys note; `Map.of/ofEntries/copyOf` reject nulls and duplicate keys; iteration order unspecified.
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/SuppressWarnings.html — annotate "the most deeply nested element where it is effective".
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Objects.html — `requireNonNull` "designed primarily for doing parameter validation in methods and constructors", `requireNonNullElse`, `checkIndex`.
- https://www.slf4j.org/faq.html — parameterized logging (`{}`), the throwable as last argument (since 1.6.0), no-op fallback when no provider is present.

### Testing
- https://docs.junit.org/current/user-guide/ — JUnit 6.1.x overview: requires Java 17; Vintage is deprecated.
- https://docs.junit.org/current/writing-tests/test-classes-and-methods.html — not `private`, `public` optional and discouraged, no return value.
- https://docs.junit.org/current/writing-tests/test-execution-order.html — default order "deterministic but intentionally nonobvious"; unit tests should not rely on order.
- https://docs.junit.org/current/writing-tests/disabling-tests.html — the JUnit team recommends a reason on `@Disabled`.
- https://docs.junit.org/current/writing-tests/assertions.html — `assertAll`, `assertThrows`, `assertTimeoutPreemptively` runs on another thread (ThreadLocal caveat), lazy `Supplier` messages, AssertJ/Hamcrest/Truth recommended.
- https://docs.junit.org/current/writing-tests/test-instance-lifecycle.html — PER_METHOD default and why; PER_CLASS allows non-static `@BeforeAll`.
- https://docs.junit.org/current/writing-tests/parameterized-classes-and-tests.html — sources; `@MethodSource` factories must be static unless PER_CLASS; display-name placeholders.
- https://docs.junit.org/current/writing-tests/nested-tests.html — `@Nested` on inner classes only.
- https://docs.junit.org/current/writing-tests/timeouts.html — `@Timeout`, `SAME_THREAD` default, `disabled_on_debug`.
- https://docs.junit.org/current/writing-tests/parallel-execution.html — opt-in, `@ResourceLock`, `@Isolated`, system-property races.
- https://docs.junit.org/current/migrating-from-junit4.html — annotation mapping; `@Rule` support deprecated for removal since 6.0.
- https://docs.junit.org/current/extensions/registering-extensions.html — `@ExtendWith` vs `@RegisterExtension` (static vs instance field consequences).
- https://raw.githubusercontent.com/mockito/mockito/main/mockito-core/src/main/java/org/mockito/Mockito.java — the current class-level Javadoc: "don't mock the List class", `verifyNoMoreInteractions` not in every test, spies and `doReturn`, `ArgumentCaptor` for verification not stubbing, partial mocks caution, inline mock maker default since 5.0.0, strictness (section 40), `lenient()` (46), `mockStatic`/`mockConstruction` in try-with-resources (48, 49), `@DoNotMock` (51), per-mock strictness (52).
- https://site.mockito.org/javadoc/current/org/mockito/Mockito.html — served an older (2.2.7) rendering; used only to confirm the older sections.
- https://java.testcontainers.org/test_framework_integration/junit_5/ — `@Testcontainers`/`@Container`: static shared vs instance restarted; parallel execution unsupported.
- https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/ — the singleton container pattern; Ryuk stops it.

### Build
- https://docs.oracle.com/en/java/javase/21/docs/specs/man/javac.html — all `-Xlint` keys (incl. `this-escape`, `synchronization`, `lossy-conversions`, `serial`, `rawtypes`, `unchecked`), `-Werror`, `--release` vs `-source/-target`.
- https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html — scopes and transitive scope table, nearest-definition mediation, `dependencyManagement`, BOM import, optional, exclusions.
- https://maven.apache.org/enforcer/enforcer-rules/dependencyConvergence.html — the convergence rule and its output.
- https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html — `maven.compiler.release` and why it beats `source`/`target`.
- https://docs.gradle.org/current/userguide/java_library_plugin.html — `api` vs `implementation` vs `compileOnly` vs `runtimeOnly`; `compile` removed in Gradle 7.
- https://docs.gradle.org/current/userguide/version_catalogs.html — `libs.versions.toml`; catalog versions are requests, not constraints.
- https://docs.gradle.org/current/userguide/platforms.html — `platform()` vs `enforcedPlatform()`; enforced only in applications.

## 2. Detecting the language and version

**It is Java when** the diff touches `*.java` (not `*.kt`, `*.scala`, `*.groovy`), `module-info.java`,
`package-info.java`, or a build file: `pom.xml` (Maven), `build.gradle` / `build.gradle.kts` /
`settings.gradle(.kts)` / `gradle/libs.versions.toml` (Gradle), `build.xml` (Ant, rare),
`.mvn/`, `mvnw`, `gradlew`. Kotlin DSL build files are still a Java project if the sources are
`.java`. Generated sources (`target/generated-sources`, `build/generated`, protobuf, Lombok's
`@Generated`) are not worth reviewing line by line.

**Find the compile target** before judging an idiom. In order of authority:
- Maven: `<maven.compiler.release>` (preferred, JDK 9+) or `<maven.compiler.source>`/`target`; the
  compiler plugin's `<release>`; a `<jdk>`-activated profile. `release` must be a JEP 223 number
  (`17`, not `1.8`) [maven-compiler-plugin].
- Gradle: `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`, or
  `options.release.set(21)`, or `sourceCompatibility`/`targetCompatibility`. `release` checks API
  use against the target release; `sourceCompatibility` alone does not [javac manual].
- `.sdkmanrc`, `.java-version`, `.tool-versions`, CI matrix (`setup-java` `java-version`),
  `Dockerfile` base image (`eclipse-temurin:21-jre`), `module-info.java` (implies ≥ 9).

**Infer the minimum version from the code** when the build is not in the diff:

| Construct seen in the diff | Minimum Java |
|---|---|
| `var` locals | 10 |
| `switch` expressions (`->`, `yield`) | 14 |
| text blocks `"""` | 15 |
| `record` | 16 |
| `instanceof Type t` pattern | 16 |
| `Stream.toList()` | 16 |
| `sealed` / `non-sealed` / `permits` | 17 |
| `switch` with type patterns, `case null`, `when` guards, record patterns | 21 |
| virtual threads: `Thread.ofVirtual()`, `Executors.newVirtualThreadPerTaskExecutor()` | 21 |
| `SequencedCollection`, `list.getFirst()`, `reversed()` | 21 |
| `ExecutorService` in try-with-resources (`close()`) | 19 (API), 21 (LTS) |
| `_` unnamed variables/patterns | 22 |
| `ScopedValue` without `--enable-preview` | 25 |
| statements before `super(...)` | 25 |
| `import module java.base;`, compact source file with instance `main` | 25 |
| `StructuredTaskScope` | still preview in 25 (fifth preview, JEP 505); requires `--enable-preview` |
| `STR."..."` string templates | preview only in 21–22; **withdrawn** (JEP 465). Flag any use. |

If the diff uses a construct newer than the declared `release`, that is a build break, not a
style point. If the project declares 17 but the code could use 21 features, do not demand them.

**Glance at tooling before flagging anything** so you do not duplicate it (section 5):
- Formatter: `.editorconfig`, `spotless { java { googleJavaFormat() | palantirJavaFormat() | eclipse() } }` in Gradle, `spotless-maven-plugin`, `fmt-maven-plugin`, `google-java-format` in a pre-commit hook. `ratchetFrom` means only changed files are formatted [Spotless].
- Error Prone: `net.ltgt.errorprone` Gradle plugin, `-Xplugin:ErrorProne` / `<compilerArgs>` in Maven, `errorprone.disable(...)`/`check("X", ERROR)`; NullAway appears as `errorprone("com.uber.nullaway:nullaway")` with `option("NullAway:AnnotatedPackages", ...)` or `OnlyNullMarked` [NullAway].
- Checkstyle: `checkstyle.xml`, `config/checkstyle/`, `google_checks.xml`, `sun_checks.xml`.
- SpotBugs: `spotbugs` Gradle plugin, `spotbugs-maven-plugin`, `spotbugs-exclude.xml`, `@SuppressFBWarnings`.
- PMD: `pmd` plugin, `ruleset.xml`. Sonar: `sonar-project.properties`, `sonar.java.source`.
- `-Xlint:all -Werror` in `compilerArgs` / `options.compilerArgs` — if present, the compiler already rejects raw types, unchecked ops, fall-through, `this-escape`, missing `serialVersionUID`, synchronization on value-based classes, lossy compound assignment [javac manual].
- Nullness: JSpecify (`org.jspecify.annotations`), `@NullMarked` in `package-info.java` or `module-info.java`; or JetBrains / Checker Framework / `javax.annotation` annotations.
- Lombok (`lombok.config`): `@Data`, `@Builder`, `@Value` generate `equals`/`hashCode`/builders; NullAway does not check Lombok-generated code [NullAway].

## 3. Checklist

Each item: the rule, the concrete signal to look for in a diff, why it matters, the source.

### 3.1 Modern idioms (expected in new code targeting 17/21)

- **A new immutable data carrier is a `record`, not a class with final fields, getters, `equals`, `hashCode` and `toString`.** Signal: a new class whose body is only fields + constructor + accessors + `Object` methods, or a Lombok `@Value`. Records derive all of that correctly and cannot drift. Not a fit when the type must be mutable, needs a superclass, or must hide its representation from its API [JEP 395].
- **Record validation and defensive copies go in the compact canonical constructor.** Signal: `record R(List<String> xs)` with no compact constructor, or a record whose component is an array or mutable collection. A record is only shallowly immutable; copy (`List.copyOf`) and validate in `R { xs = List.copyOf(xs); }`. Error Prone warns on `ArrayRecordComponent` [JEP 395; EJ3 Item 50; EP ArrayRecordComponent].
- **A closed type hierarchy is `sealed`, and a `switch` over it has no `default`.** Signal: `interface Shape` with a fixed set of implementations and `if/else instanceof` chains; a `switch` on a sealed type that ends in `default -> throw`. Sealed + pattern `switch` makes the compiler fail when a subtype is added; a `default` hides that. JEP 441 says an exhaustive switch without a match-all clause is preferable [JEP 409; JEP 441].
- **Pattern-matching `switch` and `instanceof` replace cast-after-check.** Signal: `if (o instanceof Foo) { Foo f = (Foo) o; ... }` in code targeting ≥ 16; a `switch` on a type with a `default` that casts. Error Prone `PatternMatchingInstanceof` and `StatementSwitchToExpressionSwitch` flag these [JEP 441; EP].
- **`switch` on a reference type decides about `null` explicitly.** Signal: a pattern `switch` on a possibly-null selector with no `case null` (a plain `default` does *not* match `null`; the switch throws NPE). Checkstyle `MissingNullCaseInSwitch` exists for this [JEP 441; CS].
- **Guard order matters: constants, then guarded patterns, then unguarded.** Signal: a `case Foo f` before `case Foo f when ...` — a dominated label is a compile error, so the diff would not compile; but a reordering that silently changes which case wins is a logic change worth reading [JEP 441].
- **`var` only where the type is obvious from the right-hand side.** Signal: `var x = service.compute();` with a non-obvious return type; `var` on a literal that changes type (`var n = 1L`). `var` cannot be used for fields or parameters. Google style permits it; readability is the only test [GJS; EJ3 Item 64 for interface types].
- **Text blocks for multi-line literals; `\s` and trailing-space semantics are traps.** Signal: `"..." + "\n" + "..."` concatenations (EP `StringConcatToTextBlock`); a text block whose closing `"""` placement changes the indentation; `\s` where a plain space was meant (EP `MisleadingEscapedSpace`, ERROR) [EP; javac `-Xlint:text-blocks`].
- **`Stream.toList()` returns an unmodifiable list; `Collectors.toList()` makes no promise either way.** Signal: `.toList()` followed by `.add(...)` (throws `UnsupportedOperationException`); `collect(Collectors.toList())` where the result is later treated as immutable. Use `toCollection(ArrayList::new)` when mutability is wanted [JD21 Stream].
- **Sequenced collections: `getFirst()`/`getLast()`/`reversed()` on JDK 21.** Signal: `list.get(0)`, `list.get(list.size() - 1)`, `new LinkedList<>()` only for `addFirst`, `Collections.reverse(copy)`. Note `SortedSet`/`SortedMap` throw on `addFirst`/`putLast` [JEP 431].
- **`Optional` is a return type, never a field, parameter, or collection element.** Signal: `private Optional<X> field`, `void f(Optional<X> p)`, `List<Optional<X>>`, `Optional<X> o = null`, `Optional.of(x).get()`. The Javadoc API note: "primarily intended for use as a method return type"; "a variable whose type is Optional should never itself be null". Also: `isPresent()` then `get()` instead of `map/orElse/orElseThrow`; `Optional` of a primitive instead of `OptionalInt`; `Optional` of a collection instead of an empty collection. Error Prone `NullableOptional`, `OptionalEquality`, `OptionalNotPresent`, `OptionalMapToOptional` [JD21 Optional; EJ3 Item 55].
- **Lambdas over anonymous classes, method references where clearer, streams where they read better than loops.** Signal: `new Runnable() { public void run() ... }`; a lambda longer than a few lines with side effects; a stream that mutates captured state (`forEach(list::add)`), or `peek` used for logic (may be skipped by `count()`). Streams need non-interfering, stateless behavioural parameters [EJ3 Items 42–46; JD21 Stream].
- **Parallel streams and `forEach` ordering.** Signal: `.parallel()`/`parallelStream()` on I/O work, on a `Collections.synchronizedList` accumulator, or inside a servlet request; `parallelStream().forEach(...)` where order matters (`forEachOrdered`). The common pool is shared with `CompletableFuture` [EJ3 Item 48; JD21 Stream].
- **Features that do not belong in a library or service.** Signal: compact source files / instance `main` (JDK 25) in production modules — intended for beginners, scripts and prototypes [JEP 512]; `--enable-preview` in a library's compiler args; `STR."..."` string templates (withdrawn) [JEP 465].

### 3.2 Types, nulls and immutability

- **Minimise mutability: classes final, fields `private final`, no setters unless required.** Signal: a new class with non-final fields and setters that is never mutated after construction; a public non-final class with no documented extension points. Immutable objects are inherently thread-safe and can be shared freely. Checkstyle `FinalClass`, `DesignForExtension`, `VisibilityModifier` [EJ3 Items 17, 19; SCG MUTABLE-1].
- **Public static fields are `final` and hold deeply immutable values.** Signal: `public static` non-final; `public static final` array, `ArrayList`, `HashMap`, `Date`, `StringBuilder`. A `public static final String[]` is still mutable. Use `List.of`, `Map.of`, `Set.of`, or an unmodifiable view. SpotBugs `MS_SHOULD_BE_FINAL`, `MS_MUTABLE_ARRAY`, `MS_MUTABLE_COLLECTION`; EP `MutablePublicArray` [SCG MUTABLE-9, MUTABLE-10; GJS constants definition].
- **Mutable statics are shared global state; treat private statics as public.** Signal: `private static Map<...> cache = new HashMap<>()`, `static int counter`, `static List<Listener>`. Mutable statics change behaviour between unrelated code and are a concurrency hazard; mutable objects should never be cached in statics. SpotBugs `ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD`, EP `StaticAssignmentInConstructor` [SCG MUTABLE-11; EJ3 Item 78].
- **Defensive copies on the way in and on the way out.** Signal: a constructor storing a `Date`, array, `List` parameter directly; a getter returning an internal array or collection. Copy *before* validating (TOCTOU). SpotBugs `EI_EXPOSE_REP`, `EI_EXPOSE_REP2` [EJ3 Item 50; SCG MUTABLE-2, MUTABLE-3].
- **Nullness is declared, and declared with type-use annotations in a `@NullMarked` scope.** Signal: `@Nullable` on a method instead of its return type (`@Nullable String f()` is fine; `public @Nullable List<@Nullable String> f()` shows type-use); `javax.annotation.Nullable` mixed with JSpecify; `@Nullable` on a primitive, on a local variable's root type, or on an outer class (EP `NullablePrimitive`, `NullableOnContainingClass`, `MultipleNullnessAnnotations`). With `@NullMarked`, unannotated types are non-null; `<E>` cannot be instantiated with a nullable type unless bounded `<E extends @Nullable Object>`; `@Nullable String[]` means nullable elements, `String @Nullable []` a nullable array [JSpecify user guide].
- **`Objects.requireNonNull` at the top of constructors and public methods, with a message when the parameter name is not obvious.** Signal: a parameter stored without a check and dereferenced later; `requireNonNull` after the first use; `if (x == null) throw new IllegalArgumentException` (an NPE is the conventional exception for a null argument). `requireNonNullElse` replaces `x == null ? d : x` [JD21 Objects; EJ3 Item 49].
- **Return empty collections or arrays, not `null`; never return `null` for `Optional`, `Stream`, or a collection.** Signal: `return null;` in a method whose type is `List<...>`, `Optional<...>`, `Stream<...>`, or an array. SpotBugs `NP_OPTIONAL_RETURN_NULL`, `PZLA_PREFER_ZERO_LENGTH_ARRAYS` [EJ3 Item 54].
- **Prefer primitives to boxed primitives; watch for auto-unboxing NPEs and boxed arithmetic.** Signal: `Long count = map.get(k); count++` (NPE on absent key, unboxing+reboxing per increment); `Integer` loop counters; `Boolean` returned from a method that may return `null`; ternary mixing `int` and `Integer` (`NullTernary`, `ConditionalExpressionNumericPromotion`). SpotBugs `BX_*`, `DM_NUMBER_CTOR`; Sonar S2447 [EJ3 Item 61; EP].
- **Avoid strings where another type fits.** Signal: a `String` holding an enum value, a type name, a composite key (`a + ":" + b`), a duration, or a path; `switch` over string constants that mirror an enum. Use enums, records, `Duration`, `Path` [EJ3 Item 62].
- **Enums over int constants; never persist or compare `ordinal()`.** Signal: `public static final int STATUS_OPEN = 1`; `enum.ordinal()` stored, serialised, or used as an array index (use `EnumMap`); bit flags where `EnumSet` fits; an enum with mutable fields (EP `ImmutableEnumChecker`); `getClass()` on an enum constant with a body (EP `GetClassOnEnum`) [EJ3 Items 34–37; EP `EnumOrdinal`].
- **Local variables: smallest scope, declared where first used, effectively final by default.** Signal: variables declared at the top of a method and assigned far below (CS `VariableDeclarationUsageDistance`); a loop variable reused after the loop; reassigning a parameter (CS `ParameterAssignment`). Error Prone's `Var` check is the opt-in "final by default" style; do not demand `final` on locals where the project does not [EJ3 Item 57; EP Var].
- **`Date`, `Calendar`, `SimpleDateFormat` and `TimeZone.getTimeZone("PST")` are legacy.** Signal: new `java.util.Date`/`Calendar` fields (EP `JavaUtilDate`, `DateChecker`); a `static SimpleDateFormat` (not thread-safe; SpotBugs `STCAL_*`, EP `DateFormatConstant`); three-letter zone IDs (EP `ThreeLetterTimeZoneID`); `TimeZone.getTimeZone(bad)` silently returns GMT (EP `InvalidTimeZoneID`). Use `java.time`: `Instant`, `LocalDate`, `ZonedDateTime`, `DateTimeFormatter` (immutable, thread-safe) [JD21 SimpleDateFormat; EP].
- **Time zone and clock are explicit.** Signal: `LocalDate.now()`, `LocalDateTime.now()`, `ZoneId.systemDefault()` in server code (EP `JavaTimeDefaultTimeZone`); `LocalDateTime` where an `Instant`/`ZonedDateTime` is meant (no zone, cannot be compared across zones); `Duration` added to a `LocalDate` or `Period` (EP `LocalDateTemporalAmount`, `PeriodTimeMath`); `YYYY` (week-year) in a date pattern where `yyyy` was meant (EP `MisusedWeekYear`, ERROR); time read in a static initialiser (EP `TimeInStaticInitializer`). Inject a `Clock` for testability [EP].
- **Locale and charset are explicit.** Signal: `toLowerCase()`/`toUpperCase()` without a `Locale` (Turkish-i; EP `StringCaseLocaleUsage`, SB `DM_CONVERT_CASE`); `String.format("%,d", n)` for machine-readable output without `Locale.ROOT`; `new String(bytes)`, `getBytes()`, `new FileReader(f)`, `new InputStreamReader(in)` without a charset (EP `DefaultCharset`, SB `DM_DEFAULT_ENCODING`); charset by name instead of `StandardCharsets.UTF_8` (EP `StringCharset`) [EP; SB].

### 3.3 Equality, hashing and value semantics

- **`equals` and `hashCode` are overridden together and obey the contract.** Signal: one without the other (EP `EqualsHashCode`, ERROR; SB `HE_EQUALS_USE_HASHCODE`; CS `EqualsHashCode`); `hashCode` over fields `equals` ignores (EP `InconsistentHashCode`); `equals` that is not symmetric across a subclass (`instanceof` on an extensible class versus `getClass()`); `equals(Object)` missing in favour of an overload `equals(Foo)` (EP `NonOverridingEquals`, CS `CovariantEquals`); `equals` that does not handle `null` (SB `NP_EQUALS_SHOULD_HANDLE_NULL_ARGUMENT`); `equals` comparing only hash codes (EP `EqualsUsingHashCode`) [JD21 Object; EJ3 Items 10, 11].
- **A record, enum, or `Objects.equals`/`Objects.hash` beats a hand-written `equals`.** Signal: a 30-line `equals` with a `getClass()` check and field-by-field comparison on a type that could be a record. Hand-written versions drift when a field is added [JEP 395; EJ3 Item 10].
- **`==` on boxed types or strings is a reference comparison.** Signal: `Integer a == Integer b`, `Long x == 0L` where `x` is `Long` (unboxes; fine) versus `Long x == Long y` (reference); `s == "literal"`; `optionalA == optionalB`. JLS guarantees `==` identity for boxed constants only in -128..127 (and `boolean`, `char` ≤ 0x7f); outside that range identity is unspecified. EP `BoxedPrimitiveEquality` (ERROR), `ReferenceEquality`, `OptionalEquality`; SB `ES_COMPARING_STRINGS_WITH_EQ`, `RC_REF_COMPARISON`; CS `StringLiteralEquality` [JLS §5.1.7; EP; SB].
- **Arrays: `equals`, `hashCode`, `toString` are identity-based.** Signal: `a.equals(b)` on arrays, `Objects.hash(array)`, `array.toString()` in a log, an array as a `HashMap` key or record component. Use `Arrays.equals`/`deepEquals`, `Arrays.hashCode`, `Arrays.toString`. EP `ArrayEquals`, `ArrayHashCode`, `ArrayToString` (all ERROR) [EP].
- **Value-based classes: never `==`, `synchronized`, or identity-hash them.** Signal: `synchronized (someInteger)`, `synchronized (optional)`, `synchronized (list)` where `list` came from `List.of` (value-based), `IdentityHashMap` keyed by `Integer`. "Synchronization may fail" in a future release; javac `-Xlint:synchronization` warns. EP `LockOnBoxedPrimitive` (ERROR), `IdentityHashMapBoxing`; SB `DL_SYNCHRONIZATION_ON_BOXED_PRIMITIVE`, `DL_SYNCHRONIZATION_ON_SHARED_CONSTANT` [JD21 ValueBased; javac manual].
- **`BigDecimal`: `compareTo` for numeric equality, `String`/`valueOf` for construction, scale and rounding for division.** Signal: `bd.equals(other)` for amounts (`2.0` ≠ `2.00` under `equals`; EP `BigDecimalEquals`); `BigDecimal` in a `TreeSet`/`SortedMap` ("natural ordering is inconsistent with equals"); `new BigDecimal(0.1)` (EP `BigDecimalLiteralDouble`; Javadoc calls the double constructor "somewhat unpredictable"); `a.divide(b)` with no scale or `MathContext` (throws `ArithmeticException` on 1/3) [JD21 BigDecimal].
- **Money and exact quantities are not `float`/`double`.** Signal: `double price`, `float amount`, `Math.round(x * 100) / 100.0`, `==` between floating-point values (SB `FE_FLOATING_POINT_EQUALITY`). Use `BigDecimal` or `long` minor units. Also `float`-precision math where `double` was intended (SB `FL_MATH_USING_FLOAT_PRECISION`) [EJ3 Item 60].
- **`compareTo` is consistent with `equals`, returns sign not difference, and `Comparable<T>` is on the right `T`.** Signal: `return this.x - o.x` (overflow; EP `BadComparable`), `return Integer.MIN_VALUE` (SB `CO_COMPARETO_RESULTS_MIN_VALUE`), `-a.compareTo(b)` to reverse (SB `RV_NEGATING_RESULT_OF_COMPARETO`); comparing `compareTo` results to anything but zero (EP `CompareToZero`); `class A implements Comparable<B>` (EP `ComparableType`). Prefer `Comparator.comparing(...).thenComparing(...)`; use the primitive variants to avoid boxing (EP `BoxingComparator`) [EJ3 Item 14; EP; SB].
- **Mutable objects as `HashMap`/`HashSet` keys, or hashed on mutable fields.** Signal: a `hashCode` over a field that has a setter; a key object mutated after insertion. "The behavior of a map is not specified if the value of an object is changed in a manner that affects equals comparisons while the object is a key in the map." `URL` keys trigger DNS (EP `URLEqualsHashCode`, SB `DMI_COLLECTION_OF_URLS`) [JD21 Map; EP].
- **`toString` is overridden for domain types and never returns `null` or leaks secrets.** Signal: a logged domain object with the default `Foo@1a2b3c` (EP `ObjectToString`); a `toString` including a password, token or PII; `toString` on a `Stream` (EP `StreamToString`) or on a lambda [EJ3 Item 12; EP `ToStringReturnsNull`; SCG CONFIDENTIAL-2].
- **`clone` is avoided; copy constructors or static factories instead.** Signal: `implements Cloneable`, `super.clone()` with a cast, a `clone()` that does not call `super.clone()` (SB `CN_IDIOM_NO_SUPER_CALL`), a non-final class that could be cloned by a subclass. `Object.clone` is a shallow copy with a checked exception and no constructor call. CS `NoClone`, `SuperClone` [JD21 Object; EJ3 Item 13; SCG OBJECT-5].

### 3.4 Collections and generics

- **No raw types; eliminate (do not blanket-suppress) unchecked warnings.** Signal: `List list = new ArrayList();`, `Map<String, List>`, `(List<String>) obj`, `@SuppressWarnings("unchecked")` on a class or on a method that is more than one statement. Suppress on the smallest scope (a local variable declaration) with a comment proving safety. javac `-Xlint:rawtypes,unchecked`; CS `IllegalType`; the `SuppressWarnings` Javadoc: "always use this annotation on the most deeply nested element where it is effective" [EJ3 Items 26, 27; JD21 SuppressWarnings].
- **Bounded wildcards on parameters, never on return types (PECS).** Signal: `void addAll(Collection<E> c)` where `Collection<? extends E>` would accept subtypes; `List<? extends T> f()` as a return type (forces callers to use wildcards); `T extends Object` (redundant; EP `ExtendsObject`); a type parameter used only in the return type (EP `TypeParameterUnusedInFormals`) [EJ3 Item 31].
- **Lists over arrays in APIs; generic varargs with `@SafeVarargs` only when provably safe.** Signal: `public T[] toArray()` of a generic type; `List<String>[]`; a varargs `T...` parameter whose array is stored or returned; `@SafeVarargs` on a method that is not `static`, `final`, or `private` (compile error) or that leaks the array. javac `-Xlint:varargs` [EJ3 Items 28, 32].
- **Unmodifiable collection factories vs unmodifiable views, and their null rules.** Signal: `List.of(a, null)` (NPE), `Map.of(k, v, k, v2)` (`IllegalArgumentException` for duplicate keys), code relying on `Map.of` iteration order (unspecified), `Collections.unmodifiableList(internal)` returned as a "copy" (it is a *view*: changes to the backing list show through), `List.copyOf` where callers expect to see later updates. `List.of`/`copyOf` are also value-based: no `==`, no `synchronized` [JD21 List, Map; SCG MUTABLE-10, MUTABLE-12].
- **A method returns either mutable or immutable collections, not both.** Signal: one branch `return List.of()` and another `return new ArrayList<>(...)` (EP `MixedMutabilityReturnType`); callers that `add` to a returned collection. Document or return `Collection`/`List` consistently; prefer `Collection` to `Stream` as a return type [EJ3 Item 47; EP].
- **Structural modification during iteration.** Signal: `for (X x : list) { if (...) list.remove(x); }` (EP `ModifyCollectionInEnhancedForLoop`), modifying a stream's source inside its pipeline (EP `ModifySourceCollectionInStream`), a `HashMap` iterated while another thread mutates it. `ConcurrentModificationException` is best-effort — "it would be wrong to write a program that depended on this exception for its correctness". Use `removeIf`, `Iterator.remove`, or a copy [JD21 HashMap; EP].
- **`Arrays.asList` and `Collections` singletons are not what they look like.** Signal: `Arrays.asList(primitiveArray)` (a `List<int[]>` of one element; EP `ArraysAsListPrimitiveArray`, ERROR); `Arrays.asList(...).add(x)` (fixed size, throws); `Collections.emptyList().add(...)`; `List.remove(int)` vs `remove(Object)` on a `List<Integer>` (EP `ListRemoveAmbiguous`) [EP].
- **Incompatible-type calls on collections always fail silently.** Signal: `List<Long>.contains(intValue)`, `Map<String, X>.get(uuid)`, `set.remove(wrongType)`, `Collection.toArray(new WrongType[0])`. EP `CollectionIncompatibleType`, `CollectionToArraySafeParameter` (ERROR); SB `GC_UNRELATED_TYPES`, `EC_UNRELATED_TYPES` [EP; SB].
- **`EnumSet`/`EnumMap` for enum-keyed collections; `TreeMap`/`TreeSet` elements must be `Comparable` or have a comparator.** Signal: `HashSet<MyEnum>`, `HashMap<MyEnum, V>`, `new TreeSet<Foo>()` where `Foo` is not `Comparable` (EP `Incomparable`, ERROR). `EnumMap` is faster and iterates in declaration order [EJ3 Items 36, 37; EP].
- **`Map.get`+`put` pairs become `computeIfAbsent`/`merge`; `putIfAbsent` returns the *previous* value.** Signal: `if (!map.containsKey(k)) map.put(k, new ArrayList<>()); map.get(k).add(v)`; `V v = map.putIfAbsent(k, x)` treating `v` as the stored value (SB `RV_RETURN_VALUE_OF_PUTIFABSENT_IGNORED`); `computeIfAbsent(k, Foo::new)` where the constructor takes the key by accident (EP `ComputeIfAbsentAmbiguousReference`, ERROR) [EP; SB].
- **Double-brace initialisation, `Hashtable`, `Vector`, `Stack`, `StringBuffer`, `Enumeration` are obsolete.** Signal: `new HashMap<>() {{ put(...); }}` (an anonymous subclass holding `this`; EP `DoubleBraceInitialization`, ERROR; CS `AvoidDoubleBraceInitialization`); any `java.util.Hashtable`/`Vector` in new code (EP `JdkObsolete`); `Hashtable.contains` (means `containsValue`; EP `HashtableContains`) [EP].
- **Refer to objects by their interfaces.** Signal: `ArrayList<String> xs = new ArrayList<>()` as a field or parameter type; `HashMap<K,V>` in a signature; `LinkedHashMap` where ordering is the point — then say so in the type (`SequencedMap` on 21) [EJ3 Item 64; JEP 431].

### 3.5 Error handling and resources

- **try-with-resources for every `AutoCloseable`, including streams over files, `ExecutorService` (JDK 19+), JDBC objects, `Scanner`, `MockedStatic`.** Signal: `new FileInputStream(...)` assigned to a local and closed in `finally` (or not at all); `Files.lines(path).count()` without `try` (EP `StreamResourceLeak`); `Files.list/walk/find` likewise; an `ExecutorService` created in a method and never shut down (Sonar S2095 fires on it since the type became `AutoCloseable`); `Lock.lock()` not followed immediately by `try` (EP `LockNotBeforeTry`). SB `OS_OPEN_STREAM`, `ODR_OPEN_DATABASE_RESOURCE`, `UL_UNRELEASED_LOCK` [EJ3 Item 9; EP; SCG DOS-2; Sonar community thread on S2095].
- **Never swallow an exception; an empty `catch` needs a comment saying why.** Signal: `catch (Exception e) {}`, `catch (IOException ignored) {}` without a comment, `catch (Exception e) { return null; }`, `catch (...) { log.debug(...) }` on a failure path. Google style: log or rethrow, or explain in a comment; "impossible" cases rethrow as `AssertionError`. EP `EmptyCatch`; CS `EmptyCatchBlock`; SB `DE_MIGHT_IGNORE` [GJS; EJ3 Item 77].
- **Catch the narrowest type; `catch (Throwable)` / `catch (Exception)` / `catch (Error)` are red flags.** Signal: `catch (Throwable t)` outside a top-level dispatcher; `catch (Exception e)` wrapping a block that throws `InterruptedException` (EP `InterruptedExceptionSwallowed`); `catch (Throwable)` in a test (masks `fail()`; EP `TryFailThrowable`, ERROR); `catch (RuntimeException | Error)`. CS `IllegalCatch`; SB `REC_CATCH_EXCEPTION` [EP; CS].
- **`InterruptedException`: restore the interrupt or propagate; never log-and-continue.** Signal: `catch (InterruptedException e) { log.warn(...); }` or `{ throw new RuntimeException(e); }` without `Thread.currentThread().interrupt()`; `Thread.interrupted()` (clears the flag) where `isInterrupted()` or `currentThread().interrupt()` was meant (EP `InterruptedInCatchBlock`). The `ExecutorService` Javadoc's shutdown idiom shows the pattern: `shutdownNow(); Thread.currentThread().interrupt();` [JD21 ExecutorService; EP; JD21 Thread].
- **Checked exceptions for recoverable conditions the caller can act on; unchecked for programming errors; never `throws Exception`.** Signal: a new `extends Exception` type the caller can only log; `throws Exception`/`throws Throwable` on a public method; `catch (SpecificException e) { throw new RuntimeException(e); }` boilerplate in every caller (a sign the checked type was wrong); a checked exception from a lambda forcing wrappers. CS `IllegalThrows`, `ThrowsCount` [EJ3 Items 70, 71].
- **Throw exceptions appropriate to the abstraction, with the cause chained and failure-capture detail.** Signal: an SQL/HTTP exception escaping a domain method; `throw new MyException("failed")` inside a `catch` without `, e` (lost cause); a message with no values (`"invalid index"` instead of `"index 7 out of 0..5"`); a message that includes a secret, a password or a full file path to a user (SCG CONFIDENTIAL-1). Prefer standard exceptions (`IllegalArgumentException`, `IllegalStateException`, `NullPointerException`, `IndexOutOfBoundsException`, `UnsupportedOperationException`) to new ones [EJ3 Items 72, 73, 75; SCG CONFIDENTIAL-1].
- **Failure atomicity: validate before mutating.** Signal: a method that updates fields, then throws on a later check (leaving a half-updated object); a collection cleared before the new contents are known to be valid; a `switch` that mutates in early cases and throws in `default` [EJ3 Item 76].
- **Exceptions are not control flow and are not created without being thrown.** Signal: `try { arr[i] } catch (ArrayIndexOutOfBoundsException)` as a loop exit; `new IllegalStateException("...")` with no `throw` (EP `DeadException`, ERROR; SB `RV_EXCEPTION_NOT_THROWN`); `throw null` (EP `ThrowNull`) [EJ3 Item 69; EP].
- **`finally` never `return`s or throws; it does not hide the real failure.** Signal: `return` inside `finally` (discards the exception; EP `Finally`, javac `-Xlint:finally`); `finally { resource.close(); }` where `close()` can throw and mask the original (use try-with-resources, which records suppressed exceptions) [EP; EJ3 Item 9].
- **Logging an exception passes the throwable as the *last* argument and uses placeholders.** Signal: `log.error("Failed: " + e.getMessage())` (loses the stack trace), `log.error("Failed {}", e.getMessage(), e)` (fine), `log.error("Failed " + id, e)` (concatenation; parameterised form avoids building the string when the level is off), `log.info("x={}", e, id)` (exception not last → treated as a plain argument and *not* printed), `e.printStackTrace()` (EP `CatchAndPrintStackTrace`), `System.out.println` in production code. SLF4J ≥ 1.6.0 treats a trailing throwable as the exception [SLF4J FAQ; EP].
- **Return values that signal failure are checked.** Signal: `file.delete()`, `file.mkdirs()`, `in.read(buf)` (SB `RR_NOT_CHECKED`), `map.put`, `future` results, `BigDecimal.add(...)` or `String.trim()` with the result dropped (EP `ReturnValueIgnored`, `CheckReturnValue`, both ERROR; SB `RV_RETURN_VALUE_IGNORED`). A `Future` ignored hides its exception (EP `FutureReturnValueIgnored`) [EP; SB].
- **`System.exit`, `Runtime.halt`, `finalize`, and `Thread.stop` do not appear in library or service code.** Signal: `System.exit(1)` outside `main` (SB `DM_EXIT`); `protected void finalize()` (deprecated for removal; GJS forbids; CS `NoFinalizer`; EP `Finalize`); use `Cleaner` or `AutoCloseable` [JD21 Object; GJS; EJ3 Item 8].
- **`assert` is not validation.** Signal: `assert param != null;` guarding a public method (disabled by default at runtime); an assertion with side effects (SB `ASE_ASSERTION_WITH_SIDE_EFFECT`). Use `Objects.requireNonNull`/`IllegalArgumentException` [EJ3 Item 49; SB].

### 3.6 Concurrency

- **Shared mutable state is guarded by one consistent mechanism; "documented thread safety" is the deliverable.** Signal: a field written under `synchronized` and read without (SB `IS2_INCONSISTENT_SYNC`, `UG_SYNC_SET_UNSYNC_GET`); a `@GuardedBy` field accessed without its lock (EP `GuardedBy`, ERROR); a class used from multiple threads with no `@ThreadSafe`/`@Immutable`/"not thread-safe" statement in its Javadoc. An unannotated class is presumed not thread-safe [EJ3 Items 78, 82; jcip annotations; JLS §17.4.5].
- **`volatile` suffices only for a single write-then-publish, not for compound actions.** Signal: `volatile int count; count++` (EP `NonAtomicVolatileUpdate`; SB `VO_VOLATILE_INCREMENT`); `volatile long[]` expecting element visibility (SB `VO_VOLATILE_REFERENCE_TO_ARRAY`); check-then-act on a volatile (`if (ref == null) ref = ...`). Use `AtomicInteger`/`LongAdder`, a lock, or `AtomicReference.compareAndSet` (not on boxed types: EP `PrimitiveAtomicReference`). Non-volatile `long`/`double` writes may tear [JLS §17.7; EP; SB].
- **Double-checked locking needs `volatile`; static lazy init uses the holder idiom.** Signal: `if (inst == null) { synchronized (lock) { if (inst == null) inst = new X(); } }` with a non-volatile `inst` (EP `DoubleCheckedLocking`; SB `DC_DOUBLECHECK`); `static X inst; static X get() { if (inst == null) inst = new X(); return inst; }` (SB `LI_LAZY_INIT_STATIC`). Lazy initialisation is rarely worth it [EJ3 Item 83; EP DoubleCheckedLocking].
- **`this` does not escape the constructor.** Signal: registering a listener, starting a thread (SB `SC_START_IN_CTOR`), publishing to a static or a collection, or calling an overridable method (SB `MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR`; SCG OBJECT-4) inside a constructor; an inner-class instance created in a constructor (captures `this`). The JLS final-field guarantee holds only if the object is not visible to another thread before the constructor finishes. javac `-Xlint:this-escape` (JDK 21) warns; JDK 25's flexible constructor bodies let fields be set before `super()` [JLS §17.5; javac manual; JEP 513; SCG OBJECT-3, OBJECT-4].
- **Lock on a private final object, never on `this` of a public class, a string, a boxed value, a `Lock`, or a non-final field.** Signal: `synchronized ("lock")` (interned; SB `DL_SYNCHRONIZATION_ON_SHARED_CONSTANT`), `synchronized (someInteger)` (EP `LockOnBoxedPrimitive`, ERROR), `synchronized (reentrantLock)` (SB `JLM_JSR166_LOCK_MONITORENTER`), `synchronized (field)` where `field` is reassigned (EP `SynchronizeOnNonFinalField`; SB `ML_SYNC_ON_UPDATED_FIELD`), an instance lock guarding static data (EP `StaticGuardedByInstance`; SB `SSD_*`), `synchronized (getClass())` in a non-final class (SB `WL_USING_GETCLASS_RATHER_THAN_CLASS_LITERAL`). SpotBugs' `USO_*` family flags any lock reachable from outside [EP; SB; EJ3 Item 79].
- **No alien calls, I/O, or `Thread.sleep` while holding a lock; keep lock scopes small.** Signal: a `synchronized` method that calls a listener/callback, logs through a remote appender, or performs blocking I/O; `Thread.sleep` under a lock (SB `SWL_SLEEP_WITH_LOCK_HELD`); `wait()` with two locks held (SB `TLW_TWO_LOCK_WAIT`). JEP 491 restates JCIP: "keep lock scopes small and avoid blocking I/O while holding a lock" [EJ3 Item 79; JEP 491; SB].
- **`ConcurrentHashMap`: compound actions through `compute*`/`merge`, keep the function short, never modify the map inside it, no nulls.** Signal: `if (!chm.containsKey(k)) chm.put(k, v)` (race; use `putIfAbsent`/`computeIfAbsent`); `chm.get(k)` then `chm.put(k, f(v))` (use `merge`/`compute`); a `computeIfAbsent` whose function calls another `compute*` on the same map (may deadlock — "must not attempt to update any other mappings"); `chm.put(k, null)` (NPE); `Collections.synchronizedMap` iterated without holding the map's lock. Size/iteration is weakly consistent [JD21 ConcurrentHashMap, HashMap; SB `AT_OPERATION_SEQUENCE_ON_CONCURRENT_ABSTRACTION`].
- **Executors are owned, bounded, shut down, and never leaked; prefer tasks to threads.** Signal: `new Thread(...).start()` in request code (EJ3 Item 80); `Executors.newCachedThreadPool()` on a request path (unbounded threads); `new ThreadPoolExecutor(core, max, ..., new LinkedBlockingQueue<>())` where `max` never applies (EP `ErroneousThreadPoolConstructorChecker`); an executor field with no `shutdown`/`close` in a lifecycle hook; `shutdown()` with no `awaitTermination` when the caller needs completion; `Thread.run()` instead of `start()` (SB `RU_INVOKE_RUN`); `thread.join()` not in a loop (EP `ThreadJoinLoop`). The Javadoc idiom: `shutdown()` → `awaitTermination` → `shutdownNow()` → re-interrupt; on JDK 21 `try (var e = Executors...)` [JD21 ExecutorService; EP; SB].
- **`CompletableFuture`: supply an executor for blocking work, never drop a future, and understand which thread runs a dependent.** Signal: `supplyAsync(() -> jdbc.query(...))` with no executor (blocks the common `ForkJoinPool`, shared with parallel streams); `future.join()` or `get()` on a request thread (undoes the async); `thenApply` after an already-completed stage (runs synchronously in the caller — fine, but not where the author assumed another thread); `future.thenAccept(...)` result discarded (EP `FutureReturnValueIgnored`); a chain with no `exceptionally`/`handle`/`whenComplete` and nobody joining it (exception never observed; `CompletionException` wraps the cause; `get()` rethrows as `ExecutionException`); `thenApply(f)` where `f` returns a future (nested future; use `thenCompose`); no `orTimeout`/`completeOnTimeout` on a remote call; `obtrudeValue` anywhere [JD21 CompletableFuture; EP].
- **`wait`/`notify` are legacy; when present they are in a loop under the monitor and use `notifyAll`.** Signal: `wait()` not in a `while` (EP `WaitNotInLoop`; SB `WA_NOT_IN_LOOP`), `notify()` (SB `NO_NOTIFY_NOT_NOTIFYALL`), `Condition.await()` not in a loop (SB `WA_AWAIT_NOT_IN_LOOP`), `Object.wait` called on a `Condition` (SB `DM_MONITOR_WAIT_ON_CONDITION`). Prefer `CountDownLatch`, `Semaphore`, `BlockingQueue`, `CompletableFuture`, `Phaser` [EJ3 Item 81; JD21 Object; EP; SB].
- **`ThreadLocal` is `static final`, has a bounded lifetime, and is `remove()`d in pooled threads.** Signal: a non-static `ThreadLocal` field (EP `ThreadLocalUsage`); `ThreadLocal.set` in a servlet/worker without `remove` in `finally` (the value lives as long as the pooled thread); `ThreadLocal` used as a hidden parameter in request code (candidate for `ScopedValue` on 25); `InheritableThreadLocal` with executors (not inherited through a pool). Each virtual thread has its own copy — expensive objects in thread-locals multiply by the number of virtual threads [JD21 ThreadLocal; JEP 444; JEP 506; EP].
- **Virtual threads (JDK 21+): one per task, never pooled, not for CPU-bound work, no `synchronized` around blocking I/O before JDK 24.** Signal: `Executors.newFixedThreadPool(n, Thread.ofVirtual().factory())` (pooling virtual threads defeats the point; use a `Semaphore` to bound a resource); CPU-bound loops on virtual threads (no gain); a `synchronized` block or method containing JDBC/HTTP/`socket.read` on a 21 runtime (pins the carrier; `jdk.VirtualThreadPinned` JFR event, `-Djdk.tracePinnedThreads=full`) — JEP 444's fix is `ReentrantLock`; on JDK 24+ JEP 491 removes the pinning and that migration is unnecessary; native frames and class initialisers still pin. Virtual threads are daemon, unnamed by default (set a name for diagnostics), fixed priority [JEP 444; JEP 491; JD21 Thread].
- **Structured concurrency (preview through JDK 25) and scoped values (final in 25).** Signal: `StructuredTaskScope` in a non-preview build (needs `--enable-preview`; API shape changed between previews: `open()`, `Joiner`); a fork-join of subtasks hand-rolled with `ExecutorService` + `invokeAll` where one failure should cancel siblings (thread leak, delayed failure — the problems JEP 505 names); `ScopedValue.where(K, v).run(...)` read outside the binding (throws); `ScopedValue.orElse(null)` (rejected in the final API); migrating a two-way `ThreadLocal` (callee `set`s for a caller) to `ScopedValue` (immutable; wrong fit) [JEP 505; JEP 506].
- **Static mutable state and test interference.** Signal: a `static` cache, counter, `Random`, or `SimpleDateFormat` touched from request threads; a `static @Mock` (EP `StaticMockMember`); `static` `Throwable` fields (EP `StaticAssignmentOfThrowable`). Static state is shared by every thread and every test in the JVM [EJ3 Item 78; EP; SCG MUTABLE-11].
- **Thread safety of `Random` and formatters.** Signal: `new Random()` per call for a single value (SB `DMI_RANDOM_USED_ONLY_ONCE`); a shared `Random` on a hot path (contention; use `ThreadLocalRandom`); `random.nextInt() % n` (may be negative; EP `RandomModInteger`); `(int) random.nextDouble()` (always 0; EP `RandomCast`); a shared `SimpleDateFormat`/`DecimalFormat`/`MessageFormat` (not synchronized) [JD21 Random, SimpleDateFormat; EP; SB].

### 3.7 Security

- **No dynamic SQL; parameters go through `PreparedStatement`/`CallableStatement` placeholders.** Signal: `"SELECT ... WHERE id = " + id`, `String.format` into `createStatement().execute(...)`, `prepareStatement(sql + userInput)`, string-built JPQL/HQL, `@Query` with concatenation. SB `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE`, `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING` [SCG INJECT-2; SB].
- **Untrusted data is encoded for the sink: HTML/XML/JSON/JS output, headers, cookies, logs.** Signal: `out.println(request.getParameter(...))` (SB `XSS_REQUEST_PARAMETER_TO_SERVLET_WRITER`), `response.setHeader(name, userValue)` (SB `HRS_REQUEST_PARAMETER_TO_HTTP_HEADER`), hand-built XML by concatenation, templating with escaping disabled. Prefer an allow-list of safe characters and a library encoder [SCG INJECT-1, INJECT-3; SB].
- **No untrusted data on a command line; no path built from a request without canonicalising and checking the prefix.** Signal: `new ProcessBuilder("sh", "-c", cmd + userArg)`, `Runtime.exec(...)` with user input; `new File(baseDir, request.getParameter("name"))` without `toRealPath()`/`normalize()` and a `startsWith(base)` check (SB `PT_RELATIVE_PATH_TRAVERSAL`, `PT_ABSOLUTE_PATH_TRAVERSAL`); zip entry names used as paths (zip slip) [SCG INJECT-4, INPUT-1; SB].
- **XML parsers are hardened: no external entities, no DTDs, no XInclude.** Signal: `DocumentBuilderFactory.newInstance()` / `SAXParserFactory` / `XMLInputFactory` / `TransformerFactory` / `SchemaFactory` / `Unmarshaller` without `setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)`, `disallow-doctype-decl`, `setExpandEntityReferences(false)`, `ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_SCHEMA` set to `""`; `javax.xml.xpath` on untrusted documents [SCG INJECT-5].
- **Java deserialization of untrusted data is avoided; if unavoidable it is filtered with an allow-list.** Signal: `new ObjectInputStream(request.getInputStream()).readObject()`; `readObject` with no `ObjectInputFilter`; a filter that is a reject-list; JNDI lookups with user-controlled names (EP `BanJNDI`, ERROR); `XMLDecoder`, `XStream`, Jackson default typing (`enableDefaultTyping`, `@JsonTypeInfo(use = CLASS)`) on untrusted input; `Class.forName(userString)`. SCG section 8: "Deserialization of untrusted data is inherently dangerous and should be avoided." Filters: `ObjectInputStream.setObjectInputFilter`, `-Djdk.serialFilter=maxdepth=..;maxrefs=..;maxbytes=..;maxarray=..;com.example.*;!*`, filter factories (JDK 17) [SCG SERIAL-1, SERIAL-3, SERIAL-6; JEP 290; JDK 21 serialization filtering guide; EP].
- **`SecureRandom` for anything security-relevant; `MessageDigest`/`Cipher` algorithms and modes are current.** Signal: `new Random()` or `Math.random()` for tokens, session IDs, salts, nonces, OTPs, filenames in shared dirs; `MD5`/`SHA-1` for passwords or signatures; `"AES"` or `"AES/ECB/..."` (ECB), `"DES"`, `"RSA"` with no OAEP; a hard-coded or reused IV; `SSLContext.getInstance("SSL"|"TLSv1")`; a `TrustManager` that accepts everything or a `HostnameVerifier` returning `true`. The `Random` Javadoc: "Instances of java.util.Random are not cryptographically secure" [JD21 Random; SCG fundamentals].
- **Secrets are not in source, logs, exceptions, or `toString`; sensitive memory is cleared.** Signal: string literals that look like passwords/keys/tokens (SB `DMI_CONSTANT_DB_PASSWORD`, `DMI_EMPTY_DB_PASSWORD`); `log.info("user={} password={}", ...)`; a password held in a `String` where `char[]` and `Arrays.fill` are feasible; stack traces returned in HTTP bodies; `FileNotFoundException` messages (reveal paths and existence) propagated to clients [SCG CONFIDENTIAL-1, CONFIDENTIAL-2, CONFIDENTIAL-3; SB].
- **Input validation happens after defensive copying, at the trust boundary, with limits that cannot overflow.** Signal: `size * count` or `offset + length` checked after the multiplication (overflow; use `Math.multiplyExact`/`addExact`); a resource limit on a user-controlled `int` without a range check; `Integer.parseInt(userInput)` with no exception handling; decompression without a size bound (zip bomb); user-controlled keys in a `HashMap` on a hot path (hash-collision DoS; SCG DOS-5) [SCG INPUT-1, DOS-1, DOS-3, DOS-5; JD21 Math].
- **Accessibility is minimal: no public mutable statics, no public constructors on sensitive classes, no protected statics, sealed or final where extension is not designed.** Signal: `public` on a class or member used only inside its package (EP `EffectivelyPrivate`; CS `VisibilityModifier`); `protected static`; a security-sensitive class that is non-final, serializable, or cloneable; a `ClassLoader` exposed via a getter or the thread context (SCG EXTEND-4) [SCG EXTEND-1, EXTEND-5, OBJECT-1, OBJECT-5; EJ3 Item 15].
- **Reflection, `setAccessible`, `sun.misc.Unsafe`, and dynamic class loading are not in new code without a stated reason.** Signal: `Class.forName`, `Method.invoke`, `field.setAccessible(true)`, `--add-opens` additions in the build, `Unsafe` (SB `UNS_UNSAFE_CALL`), `Class.newInstance()` (EP `ClassNewInstance`; bypasses checked exceptions), `URLClassLoader` with user-controlled URLs [EJ3 Item 65; SB; EP].
- **The `SecurityManager` is gone.** Signal: `System.setSecurityManager`, `AccessController.doPrivileged`, `java.security.Policy` in new code. Deprecated for removal in 17 (JEP 411) and permanently disabled in JDK 24 per SCG section 9; `doPrivileged` is a no-op with cost [SCG section 9; JDK 17 JEP list].
- **Hash-collision and regex DoS.** Signal: `Pattern.compile` of a user-supplied regex; a regex with nested quantifiers (`(a+)+`) applied to untrusted input; `Pattern.compile` inside a loop with a constant (SB `IIL_PATTERN_COMPILE_IN_LOOP`); `String.split(userRegex)`; `String.split(".")` (regex; EP `StringSplitter`, `BareDotMetacharacter`) [SCG DOS-1; EP; SB].

### 3.8 Serialization and interop

- **Prefer alternatives to Java serialization; `implements Serializable` on a new class is a design decision.** Signal: `implements Serializable` added to a domain class "just in case", to a lambda's functional interface, or inherited unknowingly via a superclass/interface. Serialization "effectively adds a hidden public constructor" and exposes every field. Records can be serialized but cannot define `writeObject`/`readObject` [EJ3 Items 85, 86; SCG SERIAL-1; JEP 395].
- **When a class is `Serializable`: `serialVersionUID`, `transient` for non-serializable or sensitive fields, defensive `readObject`, `readResolve` for singletons, serialization proxy for invariants.** Signal: no `private static final long serialVersionUID` (javac `-Xlint:serial`; SB `SE_NO_SERIALVERSIONID`); a non-transient field of a non-serializable type (SB `SE_BAD_FIELD`); a `transient` field never restored (SB `SE_TRANSIENT_FIELD_NOT_RESTORED`); `readObject` that calls `defaultReadObject()` and never re-validates invariants or copies mutable components (SCG SERIAL-3, SERIAL-4); a serializable singleton without `readResolve` (or not an enum); `readObject`/`clone` calling overridable methods (SB `MC_OVERRIDABLE_METHOD_CALL_IN_READ_OBJECT`); a `private readResolve` in a class with subclasses (SB `SE_PRIVATE_READ_RESOLVE_NOT_INHERITED`) [EJ3 Items 87–90; SCG SERIAL-2..4; SB].
- **JSON/protobuf/other wire formats: the contract is explicit and backward compatible.** Signal: a renamed field or enum constant on a type that is persisted or sent over the wire; a Jackson type without `@JsonCreator`/record support relying on a default constructor and setters; `FAIL_ON_UNKNOWN_PROPERTIES` left default-true on an input type that must tolerate additions; `Enum.valueOf` on wire input with no fallback; proto `ordinal()`/`getNumber()` confusion (EP `ProtocolBufferOrdinal`, ERROR); a proto `build()` result dropped (EP `ProtoBuilderReturnValueIgnored`); a lite proto `toString` in logs (EP `LiteProtoToString`) [EP].
- **Native and foreign code is wrapped.** Signal: a `public native` method (declare `private` and expose through a Java wrapper that validates); FFM (`java.lang.foreign`) calls with lengths not validated; native frames on virtual threads (still pin). javac `-Xlint:...` does not cover this; SCG INPUT-3 does [SCG INPUT-3; JEP 491; EJ3 Item 66].
- **Charset and endianness are explicit at every byte boundary.** Signal: `String.getBytes()`, `new String(byte[])`, `ByteBuffer` without `order(...)` for a protocol with a defined endianness, `DataOutputStream.writeUTF` for interop (modified UTF-8), `ByteBuffer.array()` on a buffer that may be read-only or have an offset (EP `ByteBufferBackingArray`) [EP DefaultCharset; EP].
- **Module boundaries (`module-info.java`) are honoured.** Signal: `exports com.example.internal`; `opens` added to satisfy a reflective framework without comment; `requires transitive` on an implementation dependency; `requires` of an automatic module (javac `-Xlint:requires-automatic`); a public type in an exported package with no explicit constructor (`-Xlint:missing-explicit-ctor`); reflective access across modules relying on `--add-opens` in run scripts [javac manual; SCG EXTEND-2].

### 3.9 API design and encapsulation

- **Minimise accessibility; prefer package-private; make classes final unless designed for extension.** Signal: `public class` for a type used only in its package; `public` helpers only tests use (make them package-private and put the test in the same package); a `protected` member in a `final` class (EP `ProtectedMembersInFinalClass`); a non-final class with no documented self-use pattern of its overridable methods. CS `FinalClass`, `DesignForExtension` [EJ3 Items 15, 19; SCG EXTEND-1, EXTEND-5].
- **Composition over inheritance; interfaces over abstract classes; interfaces define types, not constants.** Signal: `extends HashMap<...>` or `extends ArrayList<...>` to add behaviour (self-use fragility); an abstract base class that exists only to share helper methods; an interface containing only `static final` constants (CS `InterfaceIsType`); a utility class without a private constructor (CS `HideUtilityClassConstructor`). Prefer a wrapper (forwarding class) or default methods [EJ3 Items 18, 20, 22, 4].
- **Builder for ≥ 4 constructor parameters or several optional ones; static factories over constructors when naming helps.** Signal: telescoping constructors `Foo(a)`, `Foo(a, b)`, `Foo(a, b, c)`; a constructor with several same-typed parameters (`(String, String, String)` — EP `ArgumentSelectionDefectChecker` warns on swapped arguments); a public constructor on a type that caches or returns subtypes. Records + a builder (or withers) for value types [EJ3 Items 1, 2, 51].
- **Overloads do not differ only in boxed/primitive or in generic parameter; varargs used carefully.** Signal: `f(int)` and `f(Integer)`, `f(List<A>)` and `f(List<B>)` (erasure), `remove(int)` vs `remove(Object)` traps (EP `ListRemoveAmbiguous`); overloads with the same arity and types compatible by autoboxing; a varargs method with no non-varargs overload for the common small cases; `null` passed to a varargs parameter (EP `NullNeedsCastForVarargs`). Google style: overloads appear together [EJ3 Items 52, 53; GJS].
- **Immutable collections are returned by type and by fact; internal arrays are copied.** Signal: `List<X> getItems() { return items; }` returning the live internal list; a getter returning `Collections.unmodifiableList(items)` labelled as a copy; `public X[] values()` returning a field. Use `List.copyOf` (snapshot, rejects nulls) when nulls are impossible, else `unmodifiableList(new ArrayList<>(items))`. SB `EI_EXPOSE_REP` [SCG MUTABLE-2, MUTABLE-12; EJ3 Item 50].
- **Exceptions are part of the API: documented with `@throws`, standard types preferred, checked ones justified.** Signal: a public method throwing an undocumented unchecked exception that callers must handle; `@throws` for an exception not thrown (EP `InvalidThrows`); a new exception type that adds no information; `throws` of implementation exceptions (`SQLException`, `IOException`) from a domain interface [EJ3 Items 72–74; GJS Javadoc].
- **Nullability is part of the API: `@Nullable` on returns and parameters that may be null; otherwise non-null by `@NullMarked` default.** Signal: a public method returning `null` with no `@Nullable`; a parameter nullable by behaviour but unannotated; `Optional` as a parameter to express optionality (use an overload or `@Nullable`). With NullAway/JSpecify, annotations are enforced; without, they are documentation the reviewer must check [JSpecify; NullAway; EJ3 Item 55].
- **`Object` methods are overridden with `@Override`, and consistently.** Signal: `public boolean equals(Foo other)` without `@Override` (an overload, not an override; EP `NonOverridingEquals`), `hashcode()`/`tostring()` misspellings (EP `MissingOverride` catches the real override case), `@Override` on a record accessor (no effect; EP `RecordComponentOverride`). Google style: always `@Override` when legal [EJ3 Item 40; GJS].
- **Javadoc on every public type and member that is not self-explanatory, with a summary fragment, `@param`/`@return`/`@throws` in order and never empty.** Signal: a new public API with no Javadoc; a first sentence that is a full sentence or starts "This method returns..." (summary fragment should be a noun or verb phrase; `{@return ...}` is allowed); `@param` for a parameter that does not exist (EP `InvalidParam`); `/**` on a non-Javadoc comment (EP `NotJavadoc`); `@inheritDoc` misuse; Javadoc with empty block tags (EP `EmptyBlockTag`) [GJS Javadoc; EJ3 Item 56].
- **Naming follows the conventions a formatter cannot fix.** Signal: `XMLHTTPRequest` (acronyms as words: `XmlHttpRequest`), `m_field`/`kConstant` prefixes, `UPPER_SNAKE` on a mutable `static final` (a `static final List` that is mutated is not a constant), single-letter parameter names on public methods, `getFoo()` for a non-property computation, boolean methods not reading as predicates, a class named like `java.lang` types (`String`, `Object`; EP `AvoidCommonTypeNames`). CS naming checks; Google style's camel-case algorithm [GJS naming; EJ3 Item 68].
- **Static members are qualified by class, not instance; one top-level class per file; no wildcard imports; `L` suffix on longs.** Signal: `instance.staticMethod()` (javac `-Xlint:static`, GJS), two top-level classes in one file (CS `OneTopLevelClass`, EJ3 Item 25), `import java.util.*;` (CS `AvoidStarImport`), `long x = 3000000000;` (compile error) or `1l` (looks like `11`) [GJS; Oracle Code Conventions 10.2; CS].
- **Deprecation is complete.** Signal: `@Deprecated` without an `@deprecated` Javadoc tag saying what to use instead (CS `MissingDeprecated`); `@deprecated` tag without the annotation (javac `-Xlint:dep-ann`); `@Deprecated(forRemoval = true)` with no `since`; `@SuppressWarnings("deprecated")` (wrong string; EP `SuppressWarningsDeprecated`, ERROR; the name is `"deprecation"`) [javac manual; EP; CS].

### 3.10 Tests

- **JUnit 5 (Jupiter) idioms, not JUnit 4 leftovers.** Signal: `org.junit.Test`, `@Before/@After`, `@BeforeClass`, `@Ignore`, `@RunWith`, `@Rule`, `@Test(expected = ...)`, `assertEquals("message", expected, actual)` (Jupiter puts the message *last*), `junit-vintage-engine` added to a new module (Vintage is deprecated; rule migration support deprecated for removal since 6.0). Mapping: `@BeforeEach/@AfterEach`, `@BeforeAll/@AfterAll`, `@Disabled`, `@Tag`, `@ExtendWith`/`@RegisterExtension`, `assertThrows` [JUnit migrating-from-junit4].
- **Test classes and methods are package-private, return `void`, and are not `private`.** Signal: `public class FooTest`, `public void test...()` (recommended to omit `public`), `private void test...()` (not discovered: silently never runs — ask whether CI counts tests), a test method returning a value, `@BeforeAll` non-static without `@TestInstance(PER_CLASS)` (fails at runtime). EP `JUnit4TestNotRun` catches the JUnit 4 case; Sonar S5786/S5810 the Jupiter one [JUnit test-classes-and-methods, test-instance-lifecycle].
- **Every test asserts something, and the assertion is complete.** Signal: a test with only method calls and no `assert*`/`verify`; `assertThat(x)` with no assertion method after it (AssertJ warns this is a no-op; SpotBugs `RV_RETURN_VALUE_IGNORED_INFERRED`, Sonar S2970); `assertTrue(a.equals(b))` instead of `assertThat(a).isEqualTo(b)`; `as("...")` placed *after* the assertion (ignored); `assertEquals(actual, expected)` swapped (EP `AssertEqualsArgumentOrderChecker`); an `assertThrows` lambda with more than one statement (EP `AssertThrowsMultipleStatements`); assertions on constants (EP `TruthConstantAsserts`) [AssertJ doc; EP].
- **`assertThrows`/`assertThatThrownBy` replaces `try { ...; fail(); } catch`.** Signal: `fail()` after a call expected to throw (EP `MissingFail` for the inverse: expected-exception tests with no `fail`); `catch (Exception e)` in a test asserting on `e.getMessage()` only; `assertThrows(Exception.class, ...)` (too broad; also matches a wrong exception). Check the message or cause when the type is not specific enough [JUnit assertions; EP].
- **Grouped and lazy assertions; preemptive timeouts are suspect.** Signal: a dozen sequential `assertEquals` on one object (use `assertAll` or AssertJ `usingRecursiveComparison`/`extracting`); `assertEquals(a, b, "expensive " + msg)` (use a `Supplier`); `assertTimeoutPreemptively` or `@Timeout(threadMode = SEPARATE_THREAD)` on code using `ThreadLocal` or Spring transactions (runs on another thread; commits may not roll back) [JUnit assertions, timeouts].
- **Parameterized tests for the same test body over several inputs; names that say what is tested.** Signal: `test1`, `test2`, copy-pasted test methods differing in a literal (use `@ParameterizedTest` with `@ValueSource`, `@CsvSource`, `@MethodSource`, `@EnumSource`, `@NullAndEmptySource`); a `@MethodSource` factory that is not `static` in a PER_METHOD class (fails); `@ParameterizedTest` with no source; `junit-jupiter-params` missing from the build. Method names should read as behaviour (`rejectsNegativeAmount`), with `@DisplayName` or `name = "{index}: {arguments}"` where it helps [JUnit parameterized-classes-and-tests; GJS allows underscores in test method names].
- **`@Disabled` carries a reason, ideally an issue reference.** Signal: bare `@Disabled`; `@Disabled("flaky")`; a test body commented out; `@Tag("slow")` with no CI configuration running it. The JUnit team "recommends that developers provide a short explanation"; some teams require issue tracking numbers [JUnit disabling-tests].
- **Tests do not depend on order, time, or each other.** Signal: `@TestMethodOrder(OrderAnnotation.class)` on a unit test (JUnit: "true unit tests typically should not rely on the order"); static mutable state across tests; `LocalDate.now()`/`System.currentTimeMillis()` in assertions (inject a `Clock`, or use `Clock.fixed`); `Thread.sleep` to wait for async work (use `CountDownLatch`, Awaitility, or a `CompletableFuture.get(timeout)`); iteration over a `HashSet`/`Map.of` expecting an order; `Random` without a seed; tests sharing system properties or `Locale` without `@ResourceLock` when parallel execution is enabled [JUnit test-execution-order, parallel-execution; JD21 Map].
- **Mockito: mock collaborators, not values, collections, or types you do not own; strict stubs on.** Signal: `mock(List.class)`, `mock(String.class)`, a mocked record or DTO, a mocked `LocalDate` ("please don't mock the List class. Use a real instance instead"; `@DoNotMock` exists for owned types; EP `DoNotMock`, ERROR); `MockitoExtension` with `@MockitoSettings(strictness = LENIENT)` or `lenient()` sprinkled to silence `UnnecessaryStubbingException` (fix the dead stub instead); `Mockito.mockitoSession()` missing where no extension is used [Mockito.java sections 1, 40, 46, 51, 52; EP].
- **Mockito: `any()` leniency, `verifyNoMoreInteractions`, captors, spies.** Signal: `when(repo.find(any())).thenReturn(x)` where a specific argument would prove the call (and hides a wrong-argument bug); `verifyNoMoreInteractions(mock)` in every test ("overspecified, less maintainable"); `verify(mock).foo()` on a stubbed call ("usually just redundant"); `ArgumentCaptor` used in stubbing rather than verification; `when(spy.get(0))` on a spy (calls the real method; use `doReturn`); `spy` of a class with final methods; `reset(mock)` (a smell); `mockStatic`/`mockConstruction` not in try-with-resources (leaks across tests); deep stubs `RETURNS_DEEP_STUBS` ("every time a mock returns a mock a fairy dies"); mocks configured but never passed to production code (EP `MockNotUsedInProduction`); a method invoked directly on a mock outside `when`/`verify` (EP `DirectInvocationOnMock`); a stub throwing a checked exception the method cannot throw (EP `MockIllegalThrows`) [Mockito.java sections 2, 8, 13, 15, 16, 17, 32, 48, 49; EP].
- **Over-mocking is a design signal.** Signal: a test with more `@Mock` fields than lines of assertion; `@InjectMocks` on a class whose constructor injection silently fell back to field injection (a null collaborator at runtime); mocking the class under test's own methods (partial mock: "I wouldn't use partial mocks for new, test-driven and well-designed code") [Mockito.java sections 16, 23].
- **Testcontainers lifecycle is deliberate.** Signal: `@Container` on an *instance* field (container restarted per test — slow; intended?) vs `static` (shared per class); `@Container` in a `@Nested` class expecting sharing (shared containers must be declared in the top-level class); the singleton pattern (static container started in a static initialiser, never stopped — Ryuk stops it) mixed with `@Testcontainers`; parallel JUnit execution with the Testcontainers extension (documented as unsupported); `withReuse(true)` committed without `testcontainers.reuse.enable` in CI [Testcontainers junit_5, manual_lifecycle_control].
- **Test-only code stays out of `main`.** Signal: `@VisibleForTesting` members made `public`; test fixtures in `src/main`; `junit`/`mockito`/`assertj` in a non-test scope (section 3.11); production classes with a `static void main` used as a smoke test; `assert` statements in production relied on by tests (`-ea` only in surefire) [EJ3 Item 15].

### 3.11 Build and dependencies

- **Compile with `--release`, not `-source`/`-target`, and keep code within the declared release.** Signal: `<maven.compiler.source>17</maven.compiler.source>` without `release`; `sourceCompatibility = 17` with a JDK 21 toolchain and `.toList()`/`getFirst()` in the code (compiles on 21, fails to run on 17 — `--release` would catch it); `maven.compiler.release` as `1.8` (must be `8`); Gradle `java.toolchain.languageVersion` disagreeing with `options.release` [javac manual; maven-compiler-plugin set-compiler-release].
- **Warnings are errors, or at least visible.** Signal: a PR that introduces `-Xlint:none`, removes `-Werror`, or adds `-Xlint:-unchecked`/`-rawtypes`/`-deprecation`; `@SuppressWarnings("all")`; `failOnWarning` set to false; a growing `spotbugs-exclude.xml`/`checkstyle-suppressions.xml`. If `-Xlint:all -Werror` is on, section 5 says what the compiler already checks [javac manual].
- **Maven scopes: `compile` only for what the public API needs; `provided` for container APIs; `runtime` for drivers/impls; `test` for test libs; never `system`.** Signal: `slf4j-simple`/`logback-classic` in `compile` scope of a library (forces a logging backend on consumers); `jakarta.servlet-api` in `compile` instead of `provided`; a JDBC driver in `compile`; `junit`, `mockito`, `assertj`, `testcontainers` without `<scope>test</scope>`; `<scope>system</scope>` with `systemPath` ("not recommended"). Remember transitive scope: a `provided` dependency's dependencies arrive as `provided`; `test`-scoped and `provided` do not propagate [Maven dependency mechanism].
- **Gradle: `implementation` by default, `api` only for types in the module's public signatures, `compileOnly` for annotations/provided APIs, `runtimeOnly` for impls.** Signal: `api(...)` for a JSON library used only internally (leaks onto consumers' compile classpath); `compile`/`runtime` configurations (removed in Gradle 7); `compileOnly` for something needed at runtime (`NoClassDefFoundError`); `testImplementation` deps in `implementation` [Gradle java_library_plugin].
- **Versions are managed once: a BOM (`dependencyManagement` + `import`), a Gradle platform, or a version catalog.** Signal: the same artifact with different versions in sibling modules; a hard-coded version where the catalog has an alias; a Spring/Jackson/Netty/gRPC artifact pinned outside its BOM (mixing versions within a family); `enforcedPlatform` in a *library* ("should generally only be used in applications, not in libraries"); relying on a catalog to *pin* a version (catalog versions are requested, not enforced) [Maven dependency mechanism; Gradle platforms, version_catalogs].
- **Dependency convergence and nearest-wins mediation are understood.** Signal: an upgrade PR that bumps one artifact and lets Maven's "nearest definition" pick an *older* transitive version; `maven-enforcer-plugin` `dependencyConvergence` or `requireUpperBoundDeps` disabled or given a new `<excludes>`; Gradle `resolutionStrategy.force` added to paper over a conflict; a `<exclusion>` without a comment saying why. Check `mvn dependency:tree` / `gradle dependencies` output in the PR description for anything that moved [Maven dependency mechanism; Maven enforcer dependencyConvergence].
- **Declare what you use; do not rely on transitive leakage.** Signal: an `import` of a class from an artifact that is not a direct dependency (`dependency:analyze` reports "used undeclared"); declared dependencies no longer imported ("unused declared"); Gradle `implementation` removed from a dependency that a consumer compiled against transitively [Maven dependency mechanism "Best Practice"].
- **Logging facade in libraries, one backend in the application.** Signal: `slf4j-api` plus a backend in a library's `compile`/`api`; two backends (`logback-classic` + `log4j-slf4j2-impl`) on one runtime classpath; SLF4J 2.x API with a 1.7 binding (the "no providers were found" warning); `System.out` as the logger [SLF4J FAQ].
- **`module-info.java` is consistent with the build.** Signal: a `requires` for an artifact not on the module path; `requires transitive` of an `implementation` dependency; a new `exports` of an `internal` package; `opens` for Jackson/Hibernate/JUnit without a comment; automatic-module names (`-Xlint:requires-automatic`) in a published library [javac manual; SCG EXTEND-2].
- **Reproducibility and hygiene.** Signal: `+`/`latest.release`/`[1.0,)` version ranges; `-SNAPSHOT` dependencies on a release branch; `mavenLocal()` as a repository in CI; `mvnw`/`gradlew` wrapper version changed without saying why; `--add-opens`/`--add-exports` JVM args added to run or test configuration (a module-encapsulation workaround; JEP 403 in JDK 17 strongly encapsulates internals); `-Djava.security.manager` in run scripts [JDK 17 JEP list].
- **Annotation processors and Lombok are declared as processors, not compile deps, and their output is not reviewed as if hand-written.** Signal: `lombok` in `implementation`/`compile` (use `compileOnly` + `annotationProcessor`, or Maven `provided` + processor path); a `@Data` class used as a `HashMap` key or JPA entity (generated `equals`/`hashCode` over mutable fields); `errorprone` or `nullaway` versions in a `compileOnly` configuration instead of `errorprone(...)`. NullAway ignores `@lombok.Generated` code [NullAway; EJ3 Item 11].

## 4. Severity hints

**Blocking** (correctness or security; do not merge):
- Dynamic SQL, command injection, path traversal, XXE, deserialization of untrusted data, broken crypto/TLS, hard-coded secrets, `Random` for security (3.7).
- `equals` without `hashCode`, `==` on boxed/strings/arrays, `BigDecimal.equals` for amounts, `compareTo` by subtraction, `Comparable<Wrong>` (3.3); money in `double`.
- Dropped exceptions: empty catch without comment, `InterruptedException` swallowed, `Future`/`CompletableFuture` dropped, `catch (Throwable)` in production paths, `finally` that returns (3.5).
- Resource leaks: `Files.lines`/streams/JDBC/executors outside try-with-resources (3.5).
- Concurrency defects: unsynchronised shared mutable state, non-volatile DCL, `volatile` compound updates, `this` escaping a constructor, locking on strings/boxed/`Lock` objects, `check-then-put` on `ConcurrentHashMap`, `compute` modifying the same map, blocking under `synchronized` on a JDK 21 virtual-thread path, pooled virtual threads (3.6).
- Code that needs a newer JDK than the build declares, preview APIs or withdrawn string templates in production, `--enable-preview` in a library (3.1, 3.11).
- Mutable `public static` state, returned internal arrays/collections in a security-relevant class (3.2, 3.9).
- Serialization added to a sensitive class, `readObject` without validation (3.8).
- Tests that never run (`private` test methods, `@Test` on the wrong annotation, `@MethodSource` non-static), tests with no assertion, `assertThat(x)` with no assertion (3.10).

**Should fix** (will cost later; fix in this PR or a named follow-up):
- Missing records/sealed/pattern switch where the hierarchy is closed; `default` on a sealed switch; no `case null` where null reaches a pattern switch (3.1).
- `Optional` as field/parameter, `isPresent/get`, `Optional` returning `null` (3.1, 3.2).
- Missing defensive copies, non-final classes without extension design, public where package-private suffices, telescoping constructors (3.2, 3.9).
- Raw types, broad `@SuppressWarnings`, unchecked casts not localised (3.4).
- Checked exceptions callers cannot handle, exceptions without cause or detail, logging with concatenation or `getMessage()` only (3.5).
- `ThreadLocal` non-static or not removed, executors without shutdown, `wait` not in a loop, `CompletableFuture` on the common pool for I/O (3.6).
- `Date`/`Calendar`/`SimpleDateFormat`, implicit time zone/locale/charset (3.2).
- Wrong dependency scope, `api` leakage, versions outside the BOM, `enforcedPlatform` in a library, convergence disabled, `-Werror` removed (3.11).
- Over-mocking, mocked value types, `lenient()` to silence strict stubs, order-dependent or time-dependent tests, `Thread.sleep` in tests, bare `@Disabled` (3.10).
- Missing Javadoc on a new public API, missing `@throws`, nullability undeclared in an annotated codebase (3.9).

**Nit** (mention once, do not block):
- `var` readability, method references vs lambdas, text block vs concatenation, `getFirst()` vs `get(0)`, `toList()` vs `collect(toList())` when mutability is irrelevant (3.1).
- Naming details a formatter cannot fix (acronym casing, test method naming), modifier order, `L` suffix, static qualified by instance (3.9).
- Preference for `EnumMap`/`EnumSet`, interface types in signatures, `Objects.requireNonNull` message wording (3.2, 3.4).
- `@DisplayName`, parameterised-test display names, `assertAll` grouping (3.10).

## 5. Already enforced by tooling

Skip these when the repository's build runs the tool. Confirm the tool actually runs in CI (a plugin
declared but with `skip`/`ignoreFailures`/`failOnViolation=false` enforces nothing).

**google-java-format / Spotless / palantir-java-format**: all whitespace, indentation, line length,
line wrapping, brace placement, blank lines, import ordering and unused-import removal, Javadoc
reflow. The formatter has no configuration knobs for its algorithm. It does **not** enforce naming,
`@Override`, Javadoc presence, modifier order semantics beyond layout, exception handling, or
anything in sections 3.1–3.11 except 3.9's import/whitespace items. Do not comment on formatting
when a formatter runs; if the diff is unformatted, the CI check fails on its own.

**Error Prone (default severities)**: everything listed as ERROR in the bug-pattern catalogue fails
the build: `ArrayEquals/HashCode/ToString`, `BoxedPrimitiveEquality`, `EqualsHashCode`,
`EqualsNull/Reference/WrongThing`, `ComparableType`, `CompareToZero`, `CheckReturnValue`,
`ReturnValueIgnored`, `MustBeClosedChecker`, `DeadException`, `DeadThread`, `GuardedBy`,
`LockOnBoxedPrimitive`, `DoubleBraceInitialization`, `FormatString`, `MisusedWeekYear`,
`InvalidTimeZoneID`, `ZoneIdOfZ`, `RandomCast`, `RandomModInteger`, `StreamToString`,
`TryFailThrowable`, `ThrowNull`, `SelfEquals/Assignment/Comparison`, `UnicodeDirectionalityCharacters`,
`ComputeIfAbsentAmbiguousReference`, `CollectionIncompatibleType`, `Incomparable`,
`ArraysAsListPrimitiveArray`, `MissingSuperCall`, `JUnit4TestNotRun`, `MockitoUsage`, `DoNotMock`,
`DoNotCall`, `BanJNDI`, `DurationFrom/TemporalUnit`, `InvalidZoneId`, `SuppressWarningsDeprecated`,
`InfiniteRecursion`, `IdentityBinaryExpression`, `MathRoundIntLong`, `SubstringOfZero`,
`StringBuilderInitWithChar`, `OptionalEquality`, `ImpossibleNullComparison`. WARNING-level items are
reported but do not fail unless the build sets `-Werror`/`allErrorsAsWarnings=false`; the common ones
(`MissingOverride`, `ReferenceEquality`, `DefaultCharset`, `StringCaseLocaleUsage`, `JavaUtilDate`,
`JavaTimeDefaultTimeZone`, `DoubleCheckedLocking`, `NonAtomicVolatileUpdate`, `FutureReturnValueIgnored`,
`StreamResourceLeak`, `ThreadLocalUsage`, `SynchronizeOnNonFinalField`, `WaitNotInLoop`,
`EmptyCatch`, `CatchAndPrintStackTrace`, `Finally`, `FallThrough`, `MissingCasesInEnumSwitch`,
`InconsistentHashCode`, `EqualsGetClass`, `BigDecimalEquals`, `BigDecimalLiteralDouble`,
`UnusedVariable/Method`, `UnnecessaryLambda`, `ModifyCollectionInEnhancedForLoop`,
`MixedMutabilityReturnType`, `MutablePublicArray`, `ClassCanBeStatic`, `InterruptedInCatchBlock`,
`StaticAssignmentInConstructor`, `ErroneousThreadPoolConstructorChecker`, `AssertEqualsArgumentOrderChecker`,
`MissingFail`, `MockNotUsedInProduction`, `DirectInvocationOnMock`, `PatternMatchingInstanceof`,
`StatementSwitchToExpressionSwitch`, `StringConcatToTextBlock`) still deserve a glance at whether the
PR suppressed them. `InterruptedExceptionSwallowed` and `Var` are **off** by default; do not assume them.

**NullAway (as an Error Prone check)**: dereferences of `@Nullable` values, `@Nullable` passed to
non-null parameters, non-null fields not initialised in constructors, within `AnnotatedPackages` or
`@NullMarked` scope, when set to `ERROR`. It does **not** check code outside the annotated packages,
Lombok-generated code, third-party return values (assumed non-null), or generics unless JSpecify mode
is on; and it is often disabled for tests. Nullability of *API design* (3.9) is still a review item.

**Checkstyle** (depending on the configured checks; `google_checks.xml` is mostly layout):
`MissingOverride` (only with `{@inheritDoc}`), `EqualsHashCode`, `CovariantEquals`,
`StringLiteralEquality`, `EmptyCatchBlock`, `IllegalCatch`, `IllegalThrows`, `NoFinalizer`,
`NoClone`, `SuperClone`, `FinalClass`, `HideUtilityClassConstructor`, `DesignForExtension`,
`VisibilityModifier`, `InterfaceIsType`, `MutableException`, `OneTopLevelClass`, `AvoidStarImport`,
`UnusedImports`, `IllegalImport`, `ImportControl`, `AvoidDoubleBraceInitialization`,
`MissingSwitchDefault`, `MissingNullCaseInSwitch`, `FallThrough`, `DefaultComesLast`, `MagicNumber`,
`ParameterAssignment`, `HiddenField`, `InnerAssignment`, `ModifiedControlVariable`,
`UnusedLocalVariable`, `UnusedPrivateField`, `MissingDeprecated`, `ModifierOrder`,
`RedundantModifier`, all naming checks, `SealedShouldHavePermitsList`, `UnnecessaryPermitsClause`,
`UseEnhancedSwitch`, `WhenShouldBeUsed`, `PatternVariableAssignment`, metrics (`CyclomaticComplexity`,
`NPathComplexity`, `ClassFanOutComplexity`), sizes (`MethodLength`, `ParameterNumber`,
`RecordComponentNumber`). Read `checkstyle.xml` to see which are on.

**SpotBugs** (when run with at least `medium` effort and not `ignoreFailures`): the Bad practice,
Correctness, Multithreaded correctness and Malicious code categories listed in section 3 —
`HE_*`, `EQ_*`, `ES_COMPARING_STRINGS_WITH_EQ`, `NP_*`, `RV_*`, `OS_OPEN_STREAM`,
`ODR_OPEN_DATABASE_RESOURCE`, `SE_*`, `IS2_INCONSISTENT_SYNC`, `DC_DOUBLECHECK`, `LI_LAZY_INIT_STATIC`,
`VO_VOLATILE_INCREMENT`, `SC_START_IN_CTOR`, `WA_NOT_IN_LOOP`, `UW_UNCOND_WAIT`, `DL_SYNCHRONIZATION_ON_*`,
`JLM_JSR166_*`, `SWL_SLEEP_WITH_LOCK_HELD`, `STCAL_*`, `EI_EXPOSE_REP(2)`, `MS_SHOULD_BE_FINAL`,
`MS_MUTABLE_ARRAY/COLLECTION`, `MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR`, `DM_EXIT`,
`DMI_RANDOM_USED_ONLY_ONCE`, `DM_DEFAULT_ENCODING`, `DM_CONVERT_CASE`; Security (`SQL_*`, `XSS_*`,
`HRS_*`, `PT_*_PATH_TRAVERSAL`, `DMI_CONSTANT_DB_PASSWORD`) with the `findsecbugs` plugin going
further. Dodgy-code findings (`REC_CATCH_EXCEPTION`, `DLS_DEAD_LOCAL_STORE`, `BC_UNCONFIRMED_CAST`,
`SF_SWITCH_NO_DEFAULT`) are usually low-confidence and often excluded.

**javac `-Xlint:all -Werror`**: raw types, unchecked operations, deprecation and removal, switch
fall-through, `finally` that does not complete normally, lossy compound assignments, `serial`
(missing `serialVersionUID`), `static` access via instance, `synchronization` on value-based classes,
`this-escape` (JDK 21+), `try` (unused or unclosed resources), `varargs`, `overloads`, `overrides`,
`dep-ann`, `divzero`, `empty`, `text-blocks`, `missing-explicit-ctor`, module warnings. If the build
is clean under this, do not re-raise those categories.

**SonarQube/SonarCloud** (when a quality gate blocks merges): null dereference, unclosed resources
(including `ExecutorService` on JDK 19+), `equals`/`hashCode`, strings compared with `==`,
`InterruptedException` handling, `Optional.get` without presence check, JUnit 5 visibility, missing
assertions and incomplete AssertJ assertions (S2970), hard-coded credentials, SQL/XSS/path
injection, weak crypto, deserialization. The rule site could not be fetched for this document
(section 6); treat Sonar's coverage as overlapping with Error Prone + SpotBugs and check the
project's quality profile rather than assuming a rule.

## 6. Open questions / disagreements

- **`synchronized` on virtual threads.** JEP 444 (JDK 21) tells authors to replace frequently-run
  `synchronized` blocks guarding long I/O with `ReentrantLock`; JEP 491 (JDK 24) removes the pinning
  and says new code should follow JCIP: `synchronized` where practical, `ReentrantLock` for
  fairness/timed/interruptible/read-write needs, and existing migrations need not be reverted. A
  reviewer must know the *runtime* JDK (21 LTS vs 25 LTS) before flagging `synchronized` around I/O.
- **`Optional` as a parameter or field.** The Javadoc says only "primarily intended for use as a
  method return type"; the ban on fields and parameters is community convention (Bloch's Item 55 and
  Error Prone's `NullableOptional`/`NullOptional`), not a specification. Jackson/JPA fields of type
  `Optional` are a common pragmatic violation; treat as should-fix, not blocking.
- **Checked vs unchecked exceptions.** EJ3 Items 70–71 keep a place for checked exceptions; much
  modern practice (Spring, reactive and functional code, lambdas) is unchecked-only. Flag `throws
  Exception` and checked types nobody can recover from; do not demand conversion of a codebase's
  existing convention either way.
- **`final` on locals and parameters.** Google style does not require it; Error Prone's `Var`
  check (off by default) inverts the default; Checkstyle's `FinalLocalVariable` demands it. Follow the
  repository's existing style; never comment on it in a mixed codebase.
- **`default` in enum switches.** Error Prone `MissingCasesInEnumSwitch` wants all constants *or* a
  default; JEP 441 prefers exhaustive enum switch *expressions* without a default so a new constant
  fails to compile; Checkstyle `MissingSwitchDefault` wants a default on statements. For switch
  *expressions* over enums and sealed types, prefer no `default`; for legacy switch *statements* a
  `default` that throws is acceptable.
- **`equals` with `instanceof` vs `getClass()`.** EJ3 Item 10 and Error Prone `EqualsGetClass`
  favour `instanceof` (Liskov); `getClass()` is defensible for non-final classes where subclasses
  add state. Records and final classes make the question moot; prefer those.
- **`Collections.unmodifiableList` vs `List.copyOf`.** `List.copyOf` rejects `null` elements and
  is a snapshot; `unmodifiableList` is a view and allows nulls. Which is "right" depends on whether
  callers must see later changes and whether nulls are legal. The SCG text (MUTABLE-10) explains the
  difference but does not prescribe one.
- **`assertTimeoutPreemptively` and `@Timeout(SEPARATE_THREAD)`** are documented hazards with
  thread-bound state but are sometimes the only way to bound a hung test. Accept with a comment.
- **Testcontainers reuse (`withReuse(true)`)** is still documented as experimental; teams disagree
  whether it belongs in committed code or only in local `~/.testcontainers.properties`.
- **Lombok.** `@Data` on entities and map keys is a known `equals`/`hashCode` hazard, and NullAway
  skips generated code, yet many teams consider Lombok settled. Flag the specific hazard
  (mutable-field hashing, `@EqualsAndHashCode` on entities), not the library.
- **Error Prone's `InterruptedExceptionSwallowed` is off by default** and its page gives no
  re-interrupt recipe; the Javadoc idiom (`shutdownNow(); Thread.currentThread().interrupt();`) and
  Sonar S2142 fill the gap. Teams that rely on Error Prone alone still miss this class of bug.
- **Could not be fetched** (403/404 at research time, so nothing from them is cited): SonarSource's
  rule pages (`rules.sonarsource.com/java/...`) and the `SonarSource/rspec` repository (404 via both
  `gh api` and HTTP); O'Reilly's TOC; two university slide decks summarising JCIP chapter 3 (one
  rejected its certificate, one was an unreadable PDF); javadoc.io's rendering of Mockito 5.24
  (empty response; the Javadoc was read from the Mockito source on GitHub instead). JCIP itself has no
  freely readable primary text; its rules are represented here through the JLS memory-model sections,
  the `jcip.net` annotations, and the Error Prone / SpotBugs patterns that encode them.
- **JDK 25 is the current LTS but most production code targets 17 or 21.** Rules that depend on 25
  (scoped values, flexible constructor bodies, module import declarations, compact source files) are
  included for awareness; do not demand them in a 21 codebase.

## Appendix: Effective Java, 3rd edition — the 90 items

Numbering is the 3rd edition's (the 2nd edition had 78 items and different numbers). Items marked
**(common)** are the ones most often violated in pull requests, per the checklist above.

**Chapter 2: Creating and Destroying Objects**
1. Consider static factory methods instead of constructors
2. Consider a builder when faced with many constructor parameters **(common)**
3. Enforce the singleton property with a private constructor or an enum type
4. Enforce noninstantiability with a private constructor
5. Prefer dependency injection to hardwiring resources
6. Avoid creating unnecessary objects
7. Eliminate obsolete object references
8. Avoid finalizers and cleaners
9. Prefer try-with-resources to try-finally **(common)**

**Chapter 3: Methods Common to All Objects**
10. Obey the general contract when overriding equals **(common)**
11. Always override hashCode when you override equals **(common)**
12. Always override toString
13. Override clone judiciously
14. Consider implementing Comparable **(common)**

**Chapter 4: Classes and Interfaces**
15. Minimize the accessibility of classes and members **(common)**
16. In public classes, use accessor methods, not public fields
17. Minimize mutability **(common)**
18. Favor composition over inheritance **(common)**
19. Design and document for inheritance or else prohibit it
20. Prefer interfaces to abstract classes **(common)**
21. Design interfaces for posterity
22. Use interfaces only to define types
23. Prefer class hierarchies to tagged classes
24. Favor static member classes over nonstatic
25. Limit source files to a single top-level class

**Chapter 5: Generics**
26. Don't use raw types **(common)**
27. Eliminate unchecked warnings **(common)**
28. Prefer lists to arrays
29. Favor generic types
30. Favor generic methods
31. Use bounded wildcards to increase API flexibility **(common)**
32. Combine generics and varargs judiciously
33. Consider typesafe heterogeneous containers

**Chapter 6: Enums and Annotations**
34. Use enums instead of int constants **(common)**
35. Use instance fields instead of ordinals
36. Use EnumSet instead of bit fields **(common)**
37. Use EnumMap instead of ordinal indexing **(common)**
38. Emulate extensible enums with interfaces
39. Prefer annotations to naming patterns
40. Consistently use the Override annotation **(common)**
41. Use marker interfaces to define types

**Chapter 7: Lambdas and Streams**
42. Prefer lambdas to anonymous classes **(common)**
43. Prefer method references to lambdas
44. Favor the use of standard functional interfaces
45. Use streams judiciously **(common)**
46. Prefer side-effect-free functions in streams **(common)**
47. Prefer Collection to Stream as a return type
48. Use caution when making streams parallel

**Chapter 8: Methods**
49. Check parameters for validity **(common)**
50. Make defensive copies when needed **(common)**
51. Design method signatures carefully
52. Use overloading judiciously
53. Use varargs judiciously
54. Return empty collections or arrays, not nulls **(common)**
55. Return optionals judiciously **(common)**
56. Write doc comments for all exposed API elements

**Chapter 9: General Programming**
57. Minimize the scope of local variables
58. Prefer for-each loops to traditional for loops
59. Know and use the libraries
60. Avoid float and double if exact answers are required **(common)**
61. Prefer primitive types to boxed primitives **(common)**
62. Avoid strings where other types are more appropriate
63. Beware the performance of string concatenation
64. Refer to objects by their interfaces
65. Prefer interfaces to reflection
66. Use native methods judiciously
67. Optimize judiciously
68. Adhere to generally accepted naming conventions

**Chapter 10: Exceptions**
69. Use exceptions only for exceptional conditions
70. Use checked exceptions for recoverable conditions and runtime exceptions for programming errors **(common)**
71. Avoid unnecessary use of checked exceptions **(common)**
72. Favor the use of standard exceptions
73. Throw exceptions appropriate to the abstraction **(common)**
74. Document all exceptions thrown by each method
75. Include failure-capture information in detail messages
76. Strive for failure atomicity **(common)**
77. Don't ignore exceptions **(common)**

**Chapter 11: Concurrency**
78. Synchronize access to shared mutable data **(common)**
79. Avoid excessive synchronization **(common)**
80. Prefer executors, tasks, and streams to threads **(common)**
81. Prefer concurrency utilities to wait and notify
82. Document thread safety
83. Use lazy initialization judiciously **(common)**
84. Don't depend on the thread scheduler

**Chapter 12: Serialization**
85. Prefer alternatives to Java serialization **(common)**
86. Implement Serializable with great caution **(common)**
87. Consider using a custom serialized form
88. Write readObject methods defensively **(common)**
89. For instance control, prefer enum types to readResolve
90. Consider serialization proxies instead of serialized instances
