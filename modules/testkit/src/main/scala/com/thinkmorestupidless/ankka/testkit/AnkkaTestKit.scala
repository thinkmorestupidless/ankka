package com.thinkmorestupidless.ankka.testkit

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import com.thinkmorestupidless.ankka.agent.autonomous.{TaskType, TypedTaskSnapshot, forTask}
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.{
  ServiceBuilder,
  Ankka,
  AnkkaService,
  RuntimeExtension,
  SecretKey
}
import com.thinkmorestupidless.ankka.http.{Caller, LocalCallers}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore}
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.{DockerImageName, MountableFile}

import java.nio.file.{Files, Path}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Concrete subclass purely to pin testcontainers' `SELF` type parameter.
 * `PostgreSQLContainer[SELF <: PostgreSQLContainer[SELF]]` is a Java self-type idiom that Scala
 * infers as `Nothing`, which makes the fluent setters unusable.
 */
private final class AnkkaPostgres(image: DockerImageName)
    extends PostgreSQLContainer[AnkkaPostgres](image)

/**
 * Runs a whole ankka service against a throwaway Postgres, for tests that need the real thing:
 * sharding, persistence, replay, snapshots and the ComponentClient.
 *
 * The schema comes from the same DDL that docker-compose applies, shipped on the runtime's
 * classpath — so a test can never pass against a schema that local development does not have.
 */
final class AnkkaTestKit private (
    descriptors: Seq[ComponentDescriptor],
    extensions: Seq[RuntimeExtension],
    configure: ServiceBuilder => ServiceBuilder,
    config: Config,
    container: AnkkaPostgres,
    readyTimeout: FiniteDuration,
    private var current: AnkkaService,
    /** The secret key the running service was started with, or `None` for none. */
    private var currentKey: Option[String]
):

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
  def jdbcUrl: String = container.getJdbcUrl

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
   */
  def restartService(secretKey: Option[String] = currentKey): Unit =
    current.terminate()
    scala.concurrent.Await.ready(current.whenTerminated, readyTimeout): Unit
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
    container.stop()
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

  private val PostgresImage = "postgres:17-alpine"

  private val DdlResources = Seq(
    "/ankka/ddl/10-journal-postgres.sql"    -> "/docker-entrypoint-initdb.d/10-journal.sql",
    "/ankka/ddl/20-projection-postgres.sql" -> "/docker-entrypoint-initdb.d/20-projection.sql",
    "/ankka/ddl/30-timers-postgres.sql"     -> "/docker-entrypoint-initdb.d/30-timers.sql",
    "/ankka/ddl/40-secrets-postgres.sql"    -> "/docker-entrypoint-initdb.d/40-secrets.sql"
  )

  /**
   * Starts Postgres, applies the schema, and hosts `descriptors`.
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
      secretKey: Option[String] = Some(generateSecretKey())
  ): AnkkaTestKit =
    val container = AnkkaPostgres(DockerImageName.parse(PostgresImage))
      .withDatabaseName("ankka")
      .withUsername("ankka")
      .withPassword("ankka")

    DdlResources.foreach { (resource, target) =>
      val _ = container.withCopyFileToContainer(
        MountableFile.forClasspathResource(resource),
        target
      )
    }

    container.start()

    // Keep the service registry out of the developer's home directory. A service announces itself
    // into `~/.ankka/running` for `ankka local console` to find; a *test* doing that leaves an
    // entry per suite on the machine of whoever ran it — 38 of them had accumulated before anyone
    // noticed, because the console sweeps stale entries on read and so nothing ever complained.
    // Only set when a suite has not chosen its own directory: the console suites point this at a
    // temp directory they then read, and must keep the one they picked.
    claimRegistryDirectory()

    val config = configFor(container)

    val service =
      try
        hostService(
          descriptors,
          extensions,
          configure,
          withSecretKey(config, secretKey),
          readyTimeout
        )
      catch
        case failure: Throwable =>
          container.stop()
          throw failure

    new AnkkaTestKit(
      descriptors,
      extensions,
      configure,
      config,
      container,
      readyTimeout,
      service,
      secretKey
    )

  /** A fresh secret key, written as `ANKKA_SECRET_KEY` takes it. */
  def generateSecretKey(): String = SecretKey.generate().encoded

  /**
   * The config with the service's secret key set, or set empty: always set, so a developer's own
   * `ANKKA_SECRET_KEY` never reaches a test's service.
   */
  private[testkit] def withSecretKey(config: Config, key: Option[String]): Config =
    config.withValue("ankka.secrets.key", ConfigValueFactory.fromAnyRef(key.getOrElse("")))

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

  private def configFor(container: AnkkaPostgres): Config =
    ConfigFactory
      .parseMap(
        Map(
          "pekko.persistence.r2dbc.connection-factory.host" -> container.getHost,
          "pekko.persistence.r2dbc.connection-factory.port" ->
            container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
          "pekko.persistence.r2dbc.connection-factory.database" -> "ankka",
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
