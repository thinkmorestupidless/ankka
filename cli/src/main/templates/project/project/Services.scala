import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.{Docker, dockerBuildxPlatforms}
import sbt._
import sbt.Keys._

import scala.sys.process._

/**
 * The project's Scala services, found by their directories: every `services/<name>/` holding a
 * `src/main/scala` and no build file of its own is an sbt project `<name>`, built against the
 * shared module and packaged as the image `<name>`. A service in another language has a build file
 * of its own and is built by its own tools, which is why it is not one of these.
 */
object Services extends AutoPlugin {

  override def trigger  = allRequirements
  override def requires = plugins.JvmPlugin

  /** The ankka version every service builds against; each service.json declares it as `runtime`. */
  val ankkaVersion = "{{ankka_version}}"

  private val root = file(sys.props.getOrElse("user.dir", "."))

  private val ownBuilds =
    Seq(
      "build.sbt",
      "pyproject.toml",
      "package.json",
      "Cargo.toml",
      "go.mod",
      "pom.xml",
      "build.gradle.kts"
    )

  /** The names of the Scala services, in order. */
  lazy val names: Seq[String] = {
    // The root is where sbt was started. Started anywhere else, the build would silently hold no
    // service and the tests module would check nothing, so it refuses to load instead.
    if (!(root / "services").isDirectory)
      sys.error(s"$root has no services/ directory: start sbt from the project's root")
    val dirs = Option((root / "services").listFiles()).map(_.toSeq).getOrElse(Nil)
    dirs
      .filter(d => d.isDirectory && (d / "src" / "main" / "scala").isDirectory)
      .filterNot(d => ownBuilds.exists(b => (d / b).exists))
      .map(_.getName)
      .sorted
  }

  override def extraProjects: Seq[Project] = names.map(service)

  private def service(name: String): Project =
    Project(name, file(s"services/$name"))
      .enablePlugins(JavaAppPackaging, DockerPlugin)
      .dependsOn(LocalProject("shared"))
      .settings(
        scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
        libraryDependencies ++= Seq(
          "com.thinkmorestupidless" %% "ankka-sdk"     % ankkaVersion,
          "com.thinkmorestupidless" %% "ankka-runtime" % ankkaVersion,
          "com.thinkmorestupidless" %% "ankka-http"    % ankkaVersion,
          // Exports the service's traces and metrics to the collector the installation names.
          "com.thinkmorestupidless" %% "ankka-telemetry-otlp" % ankkaVersion,
          "com.thinkmorestupidless" %% "ankka-testkit"        % ankkaVersion % Test,
          "org.scalameta"           %% "munit"                % "1.3.6"      % Test
        ),
        // The test kit starts a throwaway Postgres in Docker; a forked JVM keeps it out of sbt.
        Test / fork          := true,
        version              := versionOf(name),
        Docker / packageName := name,
        // Set, the image is tagged for that registry and `Docker/publish` pushes there.
        Docker / dockerRepository := sys.env.get("DOCKER_REPOSITORY"),
        // Set (the deploy workflow sets both), `Docker/publish` builds for each architecture.
        dockerBuildxPlatforms := sys.env
          .get("ANKKA_IMAGE_PLATFORMS")
          .toSeq
          .flatMap(_.split(','))
          .map(_.trim)
          .filter(_.nonEmpty),
        dockerBaseImage    := "eclipse-temurin:21-jre",
        dockerUpdateLatest := true,
        dockerExposedPorts := Seq(portOf(name)),
        // A Docker tag may not contain '+'; a version derived from the tags might.
        Docker / version := version.value.replace('+', '-')
      )

  /**
   * The service's local HTTP port, from its descriptor; the platform sets its own when deployed.
   */
  def portOf(name: String): Int = {
    val descriptor = root / "services" / name / "service.json"
    val text       = if (descriptor.exists) IO.read(descriptor) else ""
    "\"port\"\\s*:\\s*(\\d+)".r.findFirstMatchIn(text).map(_.group(1).toInt).getOrElse(9000)
  }

  /**
   * The service's version: SERVICE_VERSION when set, as the deploy workflow sets it; otherwise from
   * the service's own tags, `<name>/v<version>`, so each service's version follows its own
   * releases. At a tag it is that version; past one, or with no tag yet, a snapshot naming the
   * commit.
   */
  def versionOf(name: String): String =
    sys.env.get("SERVICE_VERSION").filter(_.nonEmpty).getOrElse {
      def git(args: String*): Option[String] =
        try {
          val out = Process("git" +: args, root).!!(ProcessLogger(_ => ())).trim
          if (out.isEmpty) None else Some(out)
        } catch { case _: Exception => None }
      val dirty = git("status", "--porcelain").isDefined
      git("rev-parse", "--short=8", "HEAD") match {
        case None      => "0.0.0-SNAPSHOT"
        case Some(sha) =>
          val described = git("describe", "--tags", "--long", "--match", s"$name/v*")
          val Described = s"$name/v(.+)-(\\d+)-g[0-9a-f]+".r
          described match {
            case Some(Described(release, "0")) if !dirty => release
            case Some(Described(release, distance))      => s"$release+$distance-$sha-SNAPSHOT"
            case _                                       =>
              val count = git("rev-list", "--count", "HEAD").getOrElse("0")
              s"0.0.0+$count-$sha-SNAPSHOT"
          }
      }
    }
}
