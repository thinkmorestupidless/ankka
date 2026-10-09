package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  StatusCodes
}
import org.apache.pekko.management.HealthCheckSettings
import org.apache.pekko.stream.Materializer
import org.apache.pekko.management.scaladsl.HealthChecks

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * `GET /ready` on a plain HTTP listener of its own, and nothing else.
 *
 * The kubelet cannot present a client certificate, and TLS client authentication is a property of a
 * listener rather than of a route — so once the management port requires a certificate from every
 * caller, the readiness probe has nowhere to go but here. It answers from the very checks
 * management answers `/ready` with (Cluster Bootstrap's membership check plus every extension with
 * an opinion), so moving the probe changes where the question is asked and not what the answer
 * means.
 *
 * It discloses one bit: ready or not. That is why it may be reachable from anywhere, including a
 * network policy that cannot name the node the kubelet runs on.
 */
object ProbeEndpoint:

  val EnabledKey: String = "ankka.probe.enabled"
  val PortKey: String    = "ankka.probe.port"

  /** Binds when enabled and answers the bound port; `None` when this process runs no probe. */
  def start(system: ActorSystem[?]): Option[Int] =
    val config = system.settings.config
    if !(config.hasPath(EnabledKey) && config.getBoolean(EnabledKey)) then None
    else
      given ActorSystem[?]   = system
      given ExecutionContext = system.executionContext
      explain = () => ExtensionsReadiness(system).reasons
      val checks = HealthChecks(
        system.toClassic.asInstanceOf[ExtendedActorSystem],
        HealthCheckSettings(config.getConfig("pekko.management.health-checks"))
      )
      val binding = Await.result(
        Http()
          .newServerAt("0.0.0.0", config.getInt(PortKey))
          .bind(request => answer(request, checks, Materializer.matFromSystem(using system))),
        30.seconds
      )
      system.log.info("readiness probe listening on port {}", binding.localAddress.getPort)
      Some(binding.localAddress.getPort)

  private def answer(request: HttpRequest, checks: HealthChecks, materializer: Materializer)(using
      ExecutionContext
  ): Future[HttpResponse] =
    if request.method != HttpMethods.GET || request.uri.path.toString != "/ready" then
      request.discardEntityBytes()(using materializer): Unit
      Future.successful(HttpResponse(StatusCodes.NotFound))
    else
      checks.readyResult().transform {
        case Success(Right(_))  => Success(text(StatusCodes.OK, "ready"))
        case Success(Left(why)) => Success(text(StatusCodes.ServiceUnavailable, withReasons(why)))
        case Failure(e) => Success(text(StatusCodes.ServiceUnavailable, withReasons(e.getMessage)))
      }

  /** The management check's answer, and why the extensions that can say are not ready. */
  @volatile private var explain: () => Vector[String] = () => Vector.empty

  private def withReasons(why: String): String =
    (why +: explain()).mkString("\n")

  private def text(status: org.apache.pekko.http.scaladsl.model.StatusCode, body: String) =
    HttpResponse(status, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, body))
