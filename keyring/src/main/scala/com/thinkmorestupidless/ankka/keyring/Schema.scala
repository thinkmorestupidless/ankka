package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import org.apache.pekko.actor.typed.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The keyring applies its own schema when it starts, from the DDL the runtime's jar carries — the
 * one canonical copy — as the role that owns its database. Every statement is `IF NOT EXISTS`, so a
 * start after the first changes nothing. (Its manifest cannot give it the schema the way the
 * control plane's is given: kustomize lets a component read no file outside its own directory.)
 */
object Schema:
  val Files: Vector[String] = Vector(
    "10-journal-postgres.sql",
    "20-projection-postgres.sql",
    "30-timers-postgres.sql",
    "40-secrets-postgres.sql"
  )

  def apply(system: ActorSystem[?]): Unit =
    given ActorSystem[?] = system
    val statements = Files.map { name =>
      val stream = Option(getClass.getResourceAsStream(s"/ankka/ddl/$name"))
        .getOrElse(throw IllegalStateException(s"the runtime's DDL $name is not on the classpath"))
      try SqlFragment.raw(String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
      finally stream.close()
    }
    Await.result(Database().executeAll(statements), 60.seconds)
