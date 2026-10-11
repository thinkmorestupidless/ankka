package com.thinkmorestupidless.ankka.cli

import cats.data.ValidatedNel
import cats.syntax.all.*

import java.time.{Instant, OffsetDateTime}
import java.time.format.DateTimeParseException

/**
 * A moment a member names on the command line (feature 041): RFC 3339 with a zone, refused as a
 * misuse rather than guessed. A moment with no zone is ambiguous, and a restore to the wrong hour
 * is a second incident.
 */
object Moments:

  def parse(text: String): ValidatedNel[String, Instant] =
    try OffsetDateTime.parse(text.trim).toInstant.validNel
    catch
      case _: DateTimeParseException =>
        s"'$text' is not a moment: give an RFC 3339 time with a zone, as 2026-10-08T09:20:00Z".invalidNel
