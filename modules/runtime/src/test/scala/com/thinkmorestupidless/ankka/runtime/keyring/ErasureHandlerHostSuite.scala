package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.keyring.KeyringNotice
import com.thinkmorestupidless.ankka.sdk.{ErasureHandler, ErasureReport}
import com.typesafe.config.ConfigFactory
import munit.FunSuite
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{Cluster, Join}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Feature 042 (R16, FR-010): the erasure handler runs for a notice heard before its singleton
 * starts.
 */
class ErasureHandlerHostSuite extends FunSuite:

  test("a notice heard before the handler's singleton has started waits for it, and is run") {
    // A cluster that has not formed yet: no singleton can start until the member is up.
    val config = ConfigFactory
      .parseString(
        """pekko.actor.provider = cluster
          |pekko.remote.artery.canonical.hostname = 127.0.0.1
          |pekko.remote.artery.canonical.port = 0
          |pekko.cluster.jmx.multi-mbeans-in-same-jvm = on""".stripMargin
      )
      .withFallback(ConfigFactory.load())
    val system = ActorSystem(Behaviors.empty[Nothing], "erasure-handler-host", config)
    try
      val hooks = ErasureHooks.of(system)
      // The handler reads nothing of the service, so none is given.
      ErasureHandlerHost.start(ErasureHandler(_ => _ => ErasureReport(1)), null, hooks)(using
        system
      )
      val answer = hooks.handler(KeyringNotice(1, "players", "player/8c1f", rerun = false))
      Thread.sleep(500)
      assert(!answer.isCompleted, s"answered before any singleton could start: ${answer.value}")
      Cluster(system).manager ! Join(Cluster(system).selfMember.address)
      assertEquals(Await.result(answer, 30.seconds).map(_.objectsErased), Some(1L))
    finally
      ErasureHooks.forget(system)
      system.terminate()
  }
