package com.thinkmorestupidless.ankka.graph.neo4j

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Where the store is and how to reach it. The password never appears in a message: `redact` strips
 * it, and `toString` never had it.
 */
final case class Neo4jSettings(
    uri: String,
    username: String,
    password: String,
    database: String = Neo4jSettings.DefaultDatabase,
    transactionTimeout: FiniteDuration = 30.seconds
):

  def redact(message: String): String =
    Option(message)
      .getOrElse("")
      .replace(password, "<redacted>")
      .replaceAll("""(?<=://)[^/@\s]+@""", "<redacted>@")

  override def toString: String =
    s"Neo4jSettings($uri, $username, <redacted>, $database, $transactionTimeout)"

object Neo4jSettings:
  val DefaultDatabase: String = "neo4j"
