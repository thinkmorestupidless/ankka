# Scala code review rules (Scala 3 first, Scala 2 where it still matters)

## Contents

1. Sources (official Scala documentation; community style guides; lint tooling; Pekko; testing and build)
2. Detecting the language and dialect (files; Scala 3 vs Scala 2 in a diff; tooling to glance at)
3. Checklist
   - 3.1 Idioms and Scala 3 syntax
   - 3.2 Types, nulls and totality
   - 3.3 Collections and performance
   - 3.4 Error handling
   - 3.5 Concurrency, Futures and actors
   - 3.6 Serialization and interop
   - 3.7 API design and encapsulation
   - 3.8 Tests
   - 3.9 Build and dependencies
4. Severity hints
5. Already enforced by tooling
6. Open questions / disagreements between sources

---


A checklist an AI reviewer applies to a pull request after the language-agnostic checks are done.
Researched 2026-10-23 from primary sources. Every `[source]` tag below names an entry in section 1;
items with no fetched primary source are tagged `[reviewer experience]` and should be weighed
accordingly.

## 1. Sources

URLs actually fetched, one line each, with what it contributed.

### Official Scala documentation
- https://docs.scala-lang.org/style/index.html — the style guide is advisory ("a list of rules to be broken"); section map.
- https://docs.scala-lang.org/style/naming-conventions.html — camel case, acronyms as words, no `get` prefix, parentheses mark side effects, symbolic names "Avoid!", type parameters `A`, `B`..., constants UpperCamel.
- https://docs.scala-lang.org/style/declarations.html — explicit return types on public members, no procedure syntax, modifier order, multiple parameter lists only with a reason, function parameter last.
- https://docs.scala-lang.org/style/control-structures.html — braces vs none per construct, prefer `for` over `map`/`flatMap` chains of more than one generator.
- https://docs.scala-lang.org/style/method-invocation.html — infix only for pure methods, no `/:`, dot notation for higher-order calls, parentheses on side-effecting arity-0 calls.
- https://docs.scala-lang.org/style/types.html — infer locals, annotate public API, prefer nominal over structural types.
- https://docs.scala-lang.org/scala3/book/ca-context-parameters.html — `given`/`using`; a given is for "a single canonical value for a particular type".
- https://docs.scala-lang.org/scala3/reference/contextual/using-clauses.html — anonymous context params, explicit `(using x)`, default-argument-shadows-given warning, class `using val` preferred over an explicit `given` member.
- https://docs.scala-lang.org/scala3/reference/contextual/givens.html — anonymous given names are synthesized; public libraries should name givens for binary compatibility; 3.6 syntax change.
- https://docs.scala-lang.org/scala3/reference/contextual/relationship-implicits.html — mapping implicit val/def/object/class/conversion to given/using/extension/Conversion; `implicit` still accepted, deprecation timing open.
- https://docs.scala-lang.org/scala3/reference/changed-features/implicit-conversions.html — defining a conversion warns without `import scala.language.implicitConversions`; `scala.Conversion`.
- https://docs.scala-lang.org/scala3/book/ca-extension-methods.html — extension methods replace implicit classes; must be imported from other packages.
- https://docs.scala-lang.org/scala3/book/types-opaque-types.html — zero-cost abstraction, operations via companion/extension, equality to underlying type visible only in the defining scope.
- https://docs.scala-lang.org/scala3/book/types-union.html — union types are never inferred (widens to a common supertype) unless annotated; use for ad hoc alternatives.
- https://docs.scala-lang.org/scala3/book/control-structures.html — `if/then`, `for/do`, `while/do`, `.match`; `end if` optional.
- https://docs.scala-lang.org/scala3/reference/other-new-features/control-syntax.html — new control syntax; `-rewrite -new-syntax` / `-old-syntax`.
- https://docs.scala-lang.org/scala3/reference/other-new-features/indentation.html — indentation rules, mixing braces, colon syntax (3.3+ for arguments), end markers recommended when a construct has blank lines, runs 15–20+ lines, or ends at 4+ indentation levels; `-no-indent`.
- https://docs.scala-lang.org/scala3/reference/other-new-features/export.html — export aliases are final forwarders; cannot override; composition over inheritance.
- https://docs.scala-lang.org/scala3/reference/metaprogramming/inline.html — `inline` is guaranteed, per call site, effectively final; `inline val` must be constant; `transparent inline`; `-Xmax-inlines`.
- https://docs.scala-lang.org/scala3/reference/new-types/match-types.html — reduction/disjointness rules, stuck match types, recursion limit, case order matters.
- https://docs.scala-lang.org/scala3/reference/enums/enums.html — enums compile to sealed classes; `values`/`valueOf`/`fromOrdinal`; cases take only access modifiers; `extends java.lang.Enum`.
- https://docs.scala-lang.org/scala3/reference/enums/adts.html — enums as ADTs; case applications widen to the enum type.
- https://docs.scala-lang.org/scala3/book/types-adts-gadts.html — enum "the perfect starting point for modeling your data types"; desugars to sealed class plus companion.
- https://docs.scala-lang.org/scala3/book/domain-modeling-fp.html — data as enums/case classes, behaviour in companion/modules/extensions; "if you are the designer of the data model, you will define your extension methods in the companion object".
- https://docs.scala-lang.org/scala3/reference/contextual/derivation.html — `derives`, `derived`, `Mirror`; derived instances live in the ADT's companion.
- https://docs.scala-lang.org/scala3/reference/contextual/multiversal-equality.html — `CanEqual`, `strictEquality`, `derives CanEqual`.
- https://docs.scala-lang.org/scala3/reference/experimental/explicit-nulls.html — `-Yexplicit-nulls`, `T | Null`, flexible types for Java, `.nn`, `unsafeNulls`.
- https://docs.scala-lang.org/scala3/reference/other-new-features/safe-initialization.html — `-Wsafe-init` catches reads of uninitialized fields and leaking `this`.
- https://docs.scala-lang.org/scala3/reference/other-new-features/open-classes.html — `open`; ad hoc extension from another file warns under `-source future` (default from 3.4).
- https://docs.scala-lang.org/scala3/reference/other-new-features/creator-applications.html — `new` optional via constructor proxies; an explicit `apply` suppresses them.
- https://docs.scala-lang.org/scala3/reference/other-new-features/trait-parameters.html — trait parameters; only the first extending class passes arguments.
- https://docs.scala-lang.org/scala3/reference/other-new-features/targetName.html — `@targetName` for symbolic operators, Java interop and erasure clashes.
- https://docs.scala-lang.org/scala3/reference/dropped-features/nonlocal-returns.html — `return` in a lambda deprecated since 3.2; `scala.util.boundary`/`break`.
- https://docs.scala-lang.org/scala3/reference/dropped-features/procedure-syntax.html — `def f() { }` dropped; write `: Unit =`.
- https://docs.scala-lang.org/scala3/reference/dropped-features/package-objects.html — package objects to be dropped; top-level definitions; renaming a file changes the synthetic object name.
- https://docs.scala-lang.org/scala3/reference/dropped-features/this-qualifier.html — `private[this]` deprecated; the compiler infers object-privacy.
- https://docs.scala-lang.org/scala3/reference/changed-features/wildcards.html — `?` is the wildcard type; `_` deprecated from 3.4.
- https://docs.scala-lang.org/scala3/reference/changed-features/vararg-splices.html — `xs*` replaces `xs: _*`; old form errors under `-source future`.
- https://docs.scala-lang.org/scala3/reference/changed-features/imports.html — `import a.*`, `import a.b as c`, `as _` to exclude, `import a.given`.
- https://docs.scala-lang.org/scala3/reference/changed-features/pattern-bindings.html — refutable `val` patterns and `for` generators warn; `for case (x, y) <- xs`; `: @unchecked`.
- https://docs.scala-lang.org/scala3/reference/language-versions/source-compatibility.html — `-source` levels: 3.0-migration, 3.2 (refutable patterns and nonlocal returns warn), 3.3 (fewer braces default), future, future-migration.
- https://docs.scala-lang.org/scala3/guides/migration/incompat-dropped-features.html — symbol literals, do-while, auto-application, `any2stringadd`, early initializers, existential types, `@specialized`.
- https://docs.scala-lang.org/scala3/guides/migration/incompat-syntactic.html — restricted keywords, procedure syntax, `{ (x: Int) => }`, open brace on new line, `[_: Foo]` rejected.
- https://docs.scala-lang.org/scala3/guides/migration/incompat-contextual-abstractions.html — implicit definitions need explicit types; implicit views and view bounds gone.
- https://docs.scala-lang.org/scala3/guides/migration/compiler-options.html — 2.13 → 3 option lookup: `-Xlint:private-shadow` → `-Wshadow:private-shadow`, `-Xlint:unused` → `-Wunused:linted`; `-Werror`, `-Wconf`, `-Wvalue-discard`, `-Wunused` exist in both.
- https://docs.scala-lang.org/overviews/compiler-options/index.html — Scala 2.13 flags: `-Werror`, `-Wunused:{imports,patvars,privates,locals,explicits,implicits,params,linted}`, `-Wvalue-discard`, `-Wdead-code`, `-Wnumeric-widen`, `-Xlint:*`, `-deprecation`, `-feature`.
- https://scala-lang.org/blog/2023/05/30/scala-3.3.0-released.html — `-Wunused:*` and `-Wvalue-discard` arrive in Scala 3.3; the `map` vs `flatMap` example; fewer-braces syntax stable.
- https://scala-lang.org/blog/2024/02/29/scala-3.4.0-and-3.3.3-released.html — 3.4 deprecates by default: `_` wildcard type, `private[this]`, `var x = _`, `with` as type operator, `xs: _*`, trailing `_` eta; `-rewrite -source 3.4-migration`.
- https://scala-lang.org/api/3.x/docs/error-codes/E176.html — E176 "unused value" is emitted only under `-Wnonunit-statement`.
- https://docs.scala-lang.org/overviews/core/futures.html — global EC and `blocking`, dedicated EC for blocking work, `Await` "strongly discouraged", callback order undefined, fatal exceptions never complete a Future, `recover`/`recoverWith`.
- https://docs.scala-lang.org/overviews/collections-2.13/performance-characteristics.html — the complexity table (List `apply` L, Vector eC, etc.).
- https://docs.scala-lang.org/overviews/collections-2.13/concrete-immutable-collection-classes.html — Vector is the default `IndexedSeq`; ListMap "little reason to use".
- https://docs.scala-lang.org/overviews/collections-2.13/collections-migration-213.html — `scala.Seq` is `immutable.Seq`; varargs are immutable; `Stream` → `LazyList`; `Traversable` gone.
- https://docs.scala-lang.org/overviews/collections-2.13/conversions-between-java-and-scala-collections.html — `scala.jdk.CollectionConverters`, wrappers not copies, immutable Scala → Java wrapper throws on mutation.
- https://docs.scala-lang.org/overviews/core/binary-compatibility-for-library-authors.html — binary compatibility definition, default arguments and case classes break it, private constructor/`withXxx` pattern, MiMa "strongly" encouraged.
- https://docs.scala-lang.org/tutorials/FAQ/initialization-order.html — overridden `val` read in a superclass constructor is null; fixes: parameters, `lazy val`, nested object; `-Wsafe-init`.
- https://docs.scala-lang.org/tour/pattern-matching.html — sealed types give exhaustiveness warnings; subtypes must be in the same file.
- https://docs.scala-lang.org/scala3/book/string-interpolation.html — `s`, `f` (type-checked at compile time), `raw`.
- https://www.scala-lang.org/files/archive/spec/3.4/12-the-scala-standard-library.html — `==` is null-safe then `equals`; `eq`/`ne` are reference equality; numeric `==` converts to the wider operation type.
- https://www.scala-lang.org/api/current/scala/util/Try.html — `Try` catches only non-fatal exceptions; `get` rethrows; `foreach`/`getOrElse` may throw.
- https://www.scala-lang.org/api/current/scala/collection/Iterator.html — "one should never use an iterator after calling a method on it"; list of consuming methods.

