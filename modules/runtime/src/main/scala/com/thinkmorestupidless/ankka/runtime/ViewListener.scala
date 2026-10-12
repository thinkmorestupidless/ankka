package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.sdk.{WatchEnd, WatchEnded}
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.spi.{ConnectionFactories, ConnectionFactoryOptions}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{KillSwitches, UniqueKillSwitch}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/** What an instance hears of a view's table. */
private[ankka] enum Announcement:

  /** The row under `key` was written or deleted, and the write has committed. */
  case Written(key: String)

  /** The table was emptied for a rebuild, and the rebuild has committed. */
  case Rebuilt

/**
 * The one connection an instance listens on for the writes of every view's rows (see
 * `ViewStore.Channel`).
 *
 * Opened on the first watch the instance holds and kept: a connection of its own, outside the pool,
 * because the pool closes a connection it finds idle and `LISTEN` is lost with it. It is built from
 * the block the pool is built from, through the same TLS customizer, so a database reached over TLS
 * with a client certificate is reached the same way. When the connection is lost, every subscriber
 * is told `WatchEnded(ListenerLost)` — what was written while nobody heard cannot be said — and the
 * next subscriber connects again.
 *
 * A subscriber is a pair of callbacks, registered before `subscribe` answers, so nothing committed
 * after the answer can go unheard. They run on the stream that reads the connection, one
 * announcement at a time, and must not block.
 */
private[ankka] final class ViewListener(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  /** One subscriber's callbacks. */
  final class Subscription private[ViewListener] (
      val table: String,
      private[ViewListener] val heard: Announcement => Unit,
      private[ViewListener] val lost: Throwable => Unit
  ):
    /** Hears nothing more. */
    def cancel(): Unit = subscribers.remove(this): Unit

  private final case class Listening(connection: PostgresqlConnection, kill: UniqueKillSwitch)

  private val subscribers = ConcurrentHashMap.newKeySet[Subscription]()

  private var current: Option[Future[Listening]] = None
  private var stopped                            = false

  /**
   * Calls `heard` with what is announced of `table` from the moment the future completes, and
   * `lost` once if the connection is lost first. The future completes when the connection is
   * listening; it fails when it could not be opened.
   */
  def subscribe(table: String)(
      heard: Announcement => Unit,
      lost: Throwable => Unit
  ): Future[Subscription] =
    listening().map { _ =>
      val subscription = Subscription(table, heard, lost)
      subscribers.add(subscription)
      subscription
    }

  /** Closes the connection. Subscribers are told nothing: whoever stops the listener ends them. */
  def stop(): Future[Unit] =
    val closing = synchronized {
      stopped = true
      val was = current
      current = None
      was
    }
    subscribers.clear()
    closing.fold(Future.unit)(
      _.transformWith {
        case Success(l) =>
          l.kill.shutdown()
          Source.fromPublisher(l.connection.close()).runWith(Sink.ignore).map(_ => ())
        case Failure(_) => Future.unit
      }
    )

  private def listening(): Future[Listening] =
    synchronized {
      if stopped then Future.failed(WatchEnded(WatchEnd.InstanceStopping))
      else
        current match
          case Some(open) => open
          case None =>
            val opening = connect()
            current = Some(opening)
            // One that could not be opened is tried again by the next subscriber.
            opening.failed.foreach(_ => forget(opening))
            opening
    }

  private def forget(which: Future[Listening]): Unit =
    synchronized { if current.contains(which) then current = None }

  private def dispatch(parameter: String): Unit =
    val bar = parameter.indexOf('|')
    if bar > 0 then
      val table = parameter.substring(0, bar)
      val announcement = parameter.substring(bar + 1) match
        case ViewStore.Rebuilt => Announcement.Rebuilt
        case key               => Announcement.Written(key)
      subscribers.asScala.foreach(s => if s.table == table then s.heard(announcement))

  private def connect(): Future[Listening] =
    val block = system.settings.config.getConfig("pekko.persistence.r2dbc.connection-factory")
    val builder = ConnectionFactoryOptions
      .builder()
      .option(ConnectionFactoryOptions.DRIVER, "postgresql")
      .option(ConnectionFactoryOptions.HOST, block.getString("host"))
      .option(ConnectionFactoryOptions.PORT, Integer.valueOf(block.getInt("port")))
      .option(ConnectionFactoryOptions.DATABASE, block.getString("database"))
      .option(ConnectionFactoryOptions.USER, block.getString("user"))
      .option(ConnectionFactoryOptions.PASSWORD, block.getString("password"))
    val factory = ConnectionFactories.get(DatabaseTls(system).apply(builder, block).build())
    for
      connection <- Source.fromPublisher(factory.create()).runWith(Sink.last).map {
        case postgres: PostgresqlConnection => postgres
        case other =>
          throw IllegalStateException(s"a view listener needs a Postgres connection, not $other")
      }
      _ <- Source
        .fromPublisher(connection.createStatement(s"LISTEN ${ViewStore.Channel}").execute())
        .flatMapConcat(r => Source.fromPublisher(r.getRowsUpdated))
        .runWith(Sink.ignore)
    yield
      val (kill, ended) = Source
        .fromPublisher(connection.getNotifications)
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.foreach(n => Option(n.getParameter).foreach(dispatch)))(Keep.both)
        .run()
      val listening = Listening(connection, kill)
      ended.onComplete { outcome =>
        synchronized {
          if current.exists(_.value.flatMap(_.toOption).contains(listening)) then current = None
        }
        outcome.failed.foreach { failure =>
          system.log.warn("view listener: lost ({})", failure.getMessage)
          val told = subscribers.asScala.toVector
          subscribers.clear()
          told.foreach(_.lost(WatchEnded(WatchEnd.ListenerLost)))
        }
      }
      system.log.info("view listener: listening")
      listening
