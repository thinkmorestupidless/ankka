package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.core.keyring.{HandlerReport, KeyringNotice}
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, AnkkaService}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * Runs the service's erasure handler on one instance (feature 042, R16). A cluster singleton, as
 * the timer sweeper is, marks the instance that holds it; every instance hears every notice, and
 * only that one runs the handler, so nothing crosses the cluster and two instances do not both
 * erase. A run that throws is reported as failed, with why, so the platform's own duties are
 * acknowledged all the same and the next pass runs the handler again.
 *
 * A notice heard before the singleton has started anywhere would be answered "no handler" by every
 * instance, and the first completion would record no run. So an instance not yet holding it asks
 * the singleton first: its proxy keeps the ask until the singleton is up, and the instance that
 * started it has said so by the time it answers.
 */
private[ankka] object ErasureHandlerHost:

  private val log = LoggerFactory.getLogger(getClass)

  def start(handler: ErasureHandler, service: AnkkaService, hooks: ErasureHooks)(using
      system: ActorSystem[?]
  ): Unit =
    val holding   = AtomicBoolean(false)
    val singleton = ClusterSingleton(system).init(
      SingletonActor(marker(holding), "ankka-erasure-handler")
    )
    given Timeout = Timeout(SettleWithin)
    hooks.onHandler { notice =>
      given scala.concurrent.ExecutionContext = AnkkaExecutors.virtual
      val settled                             =
        if holding.get then Future.unit
        else
          singleton.ask[String](replyTo => replyTo).recover { case NonFatal(_) => "" }.map(_ => ())
      settled.map(_ => Option.when(holding.get)(run(handler, service, notice)))
    }

  /**
   * How long a notice waits for the singleton to start somewhere before it is answered without it.
   */
  private val SettleWithin = 30.seconds

  /**
   * A service with an erasure handler and no keyring hears no notice, so its handler would never
   * run and every erasure would be certified without it: refused at start, saying what declares it.
   */
  def requireKeyring(hasHandler: Boolean, hasKeyring: Boolean): Unit =
    if hasHandler && !hasKeyring then
      throw IllegalStateException(
        "the service declares an erasure handler, but no keyring reaches it, so the handler " +
          "would never run: declare \"personal\": true in its descriptor, which gives it the " +
          "installation's keyring (ANKKA_KEYRING_ADDRESS)"
      )

  /**
   * Marks the instance holding it, and answers any ask once it is up (a String, which Pekko
   * serializes).
   */
  private def marker(holding: AtomicBoolean): Behavior[ActorRef[String]] =
    Behaviors.setup { _ =>
      holding.set(true)
      Behaviors
        .receiveMessage[ActorRef[String]] { replyTo =>
          replyTo ! "held"
          Behaviors.same
        }
        .receiveSignal { case (_, PostStop) =>
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