### Community style guides
- https://twitter.github.io/effectivescala/ — Effective Scala: visibility minimal, `private[this]`, immutable collections, `Seq[T]` in signatures, `Option` not null, `NonFatal`, `return` in closures, by-name only for control-construct DSLs, `@tailrec`, `require`/`assert`, constructor DI.
- https://github.com/databricks/scala-style-guide/blob/master/README.md — Databricks: always `override`, no `apply` on classes, no call-by-name, no symbolic methods, no `return` in closures, `Option(...)` for nullable, no `Option.get`, no `Try` in APIs (disputed), `NonFatal`, `private[this]` for synchronized state, `intercept` the most specific exception, Java interop list.

### Lint tooling
- https://scalacenter.github.io/scalafix/docs/rules/overview.html — built-in rules: ExplicitResultTypes, NoAutoTupling, OrganizeImports, RemoveUnused, DisableSyntax, LeakingImplicitClassVal, NoValInForComprehension, ProcedureSyntax, RedundantSyntax.
- https://scalacenter.github.io/scalafix/docs/rules/community-rules.html — scalafix-unified, scaluzzi, scalafix-named-params, xuwei-k rules, scala-rewrites, typelevel-scalafix.
- https://www.wartremover.org/doc/warts.html — the 37 warts and the 17 in `Warts.unsafe` (Any, AsInstanceOf, DefaultArguments, EitherProjectionPartial, IsInstanceOf, IterableOps, NonUnitStatements, Null, OptionPartial, Product, Return, Serializable, StringPlusAny, Throw, TripleQuestionMark, TryPartial, Var).
- https://scalameta.org/scalafmt/docs/configuration.html — `runner.dialect` (scala3, scala213source3) must be set; align presets; maxColumn. (Rewrite-rule section was truncated by the fetch tool; see section 6.)

### Pekko (Akka-compatible actor model)
- https://pekko.apache.org/docs/pekko/current/general/jmm.html — happens-before for actors and Futures; "Very bad: shared mutable state"; use `context.ask`/`pipeToSelf`; immutable messages.
- https://pekko.apache.org/docs/pekko/current/typed/style-guide.html — messages in the companion, `sealed trait Command`, total functions with `Behaviors.unhandled`, private internal messages, `replyTo`, past-tense events, factory `apply`, `ask` over `?`, `ActorContext.ask` inside actors.
- https://pekko.apache.org/docs/pekko/current/typed/dispatchers.html — blocking starves the default dispatcher; dedicated `thread-pool-executor` dispatcher; `DispatcherSelector.blocking()`.
- https://pekko.apache.org/docs/pekko/current/typed/interaction-patterns.html — ask needs a timeout; mapping closes over immutable values only; `pipeToSelf`, never touch state in `onComplete`; per-session child leaks; `ctx.self` to a sharded actor can dead-letter; fixed-delay over fixed-rate timers.
- https://pekko.apache.org/docs/pekko/current/serialization.html — Java serialization off by default and "highly discouraged"; marker-trait bindings; `serialize-messages = on` in tests; `SerializerWithStringManifest` for evolving data.
- https://pekko.apache.org/docs/pekko/current/typed/fault-tolerance.html — Typed stops on failure by default; `Behaviors.supervise`; validation errors belong in the protocol, failures crash; `PreRestart`; `ChildFailed`/`DeathPactException`.
- https://pekko.apache.org/docs/pekko/current/typed/persistence.html — event handler "must only update the state and never perform side effects"; `thenRun`; `withEnforcedReplies`; `StatusReply`; all state in `State`.
- https://pekko.apache.org/docs/pekko/current/typed/testing-async.html — `ActorTestKit` shutdown in `afterAll`, `TestProbe`, `ManualTime` for timer tests, `LoggingTestKit`, `LogCapturing`, `application-test.conf`.
- https://pekko.apache.org/docs/pekko/current/actors.html — classic: messages immutable (convention), messages in companion, ask needs `Timeout` and a reply, callbacks must not touch the enclosing actor.

### Testing and build
- https://scalameta.org/munit/docs/assertions.html — `assertEquals(obtained, expected)`; `Compare` type class rejects unrelated types; `assertNoDiff`; `intercept`; `clue`; `compileErrors` needs `-pagewidth` on Scala 3.
- https://scalameta.org/munit/docs/tests.html — async tests return `Future`; `munitTimeout` 30s default; `.flaky` and `MUNIT_FLAKY_OK`; "avoid slow operations when using test helpers".
- https://scalameta.org/munit/docs/fixtures.html — `FunFixture` teardown always runs; suite fixtures in `beforeAll`/`afterAll`; never open resources in the suite constructor.
- https://www.scalatest.org/scaladoc/3.2.19/org/scalatest/concurrent/Eventually.html — default patience 150 ms / 15 ms; `IntegrationPatience`; intended for integration tests, not unit tests.
- https://github.com/testcontainers/testcontainers-scala/blob/master/docs/src/main/tut/usage.md — `TestContainerForAll` vs `ForEach`; munit traits must call `super.beforeAll`/`afterAll` or containers never start/stop.
- https://www.scala-sbt.org/1.x/docs/Library-Dependencies.html — `% Test`, `%%`, Ivy/Maven ranges make "the effective version keeps changing".
- https://www.scala-sbt.org/1.x/docs/Cross-Build.html — `ThisBuild / scalaVersion`, `crossScalaVersions := Nil` on the aggregate, `CrossVersion.for3Use2_13` and its classpath-duplication hazard.
- https://github.com/lightbend-labs/mima/blob/main/README.md — `mimaPreviousArtifacts`, `mimaReportBinaryIssues` fails on problems and on an empty previous set, `ProblemFilters`, `mimaCheckDirection`.
- https://github.com/plokhotnyuk/jsoniter-scala/blob/master/README.md — derive one codec per top-level type with `JsonCodecMaker.make`; avoid `implicit def`/`inline given` (silent duplicate codecs); ADTs in an outer object; `null` serialization throws; fields with defaults are optional; one jsoniter version on the classpath.
- https://circe.io/circe/codecs/semiauto-derivation.html — `deriveDecoder`/`deriveEncoder` as `implicit val`s; `@JsonCodec`.

Fetched but contributed nothing usable (redirects or truncated): `docs.scala-lang.org/scala3/book/ca-given-using-clauses.html`, `.../migration/options-new.html`, `.../migration/options-lookup.html`, `.../overviews/core/string-interpolation.html` (all redirects, followed), `docs.scala-lang.org/tutorials/FAQ/index.html` (index only), `scala-lang.org/api/current/scala/concurrent/Future$.html` (navigation only), `pekko.apache.org/docs/pekko/current/futures.html` (only `after`/`retry`), `github.com/testcontainers/testcontainers-scala/blob/master/README.md` (landing page), `www.scala-lang.org/api/current/scala/util/Try.html` first half (navigation).

## 2. Detecting the language and dialect

