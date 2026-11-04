package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.{Database, InMemoryBroker, SqlFragment}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Base64, HexFormat}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Whether a personal value can be read back from anything a service kept (feature 042, FR-030):
 * every column of every table of its database, and every record it published to a topic, searched
 * for each value as written, as base64 (standard and URL-safe) and as hex.
 */
object PersonalAssertions:

  /** Where a value was found. */
  final case class Found(value: String, where: String, as: String)

  private def forms(value: String): Vector[(String, String)] =
    val bytes = value.getBytes(UTF_8)
    Vector(
      "plain"     -> value,
      "base64"    -> Base64.getEncoder.withoutPadding.encodeToString(bytes),
      "base64url" -> Base64.getUrlEncoder.withoutPadding.encodeToString(bytes),
      "hex"       -> HexFormat.of().formatHex(bytes)
    )

  private def search(text: String, values: Seq[String], where: String): Vector[Found] =
    val lower                        = text.toLowerCase
    def haystack(as: String): String = if as == "hex" then lower else text
    values.toVector.flatMap { v =>
      forms(v).collect {
        case (as, form) if haystack(as).contains(form) => Found(v, where, as)
      }
    }

  /** Every place one of `values` is found in `database`'s tables and `broker`'s records. */
  def find(database: Database, broker: Option[InMemoryBroker], values: Seq[String]): Vector[Found] =
    val columns = Await.result(
      database.query(
        SqlFragment.raw(
          "SELECT table_schema, table_name, column_name FROM information_schema.columns " +
            "WHERE table_schema NOT IN ('pg_catalog', 'information_schema') " +
            "ORDER BY table_schema, table_name, ordinal_position"
        )
      )(row =>
        (row.get(0, classOf[String]), row.get(1, classOf[String]), row.get(2, classOf[String]))
      ),
      30.seconds
    )
    val inTables = columns.flatMap { (schema, table, column) =>
      def quoted(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
      val texts                = Await.result(
        database.query(
          SqlFragment.raw(
            s"SELECT ${quoted(column)}::text FROM ${quoted(schema)}.${quoted(table)} " +
              s"WHERE ${quoted(column)} IS NOT NULL"
          )
        )(row => Option(row.get(0, classOf[String])).getOrElse("")),
        30.seconds
      )
      texts.flatMap(search(_, values, s"$schema.$table.$column")).distinct
    }
    val onTopics = broker.toVector.flatMap(_.published.zipWithIndex.flatMap { (d, offset) =>
      search(d.text, values, s"topic ${d.topic} at offset $offset")
    })
    inTables ++ onTopics

  /** Fails, naming each table and column or topic and offset, when any of `values` is found. */
  def assertNone(database: Database, broker: Option[InMemoryBroker], values: Seq[String]): Unit =
    val found = find(database, broker, values)
    if found.nonEmpty then
      throw AssertionError(
        "a personal value can be read back from what the service kept:\n" +
          found.map(f => s"  '${f.value}' as ${f.as} in ${f.where}").mkString("\n")
      )
