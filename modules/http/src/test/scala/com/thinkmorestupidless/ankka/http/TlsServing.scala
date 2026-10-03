package com.thinkmorestupidless.ankka.http

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Real endpoints served over the platform's mutual TLS on loopback, with no service behind them,
 * for suites outside this module that need a callee reading its caller from a certificate exactly
 * as a deployed service does. One actor system each, because the certificate is the system's.
 */
object TlsServing:

  final class Running private[TlsServing] (
      server: HttpServer,
      system: ActorSystem[Nothing]
  ):
    val port: Int = server.boundPort.getOrElse(throw IllegalStateException("not bound"))

    def stop(): Unit =
      server.stop()
      system.terminate()
      Await.ready(system.whenTerminated, 10.seconds): Unit

  /** Serves `endpoints` with the certificate in `serviceDirectory` (tls.key, tls.crt, ca.crt). */
  def start(name: String, serviceDirectory: Path, endpoints: Vector[HttpEndpoint]): Running =
    given system: ActorSystem[Nothing] = ActorSystem(
      Behaviors.empty,
      name,
      ConfigFactory
        .parseString(s"""
          |pekko.actor.provider = local
          |ankka.http.tls.enabled = on
          |ankka.tls.service-directory = "$serviceDirectory"
          |ankka.tls.reload-interval = 1s
          |""".stripMargin)
        .withFallback(ConfigFactory.load())
    )
    val server = HttpServer.at("127.0.0.1", 0)()
    server.serve(endpoints, "127.0.0.1", 0, 10.seconds)
    Running(server, system)
