package com.thinkmorestupidless.ankka.testkit

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import com.thinkmorestupidless.ankka.agent.autonomous.{TaskType, TypedTaskSnapshot, forTask}
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.{
  ServiceBuilder,
  Ankka,
  AnkkaService,
  RuntimeExtension,
  SecretKey,
  ServiceIdentity
}
import com.thinkmorestupidless.ankka.http.{Caller, LocalCallers}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore}

import java.nio.file.{Files, Path}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Runs a whole ankka service against a throwaway database, for tests that need the real thing:
 * sharding, persistence, replay, snapshots and the ComponentClient. The database is the kit's own,
 * in a Postgres every kit in the JVM shares (`SharedPostgres`), and is dropped when the kit stops.
 *
 * The schema comes from the same DDL that docker-compose applies, shipped on the runtime's
 * classpath — so a test can never pass against a schema that local development does not have.
 */
final class AnkkaTestKit private (
    private var descriptors: Seq[ComponentDescriptor],
    private var extensions: Seq[RuntimeExtension],
    configure: ServiceBuilder => ServiceBuilder,
    config: Config,
    database: TestDatabase,
    readyTimeout: FiniteDuration,
    private var current: AnkkaService,
    /** The secret key the running service was started with, or `None` for none. */
    private var currentKey: Option[String],
    /** The keyring the service reads its subject keys from, if any. */
    val keyring: Option[InMemoryKeyring]
):

  /**
   * Erases `subject` in this service's project, as an applied erasure request does, and waits until
   * this service has applied it: the key destroyed, every view redacted, the erasure handler run.
   * Answers the erasure's id.
   */
  def erase(subject: String, within: FiniteDuration = 30.seconds): String =
    val ring = keyring.getOrElse(throw IllegalStateException("this kit runs no keyring"))
    val id   = ring.erase(current.project, subject)
    awaitApplied(id, within)
    id

  /** Waits until this service has applied `erasureId` to its own tables. */
  def awaitApplied(erasureId: String, within: FiniteDuration = 30.seconds): Unit =
    val ring = keyring.getOrElse(throw IllegalStateException("this kit runs no keyring"))
    eventually(s"$erasureId applied by this service", within)(
      ring
        .completionsOf(erasureId)
        .find(_._2.erasureId == erasureId)
        .filter(_ => appliedHere(erasureId))
    ): Unit

  private def appliedHere(erasureId: String): Boolean =
    import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
    given org.apache.pekko.actor.typed.ActorSystem[?] = current.system
    scala.concurrent.Await
      .result(
        Database().queryOne(
          SqlFragment.raw(
            "SELECT erasure_id FROM ankka_erasures_applied WHERE erasure_id = "
          ) ++ sql"$erasureId"
        )(_.get(0, classOf[String])),
        10.seconds
      )
      .isDefined

  /** Runs `body` with the keyring answering nothing, as an outage of it does. */
  def keyringOutage[A](body: => A): A =
    val ring = keyring.getOrElse(throw IllegalStateException("this kit runs no keyring"))
    ring.outage = true
    try body
    finally ring.outage = false

  /**
   * Fails when any table of this service's database holds one of `values` — as written, as base64
   * or as hex — in any column. How a test shows a personal value was never stored readable.
   */
  def assertNoPersonalValue(values: String*): Unit =
    val found = PersonalValues.find(current, values)
    if found.nonEmpty then
      throw AssertionError(
        found
          .map((table, value) => s"table $table holds '$value'")
          .mkString("personal values stored readable: ", "; ", "")
      )

  def service: AnkkaService            = current
  def componentClient: ComponentClient = current.componentClient

  /** The running service's secret store: one table in this kit's database. */
  def secrets: SecretStore = current.secrets

  /** The secret key the running service has, as `ANKKA_SECRET_KEY` would give it. */
  def secretKey: Option[String] = currentKey

  /**
   * The header that makes a request to this service's HTTP endpoints arrive as `caller`, for
   * testing an `Acl.allowCallers` without a cluster. Add it to a request made with any client:
   *
   * {{{
   * val (name, value) = testKit.asCaller(Caller.Service("checkout", "orders"))
   * }}}
   *
   * It works because the service and the test kit share a JVM and so a secret; a request without it
   * arrives as `Caller.Local`, which every caller-naming ACL admits.
   */
  def asCaller(caller: Caller): (String, String) = LocalCallers.header(caller)

  /** JDBC URL of the backing database, for tests that want to inspect it directly. */
  def jdbcUrl: String = database.jdbcUrl

  /**
   * Has the database log every statement it is sent from now on, and restarts the service so that
   * every connection it holds is one that logs. How a suite shows that something never reached the
   * database, by a counter at the database rather than an argument about the code; read it with
   * `databaseLog`. The setting is this kit's database's alone.
   */
  private[testkit] def logStatements(): Unit =
    SharedPostgres.logStatements(database)
    restartService()

  /** What the shared database server has printed: every statement a logging kit sent it. */
  private[testkit] def databaseLog: String = SharedPostgres.logs

  /** The configuration the service runs with, for a suite that starts a second one beside it. */
  private[testkit] def serviceConfig: Config = AnkkaTestKit.withSecretKey(config, currentKey)

  /**
   * Opens a socket to `path` on the service's HTTP server, which must be one of its extensions. A
   * `Left` is the opening request's answer when it was not an upgrade.
   */
  def socket(
      path: String,
      headers: Map[String, String] = Map.empty,
      subprotocols: Seq[String] = Nil
  ): Either[TestSocket.Refused, TestSocket] =
    val base = current.boundAddresses
      .find(_.startsWith("http"))
      .getOrElse(throw IllegalStateException("the service has no HTTP server bound"))
    TestSocket.open("ws" + base.stripPrefix("http") + path, headers, subprotocols)

  /**
   * Polls `check` every 100ms until it answers, failing with `description` after `within`.
   *
   * The wait belongs around the value that changes; assert on anything that does not change after
   * it, outside the retry, so a stale read cannot satisfy it.
   */
  def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A = AnkkaTestKit.eventually(description, within)(check)

  /**
   * Waits until an autonomous agent's task has ended, answering its record with the result decoded
   * as `task`'s. Fails naming where the task had got to when it has not ended within `within`.
   */
  def awaitTask[R](
      taskId: String,
      task: TaskType[R],
      within: FiniteDuration = 30.seconds
  ): TypedTaskSnapshot[R] =
    componentClient.forTask(taskId).await(task, within)

  /**
   * Starts a second node of this service on the same database, joined to this node's cluster — two
   * instances of one service on one machine, for a test that stops one and watches the other carry
   * on.
   *
   * `extensions` must be fresh instances: an extension that has started holds what it started (an
   * HTTP server holds its listener), so the peer cannot share this node's. A scripted model can be
   * shared, since one instance of any component is running on one node at a time.
   */

  /** Waits for a run of a blueprint to end; `Timeout` when it has not by then. */
  def awaitRun(
      runs: com.thinkmorestupidless.ankka.agent.blueprint.RunCalls,
      runId: String,
      within: FiniteDuration = 30.seconds
  ): com.thinkmorestupidless.ankka.agent.blueprint.RunSnapshot =
    runs.await(runId, within)
  def startPeer(extensions: Seq[RuntimeExtension]): AnkkaTestKit.Peer =
    val seed = org.apache.pekko.cluster.Cluster(current.system).selfMember.address.toString
    // Stating the formation makes ClusterConfig pass this config through rather than layering the
    // local overlay over it, which would reset the seed nodes to none.
    val peerConfig = ConfigFactory
      .parseMap(
        Map(
          "ankka.cluster.formation"                -> "join-self-or-seeds",
          "ankka.cluster.seed-nodes"               -> seed,
          "ankka.join-self-if-no-seed-nodes"       -> "off",
          "pekko.remote.artery.canonical.hostname" -> "127.0.0.1",
          "pekko.remote.artery.canonical.port"     -> "0"
        ).asJava
      )
      .withFallback(AnkkaTestKit.withSecretKey(config, currentKey))
      .resolve()
    AnkkaTestKit.Peer(
      AnkkaTestKit.hostService(descriptors, extensions, configure, peerConfig, readyTimeout)
    )

  /**
   * Terminates the service and starts a fresh one against the same database.
   *
   * This is how a test proves durability rather than caching: every entity is gone from memory
   * afterwards, so the next read has no choice but to rebuild from the journal. Deterministic,
   * unlike waiting for passivation to fire.
   *
   * The service keeps its secret key unless `secretKey` says otherwise: another key is what a
   * careless rotation looks like, and `None` is a service started with none.
   *
   * `downFor` keeps the service stopped that long before it starts again, so a test can see what an
   * outage does — to a timer whose due times pass while nothing is running, say. The database stays
   * up throughout, as it would.
   */
  def restartService(
      secretKey: Option[String] = currentKey,
      downFor: FiniteDuration = scala.concurrent.duration.Duration.Zero,
      /** The extensions the fresh service starts with: a registry carrying another version, say. */
      extensions: Seq[RuntimeExtension] = this.extensions,
      /**
       * Run between the stop and the start: what happens while the service is down, as moving a
       * clock.
       */
      whileStopped: => Unit = (),
      /** The components the fresh service hosts: a view declared at a higher version, say. */
      descriptors: Seq[ComponentDescriptor] = this.descriptors
  ): Unit =
    stopService()
    if downFor > scala.concurrent.duration.Duration.Zero then Thread.sleep(downFor.toMillis)
    whileStopped
    startService(extensions, secretKey, descriptors)

  /**
   * Terminates the service and leaves it stopped, as an outage does; `startService` ends it. The
   * kit's clients are not usable in between.
   */
  def stopService(): Unit =
    current.terminate()
    scala.concurrent.Await.ready(current.whenTerminated, readyTimeout): Unit

  /** Starts a fresh service against the same database, after `stopService`. */
  def startService(
      extensions: Seq[RuntimeExtension] = this.extensions,
      secretKey: Option[String] = currentKey,
      descriptors: Seq[ComponentDescriptor] = this.descriptors
  ): Unit =
    this.descriptors = descriptors
    this.extensions = extensions
    currentKey = secretKey
    current = AnkkaTestKit.hostService(
      descriptors,
      extensions,
      configure,
      AnkkaTestKit.withSecretKey(config, secretKey),
      readyTimeout
    )

  def stop(): Unit =
    current.terminate()
    // Dropped once the service has let go of it, not before, or its last writes fail loudly.
    current.whenTerminated.andThen { case _ => SharedPostgres.release(database) }(using
      scala.concurrent.ExecutionContext.parasitic
    ): Unit
    AnkkaTestKit.releaseRegistryDirectory()

