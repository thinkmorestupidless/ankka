package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * A project's context map (feature 062): its services, what each owns and why its boundary is where
 * it is, and every way something crosses from one to another, as one file at the project's root,
 * `context-map.json`. It is the one definition the CLI's `ankka context-map check` and `render`,
 * the testkit's run-time check and the design and inspect skills all read, so they cannot disagree
 * about what the map says.
 *
 * A crossing is one of two things and nothing else: a declared topic with the contract it carries
 * and the schema of that contract, or a route of the receiving service that the sending one calls.
 * A shared database, or one service reading another's events, is a defect, not a crossing, and has
 * no shape here to be written in.
 */
final case class ContextMap(
    project: String,
    services: Vector[ContextMap.Service],
    crossings: Vector[ContextMap.Crossing] = Vector.empty
)

object ContextMap:

  val FileName: String      = "context-map.json"
  val RenderingName: String = "CONTEXT-MAP.md"

  /** Why a boundary is where it is. */
  val Criteria: Vector[String] = Vector("ownership", "scaling", "consistency")

  final case class Boundary(criterion: String, why: String = "")

  final case class Service(name: String, owns: String = "", boundary: Boundary)

  /**
   * `topic`, `contract` and `schema` together, or `route` alone. `from` and `to` name a service of
   * the project, or another project's as `<project>/<service>`.
   */
  final case class Crossing(
      from: String,
      to: String,
      topic: Option[String] = None,
      contract: Option[String] = None,
      schema: Option[String] = None,
      route: Option[String] = None
  ):
    def isTopic: Boolean         = topic.isDefined || contract.isDefined || schema.isDefined
    def isRoute: Boolean         = route.isDefined
    def isCompleteTopic: Boolean = topic.isDefined && contract.isDefined && schema.isDefined

    /** What passes, as a check's message names it: the topic, or the route. */
    def by: String = topic.orElse(route).getOrElse("")

  // Strict: a field the platform does not know is refused by name, as a descriptor's is, rather
  // than dropped. Every field is written, so the file the CLI writes back says what it means.
  given codec: JsonValueCodec[ContextMap] = JsonCodecMaker.make(
    CodecMakerConfig
      .withSkipUnexpectedFields(false)
      .withTransientDefault(false)
      .withTransientEmpty(false)
  )

  private val UnknownField = """unexpected field "([^"]*)"""".r

  /** Reads `text` as a context map, or says why it is not one. */
  def parse(text: String): Either[Vector[String], ContextMap] =
    try Right(readFromString[ContextMap](text))
    catch
      case failure: JsonReaderException =>
        UnknownField.findFirstMatchIn(failure.getMessage) match
          case Some(found) =>
            Left(
              Vector(
                s"the context map names a field the platform does not know: \"${found.group(1)}\""
              )
            )
          case None => Left(Vector(s"$FileName: ${failure.getMessage}"))

  /** The map at `root`, or the problems that stop it being read. */
  def read(root: Path): Either[Vector[String], ContextMap] =
    val file = root.resolve(FileName)
    if !Files.isRegularFile(file) then
      Left(Vector(s"$root holds no project started from the project template (no $FileName)"))
    else parse(Files.readString(file, UTF_8))

  /** The map as the file holds it: two-space indented, a newline at the end. */
  def write(map: ContextMap): String =
    writeToString(map, WriterConfig.withIndentionStep(2)) + "\n"

  private val Methods = Set("GET", "POST", "PUT", "PATCH", "DELETE")

  private def validRoute(route: String): Boolean = route.split(' ') match
    case Array(method, path) => Methods(method) && path.startsWith("/")
    case _                   => false

  /** The services `services/` holds: each directory with a `service.json`, by directory name. */
  def serviceDirectories(root: Path): Vector[String] =
    val dir = root.resolve("services")
    if !Files.isDirectory(dir) then Vector.empty
    else
      val stream = Files.list(dir)
      try
        stream.iterator.asScala
          .filter(d => Files.isRegularFile(d.resolve("service.json")))
          .map(_.getFileName.toString)
          .toVector
          .sorted
      finally stream.close()

  // The descriptor's name, read as any JSON: `core` cannot see the descriptor's own codec. A file
  // that is not JSON is the message on the left.
  private def descriptorName(file: Path): Either[String, Option[String]] =
    GraphJson
      .parse(Files.readAllBytes(file))
      .map(_.field("name").collect { case GraphJson.Str(name) =>
        name
      })

  /**
   * Everything that is wrong between the map and the project at `root`, in the order the contract
   * lists the problems, one line each. Empty means the map is true of the project's directories,
   * its contracts and its rendering.
   */
  def check(root: Path, map: ContextMap): Vector[String] =
    val p                           = map.project
    val named                       = map.services.map(_.name)
    val directories                 = serviceDirectories(root)
    val local                       = (named ++ directories).toSet
    def outside(x: String): Boolean =
      x.split('/') match
        case Array(project, service) =>
          Names.Project.problems(project).isEmpty && Names.serviceNameProblems(service).isEmpty
        case _ => false
    def quote(c: Crossing) = s"the crossing from \"${c.from}\" to \"${c.to}\""

    val project =
      Names.Project
        .problems(p)
        .map(rule => s"the context map's project \"$p\" is not a valid project name: $rule")
    val criteria = map.services.collect {
      case s if !Criteria.contains(s.boundary.criterion) =>
        s"the boundary of \"${s.name}\" is drawn by \"${s.boundary.criterion}\", which is not " +
          Criteria.mkString(", ")
    }
    val namedTwice = named
      .groupBy(identity)
      .collect { case (n, copies) if copies.size > 1 => n }
      .toVector
      .sorted
      .map(n => s"the context map names \"$n\" twice")
    val withoutDirectory = named.distinct.collect {
      case n if !directories.contains(n) =>
        s"the context map names \"$n\", and services/$n/service.json does not exist"
    }
    val notNamed = directories.collect {
      case d if !named.contains(d) =>
        s"$p has a service \"$d\" (services/$d/service.json) that the context map does not name"
    }
    val misnamed = directories.flatMap { d =>
      descriptorName(root.resolve("services").resolve(d).resolve("service.json")) match
        case Left(message) => Some(s"services/$d/service.json is not a JSON document: $message")
        case Right(Some(n)) if n != d =>
          Some(s"services/$d/service.json is named \"$n\", not \"$d\"")
        case Right(_) => None
    }
    val unresolved = map.crossings.flatMap { c =>
      Vector(c.from, c.to).distinct.collect {
        case x if !local(x) && !outside(x) =>
          s"${quote(c)} names \"$x\", which is not a service of $p; another project's service is " +
            "written \"<project>/<service>\""
      }
    }
    val neither = map.crossings.collect {
      case c if !c.isRoute && !c.isCompleteTopic =>
        s"${quote(c)} is neither a declared topic with a contract and a schema nor a route"
    }
    val both = map.crossings.collect {
      case c if c.isRoute && c.isTopic =>
        s"${quote(c)} is both a topic and a route; a crossing is one or the other"
    }
    val schemas       = map.crossings.flatMap(c => c.schema.map(c -> _)).distinctBy(_._2)
    val missingSchema = schemas.collect {
      case (c, path) if !Files.isRegularFile(root.resolve(path)) =>
        s"the crossing \"${c.by}\" names the schema $path, which does not exist"
    }
    val notJson = schemas.flatMap { (_, path) =>
      val file = root.resolve(path)
      if !Files.isRegularFile(file) then None
      else
        GraphJson.parse(Files.readAllBytes(file)).left.toOption.map { message =>
          s"the schema $path is not a JSON document: $message"
        }
    }
    val contracts = map.crossings.flatMap(_.contract).distinct.collect {
      case c if !Contract.validName(c) =>
        s"the contract \"$c\" is not a valid contract name: ${Contract.NameRule}"
    }
    val topics = map.crossings.flatMap(_.topic).distinct.collect {
      case t if !Names.validTopicName(t) =>
        s"the topic \"$t\" is not a valid topic name: ${Names.TopicNameRule}"
    }
    val routes = map.crossings.flatMap(_.route).distinct.collect {
      case r if !validRoute(r) => s"the route \"$r\" is not \"<METHOD> <path>\""
    }
    val twice = map.crossings
      .groupBy(c => (c.from, c.to, c.by))
      .collect { case ((from, to, by), copies) if copies.size > 1 && by.nonEmpty => (from, to, by) }
      .toVector
      .sorted
      .map((from, to, by) => s"the crossing from \"$from\" to \"$to\" by \"$by\" is recorded twice")
    val rendering         = root.resolve(RenderingName)
    val renderingProblems =
      if !Files.isRegularFile(rendering) then
        Vector(s"$RenderingName does not exist; run ankka context-map render")
      else if Files.readString(rendering, UTF_8) != render(map) then
        Vector(s"$RenderingName is out of step with $FileName; run ankka context-map render")
      else Vector.empty

    project ++ criteria ++ namedTwice ++ withoutDirectory ++ notNamed ++ misnamed ++ unresolved ++
      neither ++
      both ++ missingSchema ++ notJson ++ contracts ++ topics ++ routes ++ twice ++ renderingProblems

  // A table cell holds no line break and no unescaped bar.
  private def cell(text: String): String = text.replace("\n", " ").replace("|", "\\|")

  /** `CONTEXT-MAP.md`: the map for people to read. The same map always renders the same bytes. */
  def render(map: ContextMap): String =
    val services = map.services.map { s =>
      s"| ${cell(s.name)} | ${cell(s.owns)} | ${cell(s.boundary.criterion)} | ${cell(s.boundary.why)} |"
    }
    val crossings = map.crossings.map { c =>
      val (what, how) =
        if c.isRoute && !c.isTopic then ("a call", s"`${cell(c.route.getOrElse(""))}`")
        else
          (
            s"the declared topic `${cell(c.topic.getOrElse(""))}`",
            s"`${cell(c.contract.getOrElse(""))}` (`${cell(c.schema.getOrElse(""))}`)"
          )
      s"| ${cell(c.from)} | ${cell(c.to)} | $what | $how |"
    }
    (Vector(
      s"# ${map.project}",
      "",
      s"*Rendered from `$FileName` by `ankka context-map render`; edit the file, not this page.*",
      "",
      "## Services",
      "",
      "| Service | Owns | Boundary | Why |",
      "|---|---|---|---|"
    ) ++ services ++ Vector(
      "",
      "## Crossings",
      "",
      "| From | To | What passes | Contract or route |",
      "|---|---|---|---|"
    ) ++ crossings).mkString("", "\n", "\n")

  /** The topic crossings that name `service`: those it publishes to and those it reads. */
  def topicCrossings(
      map: ContextMap,
      service: String
  ): (published: Vector[Crossing], read: Vector[Crossing]) =
    val topics = map.crossings.filter(c => c.topic.isDefined && !c.isRoute)
    (published = topics.filter(_.from == service), read = topics.filter(_.to == service))
