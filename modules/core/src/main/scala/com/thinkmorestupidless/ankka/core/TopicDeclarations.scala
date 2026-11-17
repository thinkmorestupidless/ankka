package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * A project's topic declarations (feature 066): `topics.json` at the project's root, beside the
 * context map. The one file `ankka local up` makes topics from on a developer's machine and
 * `ankka projects topics apply` sends to an installation, so the two cannot differ; the context
 * map's check holds the map's topic crossings to it.
 *
 * Each field is the `ankka projects topics set` option of the same name, so the file says nothing
 * the command line could not. What an installation's control plane says of a declaration's settings
 * is its own rule, applied where the file is sent; what is here is what the file can be wrong about
 * on its own.
 */
final case class TopicDeclarations(topics: Vector[TopicDeclarations.Topic])

object TopicDeclarations:

  val FileName: String = "topics.json"

  final case class Topic(
      name: String,
      partitions: Int,
      compacted: Boolean = false,
      contract: Option[String] = None,
      schema: Option[String] = None,
      retention: Option[String] = None,
      retentionSize: Option[String] = None,
      cleanup: Option[String] = None,
      tombstoneWindow: Option[String] = None,
      minCompactionLag: Option[String] = None,
      maxCompactionLag: Option[String] = None,
      copies: Option[Int] = None,
      minInSync: Option[Int] = None
  )

  /** The bounds `ankka projects topics set` holds a declaration's partitions to. */
  val MinPartitions: Int = 1
  val MaxPartitions: Int = 1000

  // Strict: a field the platform does not know is refused by name, as the context map's is.
  given codec: JsonValueCodec[TopicDeclarations] = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(false)
  )

  private val UnknownField = """unexpected field "([^"]*)"""".r

  /** Reads `text` as topic declarations, or says why it is not a declarations file. */
  def parse(text: String): Either[Vector[String], TopicDeclarations] =
    try Right(readFromString[TopicDeclarations](text))
    catch
      case failure: JsonReaderException =>
        val why = UnknownField.findFirstMatchIn(failure.getMessage) match
          case Some(found) => s"it names a field the platform does not know: \"${found.group(1)}\""
          case None        => failure.getMessage.linesIterator.nextOption().getOrElse("")
        Left(Vector(s"$FileName is not a topic declarations file: $why"))

  /** The declarations at `root`: `None` when the project has no `topics.json`. */
  def read(root: Path): Either[Vector[String], Option[TopicDeclarations]] =
    val file = root.resolve(FileName)
    if !Files.isRegularFile(file) then Right(None)
    else parse(Files.readString(file, UTF_8)).map(Some(_))

  /**
   * What is wrong with the declarations on their own, against the project at `root` (the schema
   * documents they name), one line each. Empty means the file can be sent.
   */
  def problems(root: Path, declarations: TopicDeclarations): Vector[String] =
    val topics = declarations.topics
    val twice  = topics
      .groupBy(_.name)
      .collect { case (name, copies) if copies.size > 1 => name }
      .toVector
      .sorted
      .map(name => s"$FileName declares \"$name\" twice")
    topics.distinctBy(_.name).flatMap(topicProblems(root, _)) ++ twice

  private def topicProblems(root: Path, t: Topic): Vector[String] =
    val quoted = s"the topic \"${t.name}\""
    val name   =
      if Names.validTopicName(t.name) then Vector.empty
      else Vector(s"$quoted is not a valid topic name: ${Names.TopicNameRule}")
    val partitions =
      if t.partitions >= MinPartitions && t.partitions <= MaxPartitions then Vector.empty
      else
        Vector(
          s"$quoted declares ${t.partitions} partitions; a topic has $MinPartitions to $MaxPartitions"
        )
    val pairing = (t.contract, t.schema) match
      case (Some(_), None) => Vector(s"$quoted names a contract and no schema")
      case (None, Some(_)) => Vector(s"$quoted names a schema and no contract")
      case _               => Vector.empty
    val contract = t.contract.toVector.collect {
      case c if !Contract.validName(c) =>
        s"$quoted names the contract \"$c\", which is not a valid contract name: ${Contract.NameRule}"
    }
    val schema = t.schema.toVector.flatMap { path =>
      val file = root.resolve(path)
      if !Files.isRegularFile(file) then
        Vector(s"$quoted names the schema $path, which does not exist")
      else
        GraphJson
          .parse(Files.readAllBytes(file))
          .left
          .toOption
          .map(_ => s"$quoted names the schema $path, which is not a JSON document")
          .toVector
    }
    name ++ partitions ++ pairing ++ contract ++ schema

  /**
   * What is wrong between the context map's topic crossings and the declarations (`None`: the
   * project has no `topics.json`). A crossing sent by another project's service names that
   * project's topic and is not held to this file; a declared topic no crossing names is not a
   * problem.
   */
  def crossingProblems(
      map: ContextMap,
      declarations: Option[TopicDeclarations]
  ): Vector[String] =
    val sent = map.crossings.filter(c => c.topic.isDefined && !c.isRoute && !c.from.contains('/'))
    def quote(c: ContextMap.Crossing) = s"the crossing from \"${c.from}\" to \"${c.to}\""
    declarations match
      case None =>
        if sent.isEmpty then Vector.empty
        else
          Vector(s"${map.project} has topic crossings and no $FileName; declare each topic there")
      case Some(file) =>
        val byName = file.topics.map(t => t.name -> t).toMap
        sent.flatMap { c =>
          val topic = c.topic.getOrElse("")
          byName.get(topic) match
            case None =>
              Vector(s"${quote(c)} names the topic \"$topic\", which $FileName does not declare")
            case Some(declared) =>
              val contract = (c.contract, declared.contract) match
                case (Some(carried), Some(d)) if carried != d =>
                  Vector(
                    s"${quote(c)} carries \"$carried\" on \"$topic\", and $FileName declares \"$d\""
                  )
                case (Some(carried), None) =>
                  Vector(
                    s"${quote(c)} carries \"$carried\" on \"$topic\", and $FileName declares no contract"
                  )
                case _ => Vector.empty
              val schema = (c.schema, declared.schema) match
                case (Some(carried), Some(d)) if carried != d && contract.isEmpty =>
                  Vector(
                    s"${quote(c)} names the schema $carried for \"$topic\", and $FileName names $d"
                  )
                case _ => Vector.empty
              contract ++ schema
        }
