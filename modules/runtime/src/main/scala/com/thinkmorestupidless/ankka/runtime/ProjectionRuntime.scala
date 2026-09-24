package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.effect.{ConsumerEffect, ViewEffect}
import com.thinkmorestupidless.ankka.core.{ComponentId, ComponentKind}
import com.thinkmorestupidless.ankka.runtime.remote.{
  Conversation,
  RemoteConsumer,
  RemoteConsumerDescriptor,
  RemoteConsumerEventHandler,
  RemoteConsumerStateHandler,
  RemoteConsumerTopicHandler,
  RemoteProjection,
  RemoteSource,
  RemoteView,
  RemoteViewDescriptor,
  RemoteViewEventHandler,
  RemoteViewStateHandler,
  RemoteViewTopicHandler
}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.query.{
  DeletedDurableState,
  DurableStateChange,
  UpdatedDurableState
}
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.{R2dbcHandler, R2dbcProjection, R2dbcSession}
import org.apache.pekko.projection.scaladsl.{Handler, SourceProvider}
import org.apache.pekko.projection.{Projection, ProjectionBehavior, ProjectionId}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Runs every registered view and consumer.
 *
 * Each one is a `ShardedDaemonProcess`: the cluster splits the source's id space into slice ranges
 * and hands each range to exactly one node, so throughput scales with `parallelism` and no change
 * is ever processed twice concurrently.
 *
 * Views over event sourced entities get exactly-once delivery, because the row write and the offset
 * write happen in one Postgres transaction. Everything else is at-least-once — see
 * `DurableStateSourceProvider` for why that is a property of the source rather than a shortcut.
 */
