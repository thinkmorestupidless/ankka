package com.thinkmorestupidless.ankka.cli.local

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReaderException, readFromString}
import com.thinkmorestupidless.ankka.controlplane.api.{ServiceDescriptor, Wire}
import com.thinkmorestupidless.ankka.core.{ContextMap, TopicDeclarations}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * Everything `ankka local up` reads of a project (feature 066, research R2): the context map, each
 * service's descriptor, the topic declarations and the local file, as values. Reading answers every
 * problem it finds together, so a developer fixes them in one pass.
 */
final case class LocalProject(
    root: Path,
    map: ContextMap,
    descriptors: Map[String, ServiceDescriptor],
    topics: TopicDeclarations,
    local: LocalFile
):
  def name: String = map.project

  /** The services, in the order the context map names them. */
  def services: Vector[String] = map.services.map(_.name)

  def descriptor(service: String): ServiceDescriptor = descriptors(service)

object LocalProject:

  /** The refusal for a directory that holds no project. */
  def noProject(root: Path): String =
    s"$root holds no project: a project's directory holds ${ContextMap.FileName} and " +
      "services/<name>/service.json for each service"

  /** The project at `root`, or every problem that stops it being brought up. */
  def read(root: Path): Either[Vector[String], LocalProject] =
    if !Files.isRegularFile(root.resolve(ContextMap.FileName)) then Left(Vector(noProject(root)))
    else
      ContextMap.read(root) match
        case Left(problems) => Left(problems)
        case Right(map)     =>
          val checked     = ContextMap.check(root, map)
          val descriptors = map.services.map(_.name).distinct.flatMap { name =>
            val file = root.resolve("services").resolve(name).resolve("service.json")
            // A missing descriptor is the context map's check's line; it is not said twice.
            Option.when(Files.isRegularFile(file))(name -> descriptor(name, file))
          }
          val read     = descriptors.collect { case (name, Right(d)) => name -> d }.toMap
          val unread   = descriptors.collect { case (_, Left(problems)) => problems }.flatten
          val topics   = TopicDeclarations.read(root)
          val local    = LocalFile.read(root)
          val problems =
            (checked ++ unread ++ topics.left.getOrElse(Vector.empty) ++
              local.left.getOrElse(Vector.empty)).distinct
          if problems.nonEmpty then Left(problems)
          else
            Right(
              LocalProject(
                root,
                map,
                read,
                topics.toOption.flatten.getOrElse(TopicDeclarations(Vector.empty)),
                local.toOption.getOrElse(LocalFile())
              )
            )

  /** A descriptor read as `services apply -f` reads it: strictly, then held to its rules. */
  private def descriptor(name: String, file: Path): Either[Vector[String], ServiceDescriptor] =
    val path = s"services/$name/service.json"
    try
      val read = readFromString(Files.readString(file, UTF_8))(using Wire.strictDescriptorCodec)
      read.problems match
        case problems if problems.nonEmpty => Left(problems.map(p => s"$path: $p"))
        case _                             => Right(read)
    catch
      case failure: JsonReaderException =>
        Left(Vector(s"$path: ${Wire.descriptorProblem(failure)}"))
