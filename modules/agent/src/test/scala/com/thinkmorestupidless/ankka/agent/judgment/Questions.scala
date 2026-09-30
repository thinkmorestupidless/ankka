package com.thinkmorestupidless.ankka.agent.judgment

import java.nio.file.{Files, Path, Paths}

/** The questions the offline suites ask, declared as a developer would. */
enum Team:
  case Billing, Technical, Sales

object Questions:

  val route: ChoiceQuestion[Team] =
    Question.choice[Team]("route", "Which team should handle this ticket?")(
      Team.Billing   -> ("billing", "Payments, invoicing, refunds"),
      Team.Technical -> ("technical", "Bugs, outages, integrations"),
      Team.Sales     -> ("sales", "Pricing, upgrades, new accounts")
    )

  val frustration: ScoreQuestion =
    Question.score("frustration", "How frustrated is the customer?")(
      "Calm, stating facts",
      "Frustrated but civil",
      "Angry, strong language",
      "Abusive"
    )

  val refund: YesNoQuestion = Question.yesNo("refund", "The customer asks for a refund")

  val urgent: YesNoQuestion =
    Question
      .yesNo("urgent", "The ticket needs a reply today")
      .describing(yes = "A deadline, an outage or money at risk", no = "It can wait")

/**
 * Compares generated forms with committed files, writing any that differ, as the testkit's fixture
 * suites do: a change shows up as a diff to review, and the test fails until it is.
 */
object FixtureFiles:

  def repositoryRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.isDirectory(p.resolve("protocol")) && Files.isDirectory(p.resolve("modules"))
      )
      .getOrElse(throw IllegalStateException("could not find the repository root"))

  def check(path: Path, content: String): Option[String] =
    Files.createDirectories(path.getParent)
    if !Files.exists(path) then
      Files.writeString(path, content)
      Some(s"${path.getFileName} was missing and has been written; commit it")
    else if Files.readString(path) != content then
      Files.writeString(path, content)
      Some(s"${path.getFileName} differed and has been rewritten; review the diff")
    else None
