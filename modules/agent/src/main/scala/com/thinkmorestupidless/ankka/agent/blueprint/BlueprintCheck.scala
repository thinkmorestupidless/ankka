package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.agent.judgment.YesNoQuestion

import java.time.DayOfWeek
import scala.util.Try

/** One thing wrong with a blueprint: where (`workers[1].tools[0]`), which rule, and what. */
final case class Problem(path: String, rule: String, message: String):
  override def toString: String = s"$path: $message"

/** Something worth saying about a blueprint that is held all the same. */
final case class Note(path: String, message: String):
  override def toString: String = s"$path: $message"

/**
 * Checks a blueprint whole, against the service that will run it, before it is held. Every problem
 * is found in one pass and named at once, so a developer fixes a blueprint in one round and not one
 * refusal at a time.
 */
object BlueprintCheck:

  private val Name = "^[a-z0-9][a-z0-9-]*$".r
  private val Time = "^([01][0-9]|2[0-3]):[0-5][0-9]$".r

  /** The patterns as a blueprint written in JSON names them, and as the glossary does. */
  val PatternNames: Map[String, String] = Map(
    "Ask"      -> "ask",
    "Work"     -> "work",
    "ForEach"  -> "for-each",
    "Gather"   -> "gather",
    "Judge"    -> "judge",
    "Critique" -> "critique"
  )

  /**
   * A blueprint written in JSON, checked. A pattern the platform does not have cannot be decoded
   * into `Pattern`, so it is found first, by the step that names it.
   */
  def fromJson(
      text: String,
      registry: BlueprintRegistry
  ): Either[Vector[Problem], (Blueprint, Vector[Note])] =
    Json.parse(text) match
      case Left(error) => Left(Vector(Problem("$", "json", s"not JSON: $error")))
      case Right(json) =>
        val unknownPatterns =
          json("steps").flatMap(_.asArray).getOrElse(Vector.empty).zipWithIndex.flatMap {
            (step, i) =>
              val name    = step("name").flatMap(_.asString).getOrElse(s"#${i + 1}")
              val pattern = step("pattern").flatMap(_("type")).flatMap(_.asString)
              pattern.filterNot(PatternNames.contains).map { p =>
                Problem(
                  s"steps[$i].pattern",
                  "pattern",
                  s"step '$name' uses the pattern '$p', which the platform does not have " +
                    s"(${PatternNames.values.toVector.sorted.mkString(", ")})"
                )
              }
          }
        if unknownPatterns.nonEmpty then Left(unknownPatterns)
        else
          Blueprint.fromJson(text) match
            case Left(error) => Left(Vector(Problem("$", "json", error)))
            case Right(blueprint) =>
              val (problems, notes) = check(blueprint, registry)
              if problems.nonEmpty then Left(problems) else Right(blueprint -> notes)

  /** Every problem, and every note, in one pass. */
  def check(bp: Blueprint, registry: BlueprintRegistry): (Vector[Problem], Vector[Note]) =
    val problems = Vector.newBuilder[Problem]
    def problem(path: String, rule: String, message: String): Unit =
      problems += Problem(path, rule, message)

    if !Name.matches(bp.name) then
      problem("name", "name", s"'${bp.name}' is not a name: lower-case letters, digits and hyphens")

    // ── workers ─────────────────────────────────────────────────────────────
    val workerNames = bp.workers.map(_.name)
    bp.workers.zipWithIndex.foreach { (w, i) =>
      val at = s"workers[$i]"
      if workerNames.indexOf(w.name) != i then
        problem(s"$at.name", "duplicate-name", s"worker '${w.name}' is named twice")
      if w.instructions.isBlank then
        problem(s"$at.instructions", "instructions", s"worker '${w.name}' has no instructions")
      if w.budget <= 0 then
        problem(
          s"$at.budget",
          "budget",
          s"worker '${w.name}' needs a budget of model calls, above nought"
        )
      if !registry.hasModel(w.model) then
        problem(
          s"$at.model",
          "model",
          s"worker '${w.name}' names the model '${w.model}', which is not registered for blueprints"
        )
      w.tools.zipWithIndex.foreach { (t, j) =>
        if registry.tool(t).isEmpty then
          problem(
            s"$at.tools[$j]",
            "tool",
            s"worker '${w.name}' names the tool '$t', which is not registered for blueprints"
          )
      }
      w.guardrails.zipWithIndex.foreach { (g, j) =>
        if registry.guardrail(g).isEmpty then
          problem(
            s"$at.guardrails[$j]",
            "guardrail",
            s"worker '${w.name}' names the guardrail '$g', which is not registered for blueprints"
          )
      }
    }

    // ── steps ───────────────────────────────────────────────────────────────
    if bp.steps.isEmpty then problem("steps", "steps", "a blueprint needs at least one step")
    val stepNames = bp.steps.map(_.name)

    /** What a read gives, or why it gives nothing, seen from step `i`. */
    def resolve(i: Int, read: String): Either[(String, String), Shape] =
      val (source, field) = read.split("\\.", 2) match
        case Array(s, f) => (s, Some(f))
        case _           => (read, None)
      val base: Either[(String, String), Shape] =
        if source == "input" then Right(bp.input)
        else
          stepNames.indexOf(source) match
            case -1 => Left("read" -> s"reads '$read', which is neither input nor a step")
            case j if j >= i =>
              Left("read-order" -> s"reads the result of '$source', which does not come before it")
            case j => Right(bp.steps(j).result)
      base.flatMap { shape =>
        field match
          case None => Right(shape)
          case Some(f) =>
            shape.field(f).toRight("read" -> s"reads '$read', and '$source' has no field '$f'")
      }

    bp.steps.zipWithIndex.foreach { (step, i) =>
      val at = s"steps[$i]"
      if stepNames.indexOf(step.name) != i then
        problem(s"$at.name", "duplicate-name", s"step '${step.name}' is named twice")
      if step.name == "input" then
        problem(
          s"$at.name",
          "reserved-name",
          "a step cannot be named 'input', which is the run's input"
        )

      step.pattern.namedWorkers.distinct.foreach { w =>
        if !workerNames.contains(w) then
          problem(
            s"$at.pattern",
            "worker",
            s"step '${step.name}' names the worker '$w', which is not one of the blueprint's workers"
          )
      }
      step.pattern.namedQuestions.distinct.foreach { q =>
        if registry.question(q).isEmpty then
          problem(
            s"$at.pattern",
            "judgment-question",
            s"step '${step.name}' asks the judgment question '$q', which is not registered for blueprints"
          )
      }
      (step.reads ++ step.pattern.extraReads).distinct.foreach { read =>
        resolve(i, read).left.foreach((rule, why) =>
          problem(s"$at.reads", rule, s"step '${step.name}' $why")
        )
      }

      step.pattern match
        case Pattern.ForEach(_, over, limit, _) =>
          if limit <= 0 then
            problem(
              s"$at.pattern.limit",
              "limit",
              s"step '${step.name}' needs a limit above nought"
            )
          resolve(i, over).foreach { shape =>
            if !shape.isArray then
              problem(
                s"$at.pattern.over",
                "list",
                s"for-each step '${step.name}' needs a list, and '$over' gives ${describe(shape)}"
              )
          }
        case Pattern.Gather(workers, times, chosenBy) =>
          if workers.isEmpty then
            problem(
              s"$at.pattern.workers",
              "workers",
              s"gather step '${step.name}' names no worker"
            )
          times.foreach { n =>
            if n <= 1 then
              problem(
                s"$at.pattern.times",
                "times",
                s"gather step '${step.name}' needs times above one, or several workers"
              )
            if workers.sizeIs > 1 then
              problem(
                s"$at.pattern.times",
                "times",
                s"gather step '${step.name}' gives times and several workers; one or the other"
              )
          }
          if times.isEmpty && chosenBy.isEmpty && workers.sizeIs < 2 then
            problem(
              s"$at.pattern.workers",
              "workers",
              s"gather step '${step.name}' needs at least two workers, or one worker several times"
            )
          chosenBy.foreach { by =>
            resolve(i, by).foreach { shape =>
              if !shape.isStringArray then
                problem(
                  s"$at.pattern.chosenBy",
                  "chosen-by",
                  s"gather step '${step.name}' is chosen by '$by', which gives ${describe(shape)} and not a list of names"
                )
            }
          }
        case Pattern.Judge(questions) =>
          if questions.isEmpty then
            problem(
              s"$at.pattern.questions",
              "questions",
              s"judge step '${step.name}' asks no question"
            )
        case Pattern.Critique(drafter, verdict, rounds, _) =>
          if rounds <= 0 then
            problem(
              s"$at.pattern.rounds",
              "rounds",
              s"critique step '${step.name}' needs rounds above nought"
            )
          verdict match
            case Verdict.Critic(c) if c == drafter =>
              problem(
                s"$at.pattern.verdict",
                "verdict",
                s"critique step '${step.name}' has '$drafter' judge its own draft"
              )
            case Verdict.Judgment(q) =>
              registry.question(q).foreach {
                case _: YesNoQuestion => ()
                case _ =>
                  problem(
                    s"$at.pattern.verdict",
                    "verdict",
                    s"critique step '${step.name}' needs a yes-or-no question as its verdict, and '$q' is not one"
                  )
              }
            case _ => ()
        case _ => ()
    }

    // ── schedule and limits ───────────────────────────────────────────────────
    bp.schedule.foreach { s =>
      if s.zoneId.isEmpty then
        problem("schedule.zone", "zone", s"'${s.zone}' is not a time zone the platform knows")
      s.cadence match
        case Cadence.EveryHours(h) if h <= 0 =>
          problem("schedule.cadence", "cadence", "every so many hours needs a count above nought")
        case Cadence.EveryDays(d) if d <= 0 =>
          problem("schedule.cadence", "cadence", "every so many days needs a count above nought")
        case Cadence.Weekly(day, time) =>
          if Try(DayOfWeek.valueOf(day)).isFailure then
            problem(
              "schedule.cadence.day",
              "cadence",
              s"'$day' is not a day of the week (${DayOfWeek.values.map(_.name).mkString(", ")})"
            )
          if !Time.matches(time) then
            problem("schedule.cadence.time", "cadence", s"'$time' is not a time of day as HH:mm")
        case _ => ()
      if !registry.hasTimers then
        problem(
          "schedule",
          "timers",
          "a schedule needs the service's timers: register TimerRuntime"
        )
    }
    bp.runBudget.foreach(b =>
      if b <= 0 then problem("runBudget", "run-budget", "a run budget is above nought")
    )
    bp.timeLimit.foreach(t =>
      if t.isNegative || t.isZero then
        problem("timeLimit", "time-limit", "a time limit is above nought")
    )

    // ── notes ───────────────────────────────────────────────────────────────
    val used = bp.steps.flatMap(_.pattern.namedWorkers).toSet
    val notes = bp.workers.zipWithIndex.collect {
      case (w, i) if !used.contains(w.name) =>
        Note(s"workers[$i]", s"no step uses the worker '${w.name}'")
    }

    (problems.result(), notes)

  private def describe(shape: Shape): String = shape.kind match
    case "array"   => s"a list of ${shape.items.map(_.kind).getOrElse("anything")}"
    case "object"  => "an object"
    case "string"  => "a string"
    case "number"  => "a number"
    case "integer" => "an integer"
    case "boolean" => "a boolean"
    case other     => other
