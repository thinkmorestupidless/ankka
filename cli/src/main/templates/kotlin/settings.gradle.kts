plugins {
    // Provisions the JDK 21 toolchain the build compiles with on a machine without one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// An older JDK refuses the build here, naming the version, rather than failing somewhere later.
if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    throw GradleException("ankka needs JDK 21 or later to build this service; this is JDK ${JavaVersion.current()}")
}

rootProject.name = "{{name}}"
