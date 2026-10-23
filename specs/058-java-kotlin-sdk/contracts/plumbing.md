# Contract: templates, `ankka init`, conformance, CI, release, docs

**Feature**: [spec.md](../spec.md) | **Research**: R8, R10, R11, R13 | **Mirrors**: the Rust SDK's jobs and template, the Scala template's embedded shape

## `ankka init --language java|kotlin`

```
ankka init shop --language java   [--package com.example.shop]      # default package: com.example.<name-as-identifier>
ankka init shop --language kotlin [--package com.example.shop]
ankka init shop --language kt                                        # alias
```

`Init.Language` gains `Java("java")` and `Kotlin("kotlin")`; `parse` accepts `java`, `kotlin`, `kt`;
the error string lists six languages. `Init.problems`: `--template` refused for both (as for every
non-Scala language); `--package` accepted for both, validated as a dotted sequence of Java
identifiers none of which is a Java (or, for Kotlin, Kotlin) keyword; `--package` remains refused
for TypeScript, Rust and Web. `Main.initCommand` renders through `Scaffold.render(language, dir,
tokens)` and prints next steps:

```
Created shop (Java, Maven). Next:
  cd shop
  docker compose up -d          # Postgres
  ./mvnw test                   # the entity and endpoint tests, then the whole service against a throwaway database
  ./mvnw exec:java              # HTTP on :9000
```

(`./gradlew test` and `./gradlew run` for Kotlin.)

### `Scaffold` changes

- New tokens `{{package}}` (dotted) and `{{package_path}}` (slashed), replaced in paths and
  contents with the existing ones (`{{name}}`, `{{module}}`, `{{module_snake}}`, `{{ankka_version}}`,
  `{{protocol_version}}`); added to `PolyglotTemplateSuite`'s leftover-token check.
