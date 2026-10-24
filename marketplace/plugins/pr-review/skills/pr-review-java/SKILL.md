---
name: pr-review-java
description: The Java rules a code review checks after the general pr-review checklist: injection, deserialization and crypto (PreparedStatement, ObjectInputFilter, SecureRandom), exceptions and resources (swallowed InterruptedException, dropped Futures, try-with-resources), memory-model and concurrency defects (volatile, double-checked locking, this escaping a constructor, ConcurrentHashMap compound actions, virtual-thread pinning), equality and value semantics (equals/hashCode, == on boxed values, BigDecimal, Comparable), language features against the declared --release, JUnit 5 and Mockito hygiene, Maven and Gradle scopes. Use whenever a pull request, diff, branch or file under review contains Java (.java, module-info.java, pom.xml, build.gradle, build.gradle.kts), whether or not pr-review loaded it, and whenever someone asks for feedback on, a check of, or a second opinion about Java code. Not for writing Java or fixing the code under review.
---

# Reviewing Java

Java reviews most often miss what compiles and passes: a `catch (Exception e)` that ate an
`InterruptedException`, a `CompletableFuture` nobody joins, `equals` on a `BigDecimal` amount, `==`
on two `Long`s that were small in the tests, a listener registered in a constructor, a `Files.lines`
never closed, a test method made `private` that silently never runs. Apply the groups in this order:
security, error handling and resources, concurrency, equality, types and nulls, collections,
serialization, API design, tests, build, and only then modern idioms.

## Before reading the code

It is Java when the diff touches `*.java`, `module-info.java`, `package-info.java`, `pom.xml`,
`build.gradle(.kts)`, `settings.gradle(.kts)`, `gradle/libs.versions.toml`, `.mvn/`, `mvnw` or
`gradlew`; a Kotlin DSL build file is still a Java project when the sources are `.java`. Skim, do
not review, generated sources (`target/generated-sources`, `build/generated`, Lombok output).

Find the compile target before judging an idiom, in order of authority: Maven
`<maven.compiler.release>` (a JEP 223 number, `17` not `1.8`); Gradle `java { toolchain {
languageVersion } }` or `options.release` (`sourceCompatibility` alone does not check API use); then
`.sdkmanrc`, `.java-version`, `.tool-versions`, the CI `setup-java` matrix, the `Dockerfile` base
image. When the build is not in the diff, infer the minimum from the code. A construct newer than the
declared release is a build break, not a style point; a project on 17 is not asked to use 21 features.

| Construct in the diff | Minimum Java |
|---|---|
| `var` locals; `switch` expressions (`->`, `yield`); text blocks `"""` | 10; 14; 15 |
| `record`, `instanceof Type t`, `Stream.toList()` | 16 |
| `sealed`, `non-sealed`, `permits` | 17 |
| pattern `switch` (type patterns, `case null`, `when`, record patterns); virtual threads; `SequencedCollection` (`getFirst`, `reversed`) | 21 |
| `ExecutorService` in try-with-resources | 19 (API), 21 (LTS) |
| `_` unnamed variables and patterns | 22 |
| `ScopedValue` without preview; statements before `super(...)`; `import module`; compact source files | 25 |
| `StructuredTaskScope` | preview in 25 (fifth preview); needs `--enable-preview` |
| `STR."..."` string templates | preview only in 21 and 22, withdrawn: flag any use |

Read the tooling before flagging anything: Spotless (`googleJavaFormat()`, `palantirJavaFormat()`,
`eclipse()`; `ratchetFrom` formats only changed files) or `fmt-maven-plugin`; `net.ltgt.errorprone`
or `-Xplugin:ErrorProne`, with NullAway as `errorprone("com.uber.nullaway:nullaway")` plus
`NullAway:AnnotatedPackages` or `OnlyNullMarked`; `checkstyle.xml`; `spotbugs-exclude.xml`; PMD's
`ruleset.xml`; `sonar-project.properties`; `-Xlint:all -Werror`; JSpecify `@NullMarked` in
`package-info.java`; `lombok.config`. A plugin with `skip` or `ignoreFailures` enforces nothing.

### Already enforced: do not comment

