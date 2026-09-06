package nakka.runtime

import nakka.core.Metadata
import org.apache.pekko.Done

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * Where a consumer's `effects.produce(...)` output goes.
 *
 * An SPI rather than a concrete broker client: publishing is the one part of the
 * consumer story that is genuinely environment-specific, and a runtime that hard-codes
 * one broker forces everyone else to fork it.
 *
 * nakka ships `InMemoryPublisher` for tests. A Kafka or Pub/Sub implementation is this
 * one method over `SendProducer`; none is bundled yet, and a consumer that declares
 * `produceTo` without a publisher configured fails at startup rather than dropping
 * messages silently.
 */
trait MessagePublisher:
  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done]

/** Records published messages in memory, for tests and local development. */
final class InMemoryPublisher extends MessagePublisher:

  private val recorded = ConcurrentLinkedQueue[InMemoryPublisher.Published]()

  def publish(topic: String, payload: Array[Byte], metadata: Metadata): Future[Done] =
    recorded.add(InMemoryPublisher.Published(topic, payload, metadata)): Unit
    Future.successful(Done)

  def published: Seq[InMemoryPublisher.Published] = recorded.asScala.toSeq

  def publishedTo(topic: String): Seq[InMemoryPublisher.Published] =
    published.filter(_.topic == topic)

  def clear(): Unit = recorded.clear()

object InMemoryPublisher:
  final case class Published(topic: String, payload: Array[Byte], metadata: Metadata):
    def text: String = String(payload, "UTF-8")
