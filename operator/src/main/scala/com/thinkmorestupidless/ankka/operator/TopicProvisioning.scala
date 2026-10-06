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

  /** The topics to render: every declared topic, less any whose resource already asks for more. */
  def topicsToRender(
      spec: AnkkaProjectSpec,
      broker: Option[BrokerSettings],
      observed: Map[String, TopicState]
  ): Vector[ProjectTopicEntry] =
    if broker.isEmpty then Vector.empty
    else spec.topics.toVector.filterNot(t => shrinks(spec, t, observed))

  def decide(
      projectId: String,
      entry: ProjectTopicEntry,
      broker: Option[BrokerSettings],
      observed: Option[TopicState]
  ): TopicPlan =
    if broker.isEmpty then TopicPlan.Failed(Vector("the installation has no broker"))
    else
      val name = BrokerNames.topic(projectId, entry.name)
      observed match
        case Some(TopicState(_, Some(partitions))) if partitions > entry.partitions =>
          TopicPlan.Failed(
            Vector(
              s"topic '$name' has $partitions partitions and cannot have fewer; " +
                s"${entry.partitions} was asked"
            )
          )
        case Some(TopicState(state, _))
            if state.ready.contains(false) &&
              state.reason.exists(BrokerProvisioning.PermanentReasons) =>
          TopicPlan.Failed(
            Vector(s"topic '$name': ${state.message.orElse(state.reason).getOrElse("refused")}")
          )
        case Some(TopicState(state, partitions))
            if state.ready.contains(true) && partitions.forall(_ == entry.partitions) =>
          TopicPlan.Ready(recovered = madeBefore(state, entry))
        case _ =>
          TopicPlan.Waiting(Some(s"waiting for the broker to make topic '$name'"))

  /** Every declared topic's phase, in the order the project declares them. */
  def status(
      spec: AnkkaProjectSpec,
      broker: Option[BrokerSettings],
      observed: Map[String, TopicState]
  ): AnkkaProjectStatus =
    AnkkaProjectStatus(
      spec.topics.map { t =>
        val seen = observed.get(BrokerNames.topic(spec.projectId, t.name))
        val plan = decide(spec.projectId, t, broker, seen)
        ProjectTopicStatus(t.name, plan.phase, seen.flatMap(_.partitions), plan.explanation)
      }
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
