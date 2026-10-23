package com.thinkmorestupidless.ankka.kotlin

import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectDirectories
import org.junit.platform.suite.api.Suite

/**
 * `features/java-kotlin` for a service written in Kotlin, run by cucumber-jvm: every scenario of
 * `kotlin.feature`, and the scenarios of `embedded.feature` whose Kotlin row runs in this JVM. Their
 * Java rows are skipped here and run by `JavaKotlinFeatures`, which names this suite for the rest.
 */
@Suite
@IncludeEngines("cucumber")
@SelectDirectories("../../../features/java-kotlin")
@ConfigurationParameter(key = "cucumber.glue", value = "com.thinkmorestupidless.ankka.kotlin.steps")
@ConfigurationParameter(key = "cucumber.filter.name", value = KotlinFeaturesTest.SCENARIOS)
@ConfigurationParameter(key = "cucumber.publish.quiet", value = "true")
class KotlinFeaturesTest {
    companion object {
        /** The scenarios run here, by name; a renamed one runs nowhere until it is named again. */
        const val SCENARIOS =
            "^(" +
                // kotlin.feature, whole
                "a component declared in Kotlin's own form behaves as one declared through the Java SDK" +
                "|a handler written as a suspending function calls a component and is answered" +
                "|a value the Java SDK may give as nothing is nullable in Kotlin" +
                "|a null given where the Java SDK takes a value is refused before the service is built" +
                "|a data class is a state or an event as a Java record is" +
                "|a data class's default is taken for a field that is absent, and a field with no default that is absent fails recovery" +
                // embedded.feature, the Kotlin rows that run in this JVM
                "|a handler's name is declared apart from the function that handles it" +
                "|two handlers declared with one name fail the service at start" +
                "|a handler declared wrongly fails the service at start, not at its first request" +
                "|a component the service does not register fails at start, not at its first request" +
                "|a field holding nothing is written as absent, and a field holding null is refused" +
                ")$"
    }
}
