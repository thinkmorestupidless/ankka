package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{AnkkaService, Database, SqlFragment}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, HexFormat}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Searches every table of a service's database for values, in every encoding a column could hold.
 */
private[testkit] object PersonalValues:

  def find(service: AnkkaService, values: Seq[String]): Vector[(String, String)] =
    given org.apache.pekko.actor.typed.ActorSystem[?] = service.system
    val database                                      = Database()
    val tables = Await.result(
      database.query(
        SqlFragment.raw(
          "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
        )
      )(_.get(0, classOf[String])),
      30.seconds
    )
    tables.flatMap { table =>
      // Each row as text, bytea columns as hex, so a value in a journal's payload is seen too.
      val rows = Await.result(
        database.query(SqlFragment.raw(s"SELECT row_to_json(t)::text FROM \"$table\" t"))(
          _.get(0, classOf[String])
        ),
        30.seconds
      )
      val text = rows.mkString("\n")
      values
        .filter { value =>
          val bytes = value.getBytes(UTF_8)
          Vector(value, Base64.getEncoder.encodeToString(bytes), HexFormat.of().formatHex(bytes))
            .exists(text.contains)
        }
        .map(table -> _)
    }
