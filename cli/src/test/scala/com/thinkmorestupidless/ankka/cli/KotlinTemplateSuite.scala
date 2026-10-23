package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * `ankka init --language kotlin`: the project builds and tests with a JDK and Docker alone. Gradle
 * comes from the project's own wrapper, into a Gradle home of the test's, so the suite proves "no
 * build tool installed" and leaves nothing in the developer's `~/.gradle`. The pin is this CLI's
 * version, which the build put in `~/.m2/repository` first (`templateArtifacts`, for
 * `-Dankka.template.tests=kotlin`: the Java SDK with `publishM2`, the Kotlin SDK with Gradle's
 * `publishToMavenLocal`). A project made from the template resolves ankka from Maven Central; an
 * init script in the test's Gradle home adds the local repository for ankka's group alone.
 */
class KotlinTemplateSuite extends PolyglotTemplateSuite(Language.Kotlin, "docker"):

  private def gradleHome = project.resolve(".test-gradle-home")

  override def beforeAll(): Unit =
    super.beforeAll()
    if !munitIgnore then
      val init = Files.createDirectories(gradleHome.resolve("init.d"))
      Files.writeString(
        init.resolve("ankka-local.gradle.kts"),
        """allprojects {
          |    repositories {
          |        mavenLocal { content { includeGroup("com.thinkmorestupidless") } }
          |    }
          |}
          |""".stripMargin
      ): Unit

  private def gradlew(tasks: String*): (Int, String) =
    val command = Vector("./gradlew", "--quiet", "--no-daemon") ++ tasks
    val process = new ProcessBuilder(command*)
      .directory(project.toFile)
      .redirectErrorStream(true)
    process.environment().put("GRADLE_USER_HOME", gradleHome.toString)
    val started = process.start()
    val text    = new String(started.getInputStream.readAllBytes())
    print(text)
    (started.waitFor(), text)

  test("the wrapper is executable and pins a Gradle, and the project pins this CLI's ankka") {
    assert(Files.isExecutable(project.resolve("gradlew")), "gradlew is not executable")
    val jar = project.resolve("gradle/wrapper/gradle-wrapper.jar")
    // A jar starts PK: written as text it would not, and the wrapper could not start.
    assertEquals(Files.readAllBytes(jar).take(2).toVector, Vector('P'.toByte, 'K'.toByte))
    val wrapper = Files.readString(project.resolve("gradle/wrapper/gradle-wrapper.properties"))
    assert(wrapper.contains("distributionUrl="), wrapper)
    val properties = Files.readString(project.resolve("gradle.properties"))
    assert(properties.linesIterator.contains(s"ankkaVersion=$Version"), properties)
    val settings = Files.readString(project.resolve("settings.gradle.kts"))
    assert(settings.contains("JavaVersion.VERSION_21"), settings)
    assert(settings.contains(s"""rootProject.name = "$Name""""), settings)
    // The deploy pushes one tag holding both architectures, as every ankka image does.
    val deploy = Files.readString(project.resolve(".github/workflows/deploy.yml"))
    assert(deploy.contains("--platform linux/amd64,linux/arm64"), deploy)
  }

  test("the project's own tests pass, all three classes, none skipped") {
    val (code, _) = gradlew("test")
    assertEquals(code, 0, "./gradlew test")
    val reports = project.resolve("build/test-results/test")
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
    assertEquals(
      counts.size,
      3,
      s"expected ShoppingCartTest, CartViewTest and ServiceTest in $reports"
    )
    assert(counts.map(_._1).sum >= 5, counts.toString)
    assertEquals(counts.map(_._2).sum, 0, "a test skipped")
    assertEquals(counts.map(_._3).sum, 0, "a test failed")
  }

  test("the schema task copies the runtime's DDL where the compose file mounts it") {
    val (code, _) = gradlew("schema")
    assertEquals(code, 0, "./gradlew schema")
    val ddl = project.resolve("build/ddl/ankka/ddl")
    val sql = Files.list(ddl).iterator().asScala.map(_.getFileName.toString).toVector
    assert(sql.exists(_.endsWith(".sql")), sql.toString)
    assert(
      Files.readString(project.resolve("docker-compose.yml")).contains("./build/ddl/ankka/ddl:")
    )
  }

  test("the image builds from the installed distribution, under the descriptor's name") {
    val (code, _) = gradlew("installDist")
    assertEquals(code, 0, "./gradlew installDist")
    val (built, _) = run("docker", "build", "-q", "-t", s"$Name:local", ".")
    assertEquals(built, 0, "docker build")
    val (inspected, _) = run("docker", "image", "inspect", s"$Name:local")
    assertEquals(inspected, 0, s"$Name:local")
    run("docker", "image", "rm", "-f", s"$Name:local"): Unit
  }
