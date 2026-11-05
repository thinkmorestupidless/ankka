package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{AnkkaExecutors, AnkkaService, RuntimeExtension}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{ClusterSingleton, SingletonActor}
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/**
 * The control plane's erasure sweep (feature 042, T059): a cluster singleton that carries every
 * request on without anyone acting. Every pass applies a held request whose not-before date has
 * passed, tries again one left applying, and reads completions into one applied and not yet final;
 * every `rerunEvery` it asks the keyring to send again the notice of each request applied within
 * `window`, so every handler runs again and objects written late are erased too (FR-015), and none
 * older; and every pass reads the handlers' reports of those re-runs into the request (FR-016).
 */
final class ErasureSweep(
    plane: ErasurePlane,
    window: () => FiniteDuration,
    every: FiniteDuration = ErasureSweep.Every,
    rerunEvery: FiniteDuration = ErasureSweep.RerunEvery
) extends RuntimeExtension:

  private val log       = LoggerFactory.getLogger(classOf[ErasureSweep])
  private val lastRerun = AtomicReference[Option[Instant]](None)

  def name: String = "erasure-sweep"

  def start(service: AnkkaService): Unit =
    val _ = ClusterSingleton(service.system).init(
      SingletonActor(ErasureSweep.Ticker(() => runOnce(), every), "ankka-erasure-sweep")
    )

  /** One pass, at the control plane's clock; what it did. */
  def runOnce(): Future[ErasureSweep.Pass] =
    (plane.application, plane.viewClient) match
      case (Some(application), Some(views)) =>
        Future(pass(application, views))(using AnkkaExecutors.virtual)
      case _ => Future.successful(ErasureSweep.Pass())

  private def pass(
      application: ErasureApplication,
      views: com.thinkmorestupidless.ankka.runtime.ViewClient
  ): ErasureSweep.Pass =
    val now                       = plane.now.instant()
    val rows                      = views.forView(ErasureRequestsView)
    def await[A](f: Future[A]): A = Await.result(f, 2.minutes)
    def entity(id: String)        =
      plane.componentClient
        .getOrElse(throw IllegalStateException("the erasure plane has not started"))
        .forEventSourcedEntity(EntityId(id))
    var result                                = ErasureSweep.Pass()
    def attempt(id: String)(f: => Unit): Unit =
      try f
      catch
        case NonFatal(e) =>
          result = result.copy(failed = result.failed :+ id)
          log.warn(s"the erasure sweep could not carry erasure request $id on: ${e.getMessage}")

    // Within the window, by when the key was destroyed: the edge itself is within it.
    val since = now.minusMillis(window().toMillis)
    rows.ask(ErasureRequestsView.open, "since" -> since.toString).foreach { row =>
      row.state match
        case "held" if row.notBefore.exists(!_.isAfter(now)) =>
          attempt(row.id) {
            entity(row.id)
              .call(ErasureRequestEntity.lapse)
              .withMetadata(plane.attribution())
              .invoke()
            await(application.apply(row.id)): Unit
            result = result.copy(lapsed = result.lapsed :+ row.id)
          }
        case "applying" =>
          attempt(row.id) {
            await(application.apply(row.id)): Unit
            result = result.copy(retried = result.retried :+ row.id)
          }
        case "applied" =>
          attempt(row.id)(await(application.apply(row.id)): Unit)
        case _ => ()
    }

    val recent   = rows.ask(ErasureRequestsView.applied, "since" -> since.toString)
    val rerunDue = lastRerun.get.forall(at => !now.isBefore(at.plusMillis(rerunEvery.toMillis)))
    recent.foreach { row =>
      attempt(row.id) {
        val current = entity(row.id).call(ErasureRequestEntity.get).invoke()
        await(application.recordRuns(current)): Unit
        if rerunDue then
          await(application.resend(current))
          result = result.copy(rerun = result.rerun :+ row.id)
      }
    }
    if rerunDue then lastRerun.set(Some(now))
    result

object ErasureSweep:
  /** How often a pass runs: a lapsed hold is applied within this, well inside FR-013's bound. */
  val Every: FiniteDuration = 5.minutes

  /** How often the handlers of every request within the window are asked to run again. */
  val RerunEvery: FiniteDuration = 15.minutes

  /** What one pass did, by request id. */
  final case class Pass(
      lapsed: Vector[String] = Vector.empty,
      retried: Vector[String] = Vector.empty,
      rerun: Vector[String] = Vector.empty,
      failed: Vector[String] = Vector.empty
  )

  /** The singleton: a pass at a time, a tick during one dropped. */
  private object Ticker:
    private sealed trait Command
    private case object Tick                            extends Command
    private case object Finished                        extends Command
    private final case class Failed(failure: Throwable) extends Command

    def apply(pass: () => Future[Pass], every: FiniteDuration): Behavior[Nothing] =
      Behaviors
        .setup[Command] { ctx =>
          Behaviors.withTimers { timers =>
            timers.startTimerWithFixedDelay(Tick, every)
            def idle: Behavior[Command] = Behaviors.receiveMessage {
              case Tick =>
                ctx.pipeToSelf(pass()) {
                  case Success(_)       => Finished
                  case Failure(failure) => Failed(failure)
                }
                busy
              case _ => Behaviors.same
            }
            def busy: Behavior[Command] = Behaviors.receiveMessage {
              case Tick            => Behaviors.same
              case Finished        => idle
              case Failed(failure) =>
                ctx.log.warn("the erasure sweep failed; it runs again on the next tick", failure)
                idle
            }
            idle
          }
        }
        .narrow