### Files
- Sources: `*.scala`, `*.sc` (scripts/worksheets), `*.sbt` (build definition, Scala syntax). Generated ScalaPB code also ends in `.scala`; do not review it (look for `// Generated by the Scala Plugin for the Protocol Buffer Compiler` or a `target/` or `src_managed/` path).
- Build: `build.sbt`, `project/*.scala` (e.g. `project/Dependencies.scala`), `project/build.properties` (sbt version), `project/plugins.sbt` (scalafmt, scalafix, MiMa, sbt-native-packager, sbt-dynver). Mill uses `build.sc`/`build.mill`; Maven uses `pom.xml` with `scala-maven-plugin`; Gradle uses the `scala` plugin.
- Formatting: `.scalafmt.conf` (`version`, `runner.dialect`, `rewrite.*`, `maxColumn`, `align.preset`). Linting: `.scalafix.conf`. Git hooks: `.githooks/` (this family runs a scalafmt pre-commit hook).

### Scala 3 vs Scala 2 in a diff
Read `scalaVersion` in `build.sbt` (`ThisBuild / scalaVersion := "3.x.y"`) and `runner.dialect` in `.scalafmt.conf` (`scala3` vs `scala213` / `scala213source3`) first. Syntax markers that prove Scala 3:
- `enum`, `given`, `using`, `extension`, `export`, `opaque type`, `inline def`, `transparent inline`, `derives`, `then`/`do` after `if`/`while`/`for`, `end <name>` markers, colon-template bodies (`object Foo:`), `?` as a wildcard type (`List[?]`), `xs*` splice, `import a.*`, `import a.b as c`, `A | B` and `A & B` types, `@main`, top-level `def`/`val` without an enclosing object, `-source` language imports (`import scala.language.future`), `summon`, `scala.util.boundary`.
Markers that prove Scala 2 (or Scala 3 code that has not been migrated):
- `implicit val/def/class`, `import a._`, `import a.{b => c}`, `xs: _*`, `List[_]`, `package object`, `private[this]`, `def f() { }` procedure syntax, `'symbol` literals, `do { } while`, `with` as a type operator (`A with B`), `Enumeration`, `existential forSome`, `@specialized`, `-Xsource:3` in scalacOptions (2.13 adopting Scala 3 syntax piecemeal).
Both Scala 2.13 and 3 share: `scala.jdk.CollectionConverters`, `LazyList`, `immutable.Seq` as the default `Seq`, `-Wunused`, `-Werror`, `-Wvalue-discard`.

### Tooling to glance at before reviewing (so you do not duplicate it)
- `scalacOptions` in `build.sbt`: look for `-Werror` (`-Xfatal-warnings`), `-Wunused:all` (or sub-options), `-Wvalue-discard`, `-Wnonunit-statement`, `-Wsafe-init`, `-deprecation`, `-feature`, `-source:future`/`-source:3.x`, `-language:strictEquality`, `-Yexplicit-nulls`, `-no-indent`/`-new-syntax`/`-old-syntax`, `-Xlint` (2.13). What is on here is enforced; see section 5.
- `.scalafmt.conf`: whether `rewrite.rules` includes `RedundantBraces`, `RedundantParens`, `SortModifiers`, `Imports`, `AvoidInfix`, `PreferCurlyFors`; whether `rewrite.scala3.convertToNewSyntax` / `removeOptionalBraces` / `insertEndMarkerMinLines` are set. If so, brace style, control syntax and end markers are the formatter's business, not the reviewer's. Note `SortModifiers` is deliberately absent in this family (it churned 99 declarations). [scalafmt]
- `.scalafix.conf` and `project/plugins.sbt`: `ExplicitResultTypes`, `RemoveUnused`, `OrganizeImports`, `DisableSyntax` (which can forbid `var`, `null`, `return`, `throw`, `asInstanceOf`, XML literals, etc.) and any WartRemover `wartremoverErrors` list. [scalafix overview]
- Pekko config under `src/test/resources`: `pekko.actor.serialize-messages = on` means message serializability is already checked by tests. [Pekko serialization]

## 3. Checklist

Format: **Rule.** Signal in the diff. Why. [source]. Tags: `(Scala 3)` applies only when the build is Scala 3; `(Scala 2)` applies only to 2.13 code; untagged applies to both.

### 3.1 Idioms and Scala 3 syntax

- **No Scala 2 implicit machinery in new Scala 3 code.** `implicit val`, `implicit def`, `implicit object`, `implicit class` in a file whose build is Scala 3. Replace with `given`, `using`, `extension`, `Conversion`. `implicit` still compiles, but the two systems side by side double the vocabulary and the deprecation is announced as pending. (Scala 3) [relationship-implicits]
- **No procedure syntax.** `def foo() {` or `def foo(args) {` with no `=`. Dropped in Scala 3 (a migration-mode error) and a 2.13 deprecation; write `: Unit =`. [procedure-syntax, incompat-syntactic, style/declarations]
- **Wildcards: `?` for types, `*` for imports and splices, `as` for renames.** Grep `[_]`, `[_ <:`, `import .*\._$`, `=> ` inside `import {}` braces, `: _\*`. All four are deprecated by default since 3.4 and errors under `-source future`. (Scala 3) [wildcards, imports, vararg-splices, 3.4.0 blog]
- **`private[this]` and `var x = _` are deprecated.** Replace with `private` (the compiler infers object-privacy) and `scala.compiletime.uninitialized`. (Scala 3) [this-qualifier, 3.4.0 blog]
- **`with` is for mixins, `&` is for types.** `val x: A with B` in a Scala 3 type position. Deprecated since 3.4. (Scala 3) [3.4.0 blog]
- **No `Enumeration` in new code.** `extends Enumeration`, `Value`. Reflection-based, weak exhaustiveness, no parameters; Scala 3 `enum` compiles to a sealed class with `values`/`valueOf`/`fromOrdinal`. Scala 2: `sealed abstract class` + `case object`s. [enums, wartremover Enumeration]
- **Enum and sealed-trait matches should be total.** A `match` on an `enum`/sealed type with `case _ =>` that hides a missing case. The compiler only warns on missing cases when there is no catch-all; a wildcard removes the safety the sealing bought. Also remember enum case applications widen to the enum type (`Some(1): Option[Int]`), so `val s: Some[Int] = Some(1)` needs an ascription. [tour/pattern-matching, enums/adts]
- **`given` instances: one canonical value per type, named in public APIs.** Anonymous `given Ord[Int] with ...` in a published library. The synthesized name (`given_Ord_Int`) is part of the binary API and can clash; the reference advises named givens for binary compatibility. Use a given only where there is "a single canonical value for a particular type"; otherwise pass explicitly. (Scala 3) [givens, ca-context-parameters]
- **Context parameter defaults that shadow a given.** A `using` parameter with a default value when a given of that type is in scope: the compiler warns, and which one wins depends on whether the call passed `using` explicitly. Prefer no default. (Scala 3) [using-clauses]
- **Implicit conversions are a design smell; `Conversion` needs the language import.** `given Conversion[A, B]` or `implicit def a2b` without `import scala.language.implicitConversions`. The compiler warns on the definition; both style guides say convert explicitly. Implicit views (`implicit ev: A => B` used for `.member`) and view bounds `<%` no longer work in Scala 3. [implicit-conversions, incompat-contextual-abstractions, Effective Scala, Databricks]
- **Extension methods: in the companion if you own the type, imported explicitly otherwise.** `extension (x: Foo)` in a random `Utils` object when `Foo` is in the same codebase. Members of the companion are found without an import; anything else forces every call site to import. (Scala 3) [domain-modeling-fp, ca-extension-methods]
- **Opaque types: factory and operations inside the defining object; no leaks.** `opaque type Id = String` with a public `def unwrap(id: Id): String` nowhere, or arithmetic assumed outside the scope. The equality to the underlying type is visible only inside the defining scope; give it an `apply` and the needed `extension`s there. (Scala 3) [types-opaque-types]
- **Union types must be ascribed.** `val x = if c then a else b` where a union is expected: the compiler infers a common supertype (often `Object`/`Matchable`), never `A | B`. Annotate. Prefer a sealed `enum` when the alternatives are a closed, named set; a union for ad hoc, unplanned alternatives. (Scala 3) [types-union]
- **`inline` only when the call site must be specialised.** `inline def` on ordinary helpers. Inlining is guaranteed and per call site, the method becomes effectively final, `inline val` must be a constant, and `inline if/match` fail to compile when the scrutinee is not statically known. `transparent inline` leaks a more precise result type into every caller's binary (3.4 notes it as a TASTy-breaking change). (Scala 3) [inline, 3.4.0 blog]
- **Match types: keep them reducible.** A match type whose patterns are not provably disjoint, has no upper bound on a recursive call, or relies on case order. Non-disjoint cases get "stuck" and surface as baffling errors at use sites; recursion hits the compiler's limit. Reach for them only for dependently typed return types and tuple computations. (Scala 3) [match-types]
- **`export` builds a facade; do not expect to override it.** `export inner.*` followed by an attempt to override one of the aliases, or a wildcard export dragging in names that clash. Export aliases are final forwarders; wildcard exports pull in everything eligible, so prefer explicit selectors or `as _` exclusions. (Scala 3) [export]
- **Indentation style: one per file, end markers on long regions.** A diff mixing braces and indentation in the same scope, or a 40-line `object` with no `end` marker. The reference recommends `end` where a construct has blank lines, runs 15–20+ lines, or ends at 4+ indentation levels, and markers only on the outermost region. Skip this if `.scalafmt.conf` sets `rewrite.scala3.*`. (Scala 3) [indentation]
- **Control syntax: `then`/`do` go with brace-free conditions, not with parentheses.** `if (x) then y` or `while (c) do`. Pick the new or the old form; `-rewrite -new-syntax` exists to make it mechanical. Skip when scalafmt's `convertToNewSyntax` is on. (Scala 3) [control-syntax, control-structures]
- **Fewer braces for lambdas: fine, but not for one-liners.** `xs.map: x =>` on a single-expression body that would fit on one line. The 3.3 syntax is for multi-line blocks (`test("..."):`). [3.3.0 blog, indentation]
- **Refutable patterns in `val` and `for` need `case` or `@unchecked`.** `val Some(x) = opt`, `val h :: t = xs`, `for (a, b) <- pairs`. Warns since 3.2 (the generator no longer filters silently); write `for case (a, b) <- pairs` when filtering is intended, or a `match`. (Scala 3; Scala 2 skips silently) [pattern-bindings, 3.4.0 blog]
- **No `return` inside a lambda or `for` body.** `return` in a `foreach`/`map`/`for` block. Compiles to a thrown `NonLocalReturnControl`, deprecated since 3.2, catchable by a careless `catch`, and can be thrown on another thread. Use `boundary`/`break` or restructure. [nonlocal-returns, Effective Scala, Databricks]
- **Symbolic method names need a reason and a `@targetName`.** New `def ++=`, `def !`, `def >>`. "Avoid!" outside DSLs and maths; if kept, add `@targetName("append")` so Java callers and stack traces get a readable name. [style/naming, targetName, Databricks]
- **Package objects are on the way out; top-level definitions replace them.** New `package object foo`. Scala 3 wraps top-level definitions in `foo$package`; note renaming the file changes that synthetic name and so the binary API. (Scala 3) [package-objects]
- **No `new` needed, but an explicit `apply` wins.** Reviewer note, not a rule: `Foo(x)` on a plain class is a constructor proxy; adding a companion `apply` later silently replaces the proxies. (Scala 3) [creator-applications]
- **Parentheses mark side effects.** `def size() = …` on a pure accessor, or `def close = …` on a side-effecting method; call sites must match the declaration (Scala 3 errors on omitted parentheses; auto-application is gone). [style/naming, style/method-invocation, incompat-dropped-features]
- **Infix only for operators and pure methods; dot notation for higher-order calls.** `javaList add item`, `xs map (_.f)`, `(0 /: xs)(_ + _)`. Infix on side-effecting methods misleads; `/:` and `:\` are deprecated spellings of `foldLeft`/`foldRight`. Skip if scalafmt `AvoidInfix` is on. [style/method-invocation]
- **Dropped Scala 2 features.** `'sym`, `do { } while (c)`, `x _` on a value, `obj + "str"` via `any2stringadd`, early initializers `extends { val x = 1 } with T`, existentials `forSome`, `@specialized`, `[_: TC]` as a type parameter name. Each has a Scala 3 replacement (`Symbol("sym")`/a string, `while { …; c } do ()`, `() => x`, `String.valueOf`, trait parameters, `?`/abstract type members, `inline`, a named parameter). (Scala 3) [incompat-dropped-features, incompat-syntactic, trait-parameters]
- **Universal apply/`Foo` vs `new Foo` and `open`.** `class Foo` (no modifier) extended from another file. Scala 3 treats extension as a designed feature: mark the class `open` or `final`; ad hoc extension warns from 3.4 (`-language:adhocExtensions` to allow, e.g. for test mocks). (Scala 3) [open-classes]

