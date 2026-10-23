package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * `ankka init --language java`: the project builds and tests with a JDK and Docker alone. Maven
 * comes from the project's own wrapper, fetched into a directory of the test's, so the suite proves
 * "no build tool installed" and leaves nothing in the developer's `~/.m2/wrapper`. The pin is this
 * CLI's version, which the build put in `~/.m2/repository` with `publishM2` first
 * (`templateArtifacts`, for `-Dankka.template.tests=java`).
 */
class JavaTemplateSuite extends PolyglotTemplateSuite(Language.Java, "java", "docker"):

  private def mvnw(goals: String*): (Int, String) =
    val wrapperHome = project.resolve(".test-maven-home")
    val command     = Vector("./mvnw", "-B") ++ goals
    val process = new ProcessBuilder(command*)
      .directory(project.toFile)
      .redirectErrorStream(true)
    process.environment().put("MAVEN_USER_HOME", wrapperHome.toString)
    val started = process.start()
    val text    = new String(started.getInputStream.readAllBytes())
    print(text)
    (started.waitFor(), text)

  test("the wrapper is executable and pins a Maven, and the project pins this CLI's ankka") {
    assert(Files.isExecutable(project.resolve("mvnw")), "mvnw is not executable")
    val wrapper = Files.readString(project.resolve(".mvn/wrapper/maven-wrapper.properties"))
    assert(wrapper.contains("distributionUrl="), wrapper)
    val pom = Files.readString(project.resolve("pom.xml"))
    assert(pom.contains(s"<ankka.version>$Version</ankka.version>"), pom)
    // The deploy pushes one tag holding both architectures, as every ankka image does.
    val deploy = Files.readString(project.resolve(".github/workflows/deploy.yml"))
    assert(deploy.contains("-Djib.from.platforms=linux/amd64,linux/arm64"), deploy)
  }

  test("the project's own tests pass, both of them, none skipped") {
    val (code, _) = mvnw("verify")
    assertEquals(code, 0, "./mvnw verify")
    val reports = project.resolve("target/surefire-reports")
    val counts = Files
      .list(reports)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.matches("TEST-.*\\.xml"))
      .map(Files.readString(_))
      .map { xml =>
        def attr(name: String) =
          s"""$name="(\\d+)"""".r.findFirstMatchIn(xml).map(_.group(1).toInt).getOrElse(0)
        (attr("tests"), attr("skipped"), attr("failures") + attr("errors"))
      }
      .toVector
    assertEquals(counts.size, 2, s"expected ShoppingCartTest and ServiceTest in $reports")
    assert(counts.map(_._1).sum >= 3, counts.toString)
    assertEquals(counts.map(_._2).sum, 0, "a test skipped")
    assertEquals(counts.map(_._3).sum, 0, "a test failed")
  }

  test("the image builds without a Dockerfile, under the descriptor's name") {
    val (code, _) = mvnw("-q", "-DskipTests", "package", "jib:dockerBuild")
    assertEquals(code, 0, "./mvnw package jib:dockerBuild")
    val inspect = new ProcessBuilder("docker", "image", "inspect", s"$Name:local")
      .redirectErrorStream(true)
      .start()
    assertEquals(inspect.waitFor(), 0, new String(inspect.getInputStream.readAllBytes()))
    new ProcessBuilder("docker", "image", "rm", "-f", s"$Name:local").start().waitFor(): Unit
  }
