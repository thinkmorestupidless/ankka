import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.21" apply false
}

val ankkaVersion = project.property("ankkaVersion") as String

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "com.thinkmorestupidless"
    version = rootProject.version
    extra["ankkaVersion"] = ankkaVersion

    repositories {
        // Only ankka's own artifacts from ~/.m2, where `sbt publishM2` puts this commit's Java SDK;
        // everything else from Central, so a stale local copy of a library is never picked up.
        mavenLocal { content { includeGroup("com.thinkmorestupidless") } }
        mavenCentral()
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            allWarningsAsErrors.set(true)
            // Parameter names in the class files: a route binds a path parameter by its name.
            javaParameters.set(true)
            // The Java SDK's packages are @NullMarked: read its types as non-null, and fail on a misuse.
            freeCompilerArgs.add("-Xjspecify-annotations=strict")
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"(kotlin("test-junit5"))
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // The three libraries a Kotlin service depends on, as `com.thinkmorestupidless:<project>`.
    // `publishToMavenLocal -Pversion=<v>` puts them where a template's build finds them; a release
    // publishes them signed into build/staging (`publishAllPublicationsToStagingRepository`), which
    // the release job zips and uploads to the Central Portal as the sbt modules' bundle is. The POM's
    // metadata is the sbt build's (build.sbt, ThisBuild).
    if (!path.startsWith(":samples")) {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set("ankka for Kotlin: ${project.name}")
                        url.set("https://github.com/thinkmorestupidless/ankka")
                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }
                        developers {
                            developer {
                                id.set("thinkmorestupidless")
                                name.set("Trevor Burton-McCreadie")
                                url.set("https://github.com/thinkmorestupidless")
                            }
                        }
                        scm {
                            url.set("https://github.com/thinkmorestupidless/ankka")
                            connection.set("scm:git:https://github.com/thinkmorestupidless/ankka.git")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "staging"
                    url = rootProject.layout.buildDirectory.dir("staging").get().asFile.toURI()
                }
            }
        }
        // Signed only when a key is given (ORG_GRADLE_PROJECT_signingKey and _signingPassword, the
        // release job's PGP_* secrets), so a local publish needs none.
        extensions.configure<SigningExtension> {
            val key = findProperty("signingKey") as String?
            if (key != null) {
                useInMemoryPgpKeys(key, findProperty("signingPassword") as String?)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }

    // Serialised, as the sbt build's suites are: each kit starts a database container.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 1
        systemProperty("ankka.fixtures", rootProject.file("fixtures").absolutePath)
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

// The fixtures are a copy of protocol/fixtures, which every SDK reads; this says when they differ.
tasks.register<Exec>("checkFixtures") {
    description = "Fails when fixtures/ differs from the repository's protocol/fixtures"
    commandLine("diff", "-r", rootProject.file("../../protocol/fixtures").absolutePath, rootProject.file("fixtures").absolutePath)
}
