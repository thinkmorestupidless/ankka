package com.thinkmorestupidless.ankka.cli.openapi

import com.thinkmorestupidless.ankka.cli.local.{LocalFile, SourceCommands}
import com.thinkmorestupidless.ankka.cli.local.SourceCommands.{Language, RunCommand}
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.io.{InputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

/**
 * `ankka openapi` (feature 067, contracts/command.md): runs the service's own build with
 * `ankka.document` set, so its `main` writes the document and ends, and nothing else is started.
 */
object OpenApiCommand:

  /** What the command was asked; `out` is a file, `-` for standard output, or the default. */
  final case class Options(
      dir: Path,
      out: Option[String] = None,
      strict: Boolean = false,
      tags: Vector[String] = Vector.empty,
      prefixes: Vector[String] = Vector.empty,
      apiVersion: Option[String] = None,
      check: Option[Path] = None
  )

  /** Runs a command with an environment, its output going to the stream given; its exit status. */
  type Runner = (RunCommand, Map[String, String], PrintStream) => Int

  /** The languages a document can be written for: those whose service is a JVM ankka service. */
  private val Documented: Set[Language] = Set(Language.Scala, Language.Java, Language.Kotlin)

  def run(
      options: Options,
      out: PrintStream,
      err: PrintStream,
      env: Map[String, String] = sys.env,
      runner: Runner = runProcess
  ): Int =
    locate(options.dir) match
      case Left(refusal) =>
        err.println(refusal)
        2
      case Right((root, service, serviceDir)) =>
        SourceCommands.language(serviceDir) match
          case None =>
            err.println(s"cannot tell the language of the service at $serviceDir")
            2
          case Some(language) if !Documented(language) =>
            err.println(s"a ${language.toString.toLowerCase} service has no OpenAPI document yet")
            2
          case Some(language) =>
            commandFor(root, service, language) match
              case None =>
                err.println(s"cannot tell how to run \"$service\" from its source")
                2
              case Some(command) =>
                write(options, root, service, serviceDir, command, out, err, env, runner)

  /** The project's root, the service's name and its directory, from the directory given. */
  private[openapi] def locate(dir: Path): Either[String, (Path, String, Path)] =
    val at     = dir.toAbsolutePath.normalize
    val parent = Option(at.getParent)
    if parent.exists(_.getFileName.toString == "services") then
      Right((parent.get.getParent, at.getFileName.toString, at))
    else
      val services = at.resolve("services")
      val found    =
        if !Files.isDirectory(services) then Vector.empty
        else Files.list(services).iterator.asScala.filter(Files.isDirectory(_)).toVector.sorted
      found match
        case Vector(one) => Right((at, one.getFileName.toString, one))
        case Vector() => Left(s"$at is not a service's directory, nor a project's with one service")
        case several  =>
          Left(
            s"$at holds ${several.size} services; name one: ankka openapi services/<service>"
          )

  /** `local.json`'s `run` for the service, else the language's own, where `local run` runs it. */
  private def commandFor(root: Path, service: String, language: Language): Option[RunCommand] =
    val directory =
      if language == Language.Scala then root else root.resolve("services").resolve(service)
    LocalFile
      .read(root)
      .toOption
      .flatMap(_.run.get(service))
      .filter(_.nonEmpty)
      .map(RunCommand(_, directory))
      .orElse(SourceCommands.default(root, service, language))

  /** The properties the service's `main` reads, for `JAVA_TOOL_OPTIONS`. */
  private[openapi] def properties(
      options: Options,
      service: String,
      out: Path
  ): Vector[(String, String)] =
    Vector(
      Some("ankka.document"                                    -> "openapi"),
      Some("ankka.document.out"                                -> out.toString),
      Some("ankka.service.name"                                -> service),
      Option.when(options.strict)("ankka.document.strict"      -> "true"),
      Option.when(options.tags.nonEmpty)("ankka.document.tags" -> options.tags.mkString(",")),
      Option.when(options.prefixes.nonEmpty)(
        "ankka.document.prefixes" -> options.prefixes.mkString(",")
      ),
      options.apiVersion.map("ankka.document.version" -> _)
    ).flatten

  /** `-Dk=v`, quoted when it holds a space, as `JAVA_TOOL_OPTIONS` reads it. */
  private def option(key: String, value: String): String =
    val word = s"-D$key=$value"
    if word.exists(_.isWhitespace) then "\"" + word + "\"" else word

  private def write(
      options: Options,
      root: Path,
      service: String,
      serviceDir: Path,
      command: RunCommand,
      out: PrintStream,
      err: PrintStream,
      env: Map[String, String],
      runner: Runner
  ): Int =
    val scratch   = Files.createTempDirectory("ankka-openapi")
    val temporary = scratch.resolve("openapi.json")
    try
      val added = properties(options, service, temporary).map(option.tupled).mkString(" ")
      val tool  =
        (env.get("JAVA_TOOL_OPTIONS").filter(_.trim.nonEmpty).toVector :+ added).mkString(" ")
      // Everything the build prints goes to the error stream, so `--out -` prints the document alone.
      val status = runner(command, Map("JAVA_TOOL_OPTIONS" -> tool), err)
      if status != 0 || !Files.isRegularFile(temporary) then
        if status == 0 then
          err.println(s"the build of \"$service\" ended without writing a document")
        1
      else
        val text = Files.readString(temporary, UTF_8)
        options.out match
          case Some("-") =>
            out.print(text)
            out.flush()
            0
          case chosen =>
            val target =
              chosen.map(Path.of(_)).getOrElse(serviceDir.resolve("openapi.json")).toAbsolutePath
            Option(target.getParent).foreach(Files.createDirectories(_))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            val (routes, incomplete) = counts(text)
            err.println(s"wrote ${root.relativize(target)}: $routes routes, $incomplete incomplete")
            0
    finally
      Files.deleteIfExists(temporary)
      Files.deleteIfExists(scratch): Unit

  /** How many operations a document holds, and how many of them are marked incomplete. */
  private[openapi] def counts(text: String): (Int, Int) =
    GraphJson.parse(text.getBytes(UTF_8)) match
      case Right(document) =>
        val operations = document.field("paths").toVector.flatMap {
          case GraphJson.Obj(paths) =>
            paths.flatMap {
              case (_, GraphJson.Obj(methods)) => methods.map(_._2)
              case _                           => Vector.empty
            }
          case _ => Vector.empty
        }
        (operations.size, operations.count(_.field("x-ankka-incomplete").isDefined))
      case Left(_) => (0, 0)

  /**
   * The build, with its output copied to `to`; stopped from outside, it and its children go too.
   */
  private def runProcess(
      command: RunCommand,
      environment: Map[String, String],
      to: PrintStream
  ): Int =
    val builder = new ProcessBuilder(command.words.asJava)
      .directory(command.directory.toFile)
      .redirectInput(ProcessBuilder.Redirect.INHERIT)
    environment.foreach((k, v) => builder.environment().put(k, v): Unit)
    val process                         = builder.start()
    def copy(from: InputStream): Thread =
      Thread.ofVirtual().start(() => from.transferTo(to): Unit)
    val pumps        = Vector(copy(process.getInputStream), copy(process.getErrorStream))
    def stop(): Unit =
      process.descendants().forEach(_.destroy(): Unit)
      process.destroy()
    val hook = new Thread(() => stop())
    Runtime.getRuntime.addShutdownHook(hook)
    try
      val status = process.waitFor()
      pumps.foreach(_.join())
      to.flush()
      status
    catch
      case _: InterruptedException =>
        stop()
        130
    finally
      try Runtime.getRuntime.removeShutdownHook(hook): Unit
      catch case _: IllegalStateException => ()
