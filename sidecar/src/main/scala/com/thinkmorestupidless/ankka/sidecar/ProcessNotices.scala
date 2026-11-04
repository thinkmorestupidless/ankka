package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.SubjectNotice
import com.thinkmorestupidless.ankka.core.keyring.KeyringNotice
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.QueueOfferResult
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * The erasure notices a process hears (protocol 1.23, feature 042): each instance of the process
 * holds `SubjectNotices` open and acks each notice once it has dropped the subject's key. A notice
 * is done here once every instance that held the stream when it arrived has acked it, or has closed
 * its stream, which leaves nothing cached; only then does the runtime go on with its own duties and
 * tell the keyring. A process holding no stream caches nothing, and is done at once.
 */
final class ProcessNotices:

  private val log     = LoggerFactory.getLogger(classOf[ProcessNotices])
  private val streams =
    ConcurrentHashMap[String, org.apache.pekko.stream.BoundedSourceQueue[SubjectNotice]]()
  private val awaiting = ConcurrentHashMap[(Long, String), Promise[Unit]]()

  /** The notices for `instance` from now on; a second stream under one name replaces the first. */
  def open(instance: String): Source[SubjectNotice, NotUsed] =
    Source
      .queue[SubjectNotice](1024)
      .mapMaterializedValue { queue =>
        Option(streams.put(instance, queue)).foreach(_.complete())
        log.info("process instance {} hears erasure notices", instance)
        NotUsed
      }
      .watchTermination() { (_, done) =>
        done.onComplete(_ => close(instance))(using scala.concurrent.ExecutionContext.parasitic)
        NotUsed
      }

  /** Hands `notice` to every instance holding a stream; done once each has acked or gone. */
  def notify(notice: KeyringNotice): Future[Unit] =
    val held = streams.asScala.toVector
    val acks = held.flatMap { (instance, queue) =>
      val acked = Promise[Unit]()
      awaiting.put((notice.seq, instance), acked)
      queue.offer(SubjectNotice(notice.seq, notice.project, notice.subject, notice.rerun)) match
        case QueueOfferResult.Enqueued => Some(acked.future)
        case other                     =>
          // A stream that cannot take a notice is ended: the instance reconnects and drops its
          // cache whole, and the keyring hands this notice again (R15).
          log.warn(
            s"erasure notice ${notice.seq} not handed to $instance ($other); its stream ends"
          )
          awaiting.remove((notice.seq, instance))
          queue.fail(IllegalStateException(s"erasure notice ${notice.seq} could not be handed"))
          None
    }
    Future
      .sequence(acks)(using implicitly, scala.concurrent.ExecutionContext.parasitic)
      .map(_ => ())(using
        scala.concurrent.ExecutionContext.parasitic
      )

  /** `instance` dropped the key of notice `seq`; false when nothing waited for that ack. */
  def ack(seq: Long, instance: String): Boolean =
    Option(awaiting.remove((seq, instance))).exists(_.trySuccess(()))

  /** Whether `instance` holds a stream now. */
  def holding(instance: String): Boolean = streams.containsKey(instance)

  private def close(instance: String): Unit =
    streams.remove(instance): Unit
    awaiting.asScala.keys
      .filter(_._2 == instance)
      .foreach(key => Option(awaiting.remove(key)).foreach(_.trySuccess(())))
    log.info("process instance {} no longer hears erasure notices", instance)
