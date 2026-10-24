# Quickstart: Java and Kotlin SDKs

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/](./contracts/)

How to prove the feature works, from the cheapest check to the most expensive. Each tier stands
alone; run them in order when reviewing. A JDK 21 and Docker are needed from tier 1 (Docker from
tier 3); sbt from tier 1; nothing else is installed by hand: the Kotlin build and both templates
bring their wrappers.

## Tier 1 — the surface and the codec (seconds, no Docker)

```bash
sbt 'sdkJava/testOnly *SurfaceSuite *RecordCodecFixturesSuite *DeclarationSuite'
```

Expected: `SurfaceSuite` lists every public signature of the three Java jars and finds no Scala,
Pekko, jsoniter or non-`java` ankka type (SC-006); `RecordCodecFixturesSuite` reads and re-writes
every file under `protocol/fixtures` byte for byte and fails, not skips, on a fixture with no Java
shape; `DeclarationSuite` registers the broken classes (a handler with no name, two with one name,
`@Query` returning `Effect`, a `float` field, a lambda where a method reference is needed) and sees
each problem named in `ServiceProblems` before start, and none at a request. Then break one thing
on purpose: make the codec write `1` for a whole double and see `numbers.json` fail on bytes.

## Tier 2 — every kind through the Java test kits (seconds)

```bash
sbt 'sdkJava/test' 'agentJava/test' 'testkitJava/test'
```

