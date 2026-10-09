package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.domain.FillTopicSettings
import com.thinkmorestupidless.ankka.controlplane.tenancy.TopicPolicy
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, RuntimeExtension}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal

/**
 * Fills the settings of every topic declared before topics stated them (feature 043), on every
 * start of the control plane: the installation's defaults then in force, recorded on each project
 * as the platform's act, with the topic's copies left as the broker's. A project with nothing to
 * fill records nothing, so after the first start this asks each project once and changes nothing.
 *
 * The control plane's, not the operator's: the operator cannot reach the control plane, and only
 * the control plane holds the installation's defaults and the project's history. Run off the
 * starting thread, so a slow listing never holds the control plane's start; a project whose fill
 * fails is logged and asked again at the next start.
 */
final class TopicSettingsSweep(policy: TopicPolicy) extends RuntimeExtension:

  private val log: Logger = LoggerFactory.getLogger("ankka.controlplane.topic-settings")

  @volatile private var finished: Option[Vector[(String, Vector[String])]] = None

  def name: String = "topic-settings-sweep"

  /** What the last sweep filled, by project, once it has finished. */
  def result: Option[Vector[(String, Vector[String])]] = finished

  def start(service: AnkkaService): Unit =
    Thread
      .ofVirtual()
      .name("topic-settings-sweep")
      .start(() => finished = Some(run(service))): Unit

  /** One sweep over every project, returning the topics it filled in each. */
  private[controlplane] def run(service: AnkkaService): Vector[(String, Vector[String])] =
    val projects = listing(service)
    val filled = projects.flatMap { projectId =>
      try
        val names = service.componentClient
          .forEventSourcedEntity(EntityId(projectId))
          .call(ProjectEntity.fillTopicSettings)
          .invoke(FillTopicSettings(policy.defaults))
          .names
        Option.when(names.nonEmpty)(projectId -> names)
      catch
        case NonFatal(e) =>
          log.warn("could not fill the topic settings of project '{}': {}", projectId, e.getMessage)
          None
    }
    filled.foreach((p, names) =>
      log.info(
        "filled the settings of topic(s) {} of project '{}' from the installation's defaults",
        names.mkString(", "),
        p
      )
    )
    filled

  /**
   * Every project, from the listing, which a start may find not yet answering; asked a few times.
   */
  private def listing(service: AnkkaService): Vector[String] =
    val projects = service.viewClient.forView(ProjectRows)
    Iterator
      .range(0, 10)
      .map { attempt =>
        try Some(projects.all(limit = 100000).map(_.id).distinct)
        catch
          case NonFatal(e) =>
            log.debug("the project listing is not answering yet ({}): {}", attempt, e.getMessage)
            Thread.sleep(3000)
            None
      }
      .collectFirst { case Some(ids) => ids }
      .getOrElse {
        log.warn("could not list projects to fill topic settings; the next start tries again")
        Vector.empty
      }
