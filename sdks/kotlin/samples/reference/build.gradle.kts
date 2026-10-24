// The Kotlin reference service the conformance suite drives in-process (`./gradlew conformance`).
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

val ankkaVersion = extra["ankkaVersion"] as String

dependencies {
    implementation(project(":ankka-kotlin"))
    implementation(project(":ankka-kotlin-agent"))
}

// What the suite's own class path already holds: the Java SDKs and everything they reach, Scala and
// the runtime included. The reference's class loader looks in its own entries first, so a second
// copy of any of these would be a second, unequal class: they are left out of the listing.
val javaSdks: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = true
}

dependencies {
    javaSdks("com.thinkmorestupidless:ankka-java-agent:$ankkaVersion")
}

val conformanceClasspath = tasks.register("conformanceClasspath") {
    description = "Writes the reference's class path, less what the conformance suite already holds"
    val runtime = configurations.runtimeClasspath.get()
    val main = sourceSets.main.get().output
    val listing = layout.buildDirectory.file("conformance-classpath.txt")
    inputs.files(runtime, main, javaSdks)
    outputs.file(listing)
    doLast {
        fun ModuleComponentIdentifier.key() = "$group:$module"
        val shared = javaSdks.incoming.artifacts.artifacts
            .mapNotNull { (it.id.componentIdentifier as? ModuleComponentIdentifier)?.key() }
            .toSet()
        // Kotlin's own libraries are always the reference's: the suite's class path may hold an older
        // kotlin-stdlib (something the Java SDK reaches), which lacks what this compiler emits calls to.
        fun ModuleComponentIdentifier.kotlins() = group == "org.jetbrains.kotlin" || group == "org.jetbrains.kotlinx"
        val added = runtime.incoming.artifacts.artifacts
            .filter {
                val id = it.id.componentIdentifier as? ModuleComponentIdentifier
                id == null || id.kotlins() || id.key() !in shared
            }
            .map { it.file }
        val entries = main.files.filter { it.exists() } + added
        listing.get().asFile.writeText(entries.joinToString("\n", postfix = "\n") { it.absolutePath })
    }
}

tasks.register<Exec>("conformance") {
    description = "Runs the conformance suite against the Kotlin reference, hosted in the suite's process"
    group = "verification"
    dependsOn(conformanceClasspath)
    val listing = layout.buildDirectory.file("conformance-classpath.txt").get().asFile
    workingDir = rootProject.file("../..")
    commandLine(
        "sbt",
        "-Dankka.cluster.tests=off",
        "-Dankka.template.tests=off",
        "-Dankka.conformance.reference=classpath:$listing:com.thinkmorestupidless.ankka.kotlin.reference.KotlinReference",
        "sidecar/testOnly *.ConformanceSuite",
    )
}
