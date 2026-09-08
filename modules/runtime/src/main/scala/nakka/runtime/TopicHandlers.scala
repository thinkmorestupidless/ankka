package nakka.runtime

import nakka.core.effect.ViewEffect
import nakka.sdk.*
import org.apache.pekko.Done
import nakka.sdk.ComponentClient
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.{ExecutionContext, Future}

/**
 * Projects broker messages into a view's rows.
 *
 * Unlike the entity-sourced handler, this one writes through its own connection rather
 * than a projection transaction — the offset lives in Kafka, so there is no shared
 * transaction to join. Topic-sourced views are therefore at-least-once, and a view
 * reading one must be idempotent per message.
 */
private[nakka] final class ViewTopicHandler(
    descriptor: ViewDescriptor[View[Any, Any], Any, Any],
    database: Database,
    client: ComponentClient
)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  private val view  = descriptor.create(SimpleViewContext(descriptor.componentId, client))
  private val table = descriptor.tableName

  def process(message: IncomingMessage): Future[Done] =
    message.subject match
      case None =>
        // Without a subject there is no row to key off. Failing would redeliver
        // forever, so log and move on.
        system.log.warn(
          "view '{}' skipped a message with no ce-subject",
          descriptor.componentId
        )
        Future.successful(Done)

      case Some(subject) =>
        database
          .query(ViewStore.selectByKey(table, subject))(row =>
            descriptor.rowSerializer.fromBytes(row.get("payload", classOf[String]).getBytes("UTF-8"))
          )
          .flatMap { existing =>
            view._setRow(existing.headOption)
            view._setContext(
              Some(SimpleChangeContext(subject, 0L, localOrigin = true))
            )

            val effect =
              try view.onChange(descriptor.source.decoder.fromBytes(message.payload))
              finally view._setContext(None)

            effect match
              case ViewEffect.UpdateRow(row) =>
                val json = String(descriptor.rowSerializer.toBytes(row), "UTF-8")
                database.execute(ViewStore.upsert(table, subject, json)).map(_ => Done)
              case ViewEffect.DeleteRow =>
                database.execute(ViewStore.delete(table, subject)).map(_ => Done)
              case ViewEffect.Ignore =>
                Future.successful(Done)
          }

/**
 * Runs a consumer over broker messages.
 *
 * A handler that throws produces a failed Future, which stops the offset being committed
 * — so the message is redelivered. That is the whole at-least-once contract, and it is
 * why a consumer must tolerate duplicates.
 */
private[nakka] final class ConsumerTopicHandler(
    descriptor: ConsumerDescriptor[Consumer[Any, Any], Any, Any],
    publisher: Option[MessagePublisher],
    client: ComponentClient
):

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client))

  def process(message: IncomingMessage): Future[Done] =
    val subject = message.subject.getOrElse("")

    consumer._setContext(Some(SimpleChangeContext(subject, 0L, localOrigin = true)))
    val effect =
      try consumer.onMessage(descriptor.source.decoder.fromBytes(message.payload))
      catch
        case failure: Throwable =>
          consumer._setContext(None)
          return Future.failed(failure)
      finally consumer._setContext(None)

    ProjectionSupport.applyConsumer(effect, subject, descriptor, publisher)