| If the repository runs | then skip |
|---|---|
| google-java-format, palantir-java-format or Spotless | whitespace, indentation, line length and wrapping, braces, blank lines, import order, unused imports, Javadoc reflow; the algorithm has no knobs. Not naming, `@Override`, Javadoc presence or anything semantic |
| Error Prone at default severities | the ERROR patterns, which fail the build: `ArrayEquals/HashCode/ToString`, `BoxedPrimitiveEquality`, `EqualsHashCode`, `ComparableType`, `CompareToZero`, `CheckReturnValue`, `ReturnValueIgnored`, `MustBeClosedChecker`, `DeadException`, `GuardedBy`, `LockOnBoxedPrimitive`, `DoubleBraceInitialization`, `FormatString`, `MisusedWeekYear`, `InvalidTimeZoneID`, `RandomCast`, `RandomModInteger`, `TryFailThrowable`, `ThrowNull`, `ComputeIfAbsentAmbiguousReference`, `CollectionIncompatibleType`, `Incomparable`, `ArraysAsListPrimitiveArray`, `JUnit4TestNotRun`, `DoNotMock`, `BanJNDI`, `SuppressWarningsDeprecated`, `OptionalEquality`. WARNING patterns (`MissingOverride`, `ReferenceEquality`, `DefaultCharset`, `JavaUtilDate`, `DoubleCheckedLocking`, `NonAtomicVolatileUpdate`, `FutureReturnValueIgnored`, `StreamResourceLeak`, `ThreadLocalUsage`, `EmptyCatch`, `Finally`, `BigDecimalEquals`, `MissingFail`, ...) fail only under `-Werror`: glance at whether the PR suppressed one. `InterruptedExceptionSwallowed` and `Var` are off by default; do not assume them |
| NullAway at ERROR | `@Nullable` dereferences, `@Nullable` into non-null parameters, uninitialised non-null fields, inside `AnnotatedPackages` or `@NullMarked`. Not: code outside those packages, Lombok-generated code, third-party return values, generics without JSpecify mode, usually tests. Nullability as API design stays a review item |
| Checkstyle with semantic checks on (read `checkstyle.xml`; `google_checks.xml` is mostly layout) | `EqualsHashCode`, `CovariantEquals`, `StringLiteralEquality`, `EmptyCatchBlock`, `IllegalCatch`, `IllegalThrows`, `NoFinalizer`, `NoClone`, `FinalClass`, `HideUtilityClassConstructor`, `DesignForExtension`, `VisibilityModifier`, `InterfaceIsType`, `OneTopLevelClass`, `AvoidStarImport`, `AvoidDoubleBraceInitialization`, `MissingSwitchDefault`, `MissingNullCaseInSwitch`, `FallThrough`, `MagicNumber`, `ParameterAssignment`, `MissingDeprecated`, `ModifierOrder`, naming, metrics and sizes |
| SpotBugs at medium effort or above, failing the build | Bad practice, Correctness, Multithreaded correctness, Malicious code: `HE_*`, `EQ_*`, `ES_COMPARING_STRINGS_WITH_EQ`, `NP_*`, `RV_*`, `OS_OPEN_STREAM`, `ODR_OPEN_DATABASE_RESOURCE`, `SE_*`, `IS2_INCONSISTENT_SYNC`, `DC_DOUBLECHECK`, `LI_LAZY_INIT_STATIC`, `VO_VOLATILE_INCREMENT`, `SC_START_IN_CTOR`, `WA_NOT_IN_LOOP`, `DL_SYNCHRONIZATION_ON_*`, `JLM_JSR166_*`, `SWL_SLEEP_WITH_LOCK_HELD`, `STCAL_*`, `EI_EXPOSE_REP(2)`, `MS_*`, `MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR`, `DM_EXIT`, `DM_DEFAULT_ENCODING`, `DM_CONVERT_CASE`; Security (`SQL_*`, `XSS_*`, `HRS_*`, `PT_*_PATH_TRAVERSAL`, `DMI_CONSTANT_DB_PASSWORD`), more with findsecbugs. Dodgy-code findings are low confidence and often excluded |
| javac `-Xlint:all -Werror`, clean | raw types, unchecked operations, deprecation and removal, switch fall-through, `finally` not completing normally, lossy compound assignments, missing `serialVersionUID`, static access via instance, `synchronization` on value-based classes, `this-escape` (21+), unclosed `try` resources, `varargs`, `overloads`, `overrides`, `dep-ann`, `divzero`, `text-blocks`, `missing-explicit-ctor`, module warnings |
| SonarQube or SonarCloud with a blocking quality gate | overlaps Error Prone and SpotBugs: null dereference, unclosed resources (including `ExecutorService` on 19+), `equals`/`hashCode`, `==` on strings, `InterruptedException` handling, `Optional.get` unchecked, JUnit 5 visibility, missing or incomplete assertions (S2970), hard-coded credentials, injection, weak crypto, deserialization. Check the project's quality profile rather than assuming a rule |

## Checklist

### Security

- **SQL parameters go through `PreparedStatement` placeholders.** Signal: `"... WHERE id = " + id`,
  `prepareStatement(sql + input)`, string-built JPQL, `@Query` with concatenation. Why: injection is
  prevented by construction, not validation. [SCG INJECT-2] [SB SQL_*]
- **Untrusted data is encoded for its sink and kept off command lines and paths.** Signal:
  `out.println(request.getParameter(...))`, `setHeader(name, userValue)`, `ProcessBuilder("sh", "-c",
  cmd + arg)`, `new File(baseDir, param)` without `normalize()` plus `startsWith(base)`. [SCG INJECT-1..4, INPUT-1]
- **XML parsers are hardened.** Signal: an XML `*Factory` or `Unmarshaller` created without
  `FEATURE_SECURE_PROCESSING`, `disallow-doctype-decl` or `ACCESS_EXTERNAL_DTD`/`SCHEMA` set to `""`.
  Why: XXE reads files and reaches internal hosts. [SCG INJECT-5]