object AnkkaTestKit:

  /** A second node of a test service. Stop it before the kit, or the kit's stop waits for it. */
  final class Peer private[AnkkaTestKit] (val service: AnkkaService):
    def componentClient: ComponentClient = service.componentClient

    def stop(): Unit =
      service.terminate()
      scala.concurrent.Await.ready(service.whenTerminated, 30.seconds): Unit

  private[testkit] def eventually[A](description: String, within: FiniteDuration)(
      check: => Option[A]
  ): A =
    val deadline = within.fromNow
    var last     = check
    while last.isEmpty && !deadline.isOverdue() do
      Thread.sleep(100)
      last = check
    last.getOrElse(throw AssertionError(s"not observed within $within: $description"))

  /**
   * Takes a fresh database holding the schema, and hosts `descriptors` on it.
   *
   * Returns only once the node is a cluster member, so the first call in a test cannot race
   * startup.
   */
  /**
   * `configure` is applied to the builder before it starts, on every restart too. It exists for
   * what the builder takes beyond descriptors and extensions — a sidecar suite's conversation to a
   * process in another language (feature 009) — and defaults to nothing.
   */
  def start(
      descriptors: Seq[ComponentDescriptor],
      extensions: Seq[RuntimeExtension] = Nil,
      readyTimeout: FiniteDuration = 60.seconds,
      configure: ServiceBuilder => ServiceBuilder = identity,
      /**
       * The service's secret key, as `ANKKA_SECRET_KEY` would give it. A fresh one per kit by
       * default, so the secret store works with no setup; `None` starts a service with none.
       */
      secretKey: Option[String] = Some(generateSecretKey()),
      serviceIdentity: ServiceIdentity = ServiceIdentity.unnamed,
      /**
       * Where other services are, by name, as `ankka.local-services.<name>` gives them: what a call
       * to another service from this one reaches. Kept across `restartService`. Set on this service
       * alone, unlike a system property, which every service in the JVM would read.
       */
      localServices: Map[String, String] = Map.empty,
      /**
       * Configuration the service is started with above everything else, on every restart too:
       * `ankka.telemetry.endpoint` for a test of telemetry export, say. Without it a test's service
       * names no collector, whatever the developer's own `ANKKA_OTLP_ENDPOINT` says.
       */
      settings: Config = ConfigFactory.empty(),
      /**
       * The keyring the service's personal fields' keys come from (feature 042): the JVM's
       * in-memory one by default, so a personal field works with no setup and every kit of one
       * project reads the others'; `None` for a service with no keyring, which refuses every
       * personal field.
       */
      keyring: Option[InMemoryKeyring] = Some(InMemoryKeyring.shared)
  ): AnkkaTestKit =
    // On every start and restart, as the rest of `configure` is: the identity is part of what the
    // service is, not something a restart forgets. Before `configure`, so a suite can state an
    // identity that could not be read, which only ankka's own tests have reason to. A fresh channel
    // to the keyring on each, as a restarted instance opens one.
    val configured: ServiceBuilder => ServiceBuilder =
      builder =>
        val identified = builder.withIdentity(Right(serviceIdentity))
        configure(keyring.fold(identified)(k => identified.withKeyring(k.connect())))
    val database = SharedPostgres.acquire()

    // Keep the service registry out of the developer's home directory. A service announces itself
    // into `~/.ankka/running` for `ankka local console` to find; a *test* doing that leaves an
    // entry per suite on the machine of whoever ran it — 38 of them had accumulated before anyone
    // noticed, because the console sweeps stale entries on read and so nothing ever complained.
    // Only set when a suite has not chosen its own directory: the console suites point this at a
    // temp directory they then read, and must keep the one they picked.
    claimRegistryDirectory()

    val config = settings
      .withFallback(ConfigFactory.parseString("ankka.telemetry.endpoint = \"\""))
      .withFallback(withLocalServices(configFor(database), localServices))
      .resolve()

    val service =
      try
        hostService(
          descriptors,
          extensions,
          configured,
          withSecretKey(config, secretKey),
          readyTimeout
        )
      catch
        case failure: Throwable =>
          SharedPostgres.release(database)
          throw failure

    new AnkkaTestKit(
      descriptors,
      extensions,
      configured,
      config,
      database,
      readyTimeout,
      service,
      secretKey,
      keyring
    )

  /** A fresh secret key, written as `ANKKA_SECRET_KEY` takes it. */
  def generateSecretKey(): String = SecretKey.generate().encoded

  /**
   * The config with the service's secret key set, or set empty: always set, so a developer's own
   * `ANKKA_SECRET_KEY` never reaches a test's service.
   */
  private[testkit] def withSecretKey(config: Config, key: Option[String]): Config =
    config.withValue("ankka.secrets.key", ConfigValueFactory.fromAnyRef(key.getOrElse("")))

  private[testkit] def withLocalServices(config: Config, services: Map[String, String]): Config =
    services.foldLeft(config) { case (c, (name, address)) =>
      c.withValue(s"""ankka.local-services."$name"""", ConfigValueFactory.fromAnyRef(address))
    }

  def start(first: ComponentDescriptor, rest: ComponentDescriptor*): AnkkaTestKit =
    start(first +: rest)

  private val RegistryProperty = "ankka.running.dir"

  /** Set only if the suite has not chosen one, and remembered so `stop` can put it back. */
  @volatile private var claimedRegistry: Option[Path] = None

  private def claimRegistryDirectory(): Unit =
    if sys.props.get(RegistryProperty).isEmpty then
      val directory = Files.createTempDirectory("ankka-testkit-running")
      sys.props.put(RegistryProperty, directory.toString): Unit
      claimedRegistry = Some(directory)

  private def releaseRegistryDirectory(): Unit =
    claimedRegistry.foreach { directory =>
      sys.props.remove(RegistryProperty): Unit
      try
        Files
          .list(directory)
          .iterator()
          .asScala
          .foreach(entry => Files.deleteIfExists(entry): Unit)
        Files.deleteIfExists(directory): Unit
      catch case _: Throwable => ()
    }
    claimedRegistry = None

  private def configFor(database: TestDatabase): Config =
    ConfigFactory
      .parseMap(
        Map(
          "pekko.persistence.r2dbc.connection-factory.host"     -> database.host,
          "pekko.persistence.r2dbc.connection-factory.port"     -> database.port,
          "pekko.persistence.r2dbc.connection-factory.database" -> database.name,
          "pekko.persistence.r2dbc.connection-factory.user"     -> "ankka",
          "pekko.persistence.r2dbc.connection-factory.password" -> "ankka",
          // A short idle timeout keeps the passivation path exercised by ordinary tests.
          "pekko.cluster.sharding.passivation.default-idle-strategy.idle-entity.timeout" -> "10s"
          // Deliberately NOT tuning `pekko.persistence.r2dbc.behind-current-time` down:
          // it guards against reading events whose commit timestamp is still in flight,
          // and setting it to zero makes the projection miss and backtrack repeatedly —
          // measured at 15x slower, not faster.
        ).asJava
      )
      .withFallback(ConfigFactory.load())
      .resolve()

  private[testkit] def hostService(
      descriptors: Seq[ComponentDescriptor],
      extensions: Seq[RuntimeExtension],
      configure: ServiceBuilder => ServiceBuilder,
      config: Config,
      readyTimeout: FiniteDuration
  ): AnkkaService =
    val builder =
      configure(extensions.foldLeft(Ankka.service.registerAll(descriptors))(_.withExtension(_)))
    val service = builder.start("ankka-test", config)
    try
      service.awaitReady(readyTimeout)
      service
    catch
      case failure: Throwable =>
        service.terminate()
        throw failure