### 3.2 Types, nulls and totality

- **Public members have explicit result types.** `def` / `val` without a type on a public or `protected` member, or on any `given`/`implicit`. Prevents inferred refinement types, structural leaks and accidental binary changes; implicit definitions in Scala 3 *require* the type. [style/types, Effective Scala, Databricks, incompat-contextual-abstractions, scalafix ExplicitResultTypes]
- **No `Option.get`, `Try.get`, `Either.left/right.get`, `.head`, `.last`, `.reduce`, `.max` on possibly-empty collections.** Grep `\.get\b` not preceded by `Map`/`getOrElse`, `\.head\b`, `\.last\b`, `\.reduce(`. Each throws on the empty case; use `fold`, `getOrElse`, `headOption`, `reduceOption`, `maxOption`, or a `match`. [wartremover OptionPartial/TryPartial/EitherProjectionPartial/IterableOps, Try scaladoc, Databricks, Effective Scala]
- **No `null` in Scala code; wrap Java nulls at the boundary.** `null` literal, `== null`, or a Java call result used unchecked. Use `Option(javaValue)` (never `Some(javaValue)`), or under `-Yexplicit-nulls` the type is `T | Null` and `.nn` is an explicit, auditable assertion. [wartremover Null, Databricks, Effective Scala, explicit-nulls]
- **No `asInstanceOf`/`isInstanceOf`; pattern match instead.** Casts bypass the type checker and `isInstanceOf` is erased for generics (`x.isInstanceOf[List[String]]` checks only `List`). A `case x: T =>` match at least warns on unchecked erasure. [wartremover AsInstanceOf/IsInstanceOf]
- **Inferred `Any`, `AnyVal`, `Product`, `Serializable`, `Nothing`.** A `Seq(1, "a")`, an `if` whose branches disagree, a `Map` of mixed values. These almost always signal a lost type; ascribe the intended type or fix the branches. Scala 2 has `-Xlint:infer-any`; Scala 3 has no flag, so review for it. [wartremover Any/AnyVal/Product/Serializable/Nothing, compiler-options]
- **Sealed hierarchies and enums for anything matched on; leaves `final`.** A new `trait Command` without `sealed`; a `case class` extending a sealed trait without `final`. Only a sealed parent gives exhaustiveness warnings; a non-final leaf can be subclassed and breaks `equals`/`copy` assumptions. [tour/pattern-matching, wartremover LeakingSealed/FinalCaseClass, Pekko style guide]
- **`==` between unrelated types is a silent `false`.** `id == idString`, `optValue == value`, `someEnum == "name"`. Universal equality compiles anything. Enable `-language:strictEquality` and `derives CanEqual` where the codebase allows; otherwise review for mixed-type comparisons. [multiversal-equality, wartremover Equals]
- **`==` on `Array` compares references.** `arr1 == arr2`, an `Array` field inside a `case class` (its generated `equals`/`hashCode` use the array's identity). Use `sameElements`, or `ArraySeq`/`IArray`/`Vector` in case classes. [wartremover ArrayEquals; case-class consequence is reviewer experience]
- **`eq`/`ne` only for identity checks on `AnyRef`.** `x eq y` for value comparison, or `eq` on a boxed numeric. `==` is null-safe and delegates to `equals`; `eq` is reference equality. [spec §12]
- **Numeric `==` widens; `Double` equality is fragile.** `1 == 1.0` is true (compared as `Double`); `0.1 + 0.2 == 0.3` is false; `Double.NaN == Double.NaN` is false by IEEE 754 while `java.lang.Double.equals` and `Set`/`Map` keys treat NaN as equal. Compare with a tolerance; never key a map on a `Double`. [spec §12 for widening; NaN behaviour is JVM semantics, reviewer experience]
- **Overridden or abstract `val` read during construction is null.** A trait or superclass body (or a `val` initialiser) that uses an abstract `val`, or a subclass overriding a `val` the parent uses. Fix with constructor/trait parameters, `lazy val`, or a `def`; `-Wsafe-init` catches many of these. [FAQ initialization-order, safe-initialization, trait-parameters]
- **`lazy val` cycles and lock-order.** Two `lazy val`s (possibly in different objects) that reference each other, or a `lazy val` initialised while holding another lock. Lazy vals are thread-safe by taking a lock, so cycles stack-overflow and cross-object cycles can deadlock. [FAQ initialization-order]
- **`var` in a `case class` or an `enum` case parameter.** `case class Foo(var x: Int)`. Case class `hashCode` is derived from fields; mutating one after insertion into a `Set`/`Map` corrupts the structure. [Databricks, wartremover Var]
- **A `val` that lists functions defined below it is a list of nulls.** `val handlers = List(a, b)` above `val a = …`. Strict initialisation order. [FAQ initialization-order; family trap]
- **No `???` or `TODO` stubs merged.** `???` compiles to `NotImplementedError` at runtime. [wartremover TripleQuestionMark]
- **`Unit`-returning methods that compute a value; non-Unit expressions in statement position.** `def f(): Unit = xs.map(g)` (the map result is discarded; often `foreach` or `flatMap` was meant); `Right(()).map(_ => failure)`. `-Wvalue-discard` and `-Wnonunit-statement` catch these; if the build lacks them, review for them. [3.3.0 blog, E176, wartremover NonUnitStatements]

### 3.3 Collections and performance

- **`Seq` means `immutable.Seq` since 2.13; its default is `List`; `IndexedSeq`'s is `Vector`.** `Seq(...)` where random access (`apply(i)`), `length` or `append` is in a loop: `List` is linear for all three. Use `Vector` (effectively constant for head/tail/apply/update/append/prepend) or `ArraySeq`. Accept `Seq[T]`/`Iterable[T]` in signatures; return the concrete type you built only if callers need it. [collections-migration-213, performance-characteristics, concrete-immutable-collection-classes, Effective Scala]
- **Appending to a `List` in a loop; prepending to a `Vector` is fine.** `acc :+ x` on a `List` (O(n) each, O(n²) total), `xs.length` inside a loop over a `List`, `xs(i)` indexing a `List`. Build with `ListBuffer`/`Vector`/prepend-then-reverse. [performance-characteristics]
- **`ListMap` has no good use; `TreeMap` only when ordering is needed.** `ListMap` is linear in everything; the default `HashMap` is effectively constant. [concrete-immutable-collection-classes, performance-characteristics]
- **Immutable collections by default; `mutable` named at the use site.** `import scala.collection.mutable.Map` then `Map` unqualified. Write `mutable.Map` so the mutability is visible, and keep mutable collections local to one method or one actor. [Effective Scala, wartremover MutableDataStructures]
- **An `Iterator` is consumed once.** `it.size` followed by `it.map`, `it.toList` then `it.foreach`, passing an `Iterator` to two functions. "One should never use an iterator after calling a method on it"; convert to a strict collection or `duplicate`. [Iterator scaladoc]
- **`LazyList` for laziness, never `Stream`.** `Stream` is deprecated. A `LazyList` kept in a `val` memoises everything it has produced (memory); `Iterator` for one-pass. [collections-migration-213]
- **Varargs take an immutable `Seq`; splice with `xs*`.** Passing an `Array` or `mutable.Buffer` to `f(xs: _*)`: 2.13+ requires `immutable.Seq`, so callers need `.toSeq` or `ArraySeq.unsafeWrapArray`. [collections-migration-213, vararg-splices]
- **`Option` is not a collection to iterate over implicitly.** `for (x <- opt; y <- list)` (mixed generator types) and `list.flatMap(f)` where `f` returns `Option` lean on `Option2Iterable`; both can hide an intent bug. Convert explicitly (`opt.toList`) or restructure. [wartremover Option2Iterable, style/control-structures]
- **Hot loops: `while` and arrays beat `for` and boxing, but only after profiling.** A `for` comprehension or `zipWithIndex` in a tight inner loop of a measured hot path. Both guides say profile first (jmh), then specialise. Do not demand this without evidence. [Databricks, Effective Scala]
- **`private[this]` / object-private fields for hot paths and for `synchronized` state.** Scala 3 infers object-privacy for `private` fields only reached through `this`, so the guides' `private[this]` advice reduces to: do not reach into another instance's `private` field, especially one guarded by `synchronized` (the lock is per instance). [Databricks, Effective Scala, this-qualifier]
- **Java collections cross the boundary once, through `scala.jdk.CollectionConverters`.** `asScala`/`asJava` on every access, or `JavaConversions`/`JavaConverters` imports. The converters are wrappers (no copy; mutations show through; an immutable Scala collection's Java view throws on mutation). Convert at the edge and keep the Scala type inside. [conversions-between-java-and-scala-collections, Effective Scala, wartremover JavaConversions]
- **String building: interpolation over `+`; `f` when a format matters.** `"a" + x + "b"` chains and `x + ""` to stringify (`StringPlusAny`). `s"..."` reads better; `f"$x%.2f"` is type-checked at compile time; `raw` for regexes. [string-interpolation, wartremover StringPlusAny, incompat-dropped-features any2stringadd]
- **`toString` of a function or an object with no override.** Logging `handler.toString` prints `Lambda$123/0x…`; an object's default `toString` embeds the class name and changes on rename. [wartremover ToString]

### 3.4 Error handling

- **Expected failures in the type; exceptions for the unexpected.** A public API that throws for a case the caller must handle (`NotFound`, validation). Return `Option`, `Either[Error, A]`, or a sealed result type; "Don't use exceptions for common errors that callers must handle." Databricks disagrees on `Try` in APIs (see section 6), but agrees that throwing for *abnormal* conditions is fine. [Effective Scala, Databricks, wartremover Throw]
- **Never catch `Throwable`, `Exception` or `case _ =>` blindly; use `NonFatal`.** `catch { case e: Throwable => }`, `case _: Exception =>`, `case e =>`. Fatal errors (`OutOfMemoryError`, `InterruptedException`, `ControlThrowable`) must propagate; `Try` and `Future` already catch only `NonFatal`. Handle `InterruptedException` deliberately when a thread may be interrupted. [Effective Scala, Databricks, futures overview, Try scaladoc]
- **A `Try` or `Future` body must not escape through `get`/`Await`.** `.get` on a `Try` rethrows the stored exception; `getOrElse(default)` throws if the default throws; `foreach` rethrows what the function throws. [Try scaladoc]
- **Failed `Future`s are returned, not thrown.** `def f(): Future[A] = { if (bad) throw …; Future(...) }`. The throw happens synchronously in the caller, outside the Future's error channel; return `Future.failed`. [Effective Scala]
- **`Either`/`Try` used as control flow should be exhaustive and typed.** `.left.get`, `.right.get`, `.toOption` that drops the error silently, `Either[Throwable, A]` where a sealed error type would carry meaning. [wartremover EitherProjectionPartial]
- **`require` for preconditions, `assert` for invariants, neither for user input.** `require` throws `IllegalArgumentException` on a caller's contract violation; `assert` on an internal invariant. Validation of external input belongs in the result type (or the actor protocol, see 3.5). [Effective Scala, Pekko fault-tolerance]
- **`None` is not an error.** Returning `None` from a function that can fail for several reasons loses the reason; use `Either` or a sealed outcome. [Databricks]
- **`recover` only on the failures you mean.** `.recover { case _ => default }` swallows every non-fatal failure including bugs. Match the specific exception types; `recoverWith` for an alternative Future. [futures overview]
- **Exceptions crossing an actor boundary are not replies.** Throwing in a handler stops the actor (Typed default) and the asker only sees a timeout; model validation errors in the protocol (`StatusReply`). [Pekko fault-tolerance, interaction-patterns, persistence]
- **Resource handling: `Using` or `try/finally`, and close on every path.** A `new FileInputStream`/`Connection` without `Using(...)`/`finally`. [reviewer experience; `scala.util.Using` is in the stdlib]

### 3.5 Concurrency, Futures and actors

- **Every `Future` operation names its `ExecutionContext`, and the global one is a conscious choice.** `import scala.concurrent.ExecutionContext.Implicits.global` in library or service code. The global pool is a `ForkJoinPool`; blocking inside it needs `blocking { }` and still may not spawn threads. In Pekko code use `system.executionContext`/`context.executionContext`; in this family's runtime, the designated virtual-thread executor. [futures overview, Pekko testing-async example `system.executionContext`]
- **No blocking inside a `Future`, an actor, or a callback on a shared dispatcher.** `Thread.sleep`, JDBC, `Await`, synchronous HTTP inside `Future { }`, inside `receive`/`Behaviors.receive`, or in `onComplete`. It starves the dispatcher for every other actor. Move to a dedicated `thread-pool-executor` dispatcher (`DispatcherSelector.blocking()`), bound the concurrency, or use a non-blocking client. (Virtual threads change this calculus: an await that parks a virtual thread is cheap, so a repo on virtual threads may deliberately allow blocking `invoke` in endpoints and steps; read its rules.) [Pekko dispatchers, futures overview]
- **`Await.result` only at the edges (main, tests, shutdown).** `Await.result(f, Duration.Inf)` in production code paths. "Strongly discouraged for the sake of performance and for the prevention of deadlocks". In tests prefer the framework's async support (munit returns `Future`). [futures overview, munit tests]
- **Callbacks must not close over mutable state; order is undefined.** `var total = 0; f1.foreach(total += _); f2.foreach(total += _)`. Callbacks may run concurrently and in any order; `+=` is not atomic. Fold the results (`Future.sequence`/`zip`/for-comprehension) instead. [futures overview, Pekko JMM]
- **An actor's state is touched only from its own message handler.** `Future { state = … }`, `future.onComplete { … context.self … state … }`, `ask(...).foreach { state = _ }`, `sender()` captured in a classic callback. "Very bad: shared mutable state will cause your application to break in weird ways." Use `context.pipeToSelf` / `context.ask` (Typed) or `pipeTo self` (classic) so the result arrives as a message. The `ask` mapping function may close only over immutable values captured at call time. [Pekko JMM, interaction-patterns, classic actors]
- **Messages, events and state are immutable case classes/enums; no `var`, no mutable collections, no `Array` in them.** A `case class Cmd(items: mutable.Buffer[...])` or `ListBuffer` field in persisted state. Scala cannot enforce immutability; the actor model's safety depends on it; a mutable message shared between two actors is a race. [Pekko JMM, classic actors]
- **Protocol shape: `sealed trait Command` in the companion, `replyTo: ActorRef[Reply]`, past-tense events, internal messages `private`.** Top-level message classes; a `receiveMessagePartial` that silently drops messages; `case _ =>` in a `Behaviors.receive`. Sealed commands plus total functions make an unhandled message a compiler warning, and `Behaviors.unhandled` an explicit choice. [Pekko style guide]
- **Every `ask` has a timeout and the receiver always replies.** `implicit val timeout: Timeout` missing, `system.ignoreRef` as `replyTo`, a handler path that neither replies nor fails (the asker waits for the timeout). On timeout the receiver may still process the message; choose timeouts with that and retries in mind. Inside an actor use `context.ask`, not `AskPattern` (which gives you a `Future` and reintroduces the thread-safety problem). [Pekko interaction-patterns, style guide, classic actors]
- **Supervision: validation errors in the protocol, failures crash; Typed stops by default.** `try/catch` inside a behaviour to "keep the actor alive", or a `Behaviors.supervise(...).onFailure[Exception](restart)` with no limit. Model rejections as replies (`StatusReply.error`), let real failures restart with fresh state, bound restarts with `withLimit`, create mutable state inside `Behaviors.setup` so a restart gets a fresh instance, and handle `PreRestart` *and* `PostStop` for cleanup. A parent that `watch`es without handling `Terminated` dies of `DeathPactException`. [Pekko fault-tolerance]
- **Per-session children and aggregators must stop themselves; schedule a timeout.** A spawned child with no `stop` path or no receive timeout. "It is easy to miss a case where the session actor is not stopped, causing a resource leak." [Pekko interaction-patterns]
- **Timers over `Thread.sleep` and ad hoc schedulers; fixed-delay over fixed-rate.** `system.scheduler.scheduleAtFixedRate` closing over actor state; `startTimerAtFixedRate` where a burst after a GC pause would hurt. Use `Behaviors.withTimers`; timers are cancelled on stop and restart and a stale tick is never delivered; "the page recommends fixed-delay when unsure". [Pekko interaction-patterns]
- **`ActorRef`/`context.self` in a message to a sharded entity can dead-letter.** `ctx.self` sent to a sharded actor that may passivate or move before replying; carry the `entityId` or an `EntityRef` instead. More generally an `ActorRef` escaping into a static or a long-lived collection outlives its actor. [Pekko interaction-patterns]
- **Event handlers are pure; side effects go in `thenRun`; all state in `State`.** Logging, publishing, a `var` outside the state class, or a call to another service inside an `EventSourcedBehavior` event handler. The handler is replayed on recovery, so a side effect there repeats; fields outside `State` are lost on restart. Use `withEnforcedReplies` so forgetting a reply is a compile error. [Pekko persistence]
- **Serialization: no Java serialization; a marker trait bound to a real serializer; tests with `serialize-messages = on`.** A new message/event type that does not extend the project's marker trait (`CborSerializable`/`JsonSerializable`/the project's own) or that contains a type the serializer cannot handle. Java serialization is off by default and "highly discouraged"; evolving data wants `SerializerWithStringManifest`. [Pekko serialization]
- **Shared mutable state outside actors: one mechanism, applied consistently.** A field guarded sometimes by `synchronized` and sometimes by `AtomicReference`; a `ConcurrentHashMap` mixed with `synchronized`; an iterator of a synchronized collection escaping the lock. Pick `java.util.concurrent` atomics for single variables, `ConcurrentHashMap` for maps, or `synchronized` with an object-private field, and never mix. [Databricks, Effective Scala]
- **Fatal errors never complete a Future.** A `Future` whose body may throw `Error`; the Future stays pending forever and the global EC just prints the trace. Convert with `NonFatal` and let fatal ones propagate. [futures overview]
- **Thread-locals do not cross a thread hop.** Request context, trace or caller stored in a `ThreadLocal` and read inside a `Future`/callback or another dispatcher. Virtual-thread-per-request designs make this sound only while work stays on that thread. [reviewer experience; this family's CLAUDE.md states it]

### 3.6 Serialization and interop

- **One codec per top-level type, in the companion, as a `given`/`implicit val`, never `implicit def` or `inline given`.** `implicit def codec[T]: JsonValueCodec[T] = JsonCodecMaker.make`, or `make` called inside a method body. jsoniter-scala warns the compiler may silently generate duplicate codecs; derivation cost and bytecode multiply per call. Circe semiauto follows the same `implicit val`-in-scope shape. A `derives` clause places the instance in the companion automatically. [jsoniter-scala README, circe semiauto, derivation]
- **Codecs for status-word enums live in the companion so every derivation site sees one.** A fieldless enum encoded by a codec defined at one call site: other sites derive the default `{"type":"Ready"}` shape and the wire disagrees. [jsoniter-scala README: ADTs in an outer object; family trap]
- **Know the codec's null/Option/default semantics before adding a field.** jsoniter: serializing `null` throws; a JSON `null` on an `Option` field reads as absent and applies the default; fields with constructor defaults are optional, others required; defaults and empty collections are omitted on write. An `Option` field whose default is not `None` cannot express "none". [jsoniter-scala README]
- **Persisted and wire types are a versioning boundary.** Renaming a case class field, reordering enum cases (`ordinal` changes), or changing a discriminator mapping on a type that is journaled or published. The event handler replays old JSON; plan schema evolution (string manifests, `@named`, additive fields with defaults). [Pekko serialization, Pekko persistence, jsoniter-scala README]
- **Recursive and generic codecs need care.** jsoniter supports only acyclic graphs by default; anonymous givens for generic types with concrete parameters can hit a duplicate-definition error on Scala 3 (name the given). [jsoniter-scala README]
- **Only one version of a serialization library on the classpath.** A transitive `jsoniter-scala-core` at a different version than the macros. [jsoniter-scala README]
- **Java interop: `Option(x)` at the boundary, `CollectionConverters` once, `@varargs` for Java callers, no traits with implementations in Java-facing APIs.** A Scala API meant for Java with default arguments, multiple parameter lists, implicits/`using`, or a `trait` with concrete methods. [Databricks, Effective Scala, conversions-between-java-and-scala-collections]
- **`Enum[T]` only when Java needs it.** `enum Color extends Enum[Color]` makes it a Java enum (`compareTo`, `name`), with Java's constraints. [enums]
- **Java `null`-returning APIs under explicit nulls.** A Java method's result used without a null check when `-Yexplicit-nulls` is on: the type is a flexible `T?`; a `.nn` is an auditable assertion, `unsafeNulls` is a hole. (Scala 3) [explicit-nulls]
- **`equals`/`hashCode` overrides keep the contract; case classes should not override one without the other.** A `case class` with a custom `equals` and no `hashCode`. [reviewer experience]

### 3.7 API design and encapsulation

- **Smallest visibility that works; `private[pkg]` over public.** New public members on an internal class. "Widening them later is easier than narrowing them." Prefer `private`, then `private[module]`, then `protected`. [Effective Scala]
- **Case classes are `final`; hierarchies `sealed`; classes meant for extension `open`.** A non-final `case class`, a `trait` extended across files that is matched on. Subclassing a case class breaks `equals`/`copy`; sealing gives exhaustiveness; `open` documents the extension contract. (Scala 3 for `open`) [wartremover FinalCaseClass/LeakingSealed, open-classes, tour/pattern-matching]
- **Factories on the companion; `apply` returns the companion's type; private constructor when invariants exist.** `class Foo { def apply(...) }` (apply on a class), a `case class` with a `require` that `copy` can violate. For invariants: `case class Money private (amount: Long)` with a validating `Money.apply`/`from` in the companion and, for binary stability, `withXxx` methods instead of a public `copy`. Scala 2 needs `-Xsource:3` for the private-constructor-hides-apply behaviour. [Databricks, binary-compatibility, creator-applications]
- **`copy` and `apply` on a case class with invariants are public by default.** A `case class` whose companion validates but whose generated `copy` does not. Either make the constructor private (which in Scala 3 also hides `copy`/`apply`) or drop the invariant into the type. (Scala 3 behaviour differs from 2 without `-Xsource:3`) [binary-compatibility]
- **No overloading with default arguments; prefer distinct names.** `def f(a: Int, b: Int = 0)` and `def f(a: String)`: Scala forbids defaults on more than one overload and the combination confuses resolution and Java callers; defaults also change the binary API when they change. [wartremover DefaultArguments/Overloading, binary-compatibility, Databricks]
- **Type class instances (givens) go in the companion of the type or the type class, nowhere else.** `given Ordering[Foo]` in a `Utils` object or imported ad hoc at several sites. Companions are in the implicit scope, so coherence is automatic; stray instances cause ambiguities or two different orderings for one type. Derived instances (`derives`) land in the companion by construction. [derivation, domain-modeling-fp, givens]
- **`Ordering` and `CanEqual` are explicit instances, not inherited `Comparable` surprises.** A `case class` used in `sorted` relying on an `Ordering` summoned from a different module; `derives CanEqual` for anything compared under `strictEquality`. [multiversal-equality, derivation]
- **`override` is always written, including for abstract members.** `def run(): Unit = …` implementing a trait method without `override`. The modifier makes a renamed parent method a compile error instead of a silently orphaned method. [Databricks]
- **By-name parameters only for control-construct DSLs, last position, obvious from the name.** `def log(msg: => String)` is fine; `def retry(times: Int, op: => T)` in first position or a by-name that is evaluated several times with side effects is not. Pass `() => T` when re-evaluation is the point. [Effective Scala, Databricks]
- **Multiple parameter lists need a reason** (a `using` clause, a trailing function for inference/`xs.map: x =>`, a DSL). Otherwise they complicate overloading and Java callers. [style/declarations, Databricks]
- **Constructor dependency injection; no cake pattern; traits short and orthogonal.** A new `trait FooComponent { self: BarComponent => }`. Pass dependencies through constructors and factories as functions. [Effective Scala]
- **Structural types only for reflection shorthand.** `{ def close(): Unit }` as a parameter type. Reflective dispatch at runtime; prefer a nominal trait. [style/types, Effective Scala]
- **Wire names and Scala names are separate.** Renaming a method must not change a protocol string, a journal manifest or a discriminator; a macro deriving the wire name from the Scala name defeats that. [family convention; jsoniter `@named` and Pekko manifests are the mechanisms]
- **Mutable collections as covariant type parameters are unsound.** `class Box[+A](var a: A)` or a mutable collection declared `+T`. Immutable collections covariant, mutable ones invariant. [Effective Scala]
- **Anonymous mixin instances must declare their type.** `def make() = new Service with Logging {}` infers a refinement type that leaks into the API. Annotate `: Service`. [Effective Scala]

### 3.8 Tests

- **`assertEquals(obtained, expected)` in munit; ScalaTest `shouldBe expected`.** Swapped arguments in munit produce a misleading diff (`- expected / + obtained` reversed). munit's `Compare` type class rejects unrelated types at compile time; an `assertEquals[Any, Any]` cast is a smell. [munit assertions]
- **`intercept` the most specific exception.** `intercept[Exception]` or `intercept[Throwable]` passes on a setup bug. If the type cannot be named, the code under test should throw a more specific one. [Databricks, munit assertions]
- **Could this test pass while the thing it checks is false?** An `eventually` whose assertion is outside the retry; a filter/glob that matches zero tests and reports green (munit's `--` filter needs a leading wildcard); a smoke test that accepts a 404; an `assert(true)` after a side effect. Break the behaviour once and watch it fail. [family rule; eventually scaladoc for the retry semantics]
- **`eventually` is for integration tests; retry on the value, assert the identity.** `eventually { assert(db.read().nonEmpty) }; assert(db.read().head == x)` (second read is stale/different). Default patience 150 ms / 15 ms is too short for I/O; mix in `IntegrationPatience` or pass `timeout(...)`; do not use `eventually` to paper over a unit test's nondeterminism. [ScalaTest Eventually]
- **No `Thread.sleep` or wall-clock dependence in tests; use the framework's clock.** `Thread.sleep(500)` before an assertion; a timer actor tested with real time. Pekko `ManualTime.timePasses` makes timer tests deterministic and fast; `expectNoMessageFor` asserts silence in fake time. [Pekko testing-async, munit tests]
- **Async tests return the `Future`; no `Await` inside a test body.** munit converts a returned `Future` and applies `munitTimeout` (30 s default). [munit tests]
- **Fixtures: resources in `beforeAll`/`FunFixture`, never in the suite constructor; `super.beforeAll()` when overriding.** A `val db = connect()` in the class body (the class is instantiated by IDE discovery and `afterAll` may never run); a testcontainers munit suite overriding `beforeAll` without calling `super` (containers never start). `FunFixture` teardown "always gets called, even if test failed". [munit fixtures, testcontainers-scala usage]
- **Container lifecycle matches cost.** `TestContainerForEach` on a Postgres or Kafka container starts one per test; use `ForAll` and reset state, or a shared template database. Serialised suites (`Tags.limit(Tags.Test, 1)`) exist so containers do not contend; do not parallelise them back. [testcontainers-scala usage, family build]
- **`ActorTestKit` is shut down; test config is `application-test.conf`.** A suite creating `ActorTestKit()` with no `shutdownTestKit()` in `afterAll`; a test relying on `application.conf` values the kit does not load by default. [Pekko testing-async]
- **Logging assertions use `LoggingTestKit`/`LogCapturing`, not stdout scraping.** [Pekko testing-async]
- **Tests compile under the same `-Werror`/`-Wunused` as main.** An unused `given`, parameter or import in `src/test` that only passes because `Test / scalacOptions` relaxed the flags; `@nowarn` without a reason string. [compiler-options; reviewer experience]
- **Flaky markers are visible.** `.flaky` on a test hides a race unless `MUNIT_FLAKY_OK` is set; a PR adding `.flaky` needs an issue link. [munit tests]
- **Fixed ports in tests.** `HttpServer.of` (port 9000) or any hard-coded port breaks runs beside a developer's local server; bind to port 0. [family trap]
- **A `-D` property passed to sbt does not reach a forked test JVM.** A new test switch read with `sys.props` but not forwarded in `Test / javaOptions`. [family trap]
- **Serialization verification on in tests.** `pekko.actor.serialize-messages = on` in `application-test.conf` so a non-serializable message fails a local test rather than production. [Pekko serialization]

### 3.9 Build and dependencies

- **Test and compile-time-only dependencies are scoped.** A test library (`munit`, `scalatest`, `testcontainers`, `scalacheck`, `pekko-actor-testkit-typed`) without `% Test`; a compiler plugin or an API-only jar without `% Provided`; `% "compile->test"` where a module's tests want another module's test classes. [sbt library dependencies]
- **`%%` for Scala libraries, `%` for Java; `for3Use2_13` only as a documented stopgap.** `"org.foo" % "lib" % v` on a Scala artifact (wrong binary suffix); `cross CrossVersion.for3Use2_13` in a library that will be published (users end up with `x_2.13` and `x_3` on the classpath). [sbt library dependencies, cross-build]
- **Versions are pinned, never ranges or `latest.integration`.** `"2.9.+"`, `"[1.0,)"`. "The effective version keeps changing over time"; builds stop being reproducible. Centralise versions in `project/Dependencies.scala`; in this family the Pekko line is one shared set bumped in ankka first and mirrored. [sbt library dependencies, family.md]
- **`ThisBuild` for shared settings, per-project for `crossScalaVersions`, `Nil` on the aggregate root.** `scalaVersion` repeated per project; `crossScalaVersions` on the root; a `version :=` literal (this family derives versions from tags via sbt-dynver and rewrites `0.0.0` placeholders in release jobs). [cross-build, family.md]
- **`scalacOptions` are not relaxed to make a PR compile.** A diff removing `-Werror`, `-Wunused`, `-Wvalue-discard`, adding `-Wconf:...:s` wholesale, or a `Test / scalacOptions` that drops the lints. `-Wconf` filters need a reason in the diff. [compiler-options, 3.3.0 blog]
- **MiMa guards published libraries.** A published module (`ankka-sdk`, `ankka-core`, …) without `mimaPreviousArtifacts`; a `ProblemFilters.exclude` with no justification; removing a public method, adding a parameter, adding a field to a public case class, or changing a default argument in a minor release. Binary breaks mean MAJOR under the recommended scheme; `mimaReportBinaryIssues` fails on an empty previous set so it cannot be silently skipped. [MiMa README, binary-compatibility]
- **Default arguments and case class shape are binary API.** Changing a default value or adding a case class field in a library without a MiMa filter and a version bump. [binary-compatibility]
- **Public `given`s in published libraries are named.** Anonymous givens' synthesized names are part of the binary API. (Scala 3) [givens]
- **Top-level definitions: moving them between files changes `Foo$package`.** A refactor that moves a top-level `def` from `a.scala` to `b.scala` in a published module is a binary break. (Scala 3) [package-objects]
- **`.scalafmt.conf` churn is reviewed as a build change.** Bumping `version`, changing `runner.dialect`, adding `rewrite.rules` or `SortModifiers` reformats the tree; it belongs in its own PR with no logic changes, and the dialect must match the compiler (a `scala213` dialect cannot parse `given`). [scalafmt configuration]
- **Scala 2.13 code adopting Scala 3 syntax uses `-Xsource:3`** and scalafmt `scala213source3`; without them `?` wildcards, `*` imports and `case` generators do not parse. (Scala 2) [scalafmt configuration, compiler-options]
- **One Pekko/Akka line, no BSL artefact.** A dependency pulling `com.typesafe.akka` transitively, or a Pekko module at a different version than the rest (`pekko-http` vs `pekko-actor` mismatch). [family principles; MiMa/sbt mechanics]
- **Cross-building: branch on `CrossVersion.partialVersion`, not string compare.** `if (scalaVersion.value.startsWith("3"))`. [cross-build]
- **Dependency updates: majors and formatter bumps in their own PR.** A Scala Steward PR mixing a major bump with a scalafmt bump obscures what changed. [family convention; recent commit history]

## 4. Severity hints

**Blocking (correctness, data loss, concurrency, security):**
- Mutating actor or shared state from a `Future` callback / `onComplete` / `ask` callback; `sender()` captured in a callback (3.5).
- Blocking (`Await`, JDBC, `Thread.sleep`) on the default dispatcher or inside `Future` on the global EC in service code (3.5), except where the repo's runtime deliberately runs on virtual threads and documents it.
- Side effects in an event handler; state outside `State`; a persisted/wire type changed incompatibly (field rename, enum reorder, discriminator change) without migration (3.5, 3.6).
- `ask` without timeout or a handler path that never replies (3.5).
- `Option.get`/`.head`/`.get` on possibly empty values in production paths; `null` escaping into Scala code; `asInstanceOf` on erased generics (3.2).
- `catch { case _: Throwable }` / `case _ =>` swallowing fatal errors or `InterruptedException` (3.4).
- Mutable messages or `var`/mutable collections in case classes used as keys, messages or events (3.2, 3.5).
- `==` on arrays or on mixed types where the comparison decides behaviour (3.2).
- Iterator reused after a consuming call (3.3).
- Overridden `val` read in a superclass constructor; a `val` listing later-defined values (3.2).
- Missing `super.beforeAll()` in a container suite, a test that cannot fail, a fixed port, a `-D` not forwarded (3.8).
- Binary-incompatible change to a published module without MiMa filter and version plan; relaxing `-Werror` to merge; version ranges (3.9).

**Should fix (maintainability, future bugs):**
- Scala 2 idioms in Scala 3 code: `implicit`, `_` wildcards, `: _*`, `=>` renames, `private[this]`, `with` types, procedure syntax, `Enumeration`, package objects (3.1).
- Missing `sealed`/`final`; `case _ =>` hiding missing enum cases; missing `override` (3.2, 3.7).
- `List` used for indexed access or appends in loops; `ListMap`; mutable collections not qualified (3.3).
- Exceptions for expected failures in APIs; `None` as an error; `recover { case _ => }` (3.4).
- Codecs or givens outside companions; `implicit def`/`inline given` codecs; anonymous public givens (3.6, 3.7).
- `return` in a lambda; by-name parameters outside DSLs; default arguments on overloads; `apply` on a class (3.1, 3.7).
- `eventually` in a unit test or with assertions outside the retry; `Thread.sleep` in tests; `intercept[Exception]` (3.8).
- Missing `% Test` scoping; `for3Use2_13` in a published library (3.9).

**Nit (style, only when the formatter/compiler does not already own it):**
- Infix on non-operators, dot notation for higher-order calls, parentheses conventions, `then`/`do` vs parentheses, end markers, fewer-braces on one-liners (3.1).
- Naming: `get` prefixes, acronym casing, symbolic names without `@targetName` (3.1).
- Interpolation vs `+`, `toString` on functions (3.3).
- Multiple parameter lists without reason; structural types (3.7).

## 5. Already enforced by tooling

Skip these when the repo's configuration shows the tool is on (check `scalacOptions`, `.scalafmt.conf`, `.scalafix.conf`, hooks and CI).

| If the build has | Do not flag |
|---|---|
| `-Werror` (`-Xfatal-warnings`) | anything the compiler warns about: deprecations (with `-deprecation`), feature imports (`-feature`), non-exhaustive matches on sealed types/enums, unchecked erasure casts, refutable pattern bindings (3.2+), nonlocal returns (3.2+), `_`/`: _*`/`private[this]`/`with`-type deprecations (3.4+), `given` default shadowing. [compiler-options, source-compatibility, 3.4.0 blog] |
| `-Wunused:all` (3.3+) or `-Wunused:imports,privates,locals,explicits,implicits,params` (2.13) | unused imports, locals, privates, parameters, `using` parameters, pattern variables (`patvars`, 2.13). [3.3.0 blog, compiler-options] |
| `-Wvalue-discard` | a non-Unit value discarded where `Unit` is expected (the `map` vs `flatMap` bug). [3.3.0 blog] |
| `-Wnonunit-statement` | a non-Unit expression in statement position (E176). [E176] |
| `-Wsafe-init` (`-Ysafe-init`) | reads of uninitialised fields and `this` leaking from constructors; note it does not cover Java/Scala 2 superclasses or global objects fully. [safe-initialization] |
| `-Yexplicit-nulls` | `null` assignment to a non-nullable reference; unchecked member selection on `T \| Null`. Still review `.nn` and `unsafeNulls`. [explicit-nulls] |
| `-language:strictEquality` | `==` between types without `CanEqual`. Still review `derives CanEqual` on types that should not be comparable. [multiversal-equality] |
| `-source:future` / `-source:future-migration` | the old wildcard, splice, import-rename and procedure forms are errors; nonlocal returns and refutable patterns are errors. [source-compatibility, vararg-splices, wildcards] |
| `-no-indent` / `-new-syntax` / `-old-syntax` | the brace vs indentation choice and `then`/`do` vs parentheses are a compiler setting. [indentation, control-syntax] |
| `-Xlint` (2.13) | `infer-any`, `missing-interpolator` (`"$x"` without `s`), `adapted-args` (auto-tupling), `private-shadow`, `type-parameter-shadow`, `nonlocal-return`, `unused`, `deprecation`. Scala 3 has `-Wshadow:*` for the shadow pair and no `infer-any`. [compiler-options, migration compiler-options] |
| `-Xsource:3` (2.13) | Scala 3 syntax in 2.13 code; private-constructor hides `apply`/`copy`. [binary-compatibility] |
| scalafmt with `runner.dialect` set and the pre-commit hook / `scalafmtCheckAll` in CI | all whitespace, line wrapping, alignment, trailing commas, import grouping (`rewrite.rules = [Imports]`), redundant braces/parens (`RedundantBraces`, `RedundantParens`), infix (`AvoidInfix`), modifier order (`SortModifiers`, deliberately off in this family), `for` brace style (`PreferCurlyFors`), Scala 3 control syntax and optional braces (`rewrite.scala3.convertToNewSyntax`, `removeOptionalBraces`), end markers (`insertEndMarkerMinLines`), docstring style. Only review the config file itself. [scalafmt configuration; rewrite-rule names from the scalafmt documentation index, section truncated in fetch] |
| scalafix `ExplicitResultTypes` | missing result types on public members. |
| scalafix `RemoveUnused`, `OrganizeImports` | unused code and import order. |
| scalafix `ProcedureSyntax`, `NoValInForComprehension`, `LeakingImplicitClassVal`, `RedundantSyntax`, `NoAutoTupling` | the named Scala 2 leftovers. [scalafix overview] |
| scalafix `DisableSyntax` with its options | `var`, `null`, `return`, `throw`, `asInstanceOf`, `isInstanceOf`, `while`, XML literals, `final` on objects, default args, implicit conversions, `println`, as configured. [scalafix overview] |
| WartRemover `wartremoverErrors ++= Warts.unsafe` | `Any`, `AsInstanceOf`, `DefaultArguments`, `EitherProjectionPartial`, `IsInstanceOf`, `IterableOps`, `NonUnitStatements`, `Null`, `OptionPartial`, `Product`, `Return`, `Serializable`, `StringPlusAny`, `Throw`, `TripleQuestionMark`, `TryPartial`, `Var`. Review the rest (`ArrayEquals`, `Equals`, `FinalCaseClass`, `LeakingSealed`, `Enumeration`, `JavaConversions`, `Option2Iterable`, `ToString`, `Overloading`, `Recursion`) unless listed too. [wartremover] |
| MiMa in CI (`mimaReportBinaryIssues`) | binary breaks in published modules are reported automatically; review only the *filters* added. [MiMa] |
| `pekko.actor.serialize-messages = on` in test config | non-serializable messages. Still review schema evolution of persisted events. [Pekko serialization] |
| speckit-bdd / `just features`, `-Dankka.cluster.tests` forwarded | living-feature coverage and the forked-JVM switch are already checked. [family] |

The compiler does not enforce, so keep reviewing even on a strict build: codec placement, given coherence, `Seq`/`List` performance, `Iterator` reuse, `Array` equality, NaN, actor-state-in-callback, blocking on the wrong dispatcher, ask timeouts, event-handler purity, test fixtures and `eventually` misuse, dependency scoping and pinning.

## 6. Open questions / disagreements between sources

1. **`Try` in APIs.** Effective Scala: encode expected errors in the return type (`Option`, `Try`). Databricks: "Do not return `Try` from APIs", throw and use try/catch, because `Try` increases nesting and blurs expected vs exceptional. Modern Scala 3 practice (and this family's "effects are inert data" principle) sides with typed results, usually `Either[SealedError, A]` rather than `Try`; treat `Try` as a boundary type for wrapping exception-throwing code.
2. **`return` for guard clauses.** Effective Scala and Databricks allow `return` as an early-exit guard at method top level; WartRemover's `Return` and FP-leaning teams forbid it entirely. Both agree it is wrong inside a closure. Decide per repo; this family's codebases lean functional.
3. **Call-by-name.** Databricks: avoid entirely, use `() => T`. Effective Scala: fine for control-construct DSLs in last position. The Scala 3 reference uses by-name parameters widely (`inline` parameters behave like by-name). Review for clarity and evaluation count, not for presence.
4. **Braces.** Databricks: always braces, even one-liners (a Scala 2, Spark-era rule). Official style guide: omit for pure single-line expressions. Scala 3: indentation by default, braces optional, `-no-indent` to opt out. The formatter should own this; the reviewer should not.
5. **Enums vs sealed trait + case objects in Scala 3.** The Scala 3 Book calls `enum` "the perfect starting point", but gives no hard rule; enums cannot have per-case companions, cases accept only access modifiers, and case applications widen to the enum type. For ADTs with per-case methods, companions or complex variance, a sealed trait is still legitimate.
6. **Annotation casing.** Official style guide: lower camel (`@volatile`). Databricks: PascalCase. Follow the library's own spelling (`@tailrec`, `@nowarn`, `@targetName` are lowercase in the stdlib).
7. **`private[this]`.** Effective Scala and Databricks recommend it for performance and `synchronized` safety; Scala 3 deprecates it and infers object-privacy. In Scala 3 the advice collapses to "do not touch another instance's private field"; in 2.13 the modifier is still meaningful.
8. **`Option` vs `null` in hot paths.** Databricks allows `null` with `@Nullable` in performance-sensitive code; Effective Scala and WartRemover forbid `null`. With `-Yexplicit-nulls` the type system can carry `T | Null`, which resolves the conflict in Scala 3; without it, confine `null` to measured hot paths behind a private API.
9. **Blocking in a `Future`/handler.** The Pekko and Futures docs forbid it on shared pools; a runtime built on JDK 21 virtual threads (this family's `AnkkaExecutors.virtual`) deliberately allows blocking `invoke` in endpoints, workflow steps and tool loops because the await parks the virtual thread. The rule becomes "blocking only on a virtual-thread executor or a dedicated blocking dispatcher, never on a Pekko default dispatcher thread".
10. **`eventually` defaults.** ScalaTest's 150 ms default is tuned for in-memory checks and marked "intended primarily for integration testing", a contradiction the reviewer resolves by requiring explicit patience in any `eventually` that touches I/O.
11. **`-Wnonunit-statement` vs `-Wvalue-discard`.** They overlap but neither subsumes the other (one is statement position, the other is adaptation to `Unit`); forum reports find each stricter in different cases. Enable both.
12. **Unverified in a primary source during this research:** the exact scalafmt rewrite-rule documentation (the configuration page fetch truncated before the "Rewrite Rules" section; rule names are taken from the page's index and from this family's `.scalafmt.conf` experience), `Future.sequence`'s fail-fast semantics (API page returned navigation only), the `sender()`-in-callback warning text in Pekko classic (not found in the fetched halves; the typed JMM page's equivalent guidance is cited instead), and `Double.NaN` equality (JVM/IEEE semantics, not stated on the spec page fetched). Items relying on these are tagged `[reviewer experience]` above.
13. **`implicit` deprecation timeline.** The Scala 3 reference says old-style implicits "might start to be deprecated in a version following Scala 3.0" and gives no date; as of Scala 3.7/3.8 they still compile without warning. The rule to prefer `given`/`using` is therefore a consistency rule, not a compiler-driven one.