- `index.txt` lines gain an optional mode column: `path`, `path  binary`, `path  exec`. A `binary`
  entry is copied byte for byte with no token replacement; an `exec` entry gets the owner-execute
  bit after writing (`Files.setPosixFilePermissions` where the filesystem supports it, and nothing
  on one that does not). The template resource generator (`build.sbt`) writes the mode from a
  `.scaffold-modes` file beside each template's root listing `gradle/wrapper/gradle-wrapper.jar
  binary`, `gradlew exec`, `mvnw exec`.
- The native image's resource glob `ankka/templates/**` already carries binary resources;
  `cli/native-smoke.sh` renders `java` and `kotlin` too and checks `gradlew` is executable and the
  jar's size is non-zero.

### The Java template (`cli/src/main/templates/java/`)

```
pom.xml                       groupId {{package}}; artifactId {{name}}; java.version 21; dependencies ankka-java {{ankka_version}}, ankka-java-testkit (test), junit-jupiter 6.1.x (test);
                              plugins: maven-enforcer (requireJavaVersion [21,), so an older JDK refuses the build naming the version), maven-surefire (JUnit platform), exec-maven-plugin (main class), jib-maven-plugin (image {{name}}:local, base eclipse-temurin:21-jre) [Jib vs Dockerfile: R10 verify]
mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties   (the script-only wrapper distribution, no jar: verify, R10)
src/main/java/{{package_path}}/Main.java                 Ankka.service("{{name}}").register(ShoppingCart.class).register(CartView.class).withEndpoint(CartEndpoint.class).start().awaitReady()
src/main/java/{{package_path}}/CartView.java             the view (one row per cart, a declared query by owner), as the Scala template has
src/main/java/{{package_path}}/ShoppingCart.java         the entity (records CartState, LineItem; sealed CartEvent)
src/main/java/{{package_path}}/CartEndpoint.java         the endpoint
src/test/java/{{package_path}}/ShoppingCartTest.java     EventSourcedTestKit, JUnit
src/test/java/{{package_path}}/ServiceTest.java          AnkkaTestKit against a throwaway database (Docker)
service.json                  { "name": "{{name}}", "runtime": "{{ankka_version}}", "image": "{{name}}:local", "http": { "port": 9000 } }   (embedded: no "hosting", no "protocol")
docker-compose.yml            Postgres only (the Scala template's shape), ANKKA_* for a local run
Dockerfile                    present for `PolyglotTemplateSuite`'s required-files check and for a developer who prefers it over Jib: a two-stage build with the wrapper
.github/workflows/ci.yml      setup-java 21, ./mvnw -B verify
.github/workflows/deploy.yml  on a v* tag: build, push, ankka services apply (the ankka-action), ${{ secrets.ANKKA_TOKEN }} intact
.gitignore, README.md, .mcp.json (McpInstall.ProjectLaunch.entry), .claude/skills/ (from the marketplace copy, as the other templates)
```

### The Kotlin template (`cli/src/main/templates/kotlin/`)

The same files with `build.gradle.kts` (`kotlin("jvm")`, `application` with `mainClass`, Jib Gradle
plugin, `ankka-kotlin` at `{{ankka_version}}`, `ankka-kotlin-testkit` and JUnit for tests,
`useJUnitPlatform()`), `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar` (binary), `gradle/wrapper/gradle-wrapper.properties` (the
pinned Gradle distribution URL), `src/main/kotlin/{{package_path}}/{Main,ShoppingCart,CartView,CartEndpoint}.kt`
(data classes, the DSL, one suspending step or client call so the template shows it),
`src/test/kotlin/{{package_path}}/{ShoppingCartTest,ServiceTest}.kt`.

### Template suites

`JavaTemplateSuite extends PolyglotTemplateSuite("java", "java")` and `KotlinTemplateSuite(...
"kotlin", "java")`: render through `Main.run(Seq("init", ...))`; assert the SDK pin equals
`Version.current`; rewrite nothing (the pin resolves from `mavenLocal`, which the
`templateArtifacts` hook filled with `publishM2` for `-Dankka.template.tests=java|kotlin`); run
`./mvnw -B -q verify` or `./gradlew --quiet --no-daemon test`, each with a wrapper cache under the
test's temp dir (`MAVEN_USER_HOME`, `GRADLE_USER_HOME`) so the suite proves "no build tool installed"
and leaves nothing behind; assert the surefire/junit report counts at least the template's tests
and none skipped; build the image (`jib:dockerBuild` / `jibDockerBuild`) and start it against the
compose Postgres, then one command through HTTP (as the Scala `TemplateSuite` does). Gated by
`TemplateSwitch`. `PolyglotTemplateSuite` gains the embedded branch: descriptor valid with no
`protocol`, `runtime` equal to the version, the compose file holding Postgres and no sidecar
service (the two-`ANKKA_SERVICE_NAME` assertion applies to process templates only), repository
paths list extended with `sdks/kotlin` and `modules/sdk-java`.

## The conformance target

```scala
// sidecar/src/test/.../conformance/ConformanceTarget.scala
trait EmbeddedReference {                         // what an in-process reference supplies
  def descriptors: Seq[ComponentDescriptor]
  def extensions(http: HttpServer.Binding): Seq[RuntimeExtension]
  def componentIds: Set[String]
  def readOnlyHandlers: Set[(String, String)]
  def language: String                            // "Scala" | "Java" | "Kotlin", for the suite's report
}
object ConformanceTarget {
  def fromProperty(): ConformanceTarget = sys.props.get("ankka.conformance.target") match {
    case Some(s"wasm:$path") => ModuleTarget(path)
    case Some(address)       => Sidecar(address)
    case None                => InProcess(reference = sys.props.get("ankka.conformance.reference") match {
      case None | Some("scala")                   => ScalaReference
      case Some("java")                           => JavaReference                     // sidecar.dependsOn(sdkJava % Test)
      case Some(s"classpath:$file:$entryClass")   => ClasspathReference.load(file, entryClass)   // the Kotlin reference
    })
  }
}
```

`ClasspathReference.load` builds a `URLClassLoader` over the file's entries with the suite's loader
as parent, instantiates `entryClass` (a Java class implementing
`com.thinkmorestupidless.ankka.java.conformance.ConformanceEntry`, in `ankka-java`'s test-jar or a
small `ankka-java-conformance` test artifact: verify, R8) and adapts its Java `EmbeddedReference` to
the Scala trait. `hosting` reports `embedded` for every `InProcess`. The suite's cases do not
change; its report names the language. `ankka.conformance.reference` is forwarded in
`Test / javaOptions`.

## `build.sbt`

```scala
lazy val sdkJava = project.in(file("modules/sdk-java")).dependsOn(sdk, runtime, http, grpc, telemetryOtlp, testkit % Test)
  .settings(commonSettings).settings(name := "ankka-java", crossPaths := false,
    Compile / compile / javacOptions ++= Seq("-Xlint:all", "-Werror"), libraryDependencies += "org.jspecify" % "jspecify" % "<pinned>")
lazy val agentJava   = project.in(file("modules/agent-java")).dependsOn(sdkJava, agent).settings(... name := "ankka-java-agent", crossPaths := false)
lazy val testkitJava = project.in(file("modules/testkit-java")).dependsOn(sdkJava, agentJava, testkit).settings(... name := "ankka-java-testkit", crossPaths := false)
lazy val shoppingCartJava = project.in(file("samples/shopping-cart-java")).dependsOn(sdkJava, testkitJava % Test).settings(publish / skip := true, ...)
sidecar: .dependsOn(sdkJava % Test)      // the Java reference
root: aggregate += sdkJava, agentJava, testkitJava, shoppingCartJava
templateArtifacts: when the switch names java or kotlin, publishM2 of core, sdk, runtime, http, grpc, agent, telemetryOtlp, authOidc, testkit, sdkJava, agentJava, testkitJava
template resource list: Seq("python", "typescript", "rust", "web", "java", "kotlin"); java and kotlin skip common-service
Test / javaOptions: forward ankka.conformance.reference
```

`Justfile` gains `kotlin` (publishM2 then `./gradlew check` in `sdks/kotlin`), `kotlin-conformance`,
`template-java`, `template-kotlin`, each one command.

## CI (`.github/workflows/ci.yml`)

New `changes` outputs and filters, each pattern matching a file in the same commit
(`.github/ci-coverage.py`):

```yaml
sdk-kotlin:     [*workflow, *scala-build, *templates, 'sdks/kotlin/**', 'cli/src/main/templates/kotlin/**', 'cli/src/test/scala/**/cli/KotlinTemplateSuite.scala']
template-java:  [*workflow, *scala-build, *templates, 'cli/src/main/templates/java/**',   'cli/src/test/scala/**/cli/JavaTemplateSuite.scala']
```

Jobs (`needs: changes`, `if: needs.changes.outputs.<key> == 'true'`):

- `sdk-kotlin`: setup-java 21 (Temurin), sbt and Gradle caches; `diff -r protocol/fixtures
  sdks/kotlin/fixtures`; `sbt -Dankka.cluster.tests=off -Dankka.template.tests=off publishM2` of
  the JVM modules (the `templateArtifacts` set); `cd sdks/kotlin && ./gradlew --no-daemon check
  conformance`; then `sbt -Dankka.template.tests=kotlin 'cli/testOnly *KotlinTemplateSuite'`.
- `template-java`: setup-java 21; `sbt -Dankka.template.tests=java 'cli/testOnly *JavaTemplateSuite'`.
- `build`'s `rest` group runs the Java modules' suites (fixtures, surface check, bridge, the Java
  cart, `JavaKotlinFeatures`) unlisted; the Java reference's conformance run is in `sidecar`'s group
  already (`-Dankka.conformance.reference=java` added as a second `testOnly` invocation there, or a
  fourth matrix entry `conformance-java` subtracted from `rest` if it proves slow).
- `cli-native`'s smoke renders six templates.
- `docs` already claims `sdks/**` and `docs/**`.

Branch protection names `sdk-kotlin` and `template-java` directly (neither is a matrix).

## Release (`.github/workflows/release.yml`)

- `publish`: unchanged; `ci-release` publishes `ankka-java`, `ankka-java-agent`, `ankka-java-testkit`
  with the others. The "already on Central?" probe may add `ankka-java/<v>.pom`.
- New `sdk-kotlin` (`needs: publish`, `if: startsWith(github.ref, 'refs/tags/v')`, the `environment`
  and secrets the `sdk-rust` job has): checkout the tag; setup-java 21; `sbt publishM2` of the JVM
  modules at the tag's version (so the Kotlin build resolves them from `mavenLocal` without waiting
  for Central); `sed -i "s/^version=0.0.0$/version=$version/; s/^ankkaVersion=0.0.0$/ankkaVersion=$version/" sdks/kotlin/gradle.properties`
  in the job's checkout only; `./gradlew --no-daemon check`; a probe of
  `repo1.maven.org/.../ankka-kotlin/$version/ankka-kotlin-$version.pom` that skips publishing when
  present; `./gradlew publish` with `ORG_GRADLE_PROJECT_signingKey`/`signingPassword` from `PGP_*`
  and the Central Portal credentials from `SONATYPE_*`, then the same bundle upload the `publish`
  job performs (or the Gradle Central Portal publishing plugin: verify at implementation). Kotlin's
  version is the tag's (FR-022). The marketplace, template, cli and docs jobs are unchanged; the
  templates ship inside the CLI.

## Documentation and skills

| File | Change |
|---|---|
| `docs/get-started/first-service-java.md`, `first-service-kotlin.md` | new; headings as the Rust page's (Create the project … Next); every code block an include from the template or the sample |
| `docs/reference/java-sdk.md`, `kotlin-sdk.md` | new; the Rust reference page's headings (Installing, Codec and types, The shape every component shares, one section per kind, Calling components, Running, Testing), plus "Method references and wire names", "Module system" (Java) and "Suspension", "Nullability", "Data classes" (Kotlin); frontmatter `languages: [java]` / `[kotlin]` |
| `docs/reference/limitations.md` | "Four languages" → six; a bullet each for what the Java and Kotlin SDKs lack (FR-024, `contracts/kotlin-sdk.md` lists Kotlin's; Java's: no Scala-side given/typeclass customisation of codecs, no annotation processor, `float`/`short`/`char` fields refused, gRPC endpoints only through grpc-java stubs) |
| `docs/reference/akka-divergences.md` | new section "The Java SDK adopts Akka's annotated handlers": the wire name is the annotation's string, call sites are method references resolved through the registry, what still differs (explicit registration, `acl()`, SQL views, the ACL enum, no `@ComponentId` scanning) |
| `docs/contributing/language-sdks.md` | the embedded route as a third way an SDK exists: a facade in this build, the conformance suite in process, no protocol |
| `docs/index.md`, `README.md`, `docs/concepts/polyglot.md`, `get-started/install.md`, `build/*.md`, `deploy/*.md`, `operate/telemetry.md`, `reference/model-providers.md` | Java and Kotlin named wherever the four are; tabs gain `Java` and `Kotlin` where a `/// tab` block shows code per language (the tab check refuses identical code across tabs) |
| `mkdocs.yml` | facet `languages: [scala, java, kotlin, python, typescript, rust]`; nav entries for the four new pages |
| `tools/docs/skill/ankka-java/SKILL.md`, `ankka-kotlin/SKILL.md` | new, modelled on `ankka-rust` (Rules, Development loop, Mistakes to check for), `pages:` naming the new pages |
| `tools/docs/skill/ankka{,-agents,-endpoints,-entities,-views-consumers,-workflows}/SKILL.md` | "Java differences" and "Kotlin differences" sections; the umbrella's description names six languages |
| `cli/mcp/AnkkaTools.scala:364,531` | "in Scala, Java, Kotlin, Python, TypeScript or Rust" |
| `.claude/rules/build-and-release.md`, `CLAUDE.md` commands | the module count, `sbt sdkJava/test`, the Kotlin commands, the new traps |
| docs-checking suites (`McpEndpointsDocumentationSuite`, `ServiceCallsDocumentationSuite`, `ViewStreamsDocumentationSuite`, `ModelProvidersDocumentationSuite`, `AwaitingWorkflowsDocumentationSuite`) | Java and Kotlin tab labels and include paths |

## Living features runner

`JavaKotlinFeatures extends GherkinSuite("features/java-kotlin")` in `modules/testkit-java`'s
tests runs the Java rows; Kotlin rows are `ranElsewhere` naming the Gradle build's
`KotlinFeaturesTest` (which runs the same files with `<language>` rows filtered to Kotlin). The
measurement scenario is `ranOutside` ("recorded in specs/058-java-kotlin-sdk/research.md,
Measurements; R13") and the template, documentation and CI scenarios name their suites
(`JavaTemplateSuite`, `KotlinTemplateSuite`, the docs check). Step definitions for the Java and
Kotlin rows already present in other features (`AwaitSteps.languageRow` and the like) stop assuming
Scala: the Java row runs through the Java reference or cart in the same suite where the component
exists, and otherwise names `modules/sdk-java` or `sdks/kotlin` in its reason.
