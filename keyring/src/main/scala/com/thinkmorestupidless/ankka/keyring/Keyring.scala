package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.http.MachineTokens
import com.thinkmorestupidless.ankka.runtime.{Database, RotatingTls}
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/** The keyring put together from its parts: `Main`'s, and a test's. */
object Keyring:

  private val log = LoggerFactory.getLogger("ankka.keyring")

  /** What is running, for a test that reads or stops it. */
  final case class Started(
      server: KeyringServer,
      service: KeyringService,
      replay: ErasureLogReplay,
      store: KeyStore,
      stop: () => Unit
  )

  /**
   * Starts the listeners at once, `/ready` held and every route answering 503 until the erasure log
   * has been replayed, which is tried every few seconds until both copies are read.
   */
  def start(
      database: Database,
      root: RootKey,
      grants: ProjectGrants,
      copies: Vector[LogCopy],
      tls: RotatingTls,
      port: Int,
      probePort: Int,
      machines: Option[MachineTokens],
      replica: String,
      relays: String => Set[String] = _ => Set.empty,
      retryEvery: FiniteDuration = 5.seconds
  )(using system: ActorSystem[?]): Started =
    given ExecutionContext = system.executionContext
    val store              = KeyStore(database)
    val replay             = ErasureLogReplay(store, copies)
    val service            = KeyringService(
      store,
      Wrapping(store, root),
      Admission(grants, store, relays),
      Notices(database, store, replica),
      replay
    )
    val server = KeyringServer(service, replay, tls, port, probePort, machines)
    server.start()
    replay
      .untilDone(retryEvery)
      .foreach(r =>
        log.info(
          s"the erasure log is replayed: ${r.applied} applied after ${r.from}" +
            (if r.behind.isEmpty then "" else s"; behind: ${r.behind.map(_.copy).mkString(", ")}")
        )
      )
    Started(server, service, replay, store, () => server.stop())