final class ProjectionRuntime private (
    publisherFactory: Option[ActorSystem[?] => MessagePublisher],
    subscriberFactory: Option[ActorSystem[?] => MessageSubscriber]
) extends RuntimeExtension:

  // Factories rather than instances: a Kafka client needs an ActorSystem, which does not
  // exist until the service starts. Resolved once, in `start`.
  @volatile private var publisher: Option[MessagePublisher]   = None
  @volatile private var subscriber: Option[MessageSubscriber] = None

  def name: String = "projections"

  def start(service: AnkkaService): Unit =
    given system: ActorSystem[?] = service.system
    val client                   = service.componentClient

    publisher = publisherFactory.map(_(system))
    subscriber = subscriberFactory.map(_(system))

    val views     = service.registry.components.collect { case v: ViewDescriptor[?, ?, ?] => v }
    val consumers = service.registry.components.collect { case c: ConsumerDescriptor[?, ?, ?] => c }
    val remoteViews = service.registry.components.collect { case v: RemoteViewDescriptor => v }
    val remoteConsumers = service.registry.components.collect { case c: RemoteConsumerDescriptor =>
      c
    }

    if views.isEmpty && consumers.isEmpty && remoteViews.isEmpty && remoteConsumers.isEmpty then
      system.log.debug("no views or consumers registered")
    else
      rejectUnsupported(views, consumers)
      rejectUnsupportedRemote(remoteViews, remoteConsumers)

      val database = Database()

      // Tables must exist before any projection writes to them.
      val tables = views.map(_.tableName) ++
        remoteViews.map(v => ViewDescriptor.tableFor(v.componentId))
      if tables.nonEmpty then
        // Under an advisory lock, in one transaction: several nodes of one service cold-start at
        // once and CREATE TABLE IF NOT EXISTS races (ViewStore.schemaLock explains).
        Await.result(
          database.executeAllInTransaction(
            ViewStore.schemaLock +: tables.map(ViewStore.createTable)
          ),
          30.seconds
        )
        views.foreach(v => system.log.info("view '{}' -> table {}", v.componentId, v.tableName))
        remoteViews.foreach(v =>
          system.log.info(
            "remote view '{}' -> table {}",
            v.componentId,
            ViewDescriptor.tableFor(v.componentId)
          )
        )

      views.foreach(startView(_, client))
      consumers.foreach(startConsumer(_, client))

      if remoteViews.nonEmpty || remoteConsumers.nonEmpty then
        // `validate` refused a registry holding remote descriptors without a conversation.
        val conversation = service.conversation.getOrElse(
          throw IllegalStateException("remote views or consumers registered without a conversation")
        )
        remoteViews.foreach(startRemoteView(_, conversation))
        remoteConsumers.foreach(startRemoteConsumer(_, conversation))

  /**
   * Fails fast on sources and sinks this runtime cannot serve.
   *
   * Both cases would otherwise be silent: a topic source would simply never deliver, and a
   * producing consumer without a publisher would drop every message. A startup failure naming the
   * component is far kinder than either.
   */
  private def rejectUnsupported(
      views: Vector[ViewDescriptor[?, ?, ?]],
      consumers: Vector[ConsumerDescriptor[?, ?, ?]]
  ): Unit =
    val problems = Vector.newBuilder[String]

    (views.map(v => v.componentId -> v.source) ++ consumers.map(c => c.componentId -> c.source))
      .foreach {
        case (id, source: ChangeSource.Topic[?]) if subscriber.isEmpty =>
          problems += s"'$id' consumes topic '${source.topic}' but no MessageSubscriber " +
            "was configured; pass one to ProjectionRuntime.withBroker"
        case _ => ()
      }

    consumers.foreach { consumer =>
      if consumer.produceTo.isDefined && publisher.isEmpty then
        problems += s"consumer '${consumer.componentId}' publishes to " +
          s"'${consumer.produceTo.get}' but no MessagePublisher was configured; " +
          "pass one to ProjectionRuntime.withPublisher"
    }

    val found = problems.result()
    if found.nonEmpty then
      throw IllegalArgumentException(
        found.mkString("cannot start ankka projections:\n  - ", "\n  - ", "")
      )

  /**
   * The same checks for remote components, plus one of their own: discovery lets a source name any
   * component kind, and only entities have a change stream to project.
   */
  private def rejectUnsupportedRemote(
      views: Vector[RemoteViewDescriptor],
      consumers: Vector[RemoteConsumerDescriptor]
  ): Unit =
    val problems = Vector.newBuilder[String]

    (views.map(v => v.componentId -> v.source) ++ consumers.map(c => c.componentId -> c.source))
      .foreach {
        case (id, RemoteSource.Topic(topic)) if subscriber.isEmpty =>
          problems += s"'$id' consumes topic '$topic' but no MessageSubscriber " +
            "was configured; pass one to ProjectionRuntime.withBroker"
        case (id, RemoteSource.Component(kind, sourceId))
            if kind != ComponentKind.EventSourcedEntity && kind != ComponentKind.KeyValueEntity =>
          problems += s"'$id' subscribes to $kind '$sourceId', which has no change stream; " +
            "a view or consumer follows an event sourced entity, a key value entity or a topic"
        case _ => ()
      }

    consumers.foreach { consumer =>
      if consumer.producesTo.isDefined && publisher.isEmpty then
        problems += s"consumer '${consumer.componentId}' publishes to " +
          s"'${consumer.producesTo.get}' but no MessagePublisher was configured; " +
          "pass one to ProjectionRuntime.withPublisher"
    }

    val found = problems.result()
    if found.nonEmpty then
      throw IllegalArgumentException(
        found.mkString("cannot start ankka projections:\n  - ", "\n  - ", "")
      )

  // ── Views ─────────────────────────────────────────────────────────────────

  private def startView(
      descriptor: ViewDescriptor[?, ?, ?],
      client: ComponentClient
  )(using system: ActorSystem[?]): Unit =
    type AnyView = View[Any, Any]
    val typed       = descriptor.asInstanceOf[ViewDescriptor[AnyView, Any, Any]]
    val processName = s"ankka-view-${typed.componentId}"

    typed.source match
      case ChangeSource.EventSourced(sourceId, _) =>
        daemon(processName, typed.parallelism) { index =>
          val range = eventSliceRanges(typed.parallelism)(index)
          exactlyOnceEventProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => ViewEventHandler(typed, client)
          )
        }

      case ChangeSource.KeyValue(sourceId, _) =>
        daemon(processName, typed.parallelism) { index =>
          val range = DurableStateSourceProvider.sliceRanges(typed.parallelism)(index)
          atLeastOnceStateProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => ViewStateHandler(typed, client)
          )
        }

      case ChangeSource.Topic(topic, _) =>
        subscriber.foreach { broker =>
          val handler = ViewTopicHandler(typed, Database(), client)
          broker.subscribe(topic, processName, handler.process)
          system.log.info("view '{}' consuming topic '{}'", typed.componentId, topic)
        }

  // ── Consumers ─────────────────────────────────────────────────────────────

  private def startConsumer(
      descriptor: ConsumerDescriptor[?, ?, ?],
      client: ComponentClient
  )(using system: ActorSystem[?]): Unit =
    type AnyConsumer = Consumer[Any, Any]
    val typed       = descriptor.asInstanceOf[ConsumerDescriptor[AnyConsumer, Any, Any]]
    val processName = s"ankka-consumer-${typed.componentId}"

    typed.source match
      case ChangeSource.EventSourced(sourceId, _) =>
        daemon(processName, typed.parallelism) { index =>
          val range = eventSliceRanges(typed.parallelism)(index)
          atLeastOnceEventProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => ConsumerEventHandler(typed, publisher, client)
          )
        }

      case ChangeSource.KeyValue(sourceId, _) =>
        daemon(processName, typed.parallelism) { index =>
          val range = DurableStateSourceProvider.sliceRanges(typed.parallelism)(index)
          atLeastOnceStateProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => ConsumerStateHandler(typed, publisher, client)
          )
        }

      case ChangeSource.Topic(topic, _) =>
        subscriber.foreach { broker =>
          val handler = ConsumerTopicHandler(typed, publisher, client)
          broker.subscribe(topic, processName, handler.process)
          system.log.info("consumer '{}' consuming topic '{}'", typed.componentId, topic)
        }

  // ── Remote views and consumers ────────────────────────────────────────────

  private def startRemoteView(
      descriptor: RemoteViewDescriptor,
      conversation: Conversation
  )(using system: ActorSystem[?]): Unit =
    given ExecutionContext = system.executionContext
    val processName        = s"ankka-view-${descriptor.componentId}"
    val parallelism        = RemoteProjection.Parallelism
    def view()             = RemoteView(descriptor, conversation, Observability(system))

    descriptor.source match
      case RemoteSource.Component(ComponentKind.EventSourcedEntity, sourceId) =>
        daemon(processName, parallelism) { index =>
          val range = eventSliceRanges(parallelism)(index)
          exactlyOnceEventProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => RemoteViewEventHandler(view())
          )
        }

      case RemoteSource.Component(ComponentKind.KeyValueEntity, sourceId) =>
        daemon(processName, parallelism) { index =>
          val range = DurableStateSourceProvider.sliceRanges(parallelism)(index)
          atLeastOnceStateProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => RemoteViewStateHandler(view(), Database())
          )
        }

      case RemoteSource.Topic(topic) =>
        subscriber.foreach { broker =>
          val handler = RemoteViewTopicHandler(view(), Database())
          broker.subscribe(topic, processName, handler.process)
          system.log.info("remote view '{}' consuming topic '{}'", descriptor.componentId, topic)
        }

      case RemoteSource.Component(_, _) => () // refused by rejectUnsupportedRemote

  private def startRemoteConsumer(
      descriptor: RemoteConsumerDescriptor,
      conversation: Conversation
  )(using system: ActorSystem[?]): Unit =
    given ExecutionContext = system.executionContext
    val processName        = s"ankka-consumer-${descriptor.componentId}"
    val parallelism        = RemoteProjection.Parallelism
    def consumer() = RemoteConsumer(descriptor, conversation, publisher, Observability(system))

    descriptor.source match
      case RemoteSource.Component(ComponentKind.EventSourcedEntity, sourceId) =>
        daemon(processName, parallelism) { index =>
          val range = eventSliceRanges(parallelism)(index)
          atLeastOnceEventProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => RemoteConsumerEventHandler(consumer())
          )
        }

      case RemoteSource.Component(ComponentKind.KeyValueEntity, sourceId) =>
        daemon(processName, parallelism) { index =>
          val range = DurableStateSourceProvider.sliceRanges(parallelism)(index)
          atLeastOnceStateProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => RemoteConsumerStateHandler(consumer())
          )
        }

      case RemoteSource.Topic(topic) =>
        subscriber.foreach { broker =>
          val handler = RemoteConsumerTopicHandler(consumer())
          broker.subscribe(topic, processName, handler.process)
          system.log
            .info("remote consumer '{}' consuming topic '{}'", descriptor.componentId, topic)
        }

      case RemoteSource.Component(_, _) => () // refused by rejectUnsupportedRemote

  // ── Plumbing ──────────────────────────────────────────────────────────────

  private def daemon(processName: String, parallelism: Int)(
      projection: Int => Projection[?]
  )(using system: ActorSystem[?]): Unit =
    ShardedDaemonProcess(system).init(
      processName,
      parallelism,
      index => ProjectionBehavior(projection(index))
    )
    system.log.info("started {} with parallelism {}", processName, parallelism)

  private def eventSliceRanges(parallelism: Int)(using system: ActorSystem[?]): Seq[Range] =
    EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, parallelism)

  private def eventSource(sourceId: ComponentId, range: Range)(using
      system: ActorSystem[?]
  ): SourceProvider[org.apache.pekko.persistence.query.Offset, EventEnvelope[JournalRecord]] =
    EventSourcedProvider.eventsBySlices[JournalRecord](
      system,
      R2dbcReadJournal.Identifier,
      sourceId,
      range.min,
      range.max
    )

  private def exactlyOnceEventProjection(
      id: ProjectionId,
      sourceId: ComponentId,
      range: Range,
      handler: () => R2dbcHandler[EventEnvelope[JournalRecord]]
  )(using system: ActorSystem[?]): Projection[EventEnvelope[JournalRecord]] =
    R2dbcProjection.exactlyOnce(id, None, eventSource(sourceId, range), handler)

  private def atLeastOnceEventProjection(
      id: ProjectionId,
      sourceId: ComponentId,
      range: Range,
      handler: () => Handler[EventEnvelope[JournalRecord]]
  )(using system: ActorSystem[?]): Projection[EventEnvelope[JournalRecord]] =
    R2dbcProjection.atLeastOnceAsync(id, None, eventSource(sourceId, range), handler)

  private def atLeastOnceStateProjection(
      id: ProjectionId,
      sourceId: ComponentId,
      range: Range,
      handler: () => Handler[DurableStateChange[StateRecord]]
  )(using system: ActorSystem[?]): Projection[DurableStateChange[StateRecord]] =
    R2dbcProjection.atLeastOnceAsync(
      id,
      None,
      DurableStateSourceProvider[StateRecord](sourceId, range.min, range.max),
      handler
    )

  override def stop(): Unit = subscriber.foreach(_.stop())

