val ankkaVersion = extra["ankkaVersion"] as String

dependencies {
    api("com.thinkmorestupidless:ankka-java:$ankkaVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(kotlin("reflect"))
    // java.util.concurrent.Flow.Publisher to and from a kotlinx Flow
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:1.11.0")

    testImplementation("com.thinkmorestupidless:ankka-java-testkit:$ankkaVersion")
}

// Two carriers for every virtual thread, so a handler that held its carrier while it waited would
// make fifty waiting handlers take twenty-five times as long (SuspendingHandlerTest).
tasks.withType<Test>().configureEach {
    systemProperty("jdk.virtualThreadScheduler.parallelism", "2")
    systemProperty("jdk.virtualThreadScheduler.maxPoolSize", "2")
}
