package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Contract
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/**
 * What the project declares about its topics and brokers, as the operator writes it for every
 * service of the project (feature 037, research R3): a file named by `ANKKA_PROJECT_DECLARATIONS`,
 * read once at start. Without the variable, or without the file, nothing is declared and nothing is
 * checked, which is a local run or a project with no declarations yet.
 *
 * ```json
 * {"project": "shop",
 *  "topics": [{"name": "orders", "partitions": 3, "compacted": false,
 *              "contract": {"name": "order.v1", "fingerprint": "sha256:…"}}],
 *  "brokers": [{"name": "legacy", "bootstrap": "kafka.legacy:9094", "shape": "sasl"}]}
 * ```
 */
final case class ProjectDeclarations(
    project: String,
    topics: Map[String, ProjectDeclarations.Topic],
    brokers: Map[String, ProjectDeclarations.Broker]
)

object ProjectDeclarations:

  val EnvVar: String = "ANKKA_PROJECT_DECLARATIONS"

  final case class Topic(
      name: String,
      partitions: Int,
      compacted: Boolean,
      contract: Option[Contract]
  )

  final case class Broker(name: String, bootstrap: String, shape: String)

  /**
   * `None` when the variable is unset or the file is absent; `Left` when the file is not the shape
   * above.
   */
  def fromEnv(env: Map[String, String] = sys.env): Either[String, Option[ProjectDeclarations]] =
    env.get(EnvVar).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(path) =>
        val file = Paths.get(path)
        if !Files.exists(file) then Right(None) else read(file).map(Some(_))

  def read(file: Path): Either[String, ProjectDeclarations] =
    Try(Files.readAllBytes(file)).toEither.left
      .map(e => s"${file.toString}: ${e.getMessage}")
      .flatMap(parse)

  def parse(bytes: Array[Byte]): Either[String, ProjectDeclarations] =
    GraphJson.parse(bytes).flatMap { json =>
      for
        project <- text(json, "project")
        topics  <- items(json, "topics").flatMap(all(_)(topic))
        brokers <- items(json, "brokers").flatMap(all(_)(broker))
      yield ProjectDeclarations(
        project,
        topics.map(t => t.name -> t).toMap,
        brokers.map(b => b.name -> b).toMap
      )
    }

  private def topic(json: GraphJson): Either[String, Topic] =
    for
      name       <- text(json, "name")
      partitions <- number(json, "partitions")
      compacted  <- flag(json, "compacted")
      contract <- json.field("contract") match
        case None | Some(GraphJson.Null) => Right(None)
        case Some(c) =>
          for
            cn <- text(c, "name")
            fp <- text(c, "fingerprint")
          yield Some(Contract(cn, fp))
    yield Topic(name, partitions, compacted, contract)

  private def broker(json: GraphJson): Either[String, Broker] =
    for
      name      <- text(json, "name")
      bootstrap <- text(json, "bootstrap")
      shape     <- text(json, "shape")
    yield Broker(name, bootstrap, shape)

  private def items(json: GraphJson, name: String): Either[String, Vector[GraphJson]] =
    json.field(name) match
      case None | Some(GraphJson.Null) => Right(Vector.empty)
      case Some(GraphJson.Arr(items))  => Right(items)
      case Some(other)                 => Left(s"'$name' is not an array: $other")

  private def all[A](
      items: Vector[GraphJson]
  )(read: GraphJson => Either[String, A]): Either[String, Vector[A]] =
    items.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, item) =>
      for
        done <- acc
        one  <- read(item)
      yield done :+ one
    }

  private def text(json: GraphJson, name: String): Either[String, String] = json.field(name) match
    case Some(GraphJson.Str(value)) => Right(value)
    case other                      => Left(s"'$name' is not text: ${other.getOrElse("absent")}")

  private def number(json: GraphJson, name: String): Either[String, Int] = json.field(name) match
    case Some(GraphJson.Num(value)) => Right(value.toInt)
    case other => Left(s"'$name' is not a number: ${other.getOrElse("absent")}")

  private def flag(json: GraphJson, name: String): Either[String, Boolean] = json.field(name) match
    case Some(GraphJson.Bool(value)) => Right(value)
    case None | Some(GraphJson.Null) => Right(false)
    case Some(other)                 => Left(s"'$name' is not true or false: $other")
