package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.SubjectNotice
import com.thinkmorestupidless.ankka.core.keyring.KeyringNotice
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{BoundedSourceQueue, QueueOfferResult}
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * The erasure notices a process hears (protocol 1.23, feature 042): each instance of the process
 * holds `SubjectNotices` open and acks each notice once it has dropped the subject's key. A notice
 * is done here once every instance that held the stream when it arrived has acked it, or the stream
 * it was handed on has ended, which leaves nothing cached: every SDK drops its whole cache when its
 * stream ends or opens again. Only then does the runtime go on with its own duties and tell the
 * keyring. A process holding no stream caches nothing, and is done at once.
 *
 * An instance that does not ack within `ackBound` (the keyring's notice bound, FR-022) has its
 * stream failed, which its SDK answers by dropping every key, and the notice is done for it: a hung
 * process never holds an erasure open.
 */
final class ProcessNotices(ackBound: FiniteDuration = 60.seconds):

  private type Queue = BoundedSourceQueue[SubjectNotice]

  private val log     = LoggerFactory.getLogger(classOf[ProcessNotices])
  private val streams = ConcurrentHashMap[String, Queue]()

  /** One wait for one instance's ack of one notice, on the stream it was handed on. */
  private final class Waiting(val queue: Queue, val acked: Promise[Unit])

  private val awaiting = ConcurrentHashMap[(Long, String), Waiting]()

  /** The notices for `instance` from now on; a second stream under one name replaces the first. */
  def open(instance: String): Source[SubjectNotice, NotUsed] =
    Source
      .queue[SubjectNotice](1024)
      .mapMaterializedValue { queue =>
        Option(streams.put(instance, queue)).foreach(_.complete())
        log.info("process instance {} hears erasure notices", instance)
        queue
      }
      .watchTermination() { (queue, done) =>
        done.onComplete(_ => close(instance, queue))(using ExecutionContext.parasitic)
        NotUsed
      }

  /** Hands `notice` to every instance holding a stream; done once each has acked or gone. */
  def notify(notice: KeyringNotice): Future[Unit] =
    val held = streams.asScala.toVector
    val acks = held.flatMap { (instance, queue) =>
      val key     = (notice.seq, instance)
      val waiting = Waiting(queue, Promise[Unit]())
      // A notice handed again to the same instance (the keyring's backlog after a reconnect): the
      // earlier wait is settled by this one's ack, never left waiting for an ack that came first.
      Option(awaiting.put(key, waiting)).foreach(_.acked.completeWith(waiting.acked.future))
      queue.offer(SubjectNotice(notice.seq, notice.project, notice.subject, notice.rerun)) match
        case QueueOfferResult.Enqueued =>
          deadline(key, waiting, instance, notice.seq)
          Some(waiting.acked.future)
        case other =>
          // A stream that cannot take a notice is ended: the instance reconnects and drops its
          // cache whole, and the keyring hands this notice again (R15).
          log.warn(
            s"erasure notice ${notice.seq} not handed to $instance ($other); its stream ends"
          )
          awaiting.remove(key, waiting): Unit
          queue.fail(IllegalStateException(s"erasure notice ${notice.seq} could not be handed"))
          None
    }
    Future
      .sequence(acks)(using implicitly, ExecutionContext.parasitic)
      .map(_ => ())(using ExecutionContext.parasitic)

  /** `instance` dropped the key of notice `seq`; false when nothing waited for that ack. */
  def ack(seq: Long, instance: String): Boolean =
    Option(awaiting.remove((seq, instance))).exists(_.acked.trySuccess(()))

  /** Whether `instance` holds a stream now. */
  def holding(instance: String): Boolean = streams.containsKey(instance)

  // The notice bound: an instance silent past it has its stream failed, which settles the wait in
  // `close` once the stream has ended, and is settled here too in case it never ends.
  private def deadline(key: (Long, String), waiting: Waiting, instance: String, seq: Long): Unit =
    CompletableFuture
      .delayedExecutor(ackBound.toMillis, TimeUnit.MILLISECONDS)
      .execute { () =>
        if awaiting.remove(key, waiting) then
          log.warn(
            s"process instance $instance did not ack erasure notice $seq within $ackBound " +
              "(ankka.keyring.notice-bound); its stream ends, and the process drops every key"
          )
          waiting.queue.fail(
            IllegalStateException(s"erasure notice $seq was not acked within $ackBound")
          )
          waiting.acked.trySuccess(()): Unit
      }

  // The stream `queue` ended. It is deregistered only if it is still the instance's: a stream
  // opened again under the same name replaced it, and the old one's end must not take the new one
  // away. Only the waits handed on this stream are settled.
  private def close(instance: String, queue: Queue): Unit =
    val current = streams.remove(instance, queue)
    awaiting.asScala
      .collect { case (key, waiting) if waiting.queue eq queue => key -> waiting }
      .foreach((key, waiting) =>
        if awaiting.remove(key, waiting) then waiting.acked.trySuccess(()): Unit
      )
    if current then log.info("process instance {} no longer hears erasure notices", instance)
