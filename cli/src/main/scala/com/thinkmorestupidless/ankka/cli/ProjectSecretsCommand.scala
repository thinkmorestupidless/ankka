package com.thinkmorestupidless.ankka.cli

import java.io.BufferedReader

/** How `projects secrets set` reads its `KEY=VALUE` pairs. */
private[cli] object ProjectSecretsCommand:

  /**
   * The entries the pairs name. `KEY=-` takes its value from standard input, read to the end with
   * one trailing newline removed, so the value is in no shell history or process listing; at most
   * one pair may. `input` is `Console.in`, which a test redirects with `Console.withIn`.
   */
  def entries(pairs: List[String], input: => BufferedReader): Map[String, String] =
    val split = pairs.map { pair =>
      pair.indexOf('=') match
        case -1 | 0 => throw ApiError(0, s"'$pair' is not KEY=VALUE")
        case i      => pair.take(i) -> pair.drop(i + 1)
    }
    val duplicated = split.groupBy(_._1).collect { case (k, vs) if vs.size > 1 => k }
    if duplicated.nonEmpty then
      throw ApiError(
        0,
        s"each entry is named once; ${duplicated.toVector.sorted.mkString(", ")} more than once"
      )
    val fromInput = split.filter(_._2 == "-")
    if fromInput.size > 1 then
      throw ApiError(0, "at most one entry may take its value from standard input (KEY=-)")
    split.map {
      case (key, "-") =>
        val text = Iterator.continually(input.read()).takeWhile(_ != -1).map(_.toChar).mkString
        key -> text.stripSuffix("\n").stripSuffix("\r")
      case pair => pair
    }.toMap
