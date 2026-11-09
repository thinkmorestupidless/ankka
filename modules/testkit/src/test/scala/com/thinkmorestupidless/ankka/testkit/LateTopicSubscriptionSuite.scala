package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.runtime.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * A view's topic subscription that completes after its service stopped is not kept (#157). A view
 * subscribes only once its version is checked and any rebuild has begun, both futures; a restart in
 * between stopped every subscription the service had and then the late one arrived, live, on a
 * terminated system. The next message drained into it failed "Trying to materialize stream after
 * materializer has been shutdown" in the publisher's hands. `RetentionGapSuite` met it about one
 * run in thirty, because its cases wait for a gap the rebuild reports before it subscribes.
 */
class LateTopicSubscriptionSuite extends munit.FunSuite with LogCapturing:

  import RetentionGapSuite.*
  import ViewVersionSuite.serializer

  override val munitTimeout = 3.minutes

  test(
    "restarts that do not wait for a rebuild leave no subscription reading for a stopped service"
  ) {
    val broker = InMemoryBroker()
    (0 until 5).foreach { i =>
      broker.publish(
        GapTopic,
        serializer.toBytes(sample(i)),
        Metadata.empty.withSubject(s"m$i")
      ): Unit
    }
    Entries.version = 1
    val kit = AnkkaTestKit.start(
      Seq.empty,
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      configure = _.registerAll(Seq(Entries.companion().descriptor)),
      serviceIdentity = ServiceIdentity.local("ledger")
    )
    try
      // Each restart raises the version, so each start checks it and rebuilds before subscribing;
      // none waits, so the next restart stops a service whose subscription is still on its way.
      for version <- 2 to 8 do
        Entries.version = version
        kit.restartService()
      // A publication is handed to every group still reading the topic, and fails with the first
      // handler that does: one left on a stopped service is that handler.
      Await.result(
        broker.publish(GapTopic, Some("m-last"), serializer.toBytes(sample(99)), Metadata.empty),
        30.seconds
      )
    finally
      kit.stop()
      Entries.version = 1
  }
