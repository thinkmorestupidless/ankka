package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path}

/**
 * `features/documentation/timers.feature`: the statements the published documentation must make
 * about recurring timers, held to the pages. One test per scenario, named after it, and one more
 * for what the analysis of the feature added: a timer kept for its handler, the period's bounds,
 * and the two retries told a best-effort due time during an upgrade.
 */
class TimersDocumentationSuite extends munit.FunSuite:

  private lazy val docs: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir.resolve("docs")

  private def page(path: String): String =
    Files.readString(docs.resolve(path)).replaceAll("\\s+", " ")

  private def says(path: String, statements: String*): Unit =
    val text = page(path)
    for statement <- statements do
      assert(text.contains(statement), s"$path does not say: $statement")

  test("the documentation describes a recurring timer") {
    says(
      "build/timers.md",
      "## Recurring timers",
      "createRecurringTimer",
      "Each next due time is the previous due time plus the period",
      "A recurring timer fires until it is cancelled or replaced",
      "A service may set its recurring timers every time it starts",
      "it fires once, not once for each due time that passed",
      "A handler is told the due time it is run for"
    )
  }

  test("the documentation does not tell a handler to set its own timer again") {
    says("build/timers.md", "To run something again and again, use a recurring timer")
    val text = page("build/timers.md").toLowerCase
    for advice <- Seq(
        "schedule the same timer again",
        "schedule it again from",
        "set the timer again from",
        "reschedule the timer from",
        "again from inside the handler",
        "again at the end of the handler"
      )
    do assert(!text.contains(advice), s"build/timers.md advises: $advice")
  }

  test("the documentation says what a recurring timer does after a failure") {
    says(
      "build/timers.md",
      "A recurring timer whose handler fails is retried after the same backoff",
      "the next due time is taken from the due time it failed for"
    )
  }

  test("the documentation says what a period cannot say") {
    says(
      "reference/limitations.md",
      "A period is a length of time",
      "never a time of day or a day of the week"
    )
  }

  test(
    "the documentation says a timer waits for its handler, the period's bounds, and what an upgrade blurs"
  ) {
    says(
      "build/timers.md",
      "is kept, and looked at again every 30 seconds",
      "A period is from one millisecond to 36,500 days",
      "told a best-effort due time",
      "Timers that fire once go on working on it exactly as before"
    )
  }
