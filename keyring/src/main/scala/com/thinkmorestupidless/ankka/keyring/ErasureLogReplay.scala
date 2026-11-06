package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.sdk.Bucket
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** One erasure applied, as the control plane writes it to both copies of the log (R22). */
final case class LogEntry(
    seq: Long,
    project: String,
    subject: String,
    rid: String,
    appliedAt: Instant
)

object LogEntry:
  given JsonValueCodec[LogEntry] = JsonCodecMaker.make

/** One copy of the erasure log, read from after a sequence. */
trait LogCopy:
  def name: String

  /** The entries after `seq`, in any order; throws when the copy cannot be read. */
  def after(seq: Long): Vector[LogEntry]

object LogCopy:

  private final case class Page(entries: Vector[LogEntry] = Vector.empty)
  private given JsonValueCodec[Page] = JsonCodecMaker.make

  /** `GET <base>/internal/erasure-log?after=`, over a client holding the keyring's identity. */
  def controlPlane(base: String, http: HttpClient): LogCopy = new LogCopy:
    val name                               = "control plane"
    def after(seq: Long): Vector[LogEntry] =
      val response = http.send(
        HttpRequest
          .newBuilder(URI.create(s"${base.stripSuffix("/")}/internal/erasure-log?after=$seq"))
          .timeout(Duration.ofSeconds(30))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
      if response.statusCode != 200 then
        throw IllegalStateException(s"the control plane answered ${response.statusCode}")
      readFromArray[Page](response.body).entries

  /**
   * The objects under `erasure-log/` of the platform bucket, named by their zero-padded sequence.
   */
  def bucket(open: => Bucket): LogCopy = new LogCopy:
    val name                               = "bucket"
    private val Named                      = raw"erasure-log/(\d+)-.*\.json".r
    def after(seq: Long): Vector[LogEntry] =
      val b = open
      b.list("erasure-log/")
        .collect {
          case o if o.name match { case Named(n) => n.toLong > seq; case _ => false } => o.name
        }
        .flatMap(n => b.getBytes(n).map((_, bytes) => readFromArray[LogEntry](bytes)))
        .toVector

/**
 * FR-021's keyring half (R22): before the keyring answers anything, every entry of either copy of
 * the erasure log that its database has not applied is applied, in order. A database restored from
 * before an erasure therefore destroys the key again before it can serve it. A copy that lacked an
 * entry the other held was behind, and is reported. Until both copies have been read the union is
 * unknown, so the keyring is not ready: a key is never served on half the log.
 *
 * Every entry missing from `applied_log` is replayed, not only those above the highest applied:
 * nothing serialises the order in which entries are applied. The control plane numbers an entry
 * when it writes it and asks the keyring to destroy afterwards, and a destroy that failed (the
 * keyring unavailable) is retried later, so entry 6 can be applied before entry 5, and a backup
 * taken between the two holds 6 and not 5.
 */
final class ErasureLogReplay(store: KeyStore, copies: Vector[LogCopy])(using ExecutionContext):

  private val log   = LoggerFactory.getLogger(classOf[ErasureLogReplay])
  private val state = AtomicReference[ErasureLogReplay.Report](ErasureLogReplay.Report.pending)

  /** What the last attempt found: on `/status`. */
  def report: ErasureLogReplay.Report = state.get

  def done: Boolean = state.get.finished

  /** One attempt: reads both copies, applies their union in order, and reports. */
  def run(): Future[ErasureLogReplay.Report] =
    store.lastApplied().zip(store.appliedSeqs()).flatMap { (last, applied) =>
      val read = copies.map(c =>
        c -> (try Right(c.after(0))
        catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
      )
      val unread = read.collect { case (c, Left(why)) => ErasureLogReplay.Unread(c.name, why) }
      if unread.nonEmpty then
        unread
          .foreach(u => log.warn(s"the erasure log's ${u.copy} copy could not be read: ${u.why}"))
        val r = ErasureLogReplay.Report(last, 0, Vector.empty, unread, finished = false)
        state.set(r)
        Future.successful(r)
      else
        val held = read.collect { case (c, Right(es)) =>
          c.name -> es.filterNot(e => applied(e.seq))
        }
        val union  = held.flatMap(_._2).groupBy(_.seq).values.map(_.head).toVector.sortBy(_.seq)
        val behind = held.flatMap { (name, es) =>
          val seqs    = es.map(_.seq).toSet
          val missing = union.map(_.seq).filterNot(seqs)
          Option.when(missing.nonEmpty)(ErasureLogReplay.Behind(name, missing))
        }
        behind.foreach(b =>
          log.warn(
            s"the erasure log's ${b.copy} copy was behind: it lacked ${b.missing.size} entr" +
              s"${if b.missing.size == 1 then "y" else "ies"} (${b.missing.mkString(", ")}); applied from the other"
          )
        )
        union
          .foldLeft(Future.unit)((done, e) =>
            done.flatMap(_ => store.destroy(e.seq, e.project, e.subject, e.appliedAt).map(_ => ()))
          )
          .map { _ =>
            if union.nonEmpty then
              log.info(
                s"the keyring replayed ${union.size} erasure log entries it had not applied " +
                  s"(${union.map(_.seq).mkString(", ")}); the highest it held was $last"
              )
            val r = ErasureLogReplay.Report(last, union.size, behind, Vector.empty, finished = true)
            state.set(r)
            r
          }
    }

  /** Attempts until one finishes, `every` apart, on a virtual thread of its own. */
  def untilDone(every: scala.concurrent.duration.FiniteDuration): Future[ErasureLogReplay.Report] =
    Future {
      var r = ErasureLogReplay.Report.pending
      while !r.finished do
        r =
          try scala.concurrent.Await.result(run(), scala.concurrent.duration.Duration(2, "min"))
          catch
            case NonFatal(e) =>
              log.warn(s"the erasure log replay failed: ${e.getMessage}")
              state.get
        if !r.finished then Thread.sleep(every.toMillis)
      r
    }(using com.thinkmorestupidless.ankka.runtime.AnkkaExecutors.virtual)

object ErasureLogReplay:

  final case class Behind(copy: String, missing: Vector[Long])
  final case class Unread(copy: String, why: String)

  /**
   * The replay's outcome: from which sequence, how many applied, which copies were behind or
   * unread.
   */
  final case class Report(
      from: Long,
      applied: Int,
      behind: Vector[Behind],
      unread: Vector[Unread],
      finished: Boolean
  )

  object Report:
    val pending: Report = Report(0, 0, Vector.empty, Vector.empty, finished = false)

  given JsonValueCodec[Report] = JsonCodecMaker.make