- **Java deserialization of untrusted data is avoided or allow-list filtered.** Signal:
  `ObjectInputStream.readObject()` on request data without an allow-list `ObjectInputFilter`; Jackson
  `enableDefaultTyping`; `XMLDecoder`; `XStream`; `Class.forName(userString)`; JNDI with a user name
  ("inherently dangerous"; `-Djdk.serialFilter` otherwise). [SCG SERIAL-1, SERIAL-6] [JEP 290] [EP BanJNDI]
- **`SecureRandom` and current algorithms for anything security-relevant.** Signal: `new Random()`
  for tokens, salts or nonces; `MD5`/`SHA-1` for passwords or signatures; `"AES/ECB"`, `"DES"`, `"RSA"`
  without OAEP; a reused IV; `"TLSv1"`; an accept-all `TrustManager` or `HostnameVerifier`. [JD21 Random] [SCG]
- **Secrets stay out of source, logs, exceptions and `toString`; limits cannot overflow.** Signal:
  literals shaped like keys; `log.info("... password={}")`; a stack trace in an HTTP body; `size *
  count` checked after the multiplication (`Math.multiplyExact`); `Pattern.compile(userRegex)`.
  [SCG CONFIDENTIAL-1..3, INPUT-1, DOS-1]

### Error handling and resources

- **try-with-resources for every `AutoCloseable`.** Signal: a stream closed in `finally` or never;
  `Files.lines/list/walk/find` outside `try`; an `ExecutorService` made in a method and never shut
  down (`AutoCloseable` since 19); `lock.lock()` not followed at once by `try`; JDBC, `MockedStatic`.
  Why: a leak per call under load. [EJ3 Item 9] [EP StreamResourceLeak] [SB OS_OPEN_STREAM]
- **No swallowed exception, and the narrowest catch type.** Signal: `catch (Exception e) {}` or
  `catch (IOException ignored) {}` with no comment; `catch (...) { return null; }`; a failure logged
  at `debug`; `catch (Throwable t)` outside a top-level dispatcher; `catch (Exception e)` around code
  that throws `InterruptedException`; `catch (Throwable)` in a test (masks `fail()`). Why: log or
  rethrow, or explain. [GJS] [EJ3 Item 77] [EP EmptyCatch, TryFailThrowable] [CS IllegalCatch]
- **`InterruptedException` restores the interrupt or propagates.** Signal: `catch
  (InterruptedException e) { log.warn(...); }` or `{ throw new RuntimeException(e); }` with no
  `Thread.currentThread().interrupt()`; `Thread.interrupted()` (clears the flag) for `isInterrupted()`.
  [JD21 ExecutorService] [EP InterruptedInCatchBlock]
- **Every `Future` is joined, handled or dropped with a comment; `finally` never returns.** Signal:
  `executor.submit(...)` ignored; a `CompletableFuture` chain with no `exceptionally`/`handle` and
  nobody joining it; `return` inside `finally` (discards the exception). [EP FutureReturnValueIgnored, Finally]
- **Exceptions match the abstraction, chain the cause, carry the values; checked only when the
  caller can act.** Signal: `SQLException` escaping a domain method; `throw new MyException("failed")`
  in a `catch` without `, e`; `"invalid index"` for `"index 7 out of 0..5"`; `throws Exception`. [EJ3 Items 70..73, 75]
- **Logging passes the throwable last with placeholders; return values are checked.** Signal:
  `log.error("Failed: " + e.getMessage())` (loses the trace); `log.info("x={}", e, id)` (not last,
  not printed); `file.delete()` dropped; `new IllegalStateException(...)` never thrown; `System.exit`
  outside `main`; `finalize()`. [SLF4J FAQ] [EP ReturnValueIgnored, DeadException] [EJ3 Item 8]

### Concurrency

- **Shared mutable state has one guard, and the class says its thread safety.** Signal: a field
  written under `synchronized` and read without; a `@GuardedBy` field touched without its lock; no
  `@ThreadSafe`/`@Immutable`/"not thread-safe" note on a shared class. [EJ3 Items 78, 82] [SB IS2_INCONSISTENT_SYNC]
- **`volatile` covers one write-then-publish; double-checked locking needs it; static lazy init
  uses the holder idiom.** Signal: `volatile int count; count++`; `if (ref == null) ref = ...`; DCL
  on a non-volatile field. Why: a reader can see a partly built object. [JLS §17.7] [EJ3 Item 83] [EP DoubleCheckedLocking]
- **`this` does not escape the constructor.** Signal: a listener registered, a thread started, an
  overridable method called, or an inner class made inside a constructor. Why: the final-field
  guarantee needs no other thread to see the object first; `-Xlint:this-escape` (21) warns. [JLS §17.5] [SCG OBJECT-4]
- **Lock on a private final object only; no alien calls, I/O or sleep under it.** Signal:
  `synchronized ("lock")`; on a boxed or value-based value; on a `Lock`; on a reassigned field;
  `synchronized (this)` on a public class; JDBC/HTTP or a listener call under a lock. Why: on a 21
  virtual-thread runtime this also pins the carrier; JDK 24 removes that. [EJ3 Item 79] [EP LockOnBoxedPrimitive] [JEP 444] [JEP 491]
