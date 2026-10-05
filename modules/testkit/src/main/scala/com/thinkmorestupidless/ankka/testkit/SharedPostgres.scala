package com.thinkmorestupidless.ankka.testkit

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.{DockerImageName, MountableFile}

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

/**
 * Concrete subclass purely to pin testcontainers' `SELF` type parameter.
 * `PostgreSQLContainer[SELF <: PostgreSQLContainer[SELF]]` is a Java self-type idiom that Scala
 * infers as `Nothing`, which makes the fluent setters unusable.
 */
private final class AnkkaPostgres(image: DockerImageName)
    extends PostgreSQLContainer[AnkkaPostgres](image)

/** One kit's database: its own, in the shared server. */
private[testkit] final case class TestDatabase(host: String, port: Int, name: String):
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$name"

/**
 * One Postgres for every kit in a JVM, and a database of its own for each.
 *
 * A container with the schema applied took seconds to start, once per suite; a database copied from
 * a template that already holds the schema takes tens of milliseconds. Each kit still has a
 * database nobody else writes to — two services must never share one, since the timer sweeper
 * deletes rows it does not recognise and view tables are named from the component id alone — so
 * what is shared is only the server.
 *
 * The container outlives the kit that started it by `Linger`, so the next suite in the same JVM
 * finds it running, and stops when nothing has used it for that long, so a build tool that runs
 * tests inside its own long-lived JVM is not left holding one. A forked test JVM that exits first
 * leaves it to testcontainers' reaper.
 */
private[testkit] object SharedPostgres:

  private val Image    = "postgres:17-alpine"
  private val Template = "ankka_template"
  private val Linger   = 30L // seconds

  private val DdlResources = Seq(
    "/ankka/ddl/10-journal-postgres.sql"    -> "/docker-entrypoint-initdb.d/10-journal.sql",
    "/ankka/ddl/20-projection-postgres.sql" -> "/docker-entrypoint-initdb.d/20-projection.sql",
    "/ankka/ddl/30-timers-postgres.sql"     -> "/docker-entrypoint-initdb.d/30-timers.sql",
    "/ankka/ddl/40-secrets-postgres.sql"    -> "/docker-entrypoint-initdb.d/40-secrets.sql"
  )

  private var container: AnkkaPostgres                = null
  private var leases                                  = 0
  private var pendingStop: Option[ScheduledFuture[?]] = None
  private val databases                               = AtomicInteger()

  private lazy val scheduler = Executors.newSingleThreadScheduledExecutor { runnable =>
    val thread = Thread(runnable, "ankka-testkit-postgres")
    thread.setDaemon(true)
    thread
  }

  /** A fresh database holding the schema and nothing else. Give it back with `release`. */
  def acquire(): TestDatabase = synchronized {
    pendingStop.foreach(_.cancel(false))
    pendingStop = None
    if container == null || !container.isRunning then container = startContainer()
    val name = s"ankka_${databases.incrementAndGet()}"
    createFromTemplate(name)
    leases += 1
    TestDatabase(
      container.getHost,
      container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
      name
    )
  }

  /** Drops the database, which nothing may still be connected to that should be. */
  def release(database: TestDatabase): Unit = synchronized {
    if container != null && container.isRunning then
      try psql(s"DROP DATABASE IF EXISTS ${database.name} WITH (FORCE)")
      catch case NonFatal(_) => ()
    leases -= 1
    if leases == 0 then
      pendingStop =
        Some(scheduler.schedule((() => stopIfIdle()): Runnable, Linger, TimeUnit.SECONDS))
  }

  private def stopIfIdle(): Unit = synchronized {
    if leases == 0 && container != null then
      container.stop()
      container = null
    pendingStop = None
  }

  private def startContainer(): AnkkaPostgres =
    val started = AnkkaPostgres(DockerImageName.parse(Image))
      .withDatabaseName(Template)
      .withUsername("ankka")
      .withPassword("ankka")
      // testcontainers' own default is `fsync=off`, which this keeps; a command replaces it whole.
      // Every kit's connection pool is on this one server, and the default of 100 connections is
      // a handful of kits.
      .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=500")
    DdlResources.foreach { (resource, target) =>
      val _ = started.withCopyFileToContainer(MountableFile.forClasspathResource(resource), target)
    }
    started.start()
    started

  /**
   * The copy is refused while anything is connected to the template, which nothing should be once
   * the server is up; retried briefly in case the startup's own connection is still closing.
   */
  private def createFromTemplate(name: String): Unit =
    var attempt = 1
    while try
        psql(s"CREATE DATABASE $name TEMPLATE $Template")
        false
      catch
        case NonFatal(failure) if attempt < 10 && failure.getMessage.contains("other users") =>
          attempt += 1
          Thread.sleep(200)
          true
    do ()

  private def psql(sql: String): Unit =
    val result =
      container.execInContainer(
        "psql",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        "ankka",
        "-d",
        "postgres",
        "-c",
        sql
      )
    if result.getExitCode != 0 then
      throw IllegalStateException(s"`$sql` failed: ${result.getStderr.trim}")
