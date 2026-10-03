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

import scala.concurrent.duration.{DurationInt, FiniteDuration}
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
  // What a topic source's consumer group is named for; resolved by the service, read here.
  @volatile private var identity: Either[String, ServiceIdentity] = Right(ServiceIdentity.unnamed)

  def name: String = "projections"

  def start(service: AnkkaService): Unit =
    given system: ActorSystem[?] = service.system
    val client                   = service.componentClient

    publisher = publisherFactory.map(_(system))
    subscriber = subscriberFactory.map(_(system))
    identity = service.identity

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
      // The recorded versions belong with the tables they describe, and only a service that has a
      // view over a topic needs them.
      val topicViews =
        views.collect { case v if v.source.isInstanceOf[ChangeSource.Topic[?]] => v.componentId } ++
          remoteViews.collect {
            case v if v.source.isInstanceOf[RemoteSource.Topic] => v.componentId
          }
      val versions =
        if topicViews.isEmpty then Vector.empty
        else ViewVersions.createTable +: topicViews.map(id => ViewVersions.ensure(id))
      if tables.nonEmpty then
        // Under an advisory lock, in one transaction: several nodes of one service cold-start at
        // once and CREATE TABLE IF NOT EXISTS races (ViewStore.schemaLock explains).
        Await.result(
          database.executeAllInTransaction(
            (ViewStore.schemaLock +: tables.map(ViewStore.createTable)) ++ versions
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
      consumers.foreach(startConsumer(_, client, service.secrets))

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

    // What each declared is read through `DeclaredConnections`, as the topology reads it: what
    // this refuses to start and what a console draws are the same reading of the same descriptor.
    (views ++ consumers).foreach { component =>
      DeclaredConnections.sourceOf(component) match
        case Some(DeclaredSource.Topic(topic)) if subscriber.isEmpty =>
          problems += s"'${component.componentId}' consumes topic '$topic' but no " +
            "MessageSubscriber was configured; pass one to ProjectionRuntime.withBroker, or set " +
            s"${ProjectionRuntime.KafkaEnvVar} for ProjectionRuntime.fromEnv"
        case Some(DeclaredSource.Topic(topic)) =>
          identity.left.foreach(why =>
            problems += unnamedTopicSource(component.componentId, topic, why)
          )
        case _ => ()
    }

    consumers.foreach { consumer =>
      DeclaredConnections.destinationOf(consumer).foreach { topic =>
        if publisher.isEmpty then
          problems += s"consumer '${consumer.componentId}' publishes to " +
            s"'$topic' but no MessagePublisher was configured; " +
            "pass one to ProjectionRuntime.withPublisher, or set " +
            s"${ProjectionRuntime.KafkaEnvVar} for ProjectionRuntime.fromEnv"
      }
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

    (views.map(v => v -> v.source) ++ consumers.map(c => c -> c.source)).foreach {
      (component, source) =>
        val id = component.componentId
        (DeclaredConnections.sourceOf(component), source) match
          case (Some(DeclaredSource.Topic(topic)), _) if subscriber.isEmpty =>
            problems += s"'$id' consumes topic '$topic' but no MessageSubscriber " +
              "was configured; pass one to ProjectionRuntime.withBroker"
          case (Some(DeclaredSource.Topic(topic)), _) =>
            identity.left.foreach(why => problems += unnamedTopicSource(id, topic, why))
          // A source `DeclaredConnections` does not read as one: a component with no change stream.
          case (None, RemoteSource.Component(kind, sourceId)) =>
            problems += s"'$id' subscribes to $kind '$sourceId', which has no change stream; " +
              "a view or consumer follows an event sourced entity, a key value entity or a topic"
          case _ => ()
    }

    consumers.foreach { consumer =>
      DeclaredConnections.destinationOf(consumer).foreach { topic =>
        if publisher.isEmpty then
          problems += s"consumer '${consumer.componentId}' publishes to " +
            s"'$topic' but no MessagePublisher was configured; " +
            "pass one to ProjectionRuntime.withPublisher"
      }
    }

    val found = problems.result()
    if found.nonEmpty then
      throw IllegalArgumentException(
        found.mkString("cannot start ankka projections:\n  - ", "\n  - ", "")
      )

  /**
   * A topic source reads under a group named for its service, and a deployed service whose identity
   * could not be read must not fall back to a name another service could share.
   */
  private def unnamedTopicSource(id: ComponentId, topic: String, why: String): String =
    s"'$id' consumes topic '$topic', and its consumer group is named for the service, " +
      s"whose identity could not be read: $why"

  /** The group a topic source reads under; only called once `rejectUnsupported*` has passed. */
  private def groupFor(kind: ComponentKind, componentId: ComponentId, version: Int): String =
    ConsumerGroups.name(identity.getOrElse(ServiceIdentity.unnamed), kind, componentId, version)

  // Each running subscription, so `stop` ends exactly these.
  private val subscriptions = java.util.concurrent.CopyOnWriteArrayList[Subscribed]()

  /**
   * Subscribes one topic source and says so, with everything a person looking for its messages on
   * the broker needs: the topic, the group, where it starts and its version.
   */
  private def subscribeTopic(
      broker: MessageSubscriber,
      kind: ComponentKind,
      componentId: ComponentId,
      topic: String,
      startFrom: StartFrom,
      version: Int,
      handle: IncomingMessage => Future[Done]
  )(using system: ActorSystem[?]): Subscribed =
    val group      = groupFor(kind, componentId, version)
    val subscribed = broker.subscribe(TopicSubscription(topic, group, startFrom), handle)
    subscriptions.add(subscribed): Unit
    TopicSources(system).update(componentId)(_.copy(group = group))
    system.log.info(
      "topic source subscribed: kind={} component={} topic={} group={} start={} version={}",
      kindWord(kind),
      componentId,
      topic,
      group,
      startFrom,
      version
    )
    subscribed

  /** What the service says about each topic source, before it has subscribed. */
  private def declare(
      kind: ComponentKind,
      componentId: ComponentId,
      topic: String,
      startFrom: StartFrom,
      version: Int,
      recorded: Option[Int]
  )(using system: ActorSystem[?]): Unit =
    TopicSources(system).put(
      TopicSourceStatus(
        kind,
        componentId,
        topic,
        groupFor(kind, componentId, version),
        startFrom,
        version,
        recorded,
        behind = false
      )
    )

  /**
   * A view over a topic, at the version it declares: subscribed if its rows were built at that
   * version, rebuilt first if they were built at a lower one, and left alone — reading nothing,
   * writing nothing, serving what it has — if at a higher one.
   *
   * Off the start thread: a rebuild waits for the broker to say what it still holds, and a broker
   * that is down must not hold up the service's start.
   */
  private def startTopicView(
      broker: MessageSubscriber,
      componentId: ComponentId,
      topic: String,
      startFrom: StartFrom,
      declared: Int,
      handler: ViewGuard => IncomingMessage => Future[Done]
  )(using system: ActorSystem[?]): Unit =
    given ExecutionContext = system.executionContext
    val database           = Database()
    val table              = ViewDescriptor.tableFor(componentId)
    val log                = system.log
    declare(ComponentKind.View, componentId, topic, startFrom, declared, None)

    // Set once the view has subscribed, so a write that finds it behind can stop it.
    val holder  = java.util.concurrent.atomic.AtomicReference[Option[Subscribed]](None)
    val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    val guard = ViewGuard(
      database,
      componentId,
      declared,
      recorded =>
        if stopped.compareAndSet(false, true) then
          behind(componentId, declared, recorded)
          holder.get.foreach(_.stop())
    )

    def subscribe(recorded: Int): Unit =
      TopicSources(system).update(componentId)(_.copy(recordedVersion = Some(recorded)))
      holder.set(
        Some(
          subscribeTopic(
            broker,
            ComponentKind.View,
            componentId,
            topic,
            startFrom,
            declared,
            handler(guard)
          )
        )
      )

    val started =
      ViewVersions.recorded(database, componentId).flatMap { recorded =>
        if recorded == declared then Future.successful(subscribe(recorded))
        else if recorded > declared then Future.successful(behind(componentId, declared, recorded))
        else
          retainedThenRebuild(broker, database, table, componentId, topic, recorded, declared)
            .map {
              case ViewVersions.Rebuilt.Emptied(from) =>
                log.info(
                  "view emptied for rebuild: component={} from version={} to version={}",
                  componentId,
                  from,
                  declared
                )
                subscribe(declared)
              case ViewVersions.Rebuilt.AlreadyBuilt =>
                log.info(
                  "view rebuild already done: component={} version={}",
                  componentId,
                  declared
                )
                subscribe(declared)
              case ViewVersions.Rebuilt.Behind(higher) => behind(componentId, declared, higher)
            }
      }
    started.failed.foreach(failure =>
      log.error(s"view '$componentId' could not start reading topic '$topic'", failure)
    )

  /**
   * Asks the broker what it still holds before a single row is removed, retrying until it answers:
   * a view is never emptied while there is no broker to fill it again. Then says how far back the
   * rebuild will reach, and rebuilds.
   */
  private def retainedThenRebuild(
      broker: MessageSubscriber,
      database: Database,
      table: String,
      componentId: ComponentId,
      topic: String,
      recorded: Int,
      declared: Int
  )(using system: ActorSystem[?]): Future[ViewVersions.Rebuilt] =
    given ExecutionContext = system.executionContext
    def ask(delay: FiniteDuration): Future[Map[Int, Option[java.time.Instant]]] =
      broker.earliestRetained(topic).recoverWith { case failure =>
        system.log.warn(
          "view '{}' waits to rebuild: the broker could not say what topic '{}' holds ({}); " +
            "asking again in {}",
          componentId,
          topic,
          failure.getMessage,
          delay
        )
        org.apache.pekko.pattern
          .after(delay)(ask((delay * 2).min(30.seconds)))(using system.classicSystem)
      }
    ask(1.second).flatMap { retained =>
      val reach = retained.toVector
        .sortBy(_._1)
        .map((partition, at) => s"$partition=${at.fold("holds nothing")(_.toString)}")
        .mkString(", ")
      system.log.info(
        "view rebuild: component={} table={} from version={} to version={} earliest retained per " +
          "partition: {}",
        componentId,
        table,
        recorded,
        declared,
        reach
      )
      ViewVersions.rebuild(database, table, componentId, declared)
    }

  /** A view declared below its recorded version: it is left as it is, and says so. */
  private def behind(componentId: ComponentId, declared: Int, recorded: Int)(using
      system: ActorSystem[?]
  ): Unit =
    TopicSources(system).update(componentId)(
      _.copy(recordedVersion = Some(recorded), behind = true)
    )
    system.log.warn(
      "view behind its recorded version: component={} declared={} recorded={}; this instance " +
        "reads nothing from its topic and writes nothing to its table",
      componentId,
      declared,
      recorded
    )

  private def kindWord(kind: ComponentKind): String =
    if kind == ComponentKind.View then "view" else "consumer"

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

      case ChangeSource.Topic(topic, _, startFrom) =>
        subscriber.foreach { broker =>
          // A view that declares nowhere starts at the earliest message the broker holds.
          startTopicView(
            broker,
            typed.componentId,
            topic,
            startFrom.getOrElse(StartFrom.Earliest),
            typed.version.getOrElse(1),
            guard => ViewTopicHandler(typed, Database(), client, guard).process
          )
        }

  // ── Consumers ─────────────────────────────────────────────────────────────

  private def startConsumer(
      descriptor: ConsumerDescriptor[?, ?, ?],
      client: ComponentClient,
      secrets: SecretStore
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
            () => ConsumerEventHandler(typed, publisher, client, Observability(system), secrets)
          )
        }

      case ChangeSource.KeyValue(sourceId, _) =>
        daemon(processName, typed.parallelism) { index =>
          val range = DurableStateSourceProvider.sliceRanges(typed.parallelism)(index)
          atLeastOnceStateProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () => ConsumerStateHandler(typed, publisher, client, Observability(system), secrets)
          )
        }

      case ChangeSource.Topic(topic, _, startFrom) =>
        subscriber.foreach { broker =>
          val handler =
            ConsumerTopicHandler(typed, publisher, client, Observability(system), secrets)
          // `validate` refused a consumer over a topic that declares nowhere.
          val start   = startFrom.getOrElse(StartFrom.Earliest)
          val version = typed.version.getOrElse(1)
          declare(ComponentKind.Consumer, typed.componentId, topic, start, version, None)
          subscribeTopic(
            broker,
            ComponentKind.Consumer,
            typed.componentId,
            topic,
            start,
            version,
            handler.process
          ): Unit
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

      case RemoteSource.Topic(topic, startFrom) =>
        subscriber.foreach { broker =>
          startTopicView(
            broker,
            descriptor.componentId,
            topic,
            startFrom.getOrElse(StartFrom.Earliest),
            descriptor.version.getOrElse(1),
            guard => RemoteViewTopicHandler(view(), Database(), guard).process
          )
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

      case RemoteSource.Topic(topic, startFrom) =>
        subscriber.foreach { broker =>
          val handler = RemoteConsumerTopicHandler(consumer())
          if startFrom.isEmpty then
            // Only an SDK that could not declare one gets here: `validate` refused any other.
            system.log.warn(
              "consumer '{}' reads topic '{}' and declares no start position, because its SDK " +
                "predates them; it starts at the earliest message the broker holds, as it always " +
                "has. An SDK speaking protocol {} or later can declare one.",
              descriptor.componentId,
              topic,
              ProjectionRuntime.StartPositionProtocol
            )
          val start   = startFrom.getOrElse(StartFrom.Earliest)
          val version = descriptor.version.getOrElse(1)
          declare(ComponentKind.Consumer, descriptor.componentId, topic, start, version, None)
          subscribeTopic(
            broker,
            ComponentKind.Consumer,
            descriptor.componentId,
            topic,
            start,
            version,
            handler.process
          ): Unit
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

  override def stop(): Unit =
    subscriptions.forEach(_.stop())
    subscriptions.clear()
    subscriber.foreach(_.stop())

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

  /**
   * The variable [[fromEnv]] reads, and a process-hosted service's sidecar reads, for the broker.
   */
  val KafkaEnvVar: String = "ANKKA_KAFKA_BOOTSTRAP_SERVERS"

  /** The first protocol in which a process can declare where a topic source starts. */
  val StartPositionProtocol: String = "1.7"

  /**
   * Kafka when the environment names a broker, entity sources only when it does not.
   *
   * The platform provides no broker, and a deployed service is configured by its descriptor's
   * `env`, not by code — so this is how a service reaches one: `ANKKA_KAFKA_BOOTSTRAP_SERVERS` in
   * the descriptor, the same variable a process-hosted service's sidecar reads. Without it, a
   * producing consumer or a topic-sourced view is still refused at startup.
   */
  def fromEnv(env: Map[String, String] = sys.env): ProjectionRuntime =
    env.get(KafkaEnvVar).map(_.trim).filter(_.nonEmpty).fold(apply())(withKafka)

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
  // Taken at construction, as the event handler's is: `process` runs on the projection's threads.
  private val observability = Observability(system)

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
            ProjectionSupport.handling(
              observability,
              descriptor.componentId.toString,
              ViewDescriptor.OnChange.name
            ) {
              change match
                // A deletion is a state marked deleted (`KeyValueEntityHost.Stored`).
                case updated: UpdatedDurableState[StateRecord] if updated.value.deleted =>
                  view.onDelete
                case updated: UpdatedDurableState[StateRecord] =>
                  view.onChange(descriptor.source.decoder.fromBytes(updated.value.payload))
                case _: DeletedDurableState[StateRecord] => view.onDelete
            }
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
    client: ComponentClient,
    observability: Observability,
    secrets: SecretStore
) extends Handler[EventEnvelope[JournalRecord]]:

  private val id = descriptor.componentId.toString

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client, secrets))

  def process(envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event

    consumer._setContext(
      Some(SimpleChangeContext(subject, envelope.sequenceNr, localOrigin = true))
    )
    val effect =
      try
        ProjectionSupport.handling(observability, id, ConsumerDescriptor.OnMessage.name) {
          record.kind match
            case JournalRecord.KindDomain =>
              consumer.onMessage(descriptor.source.decoder.fromBytes(record.payload))
            case JournalRecord.KindDeleted => consumer.onDelete
            case _                         => ConsumerEffect.Ignore
        }
      finally consumer._setContext(None)

    ProjectionSupport.applyConsumer(effect, subject, descriptor, publisher)

/** As `ConsumerEventHandler`, but for key value state changes. */
private final class ConsumerStateHandler(
    descriptor: ConsumerDescriptor[Consumer[Any, Any], Any, Any],
    publisher: Option[MessagePublisher],
    client: ComponentClient,
    observability: Observability,
    secrets: SecretStore
) extends Handler[DurableStateChange[StateRecord]]:

  private val id = descriptor.componentId.toString

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client, secrets))

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)

    val revision = change match
      case updated: UpdatedDurableState[StateRecord] => updated.revision
      case deleted: DeletedDurableState[StateRecord] => deleted.revision

    consumer._setContext(Some(SimpleChangeContext(subject, revision, localOrigin = true)))
    val effect =
      try
        ProjectionSupport.handling(observability, id, ConsumerDescriptor.OnMessage.name) {
          change match
            // A deletion is a state marked deleted (`KeyValueEntityHost.Stored`).
            case updated: UpdatedDurableState[StateRecord] if updated.value.deleted =>
              consumer.onDelete
            case updated: UpdatedDurableState[StateRecord] =>
              consumer.onMessage(descriptor.source.decoder.fromBytes(updated.value.payload))
            case _: DeletedDurableState[StateRecord] => consumer.onDelete
        }
      finally consumer._setContext(None)

    ProjectionSupport.applyConsumer(effect, subject, descriptor, publisher)