- **`ConcurrentHashMap` compound actions go through `compute*`/`merge`, short and self-contained.**
  Signal: `if (!chm.containsKey(k)) chm.put(k, v)`; `get` then `put(k, f(v))`; a `computeIfAbsent`
  calling another `compute*` on the same map (may deadlock); `chm.put(k, null)`. [JD21 ConcurrentHashMap]
- **Executors are owned, bounded and shut down; virtual threads (21+) one per task, never pooled.**
  Signal: `new Thread(...).start()` in request code; an unbounded `LinkedBlockingQueue` so `max`
  never applies; no `shutdown`/`close` in a lifecycle hook; `newFixedThreadPool(n,
  Thread.ofVirtual().factory())` (bound with a `Semaphore`). [JD21 ExecutorService] [EJ3 Item 80] [JEP 444]
- **`CompletableFuture`: an executor for blocking work, no `join` on a request thread.** Signal:
  `supplyAsync(() -> jdbc.query(...))` with no executor (blocks the common pool); `join()` on the
  request thread; `thenApply(f)` where `f` returns a future (`thenCompose`); no `orTimeout`. [JD21 CompletableFuture]
- **`ThreadLocal` is `static final`, bounded and removed on pooled threads; `wait`/`notify` and
  shared formatters are legacy.** Signal: a non-static `ThreadLocal`; `set` with no `remove` in
  `finally`; `wait()` not in a `while`; `notify()`; a shared `SimpleDateFormat`; `random.nextInt() % n`.
  [JD21 ThreadLocal] [EJ3 Item 81] [EP ThreadLocalUsage, WaitNotInLoop] [SB STCAL_*]

### Equality, hashing and value semantics

- **`equals` and `hashCode` together, obeying the contract.** Signal: one without the other;
  `hashCode` over fields `equals` ignores; `equals(Foo)` overload for `equals(Object)`; a 30-line
  `equals` on a type that could be a record. [EJ3 Items 10, 11] [EP EqualsHashCode, NonOverridingEquals]
- **`==` on boxed types, strings or `Optional` is reference comparison.** Signal: `Integer a ==
  Integer b`, `Long x == Long y`, `s == "literal"`, `optA == optB`. Why: identity is guaranteed only
  for boxed constants in -128..127, so small test values pass. [JLS §5.1.7] [EP BoxedPrimitiveEquality]
- **Arrays compare, hash and print by identity.** Signal: `a.equals(b)` on arrays,
  `Objects.hash(array)`, `array.toString()` in a log, an array as a map key or record component.
  Why: `Arrays.equals`/`deepEquals`/`hashCode`/`toString`. [EP ArrayEquals, ArrayHashCode, ArrayRecordComponent]
- **`BigDecimal`: `compareTo` for equality, strings for construction, a scale for division; money
  is never `double`.** Signal: `bd.equals(other)` on amounts (`2.0` is not `2.00`); `new
  BigDecimal(0.1)`; `a.divide(b)` with no scale (throws on 1/3); `double price`. [JD21 BigDecimal] [EJ3 Item 60] [EP BigDecimalEquals]
- **`compareTo` returns a sign, agrees with `equals`, and `Comparable<T>` names the right `T`.**
  Signal: `return this.x - o.x` (overflow); `-a.compareTo(b)`; a result compared to anything but
  zero; `class A implements Comparable<B>`. [EJ3 Item 14] [EP BadComparable, CompareToZero, ComparableType]
- **Hash keys are immutable in the fields `equals` reads; `toString` leaks nothing; `clone` is
  avoided.** Signal: `hashCode` over a field with a setter; `URL` keys (DNS); a Lombok `@Data` entity
  as a key; a `toString` with a token or PII; `implements Cloneable`. [JD21 Map] [EJ3 Items 11..13] [SCG CONFIDENTIAL-2]

### Types, nulls and immutability

- **Minimise mutability: final classes, `private final` fields, no idle setters; statics `final`,
  deeply immutable, never a cache.** Signal: setters never called after construction; `public static`
  non-final; `public static final` array or `ArrayList` (a `final String[]` is still mutable);
  `private static Map<...> cache` (shared by every thread and test). [EJ3 Items 17, 19, 78] [SCG MUTABLE-1, MUTABLE-9..11]
- **Defensive copies in and out, copied before validated.** Signal: a constructor storing a `Date`,
  array or `List` parameter directly; a getter returning an internal array or collection;
  `unmodifiableList(internal)` presented as a copy (it is a view). [EJ3 Item 50] [SCG MUTABLE-2, MUTABLE-3] [SB EI_EXPOSE_REP]
- **Nullness is declared with type-use annotations in a `@NullMarked` scope.** Signal:
  `javax.annotation.Nullable` mixed with JSpecify; `@Nullable` on a primitive or an outer class;
  `@Nullable String[]` where `String @Nullable []` was meant. [JSpecify] [EP NullablePrimitive]
