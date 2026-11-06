package com.thinkmorestupidless.ankka.cli

import java.time.{LocalDate, ZoneOffset}
import java.time.format.DateTimeParseException

/** What `ankka projects erasures` reads from its arguments (feature 042). */
private[cli] object ErasuresCommand:

  /** A not-before date, `2027-01-31`, as the start of that day in UTC. */
  def notBefore(text: String): java.time.Instant =
    try LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC)
    catch
      case _: DateTimeParseException =>
        throw ApiError(0, s"--not-before is a date, 2027-01-31, not '$text'")

  val States: Vector[String] = Vector("held", "applying", "applied", "withdrawn")
