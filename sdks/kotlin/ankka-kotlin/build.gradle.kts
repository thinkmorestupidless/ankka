val ankkaVersion = extra["ankkaVersion"] as String

dependencies {
    api("com.thinkmorestupidless:ankka-java:$ankkaVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(kotlin("reflect"))
}
