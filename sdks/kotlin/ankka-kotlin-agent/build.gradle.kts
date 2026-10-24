val ankkaVersion = extra["ankkaVersion"] as String

dependencies {
    api(project(":ankka-kotlin"))
    api("com.thinkmorestupidless:ankka-java-agent:$ankkaVersion")
    // java.util.concurrent.Flow.Publisher, a streamed reply, to a kotlinx Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:1.11.0")

    testImplementation(project(":ankka-kotlin-testkit"))
}
