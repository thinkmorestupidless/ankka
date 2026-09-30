package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.ServiceDescriptor

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The language a new service is written in. */
enum Language(val id: String):
  case Scala      extends Language("scala")
  case Python     extends Language("python")
  case TypeScript extends Language("typescript")
  case Rust       extends Language("rust")

object Language:
  def parse(text: String): Either[String, Language] = text.toLowerCase match
    case "scala"             => Right(Scala)
    case "python" | "py"     => Right(Python)
    case "typescript" | "ts" => Right(TypeScript)
    case "rust" | "rs"       => Right(Rust)
    case other => Left(s"unknown language '$other'; one of scala, python, typescript, rust")

/**
 * `ankka init <name>`: a new service from the platform's template.
 *
 * For Scala it runs `sbt new` — Giter8, the way every Scala framework's template is expanded — with
 * the same template `sbt new thinkmorestupidless/ankka.g8` uses; the CLI carries no Scala template
 * and no template engine, so the two front doors cannot drift. For Python, TypeScript and Rust the
 * template is this CLI's own and `Scaffold` renders it (see there for why). Either way the version
 * handed to the template is this CLI's own: the CLI you run is the version you get.
 */
object Init:

  val DefaultTemplate: String = "thinkmorestupidless/ankka.g8"

  final case class Request(
      name: String,
      template: Option[String] = None,
      pkg: Option[String] = None,
      directory: Path = Path.of("."),
      language: Language = Language.Scala
  ):
    def templateRef: String = template.getOrElse(DefaultTemplate)

  /**
   * The `sbt` invocation, as a value — so the argument shape is tested without a process.
   *
   * `new` and each of its arguments are separate arguments, never one quoted command string. sbt's
   * launcher recognises `new` only as an argument of its own, and runs it outside any build; handed
   * `"new … --name=…"` as one string, the sbt 2 launcher sends it through its thin client, which
   * appends commands of its own (`sbtCompleteExec <id>`, `shell`) that giter8 then rejects as
   * unknown template arguments — so `ankka init` failed for every sbt 2 user, whatever the
   * template.
   */
  def command(
      request: Request,
      version: String = com.thinkmorestupidless.ankka.core.BuildInfo.version
  ): Vector[String] =
    Vector("sbt", "--allow-empty", "-batch") ++ newArguments(request, version)

  def newArguments(request: Request, version: String): Vector[String] =
    Vector(
      "new",
      request.templateRef,
      s"--name=${request.name}",
      s"--ankka_version=$version"
    ) ++ request.pkg.map(p => s"--package=$p")

  /** Everything that can be wrong before a process is started; empty means go. */
  def problems(request: Request): Vector[String] =
    val name   = ServiceDescriptor.nameProblems(request.name)
    val target = request.directory.resolve(request.name)
    val occupied =
      if Files.isDirectory(target) && Files.list(target).iterator().asScala.nonEmpty then
        Vector(s"$target already exists and is not empty")
      else Vector.empty
    val options = request.language match
      case Language.Scala  => Vector.empty
      case Language.Python => template(request) ++ Scaffold.moduleProblems(Scaffold.module(request))
      case Language.TypeScript =>
        template(request) ++ request.pkg
          .map(_ => "--package applies to scala and python only")
          .toVector
      case Language.Rust =>
        template(request) ++ request.pkg
          .map(_ => "--package applies to scala and python only")
          .toVector ++ Scaffold.crateProblems(request.name)
    name ++ occupied ++ options

  private def template(request: Request): Vector[String] =
    request.template.map(_ => "--template applies to scala only; its template is sbt's").toVector

  def sbtOnPath(env: Map[String, String] = sys.env): Boolean =
    val path = env.getOrElse("PATH", "")
    path
      .split(java.io.File.pathSeparator)
      .exists(dir => Files.isExecutable(Path.of(dir).resolve("sbt")))

  /** Runs the expansion; the exit code is `sbt new`'s. */
  def run(
      request: Request,
      version: String = com.thinkmorestupidless.ankka.core.BuildInfo.version
  ): Int =
    val process = new ProcessBuilder(command(request, version)*)
      .directory(request.directory.toFile)
      .inheritIO()
      .start()
    process.waitFor()