object ProjectionRuntime:

  /** Runs views and consumers over entity sources only. */
  def apply(): ProjectionRuntime = new ProjectionRuntime(None, None)

  /** Adds a publish target for consumers that produce. */
  def withPublisher(publisher: MessagePublisher): ProjectionRuntime =
    new ProjectionRuntime(Some(_ => publisher), None)

  /**
   * Adds both directions, enabling topic-sourced views and consumers.
   *
   * `InMemoryBroker` implements both, so the whole topic path can be exercised without a broker
   * running.
   */
  def withBroker(publisher: MessagePublisher, subscriber: MessageSubscriber): ProjectionRuntime =
    new ProjectionRuntime(Some(_ => publisher), Some(_ => subscriber))

  /**
   * Publishes to and consumes from Kafka.
   *
   * Offsets are committed to Kafka and partitions assigned by consumer groups, so scaling out needs
   * no configuration here — but topic sources are at-least-once and cannot rebuild from history,
   * because a broker's retention is not an event journal.
   */
  def withKafka(bootstrapServers: String): ProjectionRuntime =
    new ProjectionRuntime(
      Some(system => KafkaPublisher(bootstrapServers)(using system)),
      Some(system => KafkaSubscriber(bootstrapServers)(using system))
    )

// ── Handlers ────────────────────────────────────────────────────────────────

