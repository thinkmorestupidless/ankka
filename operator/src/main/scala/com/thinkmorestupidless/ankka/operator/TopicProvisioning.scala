package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  AnkkaProjectStatus,
  ProjectTopicEntry,
  ProjectTopicStatus
}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Try

/** How far the platform has got with one topic a project declares (feature 027, research R22). */
enum TopicPlan:
  /** The broker's operators have not yet made the topic, or not yet grown it. */
  case Waiting(detail: Option[String])

  /** The topic is ready with its declared partitions; `recovered` when it was there before. */
  case Ready(recovered: Boolean)

  /** A problem waiting will not clear. */
  case Failed(problems: Vector[String])

  def phase: String = this match
    case Waiting(_)   => "Waiting"
    case Ready(false) => "Provisioned"
    case Ready(true)  => "Recovered"
    case Failed(_)    => "Failed"

  def explanation: Option[String] = this match
    case Waiting(detail)  => detail
    case Ready(_)         => None
    case Failed(problems) => Some(problems.mkString("; "))

/**
 * What the platform does about a project's declared topics, decided as a service's database is: a
 * pure function from what the operator observed to a plan per topic. What is rendered does not wait
 * on the plan, except that a topic is never rendered with fewer partitions than its resource
 * already asks for.
 */
object TopicProvisioning:

  /**
   * The topics to render: every declared topic, less any whose resource already asks for more
   * partitions, and less any asking for more copies than the broker has broker nodes (feature 043),
   * so nothing is made on the broker for it.
   */
  def topicsToRender(
      spec: AnkkaProjectSpec,
      broker: Option[BrokerSettings],
      observed: Map[String, TopicState],
      brokerNodes: Option[Int] = None
  ): Vector[ProjectTopicEntry] =
    if broker.isEmpty then Vector.empty
    else spec.topics.toVector.filterNot(t => shrinks(spec, t, observed) || tooMany(t, brokerNodes))

  def decide(
      projectId: String,
      entry: ProjectTopicEntry,
      broker: Option[BrokerSettings],
      observed: Option[TopicState],
      brokerNodes: Option[Int] = None
  ): TopicPlan =
    if broker.isEmpty then TopicPlan.Failed(Vector("the installation has no broker"))
    else if tooMany(entry, brokerNodes) then
      val copies = entry.replicas.getOrElse(0)
      val nodes  = brokerNodes.getOrElse(0)
      TopicPlan.Failed(
        Vector(
          s"topic '${BrokerNames.topic(projectId, entry.name)}' asks for $copies copies and the " +
            s"broker has $nodes broker node${if nodes == 1 then "" else "s"}"
        )
      )
    else
      val name = BrokerNames.topic(projectId, entry.name)
      observed match
        case Some(TopicState(_, Some(partitions), _, _, _)) if partitions > entry.partitions =>
          TopicPlan.Failed(
            Vector(
              s"topic '$name' has $partitions partitions and cannot have fewer; " +
                s"${entry.partitions} was asked"
            )
          )
        case Some(TopicState(state, _, _, _, _))
            if state.ready.contains(false) &&
              state.reason.exists(BrokerProvisioning.PermanentReasons) =>
          TopicPlan.Failed(
            Vector(s"topic '$name': ${state.message.orElse(state.reason).getOrElse("refused")}")
          )
        case Some(seen @ TopicState(state, partitions, _, _, _))
            if state.ready.contains(true) && partitions.forall(_ == entry.partitions) &&
              applied(entry, seen) =>
          TopicPlan.Ready(recovered = madeBefore(state, entry))
        case Some(TopicState(state, _, _, _, _)) if state.exists && state.ready.contains(true) =>
          TopicPlan.Waiting(Some(s"waiting for the broker to apply the settings of topic '$name'"))
        case _ =>
          TopicPlan.Waiting(Some(s"waiting for the broker to make topic '$name'"))

  /**
   * Whether the topic's resource says what the declaration says (feature 043): its copies where the
   * declaration states them, and its whole configuration where the declaration has settings; for a
   * declaration from before settings, its compaction alone (feature 037).
   */
  private def applied(entry: ProjectTopicEntry, seen: TopicState): Boolean =
    val copies = entry.replicas.forall(r => seen.replicas.contains(r))
    val config = entry.retentionMs match
      case Some(_) =>
        seen.config.contains(
          StrimziRendering
            .kafkaConfig(entry)
            .getOrElse(Map.empty)
            .map((k, v) => k -> String.valueOf(v))
        )
      case None => seen.compacted.forall(_ == entry.compacted)
    copies && config

  /** A topic that asks for more copies than the broker has broker nodes, when that is known. */
  private def tooMany(entry: ProjectTopicEntry, brokerNodes: Option[Int]): Boolean =
    (entry.replicas, brokerNodes) match
      case (Some(copies), Some(nodes)) => copies > nodes
      case _                           => false

  /** Every declared topic's phase, in the order the project declares them, and the node count. */
  def status(
      spec: AnkkaProjectSpec,
      broker: Option[BrokerSettings],
      observed: Map[String, TopicState],
      brokerNodes: Option[Int] = None
  ): AnkkaProjectStatus =
    AnkkaProjectStatus(
      spec.topics.map { t =>
        val seen = observed.get(BrokerNames.topic(spec.projectId, t.name))
        val plan = decide(spec.projectId, t, broker, seen, brokerNodes)
        ProjectTopicStatus(
          t.name,
          plan.phase,
          seen.flatMap(_.partitions),
          plan.explanation,
          seen.flatMap(_.compacted),
          seen.flatMap(_.replicas),
          seen.flatMap(_.config)
        )
      },
      brokerNodes
    )

  private def shrinks(
      spec: AnkkaProjectSpec,
      t: ProjectTopicEntry,
      observed: Map[String, TopicState]
  ): Boolean =
    observed
      .get(BrokerNames.topic(spec.projectId, t.name))
      .flatMap(_.partitions)
      .exists(_ > t.partitions)

  /**
   * A topic made before the project declared it: declared again after its declaration went.
   * Compared to the second, as Kubernetes keeps a creation time: a topic made a moment after a
   * declaration late in a second reads as made at that second's start, which is not before it.
   */
  private def madeBefore(state: StrimziObjectState, entry: ProjectTopicEntry): Boolean =
    (for
      created  <- state.createdAt
      declared <- Try(Instant.parse(entry.declaredAt)).toOption
    yield created.isBefore(declared.truncatedTo(ChronoUnit.SECONDS))).getOrElse(false)