- **Parameters checked at the top; never `null` for a collection, array, `Optional` or `Stream`.**
  Signal: a parameter stored unchecked and dereferenced later; `if (x == null) throw new
  IllegalArgumentException` (`Objects.requireNonNull`); `return null;` in a method typed `List<...>`
  or `Optional<...>`. [JD21 Objects] [EJ3 Items 49, 54] [SB NP_OPTIONAL_RETURN_NULL]
- **Primitives over boxes; enums and types over strings and ints.** Signal: `Long count =
  map.get(k); count++` (NPE on an absent key); `public static final int STATUS_OPEN = 1`; `ordinal()`
  stored or used as an index (`EnumMap`); a `String` holding an enum, a duration or a path. [EJ3 Items 34..37, 61, 62]
- **`java.time`, not `Date`, `Calendar`, `SimpleDateFormat`; zone, clock, locale and charset
  explicit.** Signal: new `java.util.Date` fields; `LocalDate.now()` or `ZoneId.systemDefault()` in
  server code; `LocalDateTime` where an `Instant` is meant; `YYYY` for `yyyy`; `toLowerCase()` without
  a `Locale`; `getBytes()` without a charset. Why: inject a `Clock`. [EP JavaUtilDate, MisusedWeekYear, DefaultCharset]

### Collections and generics

- **No raw types; suppress `unchecked` on the smallest scope with a proof.** Signal: `List list = new
  ArrayList();`, `(List<String>) obj`, `@SuppressWarnings("unchecked")` on a class or a multi-statement
  method ("the most deeply nested element where it is effective"). [EJ3 Items 26, 27]
- **Wildcards on parameters not returns (PECS); lists over arrays; `@SafeVarargs` only when the
  array does not leak.** Signal: `addAll(Collection<E> c)` where `? extends E` fits; `List<? extends
  T> f()`; `T[] toArray()` of a generic type; a `T...` array stored or returned. [EJ3 Items 28, 31, 32]
