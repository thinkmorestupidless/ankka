package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.core.keyring.{HandlerReport, KeyringNotice}
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, AnkkaService}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.actor.typed.{ActorSystem, Behavior, PostStop}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * Runs the service's erasure handler on one instance (feature 042, R16). A cluster singleton, as
 * the timer sweeper is, marks the instance that holds it; every instance hears every notice, and
 * only that one runs the handler, so nothing crosses the cluster and two instances do not both
 * erase. A run that throws is reported as failed, with why, so the platform's own duties are
 * acknowledged all the same and the next pass runs the handler again.
 */
private[ankka] object ErasureHandlerHost:

  private val log = LoggerFactory.getLogger(getClass)

  def start(handler: ErasureHandler, service: AnkkaService, hooks: ErasureHooks)(using
      system: ActorSystem[?]
  ): Unit =
    val holding = AtomicBoolean(false)
    val _       = ClusterSingleton(system).init(
      SingletonActor(marker(holding), "ankka-erasure-handler")
    )
    hooks.onHandler(notice =>
      if !holding.get then Future.successful(None)
      else Future(Some(run(handler, service, notice)))(using AnkkaExecutors.virtual)
    )

  private def marker(holding: AtomicBoolean): Behavior[String] =
    Behaviors.setup { _ =>
      holding.set(true)
      Behaviors.receiveSignal { case (_, PostStop) =>
        holding.set(false)
        Behaviors.same
      }
    }

  /** One run, on the calling thread; never throws. */
  def run(handler: ErasureHandler, service: AnkkaService, notice: KeyringNotice): HandlerReport =
    val context = new ErasureContext:
      def client: ComponentClient  = service.componentClient
      def secrets: SecretStore     = service.secrets
      def services: ServiceClients = service.services
      def storage: ObjectStorage   = service.storage
      override def erasures        = service.erasures
      def erasureSeq: Long         = notice.seq
      def rerun: Boolean           = notice.rerun
    try
      val report = handler.run(context)(DataSubject(notice.subject))
      HandlerReport(report.objectsErased, report.finality.wire)
    catch
      case NonFatal(e) =>
        // The subject is named; nothing the handler held is.
        log.warn(s"the erasure handler failed for notice ${notice.seq} of '${notice.subject}'", e)
        HandlerReport.failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
