package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec

/**
 * Another project's topic granted to a service (feature 040): which project, which of its topics,
 * and the one right the grant gives. Read from the granting projects' `AnkkaProject` resources,
 * which hold accepted grants only, and rendered as a literal entry on the grantee's `KafkaUser`.
 */
final case class GrantedTopic(project: String, topic: String, right: String)

object GrantedTopic:
  val Consume = "consume"
  val Produce = "produce"

  /** The grantee word a grant to `service` of `project` carries. */
  def grantee(project: String, service: String): String = s"service:$project/$service"

  /**
   * The topics `projects` grant to `service` of `project`: every accepted topic grant naming it,
   * from whichever project made it. A grant a project makes to its own service grants nothing the
   * service's prefix entry does not already.
   */
  def naming(
      projects: Iterable[AnkkaProjectSpec],
      project: String,
      service: String
  ): Vector[GrantedTopic] =
    val word = grantee(project, service)
    projects.toVector
      .filter(_.projectId != project)
      .flatMap(p =>
        p.grants.collect {
          case g
              if g.kind == "topic" && g.grantee == word && g.topic.isDefined && g.right.isDefined =>
            GrantedTopic(p.projectId, g.topic.get, g.right.get)
        }
      )
      .distinct
      .sortBy(g => (g.project, g.topic, g.right))
