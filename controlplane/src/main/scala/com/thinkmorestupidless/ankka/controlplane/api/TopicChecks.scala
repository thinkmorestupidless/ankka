package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.{InstanceTopologyDocument, TopicCheck}
import com.thinkmorestupidless.ankka.controlplane.domain.DeclaredTopic

import java.time.Instant
import scala.util.Try

/**
 * What each running service states for a project's declared topics, against the declarations
 * (feature 037): the runtime refuses a mismatch at start, and this is the listing's view of the
 * same thing, read from the topology edges every instance reports, so a declaration made after a
 * service started shows up without the service restarting. A topic on a declared broker is that
 * broker's and is not checked here.
 */
object TopicChecks:

  /** The checks of every edge on a declared topic with a contract, by topic name. */
  def of(
      declared: Map[String, DeclaredTopic],
      documents: Vector[(String, InstanceTopologyDocument)]
  ): Map[String, Vector[TopicCheck]] =
    documents
      .flatMap { (service, document) =>
        val started = Try(Instant.parse(document.service.startedAt)).toOption
        document.declared.collect {
          case edge if edge.kind == "topic-subscription" && edge.broker.isEmpty =>
            (edge.from.stripPrefix("topic:"), service, edge.to, "reads", edge.contract, started)
          case edge if edge.kind == "topic-publication" && edge.broker.isEmpty =>
            (edge.to.stripPrefix("topic:"), service, edge.from, "publishes", edge.contract, started)
        }
      }
      .flatMap { (topic, service, component, direction, stated, started) =>
        declared.get(topic).flatMap(_.contract).map { expected =>
          val state =
            if stated.contains(expected) then "checked"
            else if started.exists(s =>
                expected.fingerprint.nonEmpty && isBefore(s, declared(topic).declaredAt)
              )
            then "unchecked"
            else "mismatch"
          topic -> TopicCheck(topic, service, component, direction, stated.map(_.name), state)
        }
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct.sortBy(c => (c.service, c.component, c.direction)))
      .toMap

  /** The checks of one service, from its own documents, flattened. */
  def ofService(
      declared: Map[String, DeclaredTopic],
      service: String,
      documents: Vector[InstanceTopologyDocument]
  ): Vector[TopicCheck] =
    of(declared, documents.map(service -> _)).toVector.sortBy(_._1).flatMap(_._2)

  private def isBefore(started: Instant, declaredAt: Option[Instant]): Boolean =
    declaredAt.exists(started.isBefore)
