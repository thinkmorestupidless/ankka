package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*

/** Where another installation's journal is, for the runtime's own transport. */
final case class InstallationDatabase(
    host: String,
    port: Int,
    name: String,
    user: String,
    password: String
)

/**
 * What `ankka.replication` says (feature 051): which installation this is, which others it may
 * replicate with, how often the runtime's own transport polls, and how a handover waits.
 *
 * `installations` excludes this installation even when the configuration names it, so "a sibling"
 * always means another one.
 */
final case class ReplicationSettings(
    installation: Option[String],
    installations: Set[String],
    poll: FiniteDuration,
    holdWrites: Boolean,
    handoverTimeout: FiniteDuration,
    databases: Map[String, InstallationDatabase] = Map.empty
):
  /** Whether this service replicates at all: an installation of its own and at least one other. */
  def enabled: Boolean = installation.nonEmpty && installations.nonEmpty

object ReplicationSettings:

  /** No installations: what every service that never replicates runs with. */
  val none: ReplicationSettings =
    ReplicationSettings(None, Set.empty, 1.second, holdWrites = false, 30.seconds)

  private val Name = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$".r

  /** Why `name` cannot name an installation, or `None` when it can. */
  def nameProblem(name: String): Option[String] =
    if Name.matches(name) then None
    else
      Some(
        s"'$name' cannot name an installation: a name is a DNS label of at most 63 characters, " +
          "lower-case letters, digits and '-', which keeps it out of the persistence id's '|'"
      )

  /**
   * Reads the settings, with `ANKKA_INSTALLATIONS` (names alone, comma-separated) and
   * `ANKKA_INSTALLATION_<NAME>_DB_*` from `env` added over the configuration's `installations`; a
   * name in the list that also has a database is one installation, with that database. Throws
   * `IllegalArgumentException` naming the setting when a name is not one an installation may have.
   */
  def from(config: Config, env: Map[String, String] = sys.env): ReplicationSettings =
    val c    = withEnvironment(config, env).getConfig("ankka.replication")
    val self = Option(c.getString("installation")).map(_.trim).filter(_.nonEmpty)
    self.flatMap(nameProblem).foreach { p =>
      throw IllegalArgumentException(s"ankka.replication.installation: $p")
    }
    val section = c.getConfig("installations")
    val names   = section.root.keySet.asScala.toSet
    names.flatMap(nameProblem).headOption.foreach { p =>
      throw IllegalArgumentException(s"ankka.replication.installations: $p")
    }
    val databases = names.flatMap { name =>
      val one = section.getConfig(quoted(name))
      Option.when(one.hasPath("db.host"))(
        name -> InstallationDatabase(
          one.getString("db.host"),
          if one.hasPath("db.port") then one.getInt("db.port") else 5432,
          if one.hasPath("db.name") then one.getString("db.name") else "ankka",
          if one.hasPath("db.user") then one.getString("db.user") else "ankka",
          if one.hasPath("db.password") then one.getString("db.password") else ""
        )
      )
    }.toMap
    val others = names -- self
    ReplicationSettings(
      self,
      others,
      c.getDuration("poll").toScala,
      c.getBoolean("handover.hold-writes"),
      c.getDuration("handover.timeout").toScala,
      databases -- self
    )

  private def quoted(name: String): String = "\"" + name + "\""

  private val Suffixes = Map(
    "_DB_HOST"     -> "host",
    "_DB_PORT"     -> "port",
    "_DB_NAME"     -> "name",
    "_DB_USER"     -> "user",
    "_DB_PASSWORD" -> "password"
  )

  /**
   * `ANKKA_INSTALLATIONS=a,b` as `ankka.replication.installations."a" {}` and `."b" {}` (spec 057),
   * and `ANKKA_INSTALLATION_<NAME>_DB_*` as `ankka.replication.installations.<name>.db.*`.
   */
  private def withEnvironment(config: Config, env: Map[String, String]): Config =
    val configured =
      if config.hasPath("ankka.replication.named") then
        Some(config.getString("ankka.replication.named"))
      else None
    val listed = (env.get(PlatformVariables.Installations).toVector ++ configured)
      .flatMap(_.split(',').toVector)
      .map(_.trim.toLowerCase)
      .filter(_.nonEmpty)
    listed.distinct.flatMap(nameProblem).headOption.foreach { p =>
      throw IllegalArgumentException(s"${PlatformVariables.Installations}: $p")
    }
    val named = listed.foldLeft(ConfigFactory.empty) { (acc, name) =>
      acc.withValue(
        s"ankka.replication.installations.${quoted(name)}",
        ConfigValueFactory.fromMap(java.util.Map.of[String, AnyRef]())
      )
    }
    withDatabases(env).withFallback(named).withFallback(config)

  private def withDatabases(env: Map[String, String]): Config =
    val entries = env.toVector.flatMap { (key, value) =>
      if !key.startsWith(PlatformVariables.InstallationPrefix) then None
      else
        val rest = key.stripPrefix(PlatformVariables.InstallationPrefix)
        Suffixes.collectFirst {
          case (suffix, field) if rest.endsWith(suffix) && rest.length > suffix.length =>
            val name = rest.dropRight(suffix.length).toLowerCase.replace('_', '-')
            s"ankka.replication.installations.${quoted(name)}.db.$field" -> value
        }
    }
    entries
      .foldLeft(ConfigFactory.empty) { case (acc, (path, value)) =>
        acc.withValue(path, ConfigValueFactory.fromAnyRef(value))
      }