/** Applies a view's effect and the projection offset in one transaction. */
private final class ViewEventHandler(
    descriptor: ViewDescriptor[View[Any, Any], Any, Any],
    client: ComponentClient
)(using system: ActorSystem[?])
    extends R2dbcHandler[EventEnvelope[JournalRecord]]:

  private given ExecutionContext = system.executionContext
  private val view  = descriptor.create(SimpleViewContext(descriptor.componentId, client))
  private val table = descriptor.tableName
  // Captured here, at construction, not looked up inside `process`: projection handlers run on
  // the projection's own threads, and the recorded rule is to take what async work needs while
  // you are still somewhere it is safe to take it.
  private val observability = Observability(system)

  def process(session: R2dbcSession, envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event

    ProjectionSupport.loadRow(session, table, subject, descriptor.rowSerializer).flatMap { row =>
      val effect =
        ProjectionSupport
          .runView(view, descriptor, subject, envelope.sequenceNr, row, record, observability)
      ProjectionSupport.applyView(session, table, subject, effect, descriptor.rowSerializer)
    }

/** As `ViewEventHandler`, but for key value state changes. */
private final class ViewStateHandler(
    descriptor: ViewDescriptor[View[Any, Any], Any, Any],
    client: ComponentClient
)(using system: ActorSystem[?])
    extends Handler[DurableStateChange[StateRecord]]:

  private given ExecutionContext = system.executionContext
  private val database           = Database()
  private val view  = descriptor.create(SimpleViewContext(descriptor.componentId, client))
  private val table = descriptor.tableName

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)

    database
      .query(ViewStore.selectByKey(table, subject))(r =>
        descriptor.rowSerializer.fromBytes(r.get("payload", classOf[String]).getBytes("UTF-8"))
      )
      .flatMap { existing =>
        view._setRow(existing.headOption)
        view._setContext(Some(SimpleChangeContext(subject, revisionOf(change), localOrigin = true)))

        val effect =
          try
            change match
              case updated: UpdatedDurableState[StateRecord] =>
                view.onChange(descriptor.source.decoder.fromBytes(updated.value.payload))
              case _: DeletedDurableState[StateRecord] => view.onDelete
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

  private def revisionOf(change: DurableStateChange[StateRecord]): Long = change match
    case updated: UpdatedDurableState[StateRecord] => updated.revision
    case deleted: DeletedDurableState[StateRecord] => deleted.revision

/** Runs a consumer over an entity's events, publishing anything it produces. */
private final class ConsumerEventHandler(
    descriptor: ConsumerDescriptor[Consumer[Any, Any], Any, Any],
    publisher: Option[MessagePublisher],
    client: ComponentClient
) extends Handler[EventEnvelope[JournalRecord]]:

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client))

  def process(envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event

    consumer._setContext(
      Some(SimpleChangeContext(subject, envelope.sequenceNr, localOrigin = true))
    )
    val effect =
      try
        record.kind match
          case JournalRecord.KindDomain =>
            consumer.onMessage(descriptor.source.decoder.fromBytes(record.payload))
          case JournalRecord.KindDeleted => consumer.onDelete
          case _                         => ConsumerEffect.Ignore
      finally consumer._setContext(None)

    ProjectionSupport.applyConsumer(effect, subject, descriptor, publisher)

/** As `ConsumerEventHandler`, but for key value state changes. */
private final class ConsumerStateHandler(
    descriptor: ConsumerDescriptor[Consumer[Any, Any], Any, Any],
    publisher: Option[MessagePublisher],
    client: ComponentClient
) extends Handler[DurableStateChange[StateRecord]]:

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client))

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)

    consumer._setContext(Some(SimpleChangeContext(subject, 0L, localOrigin = true)))
    val effect =
      try
        change match
          case updated: UpdatedDurableState[StateRecord] =>
            consumer.onMessage(descriptor.source.decoder.fromBytes(updated.value.payload))
          case _: DeletedDurableState[StateRecord] => consumer.onDelete
      finally consumer._setContext(None)

    ProjectionSupport.applyConsumer(effect, subject, descriptor, publisher)
