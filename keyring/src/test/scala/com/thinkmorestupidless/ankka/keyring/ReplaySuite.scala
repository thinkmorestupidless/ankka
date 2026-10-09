package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.erasure.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * A copy of the erasure log that cannot be read yet — the control plane still starting beside the
 * keyring — leaves the keyring running and not ready, refusing every channel `replaying`, and
 * asking again until the copy answers; then it replays and serves.
 */
class ReplaySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  test(
    "the keyring waits for an unreadable copy of the log, refusing channels until it has replayed"
  ) {
    val attempts           = AtomicInteger(0)
    @volatile var readable = false
    val sources = new LogSources:
      def copies = 1
      def controlPlane: Option[Vector[KeyringApi.LogEntry]] =
        attempts.incrementAndGet(): Unit
        if readable then Some(Vector.empty) else throw IllegalStateException("not answering yet")
      def bucket: Option[Vector[KeyringApi.LogEntry]] = None
    val state = KeyringState(Grants.none, sources, ackWithin = 2.seconds, applySchema = false)
    val kit = AnkkaTestKit.start(
      Keyring.components,
      Seq(
        KeyringRuntime(state),
        HttpServer.at("127.0.0.1", 0)(clients => KeyringEndpoint(clients, state))
      ),
      keyring = None
    )
    try
      val base = kit.service.boundAddresses.find(_.startsWith("http")).get
      kit.eventually("a failed attempt")(Option.when(attempts.get >= 1)(())): Unit
      assert(!state.ready, "ready before the log was read")

      val closes = ConcurrentLinkedQueue[String]()
      val logs   = ConcurrentLinkedQueue[Vector[LogEntry]]()
      val listener = new KeyringListener:
        def log(entries: Vector[LogEntry]): Unit = logs.add(entries): Unit
        def destroyed(project: String, subject: String, erasureId: String): Unit = ()
        def apply(order: ErasureOrder): Unit                                     = ()
        def closed(reason: String): Unit = closes.add(reason): Unit
      val client = KeyringClient(URI.create(base.replaceFirst("^http", "ws") + "/channel"), None)
      try
        client.open(Hello("local", "players", "players-1", Set.empty, None), listener)
        kit.eventually("the channel refused while replaying")(
          Option.when(closes.contains("replaying"))(())
        ): Unit
        assert(logs.isEmpty, "the log was sent before the replay")

        readable = true
        kit.eventually("the replay, after the copy answered")(Option.when(state.ready)(())): Unit
        // The instance reconnects, as after any close, and is served now.
        kit.eventually("the channel served")(Option(logs.peek())): Unit
      finally client.close()
    finally kit.stop()
  }
