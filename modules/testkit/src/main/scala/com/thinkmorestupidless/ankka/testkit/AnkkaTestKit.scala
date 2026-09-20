package com.thinkmorestupidless.ankka.testkit

import com.typesafe.config.{Config, ConfigFactory}
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, RuntimeExtension}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
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
    config: Config,
    container: AnkkaPostgres,
    readyTimeout: FiniteDuration,
    private var current: AnkkaService
):

  def service: AnkkaService            = current
  def componentClient: ComponentClient = current.componentClient

  /** JDBC URL of the backing database, for tests that want to inspect it directly. */
  def jdbcUrl: String = container.getJdbcUrl

  /**
   * Terminates the service and starts a fresh one against the same database.
   *
   * This is how a test proves durability rather than caching: every entity is gone from memory
   * afterwards, so the next read has no choice but to rebuild from the journal. Deterministic,
   * unlike waiting for passivation to fire.
   */
  def restartService(): Unit =
    current.terminate()
    scala.concurrent.Await.ready(current.whenTerminated, readyTimeout): Unit
    current = AnkkaTestKit.hostService(descriptors, extensions, config, readyTimeout)

  def stop(): Unit =
    current.terminate()
    container.stop()
    AnkkaTestKit.releaseRegistryDirectory()

object AnkkaTestKit:

  private val PostgresImage = "postgres:17-alpine"

  private val DdlResources = Seq(
    "/ankka/ddl/10-journal-postgres.sql"    -> "/docker-entrypoint-initdb.d/10-journal.sql",
    "/ankka/ddl/20-projection-postgres.sql" -> "/docker-entrypoint-initdb.d/20-projection.sql",
    "/ankka/ddl/30-timers-postgres.sql"     -> "/docker-entrypoint-initdb.d/30-timers.sql"
  )

  /**
   * Starts Postgres, applies the schema, and hosts `descriptors`.
   *
   * Returns only once the node is a cluster member, so the first call in a test cannot race
   * startup.
   */
  def start(
      descriptors: Seq[ComponentDescriptor],
      extensions: Seq[RuntimeExtension] = Nil,
      readyTimeout: FiniteDuration = 60.seconds
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
      try hostService(descriptors, extensions, config, readyTimeout)
      catch
        case failure: Throwable =>
          container.stop()
          throw failure

    new AnkkaTestKit(descriptors, extensions, config, container, readyTimeout, service)

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

  private def hostService(
      descriptors: Seq[ComponentDescriptor],
      extensions: Seq[RuntimeExtension],
      config: Config,
      readyTimeout: FiniteDuration
  ): AnkkaService =
    val builder = extensions.foldLeft(Ankka.service.registerAll(descriptors))(_.withExtension(_))
    val service = builder.start("ankka-test", config)
    try
      service.awaitReady(readyTimeout)
      service
    catch
      case failure: Throwable =>
        service.terminate()
        throw failure
