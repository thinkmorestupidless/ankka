plugins {
    application
}

dependencies {
    implementation(project(":ankka-kotlin"))
    testImplementation(project(":ankka-kotlin-testkit"))
}

application {
    mainClass.set("shoppingcart.kotlin.MainKt")
}
