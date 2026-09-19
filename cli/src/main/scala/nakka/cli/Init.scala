package nakka.cli

import nakka.controlplane.api.ServiceDescriptor

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `nakka init <name>`: a new service from the platform's template.
 *
 * Runs `sbt new` — Giter8, the way every Scala framework's template is expanded — with the same
 * template `sbt new thinkmorestupidless/nakka.g8` uses; the CLI carries no template and no template
 * engine, so the two front doors cannot drift. The version handed to the template is this CLI's own
 * (`--nakka_version`): the CLI you run is the version you get, and the artifacts of that version
 * are the ones its `publishLocal` or release put where a build resolves them.
 */
object Init:

  val DefaultTemplate: String = "thinkmorestupidless/nakka.g8"

  final case class Request(
      name: String,
      template: String = DefaultTemplate,
      pkg: Option[String] = None,
      directory: Path = Path.of(".")
  )

  /** The `sbt` invocation, as a value — so the argument shape is tested without a process. */
  def command(request: Request, version: String = nakka.core.BuildInfo.version): Vector[String] =
    Vector("sbt", "--allow-empty", "-batch", newCommand(request, version))

  def newCommand(request: Request, version: String): String =
    val parts = Vector(
      "new",
      request.template,
      s"--name=${request.name}",
      s"--nakka_version=$version"
    ) ++ request.pkg.map(p => s"--package=$p")
    parts.mkString(" ")

  /** Everything that can be wrong before a process is started; empty means go. */
  def problems(request: Request): Vector[String] =
    val name   = ServiceDescriptor.nameProblems(request.name)
    val target = request.directory.resolve(request.name)
    val occupied =
      if Files.isDirectory(target) && Files.list(target).iterator().asScala.nonEmpty then
        Vector(s"$target already exists and is not empty")
      else Vector.empty
    name ++ occupied

  def sbtOnPath(env: Map[String, String] = sys.env): Boolean =
    val path = env.getOrElse("PATH", "")
    path
      .split(java.io.File.pathSeparator)
      .exists(dir => Files.isExecutable(Path.of(dir).resolve("sbt")))

  /** Runs the expansion; the exit code is `sbt new`'s. */
  def run(request: Request, version: String = nakka.core.BuildInfo.version): Int =
    val process = new ProcessBuilder(command(request, version)*)
      .directory(request.directory.toFile)
      .inheritIO()
      .start()
    process.waitFor()
