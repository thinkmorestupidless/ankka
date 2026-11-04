package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.core.keyring.*
import com.thinkmorestupidless.ankka.testkit.InMemoryKeyring

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * The installation's keyring for the control plane's tests (feature 042): a test kit's keyring in
 * memory, ordered as the control plane orders the real one. Each order destroys the key there and
 * sends its notice; the status is what the services' instances acknowledged.
 */
final class TestKeyringAdmin(
    keyring: InMemoryKeyring,
    services: () => Vector[String] = () => Vector.empty,
    clock: java.time.Clock = java.time.Clock.systemUTC()
) extends KeyringAdmin:

  private val seen = ConcurrentHashMap[(Long, String, Int), Instant]()

  /** The keyring's sequence for each log entry's. */
  private val notices                  = ConcurrentHashMap[Long, Long]()
  private val orders                   = java.util.concurrent.ConcurrentLinkedQueue[ErasureOrder]()
  @volatile var refuse: Option[String] = None

  def ordered: Vector[ErasureOrder] = orders.asScala.toVector

  def destroy(order: ErasureOrder): Future[ErasureDestroyed] =
    refuse.fold {
      orders.add(order)
      notices.computeIfAbsent(
        order.seq,
        _ => keyring.erase(order.project, DataSubject(order.subject))
      )
      Future.successful(ErasureDestroyed(clock.instant()))
    }(why => Future.failed(IllegalStateException(why)))

  def resend(seq: Long): Future[Unit] =
    val project = orders.asScala.find(_.seq == seq)
    project.foreach(o =>
      notices.put(seq, keyring.erase(o.project, DataSubject(o.subject), rerun = true))
    )
    Future.unit

  def status(seq: Long): Future[ErasureStatus] =
    val notice = Option(notices.get(seq))
    val acks   = notice.toVector.flatMap(n => keyring.acks.filter(_.seq == n))
    Future.successful(
      ErasureStatus(
        (services() ++ acks.map(_.service)).distinct.map { service =>
          val mine = acks.filter(_.service == service)
          mine.lastOption match
            // Each service's latest report, at when this keyring first saw it, as the real
            // keyring keeps the time of each instance's last acknowledgement.
            case Some(a) =>
              val at = seen.computeIfAbsent((notice.get, service, mine.size), _ => clock.instant())
              ServiceErasureStatus(service, Some(at), Some(a.completion))
            case None => ServiceErasureStatus(service)
        }
      )
    )

/** The log's bucket, in memory; it may be made to refuse. */
final class MemoryErasureLogBucket extends ErasureLogBucket:
  val objects: ConcurrentHashMap[String, Array[Byte]] = ConcurrentHashMap()
  @volatile var refuse: Boolean                       = false

  def putOnce(name: String, bytes: Array[Byte]): Unit =
    if refuse then throw IllegalStateException("the bucket refused the write")
    objects.putIfAbsent(name, bytes): Unit

  def entries(): Vector[ErasureLogEntry] =
    objects.values.asScala.toVector.map(
      com.github.plokhotnyuk.jsoniter_scala.core.readFromArray[ErasureLogEntry](_)
    )
