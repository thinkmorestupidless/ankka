# The Kotlin SDK

A library over the Java SDK (`modules/sdk-java`): every Java type is usable from Kotlin as it is,
and this build adds what Kotlin's own form needs, such as data classes in the codec, suspending
handlers and calls, `OrNull` forms of `Optional`, and a DSL for effects, routes and tools. A
Kotlin service is hosted by the JVM runtime in its own process, as a Java or Scala one is. There
is no sidecar.

| Project | Artifact | Is |
|---|---|---|
| `ankka-kotlin` | `com.thinkmorestupidless:ankka-kotlin` | the SDK, package `com.thinkmorestupidless.ankka.kotlin` |
| `ankka-kotlin-agent` | `ankka-kotlin-agent` | agents, over `ankka-java-agent` |
| `ankka-kotlin-testkit` | `ankka-kotlin-testkit` | the test kits, over `ankka-java-testkit` |
| `samples:shopping-cart` | not published | the shopping cart in Kotlin, held to the Scala sample's features |
| `samples:reference` | not published | the Kotlin reference the conformance suite drives |

## Building

Gradle 9.8 through the wrapper, Kotlin 2.4, a JDK 21 toolchain (provisioned when missing). The
Java SDK comes from `~/.m2`. From the repository root:

```bash
sdks/kotlin/gradlew-this-commit.sh check     # publishM2 this commit's Java SDK, then ./gradlew check
just kotlin                                  # the same
```

`gradle.properties` holds `version` and `ankkaVersion` as `0.0.0`, the one placeholder, which the
release job rewrites in its checkout. A build by hand against an SDK already in `~/.m2` passes the
version itself: `./gradlew check -PankkaVersion=<version>`.

Tests are serialised (`maxParallelForks = 1`), as the sbt build's are, because each whole-service
kit starts a database container.
