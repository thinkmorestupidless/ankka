package com.thinkmorestupidless.ankka.cli.local

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * Everything `ankka local` asks of the machine's containers (feature 066, data-model.md), behind
 * one seam: `DockerEngine` runs `docker` and `docker compose`; a suite scripts the answers. No
 * operation throws for a command that failed: it answers the exit and what was said, and the caller
 * decides what a failure means.
 */
trait Engine:
  /** Whether Docker answers and has Compose v2; the refusal when it does not. */
  def available: Either[String, Unit]
  def imagePresent(image: String): Boolean
  def pull(image: String): Engine.Result

  /** The default bridge network's gateway address. */
  def gatewayAddress: Option[String]
  def up(
      file: Path,
      project: String,
      services: Vector[String],
      removeOrphans: Boolean
  ): Engine.Result
  def down(project: String, volumes: Boolean): Engine.Result
  def ps(project: String): Vector[Engine.Container]

  /** A service's log written to `to` as Docker writes it; the exit code. */
  def logs(
      project: String,
      service: String,
      tail: Option[Int],
      follow: Boolean,
      to: PrintStream
  ): Int
  def exec(
      project: String,
      service: String,
      words: Vector[String],
      stdin: Option[String]
  ): Engine.Result

  /** The directory `path` of `image`, copied to `to`. */
  def copyOut(image: String, path: String, to: Path): Engine.Result

object Engine:

  final case class Result(exit: Int, out: String, err: String):
    def ok: Boolean = exit == 0

  /** A container of a compose project, as `docker compose ps` and `docker inspect` describe it. */
  final case class Container(
      service: String,
      state: String,
      health: String,
      id: String,
      imageId: String
  ):
    def running: Boolean = state == "running"

/** The engine over the machine's `docker` (`-Dankka.docker` names another executable). */
final class DockerEngine(executable: String = sys.props.getOrElse("ankka.docker", "docker"))
    extends Engine:

  private def run(words: Vector[String], stdin: Option[String] = None): Engine.Result =
    try
      val process = ProcessBuilder((executable +: words).asJava).start()
      stdin match
        case Some(text) =>
          val in = process.getOutputStream
          try in.write(text.getBytes(UTF_8))
          finally in.close()
        case None => process.getOutputStream.close()
      // Both streams are read at once, or a command that fills one pipe waits for ever.
      val err = java.util.concurrent.CompletableFuture.supplyAsync(() =>
        String(process.getErrorStream.readAllBytes(), UTF_8)
      )
      val out  = String(process.getInputStream.readAllBytes(), UTF_8)
      val exit = process.waitFor()
      Engine.Result(exit, out, err.get())
    catch case e: java.io.IOException => Engine.Result(127, "", e.getMessage)

  private def compose(project: String, words: String*): Vector[String] =
    Vector("compose", "-p", project) ++ words

  def available: Either[String, Unit] =
    if !run(Vector("version", "--format", "{{.Server.Version}}")).ok then
      Left("docker is not running on this machine")
    else if !run(Vector("compose", "version")).ok then
      Left("docker compose is not installed (Compose v2 is needed)")
    else Right(())

  def imagePresent(image: String): Boolean = run(Vector("image", "inspect", image)).ok

  def pull(image: String): Engine.Result = run(Vector("pull", image))

  def gatewayAddress: Option[String] =
    val answer =
      run(Vector("network", "inspect", "bridge", "--format", "{{(index .IPAM.Config 0).Gateway}}"))
    Option.when(answer.ok)(answer.out.trim).filter(_.nonEmpty)

  def up(
      file: Path,
      project: String,
      services: Vector[String],
      removeOrphans: Boolean
  ): Engine.Result =
    run(
      Vector("compose", "-p", project, "-f", file.toString, "up", "-d") ++
        Option.when(removeOrphans)("--remove-orphans") ++ services
    )

  def down(project: String, volumes: Boolean): Engine.Result =
    run(compose(project, "down") ++ Option.when(volumes)("-v"))

  def ps(project: String): Vector[Engine.Container] =
    val answer = run(compose(project, "ps", "--all", "--format", "json"))
    if !answer.ok then Vector.empty
    else
      val rows   = DockerEngine.parsePs(answer.out)
      val images =
        if rows.isEmpty then Map.empty[String, String]
        else
          val inspected = run(Vector("inspect", "--format", "{{.Id}} {{.Image}}") ++ rows.map(_.ID))
          inspected.out.linesIterator.flatMap { line =>
            line.trim.split(' ') match
              case Array(id, image) => Some(id -> image)
              case _                => None
          }.toMap
      rows.map { r =>
        val image = images.collectFirst { case (id, i) if id.startsWith(r.ID) => i }.getOrElse("")
        Engine.Container(r.Service, r.State, r.Health, r.ID, image)
      }

  def logs(
      project: String,
      service: String,
      tail: Option[Int],
      follow: Boolean,
      to: PrintStream
  ): Int =
    try
      val words = compose(project, "logs", "--no-color") ++
        tail.toVector.flatMap(n => Vector("--tail", n.toString)) ++
        Option.when(follow)("--follow") :+ service
      val process = ProcessBuilder((executable +: words).asJava).redirectErrorStream(true).start()
      process.getOutputStream.close()
      process.getInputStream.transferTo(to)
      to.flush()
      process.waitFor()
    catch case _: java.io.IOException => 127

  def exec(
      project: String,
      service: String,
      words: Vector[String],
      stdin: Option[String]
  ): Engine.Result =
    run(compose(project, "exec", "-T", service) ++ words, stdin)

  def copyOut(image: String, path: String, to: Path): Engine.Result =
    val created = run(Vector("create", image))
    if !created.ok then created
    else
      val id = created.out.trim
      try run(Vector("cp", s"$id:$path", to.toString))
      finally run(Vector("rm", id)): Unit

object DockerEngine:

  private[cli] final case class PsRow(
      Service: String,
      State: String,
      Health: String = "",
      ID: String = ""
  )

  private given rowCodec: JsonValueCodec[PsRow]          = JsonCodecMaker.make
  private given rowsCodec: JsonValueCodec[Vector[PsRow]] = JsonCodecMaker.make

  /** `docker compose ps --format json`: one array (Compose before 2.21) or one object a line. */
  private[cli] def parsePs(text: String): Vector[PsRow] =
    val trimmed = text.trim
    if trimmed.isEmpty then Vector.empty
    else if trimmed.startsWith("[") then readFromString[Vector[PsRow]](trimmed)
    else trimmed.linesIterator.map(_.trim).filter(_.nonEmpty).map(readFromString[PsRow](_)).toVector
