plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "{{package}}"
version = "0.1.0-SNAPSHOT"

val ankkaVersion: String by project

repositories {
    mavenCentral()
}

// The platform's database schema, read from the ankka-runtime artifact.
val schema: Configuration by configurations.creating { isTransitive = false }

dependencies {
    implementation("com.thinkmorestupidless:ankka-kotlin:$ankkaVersion")
    testImplementation("com.thinkmorestupidless:ankka-kotlin-testkit:$ankkaVersion")
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    schema("com.thinkmorestupidless:ankka-runtime_3:$ankkaVersion")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Each parameter's name, which binds an endpoint's path parameters.
        javaParameters.set(true)
        // The Java SDK says which of its values may be null; read that strictly, so a null given
        // where it takes a value does not compile.
        freeCompilerArgs.add("-Xjspecify-annotations=strict")
    }
}

application {
    mainClass.set("{{package}}.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew schema`: the schema into build/ddl, which docker-compose.yml mounts as Postgres' init
// directory. One copy of the schema, the runtime's: run it again after every ankka upgrade.
tasks.register<Copy>("schema") {
    description = "Copies the ankka runtime's database schema into build/ddl"
    from(provider { schema.map { zipTree(it) } }) { include("ankka/ddl/*.sql") }
    into(layout.buildDirectory.dir("ddl"))
}
