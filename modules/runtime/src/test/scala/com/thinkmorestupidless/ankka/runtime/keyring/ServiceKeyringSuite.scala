package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer, Keyring}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Feature 042: where a value read from the database is decoded. */
final class ServiceKeyringSuite extends munit.FunSuite:

  test("a decode asked for on the driver's event loop runs off it, under the service's keyring") {
    val system  = ActorSystem(Behaviors.empty, "service-keyring-suite")
    val keyring = new Keyring:
      def project                                                     = "brand"
      def key(project: String, subject: DataSubject, create: Boolean) = KeyAnswer.Absent
    ServiceKeyring.register(system, keyring)
    val loop = reactor.core.scheduler.Schedulers.newSingle("event-loop-standin")
    try
      val decoded = reactor.core.publisher.Mono
        .fromCallable(() =>
          ServiceKeyring.decoding(system)(
            (reactor.core.scheduler.Schedulers.isInNonBlockingThread(), Keyring.current)
          )
        )
        .subscribeOn(loop)
        .block(java.time.Duration.ofSeconds(10))
      val (nonBlocking, bound) = Await.result(decoded, 10.seconds)
      assert(!nonBlocking, "the decode ran on the non-blocking thread")
      assert(bound eq keyring, s"the decode read with $bound, not the service's keyring")
    finally
      loop.dispose()
      ServiceKeyring.unregister(system, keyring)
      system.terminate()
  }