Expected: one suite per kind drives a Java component through its kit and reads the same reply,
events and state the Scala kit gives the Scala component of the same shape; `WorkflowTestKit` runs
the Java checkout to its pause and on; `AgentTestKit` runs a turn against `TestModelProvider` and
fails loudly when the script runs out; the cross-language case writes a `CartState` record and the
Scala `ShoppingCart` case class and compares bytes (SC-003's unit half).

## Tier 3 — the Java shopping cart and the living features (a few minutes, Docker)

```bash
sbt -Dankka.cluster.tests=off 'shoppingCartJava/test' 'testkitJava/testOnly *JavaKotlinFeatures'
```

Expected: the Java cart passes the sample's own features from `samples/shopping-cart/features`
through `AnkkaTestKit` against a throwaway database, including `restartService()`; the
cross-language entity rows start the Scala cart's descriptors beside the Java cart's on one
database and each recovers the other's journal (SC-003); `JavaKotlinFeatures` runs every Java row
of `features/java-kotlin/` and reports the Kotlin rows as run elsewhere by name, with none silently
missing.

## Tier 4 — the Java reference through the conformance suite (minutes, Docker)

```bash
sbt -Dankka.cluster.tests=off -Dankka.conformance.reference=java 'sidecar/testOnly *ConformanceSuite'
sbt -Dankka.cluster.tests=off 'sidecar/testOnly *ConformanceSuite'       # the Scala reference, unchanged, through the generalised target
```

Expected: every case passes against the Java reference with the report saying `embedded (Java)`;
the Scala run is unchanged. Read what the run says it ran: the property is forwarded, so a typo
in it fails the run (an unknown reference name is an error, not a fall-through to Scala).

## Tier 5 — the Kotlin SDK (minutes, Docker)

```bash
sbt -Dankka.cluster.tests=off -Dankka.template.tests=off publishM2        # or: just kotlin
cd sdks/kotlin && ./gradlew --no-daemon check                             # tests, surface check, the Kotlin cart, the Kotlin rows of features/java-kotlin
./gradlew --no-daemon checkFixtures conformance                           # fixtures diff; the Kotlin reference in process through the suite
```

Expected: `KotlinFeaturesTest` runs the Kotlin rows, including "a handler written as a suspending
function calls a component and is answered", which asserts the platform thread count did not rise
while the step waited; the data class cases take a default for an absent field and fail recovery
naming `owner` without one; `conformance` reports `embedded (Kotlin)` with every case passing. If
the classloader form fails (R8), the task notes it and the run falls back to a loopback target,
which must then say `process`, and the plan's claim is amended, not silently kept.

## Tier 6 — the templates (minutes each, Docker)

```bash
sbt -Dankka.template.tests=java   'cli/testOnly *JavaTemplateSuite'
sbt -Dankka.template.tests=kotlin 'cli/testOnly *KotlinTemplateSuite'
```

Expected: each expands `ankka init shop --language <l>`, finds no leftover token, resolves
`ankka-java`/`ankka-kotlin` at the build's version from `mavenLocal` (the hook published them),
builds and tests with the wrapper under an empty tool cache (no Maven or Gradle on `PATH` is
needed; the suite fails if the wrapper is not executable or the Gradle jar is corrupt, which is
`Scaffold`'s binary handling), builds the image and answers one command. Then the documented loop
by hand:

```bash
ankka init shop --language kotlin && cd shop && docker compose up -d && ./gradlew run   # HTTP on :9000
curl -s -X POST localhost:9000/carts/c1/items -d '{"productId":"p1","name":"Pen","quantity":1}'
```

## Tier 7 — docs, skills, CI coverage and the cost (minutes)

```bash
just docs                                   # every page checks; the six new pages are in the nav and a skill
just docs-sync && git status --short        # nothing drifts
python3 .github/ci-coverage.py             # every new path claimed, every new pattern matches
just features                               # speckit-bdd: features, glossary and specs agree
(cd sdks/kotlin && ./gradlew-this-commit.sh :samples:reference:conformanceClasspath)
sbt -Dankka.cost.kotlin=$PWD/sdks/kotlin/samples/reference/build/conformance-classpath.txt 'shoppingCartJava/testOnly *FacadeCostSuite'   # R13: 1,000 add-item to the Scala, Java and Kotlin carts; prints the medians from the recorder's spans
```

Expected: `FacadeCostSuite` prints three medians; Java and Kotlin are each within 10% of Scala
(SC-009); the numbers are copied into `research.md`'s Measurements section with the machine. The
suite asserts the bound only when `-Dankka.cost.assert=true` (a developer's machine), never in CI.

## Reviewer's checklist

- [ ] `git diff --stat -- modules/runtime protocol sidecar/src/main operator controlplane controlplane-api crd` is empty (FR-026); `modules/testkit` shows only the three added kits (R9). *Not met as written:* three `private[ankka]` accessors in `modules/runtime` (`ShardingTransport.actorSystem`, `ViewClient.actorSystem`, `WorkflowHost.applyEvent`), no behaviour changed; the control plane diffs are test suites naming the new languages. `modules/testkit` adds `WorkflowTestKit`, `ViewTestKit`, `AgentTestKit`, `ClasspathReference` and small `private[ankka]` doors in `AnkkaTestKit`, `ConsumerTestKit`, `TestTransport`.
- [X] `SurfaceSuite` green on all three Java jars; the Kotlin surface check green.
- [X] Every fixture passes in Java and in Kotlin with none skipped; `diff -r protocol/fixtures sdks/kotlin/fixtures` empty.
- [X] Conformance green for Scala, Java and Kotlin references, each reported `embedded`. Each reports `in-process, <language>`: 177 passed, 0 failed, 7 skipped (process-only) for all three.
- [ ] Both template suites green in CI (`template-java`, `sdk-kotlin`), `cli-native` smoke renders six templates. Green locally (Java 9, Kotlin 10); CI after the PR. `native-smoke.sh` now renders six templates (Kotlin was missing); it runs in CI's `cli-native`.
- [ ] `ci-coverage.py` green; branch protection names `sdk-kotlin` and `template-java`. The script is green; branch protection is the user's to set.
- [X] `limitations.md` names what each SDK lacks; `akka-divergences.md` has the Java section; `docs check` green; every Java and Kotlin code block is an include.
- [X] Every place that named four languages names six (`grep -rn "Scala, Python, TypeScript" docs README.md cli tools | wc -l` is 0). Outside build output the one hit, `docs/build/consumers.md`, names Java and Kotlin in the same sentence.
- [ ] `research.md` Measurements filled; SC-009 holds on the recording machine. Filled; SC-009 not shown (see Measurements).
- [ ] The release job for Kotlin is reviewed against `sdk-rust`'s; the first tag after merge is watched (the agent never tags).
