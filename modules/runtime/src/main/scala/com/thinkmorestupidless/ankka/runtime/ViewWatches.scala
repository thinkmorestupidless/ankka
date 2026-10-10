package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.{WatchEnd, WatchEnded, WatchEvent, Watching}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The watches one instance holds open, of every view, and what keeps them live.
 *
 * Every write of a view's row is announced when it commits (`ViewStore.Channel`), and the
 * instance's one `ViewListener` hears it. A written key goes into its view's pending keys, once
 * however often it was written, and one evaluator per view reads each pending key afresh: for every
 * distinct thing watched — a declared query with its values, or one row by key — one statement
 * decides whether the row matches now and what it is. Each watch is then offered the row, or a
 * removal of a row it was given, or nothing. Reading after the announcement means a watch is never
 * offered a version older than one it was offered before.
 *
 * A watch is live, not a record: an evaluation that fails is logged and the key left for its next
 * write, and what was written while nobody watched is never replayed.
 */
private[ankka] object ViewWatches:

  /** What is watched: a declared query's statement with its values bound, or one row by its key. */
  enum Target:
    case Named(sql: String, binds: Vector[String])
    case ByKey(key: String)

private[ankka] final class ViewWatches(database: Database, askTimeout: FiniteDuration)(using
    system: ActorSystem[?]
):

  private given ExecutionContext = system.executionContext
  private given Materializer     = Materializer(system)

  import ViewWatches.Target

  private val settings    = system.settings.config.getConfig("ankka.view")
  private val bound       = settings.getInt("watch-bound")
  private val unreadBound = settings.getInt("unread-bound")

  private lazy val listener = ViewListener()

  private val open  = AtomicInteger()
  private val views = ConcurrentHashMap[String, Entry]()

  /** One open watch: what it watches, what it has given, and the queue its live rows go into. */
  private final class Watch(
      val table: String,
      val target: Target,
      queue: BoundedSourceQueue[WatchEvent[String]]
  ):
    private val delivered = mutable.HashSet.empty[String]
    // Keys found not to match while the rows now were still being given: a row given after its
    // evaluation must still be removed, once the rows now are all given.
    private val doubted = mutable.HashSet.empty[String]
    private var live    = false

    def gave(key: String): Unit = synchronized {
      delivered += key
      if live then doubted -= key
    }

    def caughtUp(): Unit =
      val removals = synchronized {
        live = true
        val r = doubted.intersect(delivered).toVector
        delivered --= r
        doubted.clear()
        r
      }
      removals.foreach(key => offer(WatchEvent.Removed(key)))

    def matched(key: String, json: String): Unit =
      synchronized { delivered += key; doubted -= key }
      offer(WatchEvent.Row(key, json))

    def unmatched(key: String): Unit =
      val wasGiven = synchronized {
        if !live then doubted += key
        delivered.remove(key)
      }
      if wasGiven then offer(WatchEvent.Removed(key))

    def end(reason: WatchEnded): Unit = queue.fail(reason)

    private def offer(event: WatchEvent[String]): Unit =
      queue.offer(event) match
        case QueueOfferResult.Enqueued => ()
        // The queue only feeds the watch's keyed buffer, which is always taking: full, it means
        // the watch is overwhelmed, and a live watch drops rather than holding back the view.
        case _ => ()

  private final class Entry(val table: String):
    val watches                                             = mutable.LinkedHashSet.empty[Watch]
    val pending                                             = mutable.LinkedHashSet.empty[String]
    var evaluating                                          = false
    var subscription: Option[Future[listener.Subscription]] = None

  /**
   * A watch of `table`: the rows `rowsNow` gives, each as its key and stored JSON, then
   * `WatchEvent.CaughtUp`, then each row as it is written and matches and a removal for each given
   * row that stops matching. Refused at once when the instance holds its bound of watches.
   */
  def watch(table: String, target: Target, watching: Watching)(
      rowsNow: Source[(String, String), NotUsed]
  ): Source[WatchEvent[String], NotUsed] =
    if open.incrementAndGet() > bound then
      open.decrementAndGet()
      Source.failed(
        CommandError(
          s"this instance holds $bound open watches, its watch bound; a watch is refused until one ends",
          ErrorCode.Unavailable
        )
      )
    else
      val (queue, live) = Source
        .queue[WatchEvent[String]](math.max(1024, unreadBound * 4))
        .via(KeyedBuffer[String](watching.unread.getOrElse(unreadBound), watching.overflow))
        .preMaterialize()
      val watch = Watch(table, target, queue)
      Source
        .futureSource(register(watch).map { _ =>
          rowsNow.map { (key, json) =>
            watch.gave(key)
            WatchEvent.Row(key, json): WatchEvent[String]
          } ++ Source.lazySingle { () =>
            watch.caughtUp()
            WatchEvent.CaughtUp: WatchEvent[String]
          } ++ live
        })
        .watchTermination() { (_, ended) =>
          ended.onComplete(_ => unregister(watch))
          NotUsed
        }

  /** Ends every watch with `InstanceStopping`, and closes the listener. */
  def stop(): Future[Unit] =
    val all = views.values().toArray(Array.empty[Entry]).toVector
    views.clear()
    all.foreach(entry =>
      entry
        .synchronized(entry.watches.toVector)
        .foreach(_.end(WatchEnded(WatchEnd.InstanceStopping)))
    )
    listener.stop()

  /**
   * Registers `watch`, hearing its table, and completes once a write committed after it is heard.
   */
  private def register(watch: Watch): Future[Unit] =
    val entry = views.computeIfAbsent(watch.table, Entry(_))
    val subscribed = entry.synchronized {
      entry.watches += watch
      entry.subscription match
        case Some(subscribing) => subscribing
        case None =>
          val subscribing = listener.subscribe(watch.table)(heard(entry, _), lost(entry, _))
          entry.subscription = Some(subscribing)
          subscribing.failed.foreach(_ => entry.synchronized { entry.subscription = None })
          subscribing
    }
    subscribed.map(_ => ())

  private def unregister(watch: Watch): Unit =
    open.decrementAndGet()
    Option(views.get(watch.table)).foreach { entry =>
      entry.synchronized {
        entry.watches -= watch
        if entry.watches.isEmpty then
          entry.subscription.foreach(_.foreach(_.cancel()))
          entry.subscription = None
          views.remove(watch.table, entry): Unit
      }
    }

  private def heard(entry: Entry, announcement: Announcement): Unit =
    announcement match
      case Announcement.Rebuilt =>
        entry.synchronized(entry.watches.toVector).foreach(_.end(WatchEnded(WatchEnd.Rebuilt)))
      case Announcement.Written(key) =>
        val start = entry.synchronized {
          entry.pending += key
          val idle = !entry.evaluating
          entry.evaluating = true
          idle
        }
        if start then evaluate(entry)

  private def lost(entry: Entry, failure: Throwable): Unit =
    val ended = failure match
      case e: WatchEnded => e
      case _             => WatchEnded(WatchEnd.ListenerLost)
    entry
      .synchronized {
        entry.subscription = None
        entry.watches.toVector
      }
      .foreach(_.end(ended))

  /** Reads each pending key of `entry` in turn, until none is left. */
  private def evaluate(entry: Entry): Unit =
    val next = entry.synchronized {
      entry.pending.headOption match
        case Some(key) =>
          entry.pending -= key
          Some((key, entry.watches.toVector))
        case None =>
          entry.evaluating = false
          None
    }
    next.foreach { (key, watches) =>
      val byTarget = watches.groupBy(_.target).toVector.filter {
        case (Target.ByKey(watched), _) => watched == key
        case _                          => true
      }
      Future
        .traverse(byTarget) { (target, sharing) =>
          rowFor(entry.table, target, key)
            .map {
              case Some(json) => sharing.foreach(_.matched(key, json))
              case None       => sharing.foreach(_.unmatched(key))
            }
            .recover { case NonFatal(failure) =>
              system.log.warn(
                "view watch: could not read the row '{}' of {} for its watchers ({})",
                key,
                entry.table,
                failure.getMessage
              )
            }
        }
        .onComplete(_ => evaluate(entry))
    }

  /** The row under `key` as `target` sees it now, or none when it does not match or is gone. */
  private def rowFor(table: String, target: Target, key: String): Future[Option[String]] =
    database.readOnly(ViewQueries.statementTimeout(askTimeout)) { tx =>
      target match
        case Target.ByKey(_) =>
          tx.query(ViewStore.selectByKey(table, key))(_.get("payload", classOf[String]))
            .map(_.headOption)
        case Target.Named(sql, binds) =>
          tx.queryUpTo(
            s"SELECT payload FROM ($sql) ankka_watched WHERE ankka_watched.row_key = $$${binds.size + 1}",
            binds :+ key,
            1
          )((row, _) => row.get("payload", classOf[String]))
            .map(_.headOption)
    }
