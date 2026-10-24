// The Kotlin SDK: a library over the Java SDK (`modules/sdk-java`), built by Gradle because a
// Kotlin developer's build is Gradle. The Java artifacts it depends on come from ~/.m2 after
// `sbt publishM2` at this commit, or from Maven Central at a release.
plugins {
    // Provisions the JDK 21 toolchain on a machine without one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ankka-kotlin-build"

include(
    "ankka-kotlin",
    "ankka-kotlin-agent",
    "ankka-kotlin-testkit",
    "samples:shopping-cart",
    "samples:reference",
)
