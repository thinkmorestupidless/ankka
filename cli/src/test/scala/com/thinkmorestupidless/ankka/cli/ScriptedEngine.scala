package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.Engine

import java.io.PrintStream
import java.nio.file.Path

/**
 * An engine that runs nothing (feature 066): every call is recorded, in order, and answered from
 * what a suite set. It keeps a compose project's containers as `up` and `down` are called, so `ps`
 * answers what a real engine would, and a suite may change a container's id or image to say it was
 * replaced.
 */
final class ScriptedEngine extends Engine:

  @volatile var availability: Either[String, Unit]                                    = Right(())
  @volatile var present: String => Boolean                                            = _ => true
  @volatile var pullable: String => Boolean                                           = _ => true
  @volatile var gateway: Option[String]                                               = None
  @volatile var execAnswer: (String, Vector[String], Option[String]) => Engine.Result =
    (_, _, _) => Engine.Result(0, "", "")
  @volatile var logLines: String => Vector[String] = service => Vector(s"$service logged")
  @volatile var copied: (String, String, Path) => Engine.Result = (_, _, _) =>
    Engine.Result(0, "", "")
  @volatile var upAnswer: Vector[String] => Engine.Result = _ => Engine.Result(0, "", "")

  /** Services whose container the next `up` replaces, and whether with another image. */
  @volatile var replace: Map[String, Boolean] = Map.empty

  private var recorded   = Vector.empty[String]
  private var containers = Map.empty[String, Map[String, Engine.Container]]
  private var next       = 0

  def calls: Vector[String] = synchronized(recorded)

  private def record(call: String): Unit = synchronized(recorded :+= call)

  private def newId(): String = synchronized { next += 1; f"c$next%04d" }

  /** The containers `project` holds now. */
  def running(project: String): Map[String, Engine.Container] =
    synchronized(containers.getOrElse(project, Map.empty))

  /** Changes a container as Docker would have, to script a replacement or a stop. */
  def change(project: String, service: String)(f: Engine.Container => Engine.Container): Unit =
    synchronized {
      val now = containers.getOrElse(project, Map.empty)
      now.get(service).foreach(c => containers += project -> (now + (service -> f(c))))
    }

  def available: Either[String, Unit] = { record("available"); availability }

  def imagePresent(image: String): Boolean = { record(s"image $image"); present(image) }

  def pull(image: String): Engine.Result =
    record(s"pull $image")
    if pullable(image) then Engine.Result(0, "", "")
    else Engine.Result(1, "", s"no such image $image")

  def gatewayAddress: Option[String] = { record("gateway"); gateway }

  def up(
      file: Path,
      project: String,
      services: Vector[String],
      removeOrphans: Boolean
  ): Engine.Result =
    record(
      s"up $project ${services.mkString(" ")}${if removeOrphans then " --remove-orphans" else ""}".trim
    )
    val answer = upAnswer(services)
    if answer.ok then
      synchronized {
        val now     = containers.getOrElse(project, Map.empty)
        val started = services.map { s =>
          val kept = now.get(s).filter(_.running)
          s -> (replace.get(s) match
            case Some(newImage) =>
              val image = kept.map(_.imageId).getOrElse(s"image-$s")
              Engine.Container(
                s,
                "running",
                "healthy",
                newId(),
                if newImage then image + "'" else image
              )
            case None =>
              kept.getOrElse(Engine.Container(s, "running", "healthy", newId(), s"image-$s")))
        }
        replace = replace -- services
        // What compose does with --remove-orphans: a container the file no longer names goes.
        val all       = now ++ started
        val remaining = if removeOrphans then all.filter((s, _) => named(file)(s)) else all
        containers += project -> remaining
      }
    answer

  private def named(file: Path)(service: String): Boolean =
    try
      com.github.plokhotnyuk.jsoniter_scala.core
        .readFromString[com.thinkmorestupidless.ankka.cli.local.Compose](
          java.nio.file.Files.readString(file)
        )
        .services
        .contains(service)
    catch case _: Exception => true

  def down(project: String, volumes: Boolean): Engine.Result =
    record(s"down $project${if volumes then " -v" else ""}")
    synchronized(containers -= project)
    Engine.Result(0, "", "")

  def ps(project: String): Vector[Engine.Container] =
    record(s"ps $project")
    running(project).values.toVector.sortBy(_.service)

  def logs(
      project: String,
      service: String,
      tail: Option[Int],
      follow: Boolean,
      to: PrintStream
  ): Int =
    record(s"logs $project $service")
    logLines(service).foreach(to.println)
    0

  def exec(
      project: String,
      service: String,
      words: Vector[String],
      stdin: Option[String]
  ): Engine.Result =
    record(s"exec $project $service ${words.mkString(" ")}")
    execAnswer(service, words, stdin)

  def copyOut(image: String, path: String, to: Path): Engine.Result =
    record(s"copy $image $path")
    copied(image, path, to)
