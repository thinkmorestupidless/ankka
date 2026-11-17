package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.controlplane.api.{
  CleanupPolicy,
  ContractDeclaration,
  GivenSettings,
  ProjectTopics,
  TopicDeclarationRequest,
  TopicSettingsRules
}
import com.thinkmorestupidless.ankka.core.TopicDeclarations
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.nio.file.{Files, Path}

/**
 * A `topics.json` entry as the control plane would be sent it (feature 066): the request
 * `ankka projects topics set` builds, its schema document read, and the rules it is held to.
 */
object TopicDeclarationRequests:

  def of(
      root: Path,
      topic: TopicDeclarations.Topic
  ): Either[Vector[String], TopicDeclarationRequest] =
    val contract = (topic.contract, topic.schema) match
      case (Some(name), Some(path)) if Files.isRegularFile(root.resolve(path)) =>
        GraphJson
          .parse(Files.readAllBytes(root.resolve(path)))
          .toOption
          .map(ContractDeclaration(name, _))
      case _ => None
    val request = TopicDeclarationRequest(
      partitions = Some(topic.partitions),
      compacted = topic.compacted,
      contract = contract,
      retention = topic.retention,
      retentionSize = topic.retentionSize,
      cleanup = topic.cleanup,
      tombstoneWindow = topic.tombstoneWindow,
      minCompactionLag = topic.minCompactionLag,
      maxCompactionLag = topic.maxCompactionLag,
      copies = topic.copies,
      minInSync = topic.minInSync
    )
    val problems = ProjectTopics.problems(topic.name, request)
    if problems.nonEmpty then Left(problems) else Right(request)

/**
 * The project's declared topics on the local broker (feature 066, research R5): what is wanted,
 * what the broker says it holds, what to make, and what cannot be made true without removing a
 * topic. Copies and minimum in-sync copies are not applied: the local broker is one node.
 */
object BrokerTopics:

  type BrokerTopic = LocalPlan.BrokerTopic

  val Script: String = "/opt/kafka/bin/kafka-topics.sh"

  /** Where the topic tool reaches the broker, from inside the broker's container. */
  val Bootstrap: String = s"${LocalPlan.Kafka}:${LocalPlan.KafkaContainersPort}"

  /** Each declaration as the broker is to hold it, under the project's prefix. */
  def wanted(project: String, declarations: TopicDeclarations): Vector[BrokerTopic] =
    declarations.topics.map { topic =>
      val request = TopicDeclarationRequest(
        partitions = Some(topic.partitions),
        compacted = topic.compacted,
        retention = topic.retention,
        retentionSize = topic.retentionSize,
        cleanup = topic.cleanup,
        tombstoneWindow = topic.tombstoneWindow,
        minCompactionLag = topic.minCompactionLag,
        maxCompactionLag = topic.maxCompactionLag
      )
      val settings = TopicSettingsRules.parse(topic.name, request).getOrElse(GivenSettings())
      LocalPlan.BrokerTopic(s"$project.${topic.name}", topic.partitions, config(topic, settings))
    }

  /** Kafka's names for the settings, as the operator writes them onto a `KafkaTopic`. */
  private def config(topic: TopicDeclarations.Topic, settings: GivenSettings): Map[String, String] =
    val cleanup =
      settings.cleanup.orElse(Option.when(topic.compacted)(CleanupPolicy.Compact)).map(_.text)
    Vector(
      "cleanup.policy"        -> cleanup,
      "retention.ms"          -> settings.retention.map(_.kafka.toString),
      "retention.bytes"       -> settings.retentionSize.map(_.kafka.toString),
      "delete.retention.ms"   -> settings.tombstoneWindow.map(_.millis.toString),
      "min.compaction.lag.ms" -> settings.minCompactionLag.map(_.millis.toString),
      "max.compaction.lag.ms" -> settings.maxCompactionLag.map(_.kafka.toString)
    ).collect { case (key, Some(value)) => key -> value }.toMap

  // `cleanup.policy=compact,delete,segment.bytes=…`: the policy's words run to the next `key=`.
  private val CleanupPolicyPattern = """cleanup\.policy=([a-z]+(?:,(?:compact|delete))*)""".r

  /** The tool's `--describe` answer: each topic's partition count and cleanup policy. */
  def parseDescribe(output: String): Map[String, (Int, String)] =
    output.linesIterator
      .map(_.trim)
      .filter(l => l.startsWith("Topic:") && !l.contains("Partition:"))
      .flatMap { line =>
        val fields = line
          .split('\t')
          .map(_.trim)
          .flatMap { f =>
            f.split(":", 2) match
              case Array(k, v) => Some(k.trim -> v.trim)
              case _           => None
          }
          .toMap
        for
          name  <- fields.get("Topic")
          count <- fields.get("PartitionCount").flatMap(_.toIntOption)
        yield
          val policy = CleanupPolicyPattern
            .findFirstMatchIn(fields.getOrElse("Configs", ""))
            .map(_.group(1))
            .getOrElse("delete")
          name -> (count, policy)
      }
      .toMap

  private def compacts(policy: String): Boolean = policy.split(',').map(_.trim).contains("compact")

  private def partitions(n: Int): String = if n == 1 then "1 partition" else s"$n partitions"

  /** What to make, and why a topic that exists otherwise than declared cannot be used. */
  def diff(
      wanted: Vector[BrokerTopic],
      existing: Map[String, (Int, String)]
  ): (Vector[BrokerTopic], Vector[String]) =
    val removes  = "ankka local down --volumes removes it"
    val toCreate = wanted.filterNot(t => existing.contains(t.name))
    val problems = wanted.flatMap { t =>
      existing.get(t.name).toVector.flatMap { (count, policy) =>
        val declaredCompacted = t.config.get("cleanup.policy").exists(compacts)
        val count_            = Option.when(count != t.partitions)(
          s"the topic \"${t.name}\" has ${partitions(count)} on the local broker and ${t.partitions} declared; $removes"
        )
        val compaction = (compacts(policy), declaredCompacted) match
          case (false, true) =>
            Some(
              s"the topic \"${t.name}\" is not compacted on the local broker and is declared compacted; $removes"
            )
          case (true, false) =>
            Some(
              s"the topic \"${t.name}\" is compacted on the local broker and is declared not compacted; $removes"
            )
          case _ => None
        count_.toVector ++ compaction
      }
    }
    (toCreate, problems)

  /** The tool's question of what the broker holds. */
  def describeCommand: Vector[String] =
    Vector(Script, "--bootstrap-server", Bootstrap, "--describe")

  /** One shell line that makes every topic of `topics`, stopping at the first that fails. */
  def createCommand(topics: Vector[BrokerTopic]): Vector[String] =
    val each = topics.map { t =>
      (Vector(
        Script,
        "--bootstrap-server",
        Bootstrap,
        "--create",
        "--if-not-exists",
        "--topic",
        t.name,
        "--partitions",
        t.partitions.toString,
        "--replication-factor",
        "1"
      ) ++
        t.config.toVector.sorted.flatMap((k, v) => Vector("--config", s"$k=$v")))
        .map(quote)
        .mkString(" ")
    }
    Vector("sh", "-c", each.mkString(" && "))

  private def quote(word: String): String =
    if word.forall(c => c.isLetterOrDigit || "-_./=:,".contains(c)) then word
    else "'" + word.replace("'", "'\\''") + "'"
