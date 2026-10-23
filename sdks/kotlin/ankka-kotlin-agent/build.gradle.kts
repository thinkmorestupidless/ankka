val ankkaVersion = extra["ankkaVersion"] as String

dependencies {
    api(project(":ankka-kotlin"))
    api("com.thinkmorestupidless:ankka-java-agent:$ankkaVersion")
}