- **Know the factories' rules: nulls, duplicates, order, view versus snapshot.** Signal: `List.of(a,
  null)` (NPE); `Map.of(k, v, k, v2)` (throws); code relying on `Map.of` iteration order;
  `Arrays.asList(primitiveArray)` (one element); `Arrays.asList(...).add(x)` (fixed size). [JD21 List, Map] [SCG MUTABLE-10]
- **A method returns mutable or immutable collections, not both; no modification during
  iteration.** Signal: one branch `return List.of()` and another `return new ArrayList<>(...)`; `for
  (X x : list) { if (...) list.remove(x); }` (`ConcurrentModificationException` is best-effort;
  `removeIf` or a copy). [EJ3 Item 47] [JD21 HashMap] [EP MixedMutabilityReturnType, ModifyCollectionInEnhancedForLoop]
- **Incompatible-type calls fail silently; `get`+`put` pairs become `computeIfAbsent`/`merge`.**
  Signal: `List<Long>.contains(intValue)`, `Map<String, X>.get(uuid)`; `if (!map.containsKey(k))
  map.put(k, ...)`; `putIfAbsent`'s result treated as the stored value (it is the previous one).
  [EP CollectionIncompatibleType, ListRemoveAmbiguous] [SB RV_RETURN_VALUE_OF_PUTIFABSENT_IGNORED]
- **`EnumSet`/`EnumMap` for enum keys; `TreeSet` elements `Comparable`; obsolete types out; refer
  by interface.** Signal: `HashSet<MyEnum>`; `new TreeSet<Foo>()` with no `Comparable` and no
  comparator; `new HashMap<>() {{ put(...); }}` (holds `this`); `Hashtable`, `Vector`, `StringBuffer`;
  `ArrayList<String>` as a field type. [EJ3 Items 36, 37, 64] [EP Incomparable, DoubleBraceInitialization, JdkObsolete]

### Serialization and interop

- **`implements Serializable` on a new class is a design decision, not a reflex.** Signal:
  `Serializable` added "just in case", to a functional interface, inherited unknowingly, or added to
  a sensitive class. Why: it "effectively adds a hidden public constructor". [EJ3 Items 85, 86] [SCG SERIAL-1]
- **A `Serializable` class has `serialVersionUID`, `transient` where needed, a defensive
  `readObject`.** Signal: no `serialVersionUID`; a non-transient non-serializable field; `readObject`
  calling `defaultReadObject()` without re-validating; a singleton without `readResolve`. [EJ3 Items 87..90] [SCG SERIAL-2..4]
- **Wire formats are explicit and backward compatible.** Signal: a renamed field or enum constant on
  a persisted or transmitted type; `FAIL_ON_UNKNOWN_PROPERTIES` default-true on input that must
  tolerate additions; `Enum.valueOf` on wire input with no fallback; proto `ordinal()`. [EP ProtocolBufferOrdinal]
- **Bytes have an explicit charset and endianness.** Signal: a `ByteBuffer` without `order(...)`
  for a protocol with defined endianness; `writeUTF` for interop (modified UTF-8);
  `ByteBuffer.array()` on a read-only or offset buffer. [EP ByteBufferBackingArray, DefaultCharset]
- **Native and foreign calls are wrapped.** Signal: a `public native` method (private, behind a
  validating wrapper); `java.lang.foreign` calls with unchecked lengths; native frames on virtual
  threads (they still pin). [SCG INPUT-3] [EJ3 Item 66] [JEP 491]
- **`module-info.java` matches the build and exports nothing internal.** Signal: `exports
  com.example.internal`; `opens` for a reflective framework without a comment; `requires transitive`
  on an `implementation` dependency; `requires` of an automatic module. [javac -Xlint:requires-automatic] [SCG EXTEND-2]

### API design and encapsulation

- **Minimise accessibility; final unless designed for extension; composition over inheritance.**
  Signal: `public class` for a type used only in its package; `public` helpers only tests use; a
  non-final class with no documented self-use; `extends HashMap<...>` to add behaviour; an interface
  of constants. [EJ3 Items 15, 18..22] [SCG EXTEND-1, EXTEND-5]
- **Builders for four or more parameters; overloads that cannot be confused.** Signal: telescoping
  constructors; `(String, String, String)` (swapped arguments compile); `f(int)` beside `f(Integer)`;
  `f(List<A>)` beside `f(List<B>)`; `null` passed to varargs. [EJ3 Items 1, 2, 51..53] [EP ArgumentSelectionDefectChecker]
- **Exceptions and nullability are part of the API.** Signal: an undocumented unchecked exception
  callers must handle; `@throws` for an exception not thrown; `IOException` on a domain interface; a
  public method returning `null` with no `@Nullable`; `Optional` as a parameter. Why: without NullAway
  the annotations are documentation the reviewer must check. [EJ3 Items 55, 72..74] [NullAway]
- **`@Override` whenever legal.** Signal: `public boolean equals(Foo other)` without `@Override` (an
  overload); `hashcode()`/`tostring()`; `@Override` on a record accessor (no effect). [EJ3 Item 40]
  [GJS] [EP NonOverridingEquals, MissingOverride]
- **Javadoc on every public type and member that is not self-explanatory.** Signal: a new public API
  with none; a first sentence that is a full sentence or "This method returns..." (a summary fragment
  is a noun or verb phrase); `@param` for a missing parameter; empty block tags. [GJS Javadoc] [EJ3 Item 56]
- **Deprecation is complete; naming the formatter cannot fix.** Signal: `@Deprecated` with no
  `@deprecated` tag naming the replacement; `@SuppressWarnings("deprecated")` (the string is
  `"deprecation"`); `XMLHTTPRequest` for `XmlHttpRequest`; `UPPER_SNAKE` on a mutated `static final`;
  two top-level classes in one file; `import java.util.*`; `1l` for `1L`. [CS MissingDeprecated] [GJS] [EJ3 Items 25, 68]

### Tests

- **A test that cannot run or cannot fail.** Signal: `private void test...()` (not discovered, never
  runs); `@BeforeAll` non-static without `PER_CLASS`; a non-`static` `@MethodSource` factory; no
  `assert*`/`verify`; `assertThat(x)` with nothing after it (a no-op); `as("...")` after the
  assertion (ignored). Why: ask whether CI counts tests. [JUnit] [AssertJ]
- **JUnit 5 idioms, not JUnit 4 leftovers.** Signal: `org.junit.Test`, `@Before`, `@BeforeClass`,
  `@Ignore`, `@RunWith`, `@Rule`, `@Test(expected = ...)`, `assertEquals("message", e, a)` (Jupiter
  puts the message last), `junit-vintage-engine` in a new module, `public` test classes. [JUnit migrating-from-junit4]
- **`assertThrows` replaces `try { ...; fail(); } catch`, with a specific type.** Signal: `fail()`
  after a call expected to throw; `assertThrows(Exception.class, ...)` (matches the wrong exception
  too); `assertEquals(actual, expected)` swapped. [JUnit assertions] [EP MissingFail, AssertEqualsArgumentOrderChecker]
- **Tests do not depend on order, time or each other; `@Disabled` carries a reason.** Signal:
  `@TestMethodOrder` on a unit test; static state across tests; `LocalDate.now()` in assertions
  (`Clock.fixed`); `Thread.sleep` for async work (`CountDownLatch`, Awaitility); iterating a `HashSet`
  expecting order; bare `@Disabled` or `@Disabled("flaky")`. [JUnit] [JD21 Map]
- **Mockito: mock collaborators, not values or types you do not own; strict stubs on; `any()`,
  spies and static mocks used deliberately.** Signal: `mock(List.class)`, a mocked record or DTO;
  `lenient()` to silence `UnnecessaryStubbingException` (delete the dead stub); `when(repo.find(any()))`
  where a specific argument would prove the call; `when(spy.get(0))` (calls the real method:
  `doReturn`); `mockStatic` outside try-with-resources. [Mockito §1, §2, §13, §40, §46, §48] [EP DoNotMock]
- **Testcontainers lifecycle is deliberate; test-only code stays out of `main`.** Signal:
  `@Container` on an instance field (a container per test: intended?); `@Container` in a `@Nested`
  class expecting sharing; `assertTimeoutPreemptively` on code using `ThreadLocal` or transactions
  (another thread); `@VisibleForTesting` made `public`; `junit` in a non-test scope. [Testcontainers] [EJ3 Item 15]

### Build and dependencies

- **Compile with `--release`, keep code within it, keep warnings as errors.** Signal:
  `<maven.compiler.source>` without `release`; `sourceCompatibility = 17` with a 21 toolchain and
  `.toList()` in the code (compiles on 21, fails on 17); `-Werror` removed; `@SuppressWarnings("all")`;
  a growing `spotbugs-exclude.xml`. [javac] [maven-compiler-plugin]
- **Maven scopes: `compile` only for the public API; never `system`.** Signal: `logback-classic` in
  `compile` of a library (forces a backend on consumers); `jakarta.servlet-api` not `provided`;
  `junit`, `mockito`, `testcontainers` without `<scope>test</scope>`; `systemPath`. [Maven dependency mechanism]
- **Gradle: `implementation` by default, `api` only for public signatures.** Signal: `api(...)` for
  a library used only internally (leaks onto consumers' compile classpath); `compile`/`runtime`
  (removed in Gradle 7); `compileOnly` for something needed at runtime. [Gradle java_library_plugin]
- **Versions managed once; convergence read, not forced.** Signal: one artifact at two versions in
  sibling modules; a Spring, Jackson or Netty artifact pinned outside its BOM; `enforcedPlatform` in a
  library; a catalog version relied on to pin (requests, not constraints); `dependencyConvergence`
  disabled; `resolutionStrategy.force`; an uncommented `<exclusion>`. [Maven enforcer] [Gradle platforms]
- **One logging facade in libraries, one backend in the application; reproducible builds.** Signal:
  a backend in a library's `compile`/`api`; two backends on one classpath; `+` or `latest.release`
  ranges; `-SNAPSHOT` on a release branch; `mavenLocal()` in CI; new `--add-opens` JVM args;
  `--enable-preview` in a library. [SLF4J FAQ] [JDK 17 JEP list]
- **Lombok and processors are declared as processors, and their output is not hand-written.**
  Signal: `lombok` in `implementation`/`compile` (`compileOnly` plus `annotationProcessor`); a `@Data`
  class as a map key or entity. Why: NullAway skips `@lombok.Generated`. [NullAway] [EJ3 Item 11]

### Modern idioms (code targeting 17 or 21)

- **A new immutable data carrier is a `record`, validated in its compact constructor.** Signal: a
  class that is only final fields, constructor, accessors and `Object` methods, or a Lombok `@Value`;
  `record R(List<String> xs)` with no compact constructor copying and validating (records are only
  shallowly immutable). [JEP 395] [EJ3 Item 50] [EP ArrayRecordComponent]
- **A closed hierarchy is `sealed`, and its `switch` has no `default`.** Signal: `interface Shape`
  with a fixed set of implementations and `if/else instanceof` chains; a `switch` on a sealed type
  ending in `default -> throw`. Why: a `default` hides the new subtype the compiler would catch. [JEP 409] [JEP 441]
- **Pattern matching replaces cast-after-check; `null` is decided explicitly (21).** Signal: `if (o
  instanceof Foo) { Foo f = (Foo) o; }` on 16+; a pattern `switch` on a possibly-null selector with
  no `case null` (`default` does not match `null`; the switch throws NPE). [JEP 441] [EP PatternMatchingInstanceof]
- **`Optional` is a return type only.** Signal: `private Optional<X> field`, `void f(Optional<X>
  p)`, `List<Optional<X>>`, `Optional<X> o = null`, `isPresent()` then `get()`, `Optional<Integer>`
  for `OptionalInt`. Why: "should never itself be null". [JD21 Optional] [EJ3 Item 55] [EP NullableOptional]
- **Streams stay side-effect free, sequential unless measured, and readable; `toList()` is
  unmodifiable.** Signal: `forEach(list::add)`; `peek` for logic (skipped by `count()`);
  `.parallel()` on I/O or in a request; `.toList()` followed by `.add(...)` (throws). [EJ3 Items 42..46, 48] [JD21 Stream]
- **`var` where the type is obvious; text blocks, sequenced collections and no preview features.**
  Signal: `var x = service.compute()` with a non-obvious type; `"..." + "\n" + "..."` on 15+; `\s`
  where a space was meant; `list.get(0)` on 21 (`getFirst`); `STR."..."` (withdrawn);
  `StructuredTaskScope` without `--enable-preview`; a compact source file or instance `main` in
  production. [GJS] [EP StringConcatToTextBlock] [JEP 431] [JEP 465] [JEP 505] [JEP 512]

## Severity

**Blocking**
- SQL through `PreparedStatement`; untrusted data encoded, kept off command lines and paths; XML
  parsers hardened; deserialization avoided or allow-list filtered; `SecureRandom`; secrets out of logs
- `equals` and `hashCode` together; `==` on boxed types, strings or `Optional`; arrays by identity;
  `BigDecimal.equals` on amounts; money in `double`; `compareTo` by subtraction or `Comparable<Wrong>`
- No swallowed exception; `InterruptedException` swallowed; a `Future` dropped; `catch (Throwable)`
  on a production path; `finally` that returns; try-with-resources missing
- Shared mutable state unguarded; non-volatile double-checked locking; `volatile` compound update;
  `this` escaping a constructor; lock on a string, boxed value or `Lock`; check-then-put on
  `ConcurrentHashMap`; `compute*` modifying the same map; blocking under `synchronized` on a 21
  virtual-thread path; pooled virtual threads
- A construct newer than the declared release; preview APIs or string templates in production;
  `--enable-preview` in a library; mutable `public static` state; internal array or collection
  returned from a security-relevant class; `Serializable` on a sensitive class; `readObject` without
  validation; a test that cannot run or cannot fail

**Should fix**
- Missing `record`, `sealed` or pattern `switch` on a closed hierarchy; `default` on a sealed
  switch; no `case null` where null reaches a pattern switch; `Optional` as field or parameter
- Missing defensive copies; non-final class with no extension design; `public` where
  package-private suffices; telescoping constructors; raw types; broad `@SuppressWarnings`
- Checked exceptions callers cannot handle; cause or detail lost; logging by concatenation;
  `ThreadLocal` non-static or never removed; executor without shutdown; `wait` not in a loop;
  `CompletableFuture` on the common pool for I/O; legacy date types; implicit zone, locale or charset
- Wrong dependency scope; `api` leakage; versions outside the BOM; `enforcedPlatform` in a library;
  convergence disabled; `-Werror` removed; missing Javadoc or `@throws` on a new public API
- Over-mocking; mocked value types; `lenient()` to silence strict stubs; order- or time-dependent
  tests; `Thread.sleep` in tests; bare `@Disabled`; nullability undeclared in an annotated codebase

**Nit**
- `var` readability; method reference versus lambda; text block versus concatenation; `getFirst()`
  versus `get(0)`; `toList()` versus `collect(toList())` when mutability is irrelevant
- Naming the formatter cannot fix; modifier order; `L` suffix; static qualified by instance;
  `EnumMap`/`EnumSet` preference; interface types in signatures; `requireNonNull` message wording;
  `@DisplayName`, parameterised display names, `assertAll` grouping

## Where the sources disagree

- `synchronized` or `ReentrantLock` around I/O on virtual threads: follow the repository's
  convention; if none, flag only on a 21 runtime, because JEP 491 (JDK 24) removes the pinning.
- `Optional` fields and parameters: follow the repository's convention; if none, should-fix, because
  the Javadoc says only "primarily intended" as a return type.
- Checked or unchecked exceptions: follow the repository's convention; if none, flag `throws
  Exception` and checked types nobody can recover from, and do not demand converting either way.
- `final` on locals: follow the repository's style; never comment on it in a mixed codebase, because
  Google style does not require it and `Var` and `FinalLocalVariable` disagree.
- `default` in enum switches: follow the repository's convention; if none, prefer no `default` on
  switch expressions so a new constant fails to compile; accept a throwing `default` on statements.
- `instanceof` or `getClass()` in `equals`: the repository's convention; if none, `instanceof` (Liskov).
- `Collections.unmodifiableList` or `List.copyOf`: the repository's convention; if none, decide by
  whether callers must see later changes (view) and whether nulls are legal (`copyOf` rejects them).
- `assertTimeoutPreemptively`: accept with a comment; a documented hazard, sometimes the only way to
  bound a hung test. `withReuse(true)` committed or local-only: the repository's call; experimental.
- Lombok: flag the specific hazard (mutable-field hashing, `@EqualsAndHashCode` on entities,
  NullAway skipping generated code), not the library, because many teams consider it settled.
- `InterruptedExceptionSwallowed` is off by default in Error Prone: review it by hand regardless.
  JDK 25 features (scoped values, flexible constructors, module imports, compact source files):
  awareness only; do not demand them in a 17 or 21 codebase.

## Reference

Open `references/java-review-research.md` for an item's full signal list, a source URL, a tool's
complete coverage (Error Prone, SpotBugs, Checkstyle, javac `-Xlint`, NullAway, Sonar), or the exact
words of a JEP or Javadoc. Its sections: 1. Sources; 2. Detecting the language and version;
3. Checklist (3.1 Modern idioms; 3.2 Types, nulls and immutability; 3.3 Equality, hashing and value
semantics; 3.4 Collections and generics; 3.5 Error handling and resources; 3.6 Concurrency;
3.7 Security; 3.8 Serialization and interop; 3.9 API design and encapsulation; 3.10 Tests; 3.11 Build
and dependencies); 4. Severity hints; 5. Already enforced by tooling; 6. Open questions and
disagreements; Appendix: Effective Java, 3rd edition, the 90 items.
