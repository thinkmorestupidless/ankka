package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.effect.{ConsumerEffect, ViewEffect}
import com.thinkmorestupidless.ankka.core.{
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  Contract
}
import com.thinkmorestupidless.ankka.runtime.remote.{
  Conversation,
  RemoteConsumer,
  RemoteConsumerDescriptor,
  RemoteConsumerEventHandler,
  RemoteConsumerStateHandler,
  RemoteConsumerTopicHandler,
  RemoteKeyedView,
  RemoteKeyedViewDescriptor,
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
    subscriberFactory: Option[ActorSystem[?] => MessageSubscriber],
    // Whether the factory makes a publisher this runtime owns (Kafka's, a producer it opened) and so
    // must close on stop; one handed in, such as a test's broker, is its giver's to close.
    ownsPublisher: Boolean = false,
    // What the project declares about its topics and brokers (feature 037): `Right(None)` declares
    // nothing and checks nothing; a `Left` is a file that could not be read, which refuses the start.
    declarations: Either[String, Option[ProjectDeclarations]] = Right(None),
    // The declared brokers (feature 037), by name: a connection each, beside the installation's.
    declaredBrokers: Map[String, ActorSystem[?] => (MessagePublisher, MessageSubscriber)] =
      Map.empty
) extends RuntimeExtension:

  /** The same runtime with a declared broker a component may name for a topic. */
  def withDeclaredBroker(
      name: String,
      publisher: MessagePublisher,
      subscriber: MessageSubscriber
  ): ProjectionRuntime =
    new ProjectionRuntime(
      publisherFactory,
      subscriberFactory,
      ownsPublisher,
      declarations,
      declaredBrokers.updated(name, _ => (publisher, subscriber))
    )

  private def withDeclaredKafka(name: String, connection: KafkaConnection): ProjectionRuntime =
    new ProjectionRuntime(
      publisherFactory,
      subscriberFactory,
      ownsPublisher,
      declarations,
      declaredBrokers.updated(
        name,
        system =>
          (
            KafkaPublisher(connection)(using system),
            KafkaSubscriber(connection, 1.second, 30.seconds)(using system)
          )
      )
    )

  @volatile private var brokers: Map[String, (MessagePublisher, MessageSubscriber)] = Map.empty

  /** The publisher for a publication: the named declared broker's, else the installation's. */
  private def publisherFor(broker: Option[String]): Option[MessagePublisher] =
    broker.fold(publisher)(name => brokers.get(name).map(_._1))

  /** The subscriber for a topic source: the named declared broker's, else the installation's. */
  private def subscriberFor(broker: Option[String]): Option[MessageSubscriber] =
    broker.fold(subscriber)(name => brokers.get(name).map(_._2))

  /** The same runtime, checking components against these declarations at start. */
  def withDeclarations(declared: ProjectDeclarations): ProjectionRuntime =
    new ProjectionRuntime(
      publisherFactory,
      subscriberFactory,
      ownsPublisher,
      Right(Some(declared)),
      declaredBrokers
    )

  /** The same runtime, refusing to start because the declarations file could not be read. */
  private[runtime] def withUnreadableDeclarations(why: String): ProjectionRuntime =
    new ProjectionRuntime(
      publisherFactory,
      subscriberFactory,
      ownsPublisher,
      Left(why),
      declaredBrokers
    )

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
    brokers = declaredBrokers.map((name, make) => name -> make(system))
    subscriber = subscriberFactory.map(_(system))
    identity = service.identity

    val views     = service.registry.components.collect { case v: ViewDescriptor[?, ?, ?] => v }
    val keyed     = service.registry.components.collect { case v: KeyedViewDescriptor[?, ?] => v }
    val consumers = service.registry.components.collect { case c: ConsumerDescriptor[?, ?, ?] => c }
    val remoteViews = service.registry.components.collect { case v: RemoteViewDescriptor => v }
    val remoteKeyed =
      service.registry.components.collect { case v: RemoteKeyedViewDescriptor => v }
    val remoteConsumers = service.registry.components.collect { case c: RemoteConsumerDescriptor =>
      c
    }

    if views.isEmpty && keyed.isEmpty && consumers.isEmpty && remoteViews.isEmpty &&
      remoteKeyed.isEmpty && remoteConsumers.isEmpty
    then system.log.debug("no views or consumers registered")
    else
      // The project's word first: a broker the project does not declare is named as such, not as
      // a publisher nobody configured.
      rejectUndeclared(service.registry.components.toVector)
      ProjectionRuntime.crossProjectProblems(service.registry.components.toVector) match
        case Vector() => ()
        case found =>
          StartRefusal.refuse(found.mkString("; "), IllegalArgumentException(_))
      rejectUnsupported(views, consumers)
      rejectUnsupportedRemote(remoteViews, remoteConsumers)

      // Opened only by a view's tables below: a service of consumers alone, with no database
      // (feature 037), never reaches it.
      lazy val database = Database()

      // Tables must exist before any projection writes to them.
      val tables = views.map(_.tableName) ++ keyed.map(_.tableName) ++
        remoteViews.map(v => ViewDescriptor.tableFor(v.componentId)) ++
        remoteKeyed.map(v => ViewDescriptor.tableFor(v.componentId))
      // The recorded versions belong with the tables they describe: every view has one, since
      // a view of entities or of a topic is rebuilt by raising it, and one that declares none is
      // at version 1 and stops writing when another instance declares a higher one.
      val viewIds = views.map(_.componentId) ++ keyed.map(_.componentId) ++
        remoteViews.map(_.componentId) ++ remoteKeyed.map(_.componentId)
      val versions =
        if viewIds.isEmpty then Vector.empty
        else ViewVersions.createTable +: viewIds.map(id => ViewVersions.ensure(id))
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
      val askTimeout = FiniteDuration(
        system.settings.config.getDuration("ankka.ask-timeout").toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )
      keyed.foreach(startKeyedView(_, client, askTimeout))
      consumers.foreach(startConsumer(_, client, service.secrets, service.services))

      if remoteViews.nonEmpty || remoteKeyed.nonEmpty || remoteConsumers.nonEmpty then
        // `validate` refused a registry holding remote descriptors without a conversation.
        val conversation = service.conversation.getOrElse(
          throw IllegalStateException("remote views or consumers registered without a conversation")
        )
        remoteViews.foreach(startRemoteView(_, conversation))
        remoteKeyed.foreach(startRemoteKeyedView(_, conversation, askTimeout))
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
        case Some(DeclaredSource.Topic(topic, _, b)) if subscriberFor(b).isEmpty =>
          problems += s"'${component.componentId}' consumes topic '$topic' but no " +
            "MessageSubscriber was configured; pass one to ProjectionRuntime.withBroker, or set " +
            s"${ProjectionRuntime.KafkaEnvVar} for ProjectionRuntime.fromEnv"
        case Some(DeclaredSource.Topic(topic, _, _)) =>
          identity.left.foreach(why =>
            problems += unnamedTopicSource(component.componentId, topic, why)
          )
        case _ => ()
    }

    consumers.foreach { consumer =>
      DeclaredConnections.publicationOf(consumer).foreach { p =>
        if publisherFor(p.broker).isEmpty then
          problems += s"consumer '${consumer.componentId}' publishes to " +
            s"'${p.topic}' but no MessagePublisher was configured; " +
            "pass one to ProjectionRuntime.withPublisher, or set " +
            s"${ProjectionRuntime.KafkaEnvVar} for ProjectionRuntime.fromEnv"
      }
    }

    val found = problems.result()
    if found.nonEmpty then
      StartRefusal.refuse(
        found.mkString("cannot start ankka projections:\n  - ", "\n  - ", ""),
        IllegalArgumentException(_)
      )

  /**
   * The project's declarations against what every component states (feature 037): a topic source or
   * publication whose contract is not the declared one, in name or fingerprint, or states none
   * where the project declares one, and a broker the project does not declare. Nothing is checked
   * for a topic the project does not list, or without declarations at all. One refusal names every
   * problem, where `ankka services get` shows it.
   */
  private def rejectUndeclared(components: Vector[ComponentDescriptor]): Unit =
    declarations match
      case Left(why) =>
        StartRefusal.refuse(
          s"cannot read the project's declarations: $why",
          IllegalArgumentException(_)
        )
      case Right(None) => ()
      case Right(Some(declared)) =>
        val problems = Vector.newBuilder[String]
        def side(
            id: ComponentId,
            kind: String,
            verb: String,
            topic: String,
            stated: Option[Contract],
            broker: Option[String]
        ): Unit =
          broker.foreach { b =>
            if !declared.brokers.contains(b) then
              problems += s"$kind '$id' $verb '$topic' on broker '$b', which project " +
                s"'${declared.project}' does not declare"
          }
          // A topic on a declared broker is that broker's; the project's contracts are on its own.
          if broker.isEmpty then
            declared.topics.get(topic).flatMap(_.contract).foreach { expected =>
              stated match
                case None =>
                  problems += s"$kind '$id' $verb '$topic' with no contract; project " +
                    s"'${declared.project}' declares '${expected.name}' (${expected.fingerprint})"
                case Some(c) if c != expected =>
                  problems += s"$kind '$id' $verb '$topic' as '${c.name}' (${c.fingerprint}); " +
                    s"project '${declared.project}' declares '${expected.name}' (${expected.fingerprint})"
                case _ => ()
            }
        components.foreach { component =>
          val kind = component.kind match
            case ComponentKind.View     => "view"
            case ComponentKind.Consumer => "consumer"
            case other                  => other.toString.toLowerCase
          DeclaredConnections.sourcesOf(component).foreach {
            case DeclaredSource.Topic(topic, contract, broker) =>
              side(component.componentId, kind, "reads", topic, contract, broker)
            case _ => ()
          }
          DeclaredConnections.publicationOf(component).foreach { p =>
            side(component.componentId, kind, "publishes to", p.address, p.contract, p.broker)
          }
        }
        val found = problems.result()
        if found.nonEmpty then
          StartRefusal.refuse(
            found.mkString("cannot start ankka projections:\n  - ", "\n  - ", ""),
            IllegalArgumentException(_)
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
          case (Some(DeclaredSource.Topic(topic, _, b)), _) if subscriberFor(b).isEmpty =>
            problems += s"'$id' consumes topic '$topic' but no MessageSubscriber " +
              "was configured; pass one to ProjectionRuntime.withBroker"
          case (Some(DeclaredSource.Topic(topic, _, _)), _) =>
            identity.left.foreach(why => problems += unnamedTopicSource(id, topic, why))
          // A source `DeclaredConnections` does not read as one: a component with no change stream.
          case (None, RemoteSource.Component(kind, sourceId)) =>
            problems += s"'$id' subscribes to $kind '$sourceId', which has no change stream; " +
              "a view or consumer follows an event sourced entity, a key value entity or a topic"
          case _ => ()
    }

    consumers.foreach { consumer =>
      DeclaredConnections.publicationOf(consumer).foreach { p =>
        if publisherFor(p.broker).isEmpty then
          problems += s"consumer '${consumer.componentId}' publishes to " +
            s"'${p.topic}' but no MessagePublisher was configured; " +
            "pass one to ProjectionRuntime.withPublisher"
      }
    }

    val found = problems.result()
    if found.nonEmpty then
      StartRefusal.refuse(
        found.mkString("cannot start ankka projections:\n  - ", "\n  - ", ""),
        IllegalArgumentException(_)
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
      handler: () => IncomingMessage => Future[Done],
      parallel: Boolean
  )(using system: ActorSystem[?]): Subscribed =
    val group = groupFor(kind, componentId, version)
    // A handler holds one component instance and sets its context per message, so a parallel
    // subscription (feature 037) gets a handler per partition: lanes never share an instance.
    val lanes: IncomingMessage => Future[Done] =
      if parallel then
        val perPartition =
          scala.collection.concurrent.TrieMap.empty[Int, IncomingMessage => Future[Done]]
        message => perPartition.getOrElseUpdate(message.partition, handler())(message)
      else handler()
    // What the service says about the source (feature 037): the change it is failing on, cleared
    // when one succeeds, and how far behind it is, asked of the broker every thirty seconds.
    val sources = TopicSources(system)
    val handle: IncomingMessage => Future[Done] = message =>
      given ExecutionContext = ExecutionContext.parasitic
      lanes(message).transform { outcome =>
        val reason = outcome.failed.toOption.map(f => Option(f.getMessage).getOrElse(f.toString))
        sources.update(componentId)(_.copy(failing = reason))
        outcome
      }
    val subscription = TopicSubscription(topic, group, startFrom, parallel)
    val subscribed   = broker.subscribe(subscription, handle)
    subscriptions.add(subscribed): Unit
    val poll = system.scheduler.scheduleWithFixedDelay(
      ProjectionRuntime.LagInterval,
      ProjectionRuntime.LagInterval
    )(() =>
      broker
        .lag(subscription)
        .foreach(lag => sources.update(componentId)(_.copy(lag = lag)))(using
          system.executionContext
        )
    )(using system.executionContext)
    subscriptions.add(() => poll.cancel(): Unit): Unit
    sources.update(componentId)(_.copy(group = group))
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
      recorded: Option[Int],
      options: TopicOptions = TopicOptions()
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
        behind = false,
        broker = options.broker,
        contract = options.contract.map(_.name)
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
      handler: ViewGuard => IncomingMessage => Future[Done],
      parallel: Boolean
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
            () => handler(guard),
            parallel
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

  /**
   * A view that reads entities, at the version it declares: started if its rows were built at that
   * version; rebuilt first — emptied once, however many instances start — if at a lower one. At a
   * higher one the instance says it is behind and still starts its projections: the daemon process
   * they run in is the same on every instance, and its coordinator lives on the oldest, which may
   * be this one; every write they try is refused by the guard, which pauses them. `start` starts
   * the view's projections under the ids its declared version gives them, which have read nothing
   * after a rebuild, so every source is read again from its first event or state.
   *
   * Off the start thread, as a topic view's is: it waits on the database.
   */
  private def startEntityView(componentId: ComponentId, declared: Int)(start: => Unit)(using
      system: ActorSystem[?]
  ): Unit =
    given ExecutionContext = system.executionContext
    val database           = Database()
    val table              = ViewDescriptor.tableFor(componentId)
    val log                = system.log
    val started =
      ViewVersions.recorded(database, componentId).flatMap { recorded =>
        if recorded == declared then Future.successful(start)
        else if recorded > declared then
          Future.successful { entityBehind(componentId, declared, recorded); start }
        else
          ViewVersions.rebuild(database, table, componentId, declared).map {
            case ViewVersions.Rebuilt.Emptied(from) =>
              log.info(
                "view emptied for rebuild: component={} from version={} to version={}; every " +
                  "source is read again from its beginning",
                componentId,
                from,
                declared
              )
              start
            case ViewVersions.Rebuilt.AlreadyBuilt =>
              log.info("view rebuild already done: component={} version={}", componentId, declared)
              start
            case ViewVersions.Rebuilt.Behind(higher) =>
              entityBehind(componentId, declared, higher)
              start
          }
      }
    started.failed.foreach(failure => log.error(s"view '$componentId' could not start", failure))

  private def entityBehind(componentId: ComponentId, declared: Int, recorded: Int)(using
      system: ActorSystem[?]
  ): Unit =
    system.log.warn(
      "view behind its recorded version: component={} declared={} recorded={}; this instance " +
        "reads nothing from its sources and writes nothing to its table",
      componentId,
      declared,
      recorded
    )

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
    val typed    = descriptor.asInstanceOf[ViewDescriptor[AnyView, Any, Any]]
    val declared = typed.version.getOrElse(1)
    val id       = typed.componentId
    val said     = java.util.concurrent.atomic.AtomicBoolean(false)
    def guard(projection: ProjectionId) =
      EntityViewGuard(id, declared, shared = true, projection, said)

    typed.source match
      case ChangeSource.EventSourced(sourceId, _) =>
        startEntityView(id, declared) {
          daemon(ViewProjections.daemon(id, None), typed.parallelism) { index =>
            val range = eventSliceRanges(typed.parallelism)(index)
            val projection =
              ProjectionId(ViewProjections.name(id, None, declared), s"${range.min}-${range.max}")
            exactlyOnceEventProjection(
              projection,
              sourceId,
              range,
              () => ViewEventHandler(typed, client, guard(projection))
            )
          }
        }

      case ChangeSource.KeyValue(sourceId, _) =>
        startEntityView(id, declared) {
          daemon(ViewProjections.daemon(id, None), typed.parallelism) { index =>
            val range = DurableStateSourceProvider.sliceRanges(typed.parallelism)(index)
            val projection =
              ProjectionId(ViewProjections.name(id, None, declared), s"${range.min}-${range.max}")
            atLeastOnceStateProjection(
              projection,
              sourceId,
              range,
              () => ViewStateHandler(typed, client, guard(projection))
            )
          }
        }

      case ChangeSource.Topic(name, _, startFrom, options) =>
        // `<project>/<name>` for another project's topic (feature 040).
        val topic = TopicAddress.of(name, options.project)
        subscriberFor(options.broker).foreach { broker =>
          // A view that declares nowhere starts at the earliest message the broker holds.
          startTopicView(
            broker,
            typed.componentId,
            topic,
            startFrom.getOrElse(StartFrom.Earliest),
            typed.version.getOrElse(1),
            guard => ViewTopicHandler(typed, Database(), client, guard).process,
            options.parallel
          )
        }

  // ── Keyed views ───────────────────────────────────────────────────────────

  /**
   * A keyed view: one projection per source, each one instance over every slice, since the view
   * handles one change at a time and slicing a source would only queue behind its lock.
   */
  private def startKeyedView(
      descriptor: KeyedViewDescriptor[?, ?],
      client: ComponentClient,
      askTimeout: FiniteDuration
  )(using system: ActorSystem[?]): Unit =
    type AnyKeyed = KeyedView[Any]
    val typed    = descriptor.asInstanceOf[KeyedViewDescriptor[AnyKeyed, Any]]
    val host     = KeyedViewHost(typed, client, askTimeout)
    val declared = typed.version.getOrElse(1)
    val id       = typed.componentId
    val said     = java.util.concurrent.atomic.AtomicBoolean(false)
    def guard(projection: ProjectionId) =
      EntityViewGuard(id, declared, shared = false, projection, said)
    startEntityView(id, declared) {
      typed.sources.foreach { source =>
        val daemonName     = ViewProjections.daemon(id, Some(source.componentId))
        val projectionName = ViewProjections.name(id, Some(source.componentId), declared)
        def handle(subject: String, sequence: Long, payload: Option[(Array[Byte], String)]) =
          host.handle(source, subject, sequence, payload)
        source.source match
          case ChangeSource.EventSourced(sourceId, _) =>
            daemon(daemonName, 1) { _ =>
              val range      = eventSliceRanges(1).head
              val projection = ProjectionId(projectionName, s"${range.min}-${range.max}")
              exactlyOnceEventProjection(
                projection,
                sourceId,
                range,
                () => KeyedViewEventHandler(host.core, guard(projection), handle)
              )
            }
          case ChangeSource.KeyValue(sourceId, _) =>
            daemon(daemonName, 1) { _ =>
              val range      = DurableStateSourceProvider.sliceRanges(1).head
              val projection = ProjectionId(projectionName, s"${range.min}-${range.max}")
              atLeastOnceStateProjection(
                projection,
                sourceId,
                range,
                () => KeyedViewStateHandler(host.core, guard(projection), handle)
              )
            }
          case ChangeSource.Topic(_, _, _, _) => () // refused by KeyedViewRules
      }
    }

  // ── Consumers ─────────────────────────────────────────────────────────────

  private def startConsumer(
      descriptor: ConsumerDescriptor[?, ?, ?],
      client: ComponentClient,
      secrets: SecretStore,
      services: ServiceClients
  )(using system: ActorSystem[?]): Unit =
    type AnyConsumer = Consumer[Any, Any]
    val typed       = descriptor.asInstanceOf[ConsumerDescriptor[AnyConsumer, Any, Any]]
    val processName = s"ankka-consumer-${typed.componentId}"
    // Where this consumer publishes: the declared broker it names, else the installation's.
    val target = publisherFor(typed.produces.flatMap(_.broker))

    typed.source match
      case ChangeSource.EventSourced(sourceId, _) =>
        daemon(processName, typed.parallelism) { index =>
          val range = eventSliceRanges(typed.parallelism)(index)
          atLeastOnceEventProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () =>
              ConsumerEventHandler(
                typed,
                target,
                client,
                Observability(system),
                secrets,
                services
              )
          )
        }

      case ChangeSource.KeyValue(sourceId, _) =>
        daemon(processName, typed.parallelism) { index =>
          val range = DurableStateSourceProvider.sliceRanges(typed.parallelism)(index)
          atLeastOnceStateProjection(
            ProjectionId(processName, s"${range.min}-${range.max}"),
            sourceId,
            range,
            () =>
              ConsumerStateHandler(
                typed,
                target,
                client,
                Observability(system),
                secrets,
                services
              )
          )
        }

      case ChangeSource.Topic(name, _, startFrom, options) =>
        // `<project>/<name>` for another project's topic (feature 040).
        val topic = TopicAddress.of(name, options.project)
        subscriberFor(options.broker).foreach { broker =>
          def handler =
            ConsumerTopicHandler(typed, target, client, Observability(system), secrets, services)
          // `validate` refused a consumer over a topic that declares nowhere.
          val start   = startFrom.getOrElse(StartFrom.Earliest)
          val version = typed.version.getOrElse(1)
          declare(ComponentKind.Consumer, typed.componentId, topic, start, version, None, options)
          subscribeTopic(
            broker,
            ComponentKind.Consumer,
            typed.componentId,
            topic,
            start,
            version,
            () => handler.process,
            options.parallel
          ): Unit
        }

  // ── Remote views and consumers ────────────────────────────────────────────

  private def startRemoteView(
      descriptor: RemoteViewDescriptor,
      conversation: Conversation
  )(using system: ActorSystem[?]): Unit =
    given ExecutionContext = system.executionContext
    val parallelism        = RemoteProjection.Parallelism
    def view()             = RemoteView(descriptor, conversation, Observability(system))
    val declared           = descriptor.version.getOrElse(1)
    val id                 = descriptor.componentId
    val daemonName         = ViewProjections.daemon(id, None)
    val projectionName     = ViewProjections.name(id, None, declared)
    val said               = java.util.concurrent.atomic.AtomicBoolean(false)
    def guard(projection: ProjectionId) =
      EntityViewGuard(id, declared, shared = true, projection, said)

    descriptor.source match
      case RemoteSource.Component(ComponentKind.EventSourcedEntity, sourceId) =>
        startEntityView(id, declared) {
          daemon(daemonName, parallelism) { index =>
            val range      = eventSliceRanges(parallelism)(index)
            val projection = ProjectionId(projectionName, s"${range.min}-${range.max}")
            exactlyOnceEventProjection(
              projection,
              sourceId,
              range,
              () => RemoteViewEventHandler(view(), guard(projection))
            )
          }
        }

      case RemoteSource.Component(ComponentKind.KeyValueEntity, sourceId) =>
        startEntityView(id, declared) {
          daemon(daemonName, parallelism) { index =>
            val range      = DurableStateSourceProvider.sliceRanges(parallelism)(index)
            val projection = ProjectionId(projectionName, s"${range.min}-${range.max}")
            atLeastOnceStateProjection(
              projection,
              sourceId,
              range,
              () => RemoteViewStateHandler(view(), Database(), guard(projection))
            )
          }
        }

      case RemoteSource.Topic(name, startFrom, options) =>
        // `<project>/<name>` for another project's topic (feature 040).
        val topic = TopicAddress.of(name, options.project)
        subscriberFor(options.broker).foreach { broker =>
          startTopicView(
            broker,
            descriptor.componentId,
            topic,
            startFrom.getOrElse(StartFrom.Earliest),
            descriptor.version.getOrElse(1),
            guard => RemoteViewTopicHandler(view(), Database(), guard).process,
            options.parallel
          )
        }

      case RemoteSource.Component(_, _) => () // refused by rejectUnsupportedRemote

  /** A keyed view in another process: the Scala keyed view's hosts, with the process answering. */
  private def startRemoteKeyedView(
      descriptor: RemoteKeyedViewDescriptor,
      conversation: Conversation,
      askTimeout: FiniteDuration
  )(using system: ActorSystem[?]): Unit =
    given ExecutionContext = system.executionContext
    val core               = KeyedViewCore(descriptor.componentId, askTimeout)
    val view               = RemoteKeyedView(descriptor, conversation, Observability(system))
    val declared           = descriptor.version.getOrElse(1)
    val id                 = descriptor.componentId
    val said               = java.util.concurrent.atomic.AtomicBoolean(false)
    def guard(projection: ProjectionId) =
      EntityViewGuard(id, declared, shared = false, projection, said)
    startEntityView(id, declared) {
      descriptor.sources.foreach {
        case RemoteSource.Component(kind, sourceId) =>
          val daemonName     = ViewProjections.daemon(id, Some(sourceId))
          val projectionName = ViewProjections.name(id, Some(sourceId), declared)
          val handle         = view.handle(sourceId)
          if kind == ComponentKind.EventSourcedEntity then
            daemon(daemonName, 1) { _ =>
              val range      = eventSliceRanges(1).head
              val projection = ProjectionId(projectionName, s"${range.min}-${range.max}")
              exactlyOnceEventProjection(
                projection,
                sourceId,
                range,
                () => KeyedViewEventHandler(core, guard(projection), handle)
              )
            }
          else if kind == ComponentKind.KeyValueEntity then
            daemon(daemonName, 1) { _ =>
              val range      = DurableStateSourceProvider.sliceRanges(1).head
              val projection = ProjectionId(projectionName, s"${range.min}-${range.max}")
              atLeastOnceStateProjection(
                projection,
                sourceId,
                range,
                () => KeyedViewStateHandler(core, guard(projection), handle)
              )
            }
        case RemoteSource.Topic(_, _, _) => () // refused by KeyedViewRules
      }
    }

  private def startRemoteConsumer(
      descriptor: RemoteConsumerDescriptor,
      conversation: Conversation
  )(using system: ActorSystem[?]): Unit =
    given ExecutionContext = system.executionContext
    val processName        = s"ankka-consumer-${descriptor.componentId}"
    val parallelism        = RemoteProjection.Parallelism
    def consumer() = RemoteConsumer(
      descriptor,
      conversation,
      publisherFor(descriptor.publication.flatMap(_.broker)),
      Observability(system)
    )

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

      case RemoteSource.Topic(name, startFrom, options) =>
        // `<project>/<name>` for another project's topic (feature 040).
        val topic = TopicAddress.of(name, options.project)
        subscriberFor(options.broker).foreach { broker =>
          def handler = RemoteConsumerTopicHandler(consumer())
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
          declare(
            ComponentKind.Consumer,
            descriptor.componentId,
            topic,
            start,
            version,
            None,
            options
          )
          subscribeTopic(
            broker,
            ComponentKind.Consumer,
            descriptor.componentId,
            topic,
            start,
            version,
            () => handler.process,
            options.parallel
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
    brokers.values.foreach { (p, s) =>
      s.stop()
      p match
        case owned: AutoCloseable => owned.close()
        case _                    => ()
    }
    brokers = Map.empty
    // A producer left open keeps its network thread, retrying a broker that may be gone, for the
    // life of the JVM: one more on every restart of a service in a test.
    if ownsPublisher then
      publisher.foreach {
        case owned: AutoCloseable => owned.close()
        case _                    => ()
      }
    publisher = None

object ProjectionRuntime:

  /**
   * Feature 040: a topic is another project's or on a broker the project declared, never both. A
   * declared broker is outside the installation and has no projects; the installation's broker
   * enforces a grant, and only that broker has another project's topics.
   */
  def crossProjectProblems(components: Vector[ComponentDescriptor]): Vector[String] =
    def refused(id: ComponentId, verb: String, address: String, broker: String) =
      val (project, name) = TopicAddress.split(address)
      s"'$id' $verb topic '$name' of project '${project.getOrElse("")}' on broker '$broker': " +
        "another project's topic is on the installation's broker; name the project or the " +
        "broker, not both"
    components.flatMap { component =>
      val sources = DeclaredConnections.sourcesOf(component).collect {
        case DeclaredSource.Topic(address, _, Some(broker))
            if TopicAddress.isCrossProject(address) =>
          refused(component.componentId, "reads", address, broker)
      }
      val publication = DeclaredConnections.publicationOf(component).collect {
        case p if p.project.isDefined && p.broker.isDefined =>
          refused(component.componentId, "publishes to", p.address, p.broker.get)
      }
      sources ++ publication
    }

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
    withKafka(KafkaConnection(bootstrapServers))

  /** To the broker `connection` describes: its address, and its TLS and prefix when it has them. */
  def withKafka(connection: KafkaConnection): ProjectionRuntime =
    new ProjectionRuntime(
      Some(system => KafkaPublisher(connection)(using system)),
      Some(system => KafkaSubscriber(connection, 1.second, 30.seconds)(using system)),
      ownsPublisher = true
    )

  /**
   * The variable [[fromEnv]] reads, and a process-hosted service's sidecar reads, for the broker.
   */
  val KafkaEnvVar: String = KafkaConnection.BootstrapVariable

  /** The first protocol in which a process can declare where a topic source starts. */
  val StartPositionProtocol: String = "1.7"

  /** How often a topic source asks its broker how far behind it is (feature 037). */
  val LagInterval: FiniteDuration = 30.seconds

  /**
   * Kafka when the environment names a broker, entity sources only when it does not.
   *
   * A deployed service is told of a broker by its environment, not by code: the operator writes the
   * installation's broker's address, certificate directory and topic prefix, and a descriptor that
   * names a broker of its own gives `ANKKA_KAFKA_BOOTSTRAP_SERVERS` instead (`KafkaConnection`).
   * Without a broker, a producing consumer or a topic-sourced view is still refused at startup.
   */
  def fromEnv(env: Map[String, String] = sys.env): ProjectionRuntime =
    val installation = KafkaConnection.fromEnv(env).fold(apply())(withKafka)
    val declared = ProjectDeclarations.fromEnv(env) match
      case Right(None)    => installation
      case Right(Some(d)) => installation.withDeclarations(d)
      case Left(why)      => installation.withUnreadableDeclarations(why)
    KafkaConnection.declaredFromEnv(env) match
      case Right(connections) =>
        connections.foldLeft(declared)((r, named) => r.withDeclaredKafka(named._1, named._2))
      case Left(why) => declared.withUnreadableDeclarations(why)

// ── Handlers ────────────────────────────────────────────────────────────────

/** Applies a view's effect and the projection offset in one transaction. */
private final class ViewEventHandler(
    descriptor: ViewDescriptor[View[Any, Any], Any, Any],
    client: ComponentClient,
    guard: EntityViewGuard
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

    guard
      .inSession(session)
      .flatMap(_ => ProjectionSupport.loadRow(session, table, subject, descriptor.rowSerializer))
      .flatMap { row =>
        val effect =
          ProjectionSupport
            .runView(view, descriptor, subject, envelope.sequenceNr, row, record, observability)
        ProjectionSupport.applyView(session, table, subject, effect, descriptor.rowSerializer)
      }

/** As `ViewEventHandler`, but for key value state changes. */
private final class ViewStateHandler(
    descriptor: ViewDescriptor[View[Any, Any], Any, Any],
    client: ComponentClient,
    guard: EntityViewGuard
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

        def write(fragment: SqlFragment): Future[Done] =
          database.inTransaction(tx =>
            guard.inTransaction(tx).flatMap(_ => tx.execute(fragment)).map(_ => Done)
          )
        effect match
          case ViewEffect.UpdateRow(row) =>
            val json = String(descriptor.rowSerializer.toBytes(row), "UTF-8")
            write(ViewStore.upsert(table, subject, json))
          case ViewEffect.DeleteRow => write(ViewStore.delete(table, subject))
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
    secrets: SecretStore,
    services: ServiceClients
) extends Handler[EventEnvelope[JournalRecord]]:

  private val id = descriptor.componentId.toString

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client, secrets, services))

  def process(envelope: EventEnvelope[JournalRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(envelope.persistenceId)
    val record  = envelope.event

    consumer._setContext(
      Some(SimpleChangeContext(subject, envelope.sequenceNr, localOrigin = true))
    )
    val (effect, context) =
      try
        ProjectionSupport.traced(observability, id, ConsumerDescriptor.OnMessage.name, None) {
          record.kind match
            case JournalRecord.KindDomain =>
              consumer.onMessage(descriptor.source.decoder.fromBytes(record.payload))
            case JournalRecord.KindDeleted => consumer.onDelete
            case _                         => ConsumerEffect.Ignore
        }
      finally consumer._setContext(None)

    ProjectionSupport.applyConsumer(effect, subject, descriptor, publisher, Some(context))

/** As `ConsumerEventHandler`, but for key value state changes. */
private final class ConsumerStateHandler(
    descriptor: ConsumerDescriptor[Consumer[Any, Any], Any, Any],
    publisher: Option[MessagePublisher],
    client: ComponentClient,
    observability: Observability,
    secrets: SecretStore,
    services: ServiceClients
) extends Handler[DurableStateChange[StateRecord]]:

  private val id = descriptor.componentId.toString

  private val consumer =
    descriptor.create(SimpleConsumerContext(descriptor.componentId, client, secrets, services))

  def process(change: DurableStateChange[StateRecord]): Future[Done] =
    val subject = PersistenceId.extractEntityId(change.persistenceId)

    val revision = change match
      case updated: UpdatedDurableState[StateRecord] => updated.revision
      case deleted: DeletedDurableState[StateRecord] => deleted.revision

    consumer._setContext(Some(SimpleChangeContext(subject, revision, localOrigin = true)))
    val (effect, context) =
      try
        ProjectionSupport.traced(observability, id, ConsumerDescriptor.OnMessage.name, None) {
          change match
            // A deletion is a state marked deleted (`KeyValueEntityHost.Stored`).
            case updated: UpdatedDurableState[StateRecord] if updated.value.deleted =>
              consumer.onDelete
            case updated: UpdatedDurableState[StateRecord] =>
              consumer.onMessage(descriptor.source.decoder.fromBytes(updated.value.payload))
            case _: DeletedDurableState[StateRecord] => consumer.onDelete
        }
      finally consumer._setContext(None)

    ProjectionSupport.applyConsumer(effect, subject, descriptor, publisher, Some(context))
