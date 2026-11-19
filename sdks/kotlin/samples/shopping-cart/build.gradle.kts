plugins {
    application
}

dependencies {
    implementation(project(":ankka-kotlin"))
    testImplementation(project(":ankka-kotlin-testkit"))
    // The sample's own features, run by cucumber-jvm rather than a reader of our own.
    testImplementation(platform("io.cucumber:cucumber-bom:8.0.4"))
    testImplementation("io.cucumber:cucumber-java")
    testImplementation("io.cucumber:cucumber-junit-platform-engine")
    testImplementation("org.junit.platform:junit-platform-suite")
    // Reads the OpenAPI documents the tests compare (feature 067); the Jackson the sbt build pins.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.23.0")
}

application {
    mainClass.set("shoppingcart.kotlin.MainKt")
}
