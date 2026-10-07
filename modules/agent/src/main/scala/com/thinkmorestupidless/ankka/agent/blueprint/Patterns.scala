package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.{
  forAutonomousAgent,
  forTask,
  tasks,
  TaskStatus
}
import com.thinkmorestupidless.ankka.agent.judgment.{
  Judgment,
  JudgmentState,
  Judgments,
  YesNoQuestion
}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, Semaphore}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * One step, carried out: what it does once, as many times as its `over` says, until its `until` is
 * met. The action is done by `once`; the overs and the until compose around it, the same for every
 * action, so a for-each of work tasks or a critique drafted by an autonomous worker is a
 * combination and not a pattern of its own.
 */
private[blueprint] object Patterns:

  /**
   * What one doing of the step answers with: the step's result, or the item of it when the step
   * repeats.
   */
  def itemShape(step: Step): Shape = step.over.itemOf(step.result)

  /** What one doing of an action produced, and what it cost. */
  final case class Produced(
      result: Json,
      sessions: Vector[String] = Vector.empty,
      usage: TokenUsage = TokenUsage.zero,
      judgmentUsage: TokenUsage = TokenUsage.zero,
      modelCalls: Int = 0
  ):
    def +(other: Produced): Produced =
      Produced(
        result,
        (sessions ++ other.sessions).distinct,
        usage + other.usage,
        judgmentUsage + other.judgmentUsage,
        modelCalls + other.modelCalls
      )

  sealed trait Outcome
  final case class Done(produced: Produced) extends Outcome
  final case class Failed(reason: String)   extends Outcome

  /** The run was cancelled or passed its deadline; the next look at the record ends it. */
  case object Interrupted extends Outcome

  /** Carries one step out and records how it ended, or ends the run. */
  def carryOut(
      w: RunWorker,
      run: RunRecord,
      blueprint: Blueprint,
      step: Step,
      judgments: Judgments
  ): Unit =
    val ref = RunRef(w.runId, step.name, blueprint.name, run.version)
    val ctx = Context(w, run, blueprint, step, ref, judgments)
    over(ctx) match
      case Interrupted    => ()
      case Failed(reason) => w.endRun(RunStatus.Failed, Some(s"${step.name}: $reason"))
      case Done(p) =>
        w.record(
          RunEvent.StepEnded(
            step.name,
            p.result.render,
            p.sessions,
            p.usage,
            p.judgmentUsage,
            p.modelCalls,
            w.now()
          )
        )

  private final case class Context(
      w: RunWorker,
      run: RunRecord,
      blueprint: Blueprint,
      step: Step,
      ref: RunRef,
      judgments: Judgments
  ):
    def worker(name: String): Worker =
      blueprint.workers
        .find(_.name == name)
        .getOrElse(throw IllegalStateException(s"no worker '$name'"))

  // ── Over ────────────────────────────────────────────────────────────────────

  private def over(ctx: Context): Outcome =
    val step = ctx.step
    step.over match
      case Over.Once =>
        until(ctx, ctx.step.does, Reads.values(step, ctx.run), "", "")
      case Over.Each(read, limit, keepGoing) =>
        val items = Reads.resolve(read, ctx.run).asArray.getOrElse(Vector.empty)
        if items.isEmpty then Done(Produced(Json.Arr(Vector.empty)))
        else
          val base = Reads.values(step, ctx.run)
          repeat(ctx, items.indices.toVector, limit, keepGoing) { i =>
            val input = Json.Obj(base.asObjectFields + ("item" -> items(i)))
            (step.does, input, s"[$i]", s" (item ${i + 1} of ${items.size})")
          }
      case Over.Workers(workers, chosenBy) =>
        val chosen = chosenBy match
          case None => workers
          case Some(by) =>
            val names =
              Reads.resolve(by, ctx.run).asArray.getOrElse(Vector.empty).flatMap(_.asString)
            workers.filter(names.contains)
        if chosen.isEmpty then Done(Produced(Json.Arr(Vector.empty)))
        else
          val base = Reads.values(step, ctx.run)
          repeat(
            ctx,
            chosen.indices.toVector,
            chosen.size,
            keepGoing = false,
            worker = Some(chosen)
          ) { i =>
            (withWorker(step.does, chosen(i)), base, "", "")
          }
      case Over.Times(n) =>
        val base = Reads.values(step, ctx.run)
        repeat(ctx, (0 until n).toVector, n, keepGoing = false, times = true) { i =>
          (step.does, base, s"[$i]", s" (${i + 1} of $n)")
        }

  private def withWorker(action: Action, worker: String): Action = action match
    case Action.Ask(_)  => Action.Ask(Some(worker))
    case Action.Work(_) => Action.Work(Some(worker))
    case other          => other

  /**
   * Does the action once per index, at most `limit` at once, each on a thread of its own, recording
   * each as it ends and skipping those the record already holds. The result is the results in
   * order; a failed item is `null` in it and marked in its record.
   */
  private def repeat(
      ctx: Context,
      indices: Vector[Int],
      limit: Int,
      keepGoing: Boolean,
      worker: Option[Vector[String]] = None,
      times: Boolean = false
  )(unit: Int => (Action, Json, String, String)): Outcome =
    val already =
      ctx.run.step(ctx.step.name).map(_.items).getOrElse(Vector.empty).map(r => r.index -> r).toMap
    val results  = ConcurrentHashMap[Int, Outcome]()
    val permits  = Semaphore(limit.max(1))
    val todo     = indices.filterNot(already.contains)
    val finished = CountDownLatch(todo.size)
    todo.foreach { i =>
      Thread.ofVirtual().name(s"run-${ctx.ref.runId}-${ctx.step.name}-$i").start { () =>
        try
          permits.acquire()
          try
            val (action, input, idSuffix, said) = unit(i)
            val outcome                         = until(ctx, action, input, idSuffix, said)
            results.put(i, outcome): Unit
            outcome match
              case Done(p) =>
                ctx.w.record(
                  RunEvent.ItemEnded(
                    ctx.step.name,
                    ItemRecord(i, Some(p.result.render), None, p.sessions.mkString(","), p.usage)
                  )
                )
              case Failed(reason) =>
                ctx.w.record(
                  RunEvent.ItemEnded(
                    ctx.step.name,
                    ItemRecord(i, None, Some(reason), "", TokenUsage.zero)
                  )
                )
              case Interrupted => ()
          finally permits.release()
        catch
          case NonFatal(e) =>
            results.put(i, Failed(Option(e.getMessage).getOrElse(e.toString))): Unit
        finally finished.countDown()
      }: Unit
    }
    finished.await()
    if results.values.asScala.exists(_ == Interrupted) then Interrupted
    else
      val record = ctx.w
        .read()
        .step(ctx.step.name)
        .map(_.items)
        .getOrElse(Vector.empty)
        .map(r => r.index -> r)
        .toMap
      val failed = indices.flatMap(i => record.get(i).flatMap(_.failure).map(i -> _))
      failed.headOption match
        case Some((i, reason)) if !keepGoing => Failed(s"item ${i + 1}: $reason")
        case _ =>
          val values = indices.map { i =>
            val value = record
              .get(i)
              .flatMap(_.result)
              .map(r => Json.parse(r).getOrElse(Json.Str(r)))
              .getOrElse(Json.Null)
            worker match
              case Some(names)   => Json.obj("worker" -> Json.str(names(i)), "result" -> value)
              case None if times => Json.obj("n" -> Json.num(i + 1), "result" -> value)
              case None          => value
          }
          val produced = indices.foldLeft(Produced(Json.Arr(values))) { (acc, i) =>
            record
              .get(i)
              .fold(acc)(r =>
                acc + Produced(
                  Json.Null,
                  if r.session.isEmpty then Vector.empty else r.session.split(',').toVector,
                  r.usage
                )
              )
          }
          Done(produced.copy(modelCalls = results.values.asScala.collect { case Done(p) =>
            p.modelCalls
          }.sum))

  // ── Until ───────────────────────────────────────────────────────────────────

  /** The action once, or, with an until, drafted until the verdict passes or the rounds run out. */
  private def until(
      ctx: Context,
      action: Action,
      input: Json,
      idSuffix: String,
      said: String
  ): Outcome =
    ctx.step.until match
      case None    => once(ctx, action, input, idSuffix, said, Vector.empty)
      case Some(u) =>
        // Rounds already recorded, for a step done once: a passed round is the answer.
        val recorded =
          if idSuffix.isEmpty then ctx.run.step(ctx.step.name).map(_.rounds).getOrElse(Vector.empty)
          else Vector.empty
        recorded.lastOption.filter(_.passed) match
          case Some(last) =>
            Done(
              Produced(
                Json.parse(last.draft).getOrElse(Json.Str(last.draft)),
                last.sessions,
                last.usage
              )
            )
          case None =>
            var round   = recorded.size + 1
            var reasons = recorded.lastOption.map(_.reasons).getOrElse(Vector.empty)
            var acc     = Produced(Json.Null)
            var outcome: Option[Outcome] = None
            while outcome.isEmpty do
              once(
                ctx,
                action,
                input,
                s"$idSuffix:round$round",
                s"$said, round $round",
                reasons
              ) match
                case Interrupted    => outcome = Some(Interrupted)
                case Failed(reason) => outcome = Some(Failed(reason))
                case Done(draft) =>
                  acc = acc + draft
                  verdict(ctx, u.verdict, draft.result, round) match
                    case Left(failure) => outcome = Some(Failed(failure))
                    case Right((passed, why, cost)) =>
                      acc = acc + cost
                      if idSuffix.isEmpty then
                        ctx.w.record(
                          RunEvent.RoundEnded(
                            ctx.step.name,
                            RoundRecord(
                              round,
                              draft.result.render,
                              passed,
                              why,
                              (draft.sessions ++ cost.sessions).distinct,
                              draft.usage + cost.usage
                            )
                          )
                        )
                      if passed then outcome = Some(Done(acc.copy(result = draft.result)))
                      else if round >= u.rounds then
                        if u.keepLast then
                          outcome = Some(
                            Done(
                              acc.copy(result =
                                Json.obj(
                                  "draft"   -> draft.result,
                                  "passed"  -> Json.bool(false),
                                  "reasons" -> Json.Arr(why.map(Json.str))
                                )
                              )
                            )
                          )
                        else
                          outcome = Some(
                            Failed(
                              s"no draft passed after ${u.rounds} round(s); the last was returned with: ${why.mkString("; ")}"
                            )
                          )
                      else
                        reasons = why
                        round += 1
            outcome.get

  /** `(passed, reasons, what the verdict cost)`, or why it could not be given. */
  private def verdict(
      ctx: Context,
      verdict: Verdict,
      draft: Json,
      round: Int
  ): Either[String, (Boolean, Vector[String], Produced)] =
    verdict match
      case Verdict.Critic(name) =>
        val critic  = ctx.worker(name)
        val session = s"run:${ctx.ref.runId}:${ctx.step.name}:$name"
        val message =
          s"Verdict on step '${ctx.step.name}', round $round.\n${Json.obj("draft" -> draft).render}"
        val before = ctx.w.history(session)
        Turns.answer(ctx.w, session, critic, message, Verdict.shape, ctx.ref, ctx.step.name) match
          case Turns.Interrupted    => Left("the run ended while the critic judged")
          case Turns.Failed(reason) => Left(s"the critic '$name' failed: $reason")
          case Turns.Answered(answer) =>
            val after  = ctx.w.history(session)
            val passed = answer("passed").flatMap(_.asBoolean).getOrElse(false)
            val reasons =
              answer("reasons").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
            Right(
              (
                passed,
                reasons,
                Produced(
                  Json.Null,
                  Vector(session),
                  RunWorker.minus(after.usage, before.usage),
                  RunWorker.minus(after.judgmentUsage, before.judgmentUsage),
                  Turns.modelCalls(after) - Turns.modelCalls(before)
                )
              )
            )
      case Verdict.Judgment(id) =>
        ctx.w.registry.question(id) match
          case Some(q: YesNoQuestion) =>
            judge(ctx, JudgmentState.Structured(draft), Vector(q)).map { j =>
              val yes = j(q).probability >= 0.5
              (
                yes,
                if yes then Vector.empty else Vector(s"the judgment answered no to '$id'"),
                Produced(Json.Null, judgmentUsage = j.usage)
              )
            }
          case _ =>
            Left(
              s"the verdict's question '$id' is not a yes-or-no question registered for blueprints"
            )

  // ── Once ────────────────────────────────────────────────────────────────────

  /** The action, once, with what the step reads (and an item, a round's reasons) as its input. */
  private def once(
      ctx: Context,
      action: Action,
      input: Json,
      idSuffix: String,
      said: String,
      reasons: Vector[String]
  ): Outcome =
    val step = ctx.step
    val base = s"Step '${step.name}'$said.\n${input.render}"
    val message =
      if reasons.isEmpty then base
      else
        s"$base\n\nYour last draft was returned with these reasons:\n${reasons.map("- " + _).mkString("\n")}\nDraft again."
    action match
      case Action.Ask(Some(name))               => ask(ctx, name, message, idSuffix)
      case Action.Work(Some(name))              => work(ctx, name, message, idSuffix)
      case Action.Ask(None) | Action.Work(None) => Failed("the step names no worker")
      case Action.Judge(ids) =>
        val questions = ids.flatMap(ctx.w.registry.question)
        judge(ctx, JudgmentState.Structured(input), questions)
          .fold(Failed(_), j => Done(Produced(render(j, questions), judgmentUsage = j.usage)))
      case Action.Call(name) =>
        ctx.w.registry.handler(name) match
          case None => Failed(s"the handler '$name' is not registered for blueprints")
          case Some(handler) =>
            val result =
              try Right(RunContext.within(ctx.ref)(handler.run(ctx.ref, input)))
              catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
            result match
              case Left(failure) => Failed(s"the handler '$name' failed: $failure")
              case Right(json) =>
                val problems = itemShape(ctx).check(json)
                if problems.nonEmpty then
                  Failed(
                    s"the handler '$name' did not answer with the step's shape: ${problems.mkString("; ")}"
                  )
                else Done(Produced(json))

  /**
   * The shape one doing must answer with: the step's; its items' when the step is over each of a
   * list; and the `result` of its items when the step is over workers or times, since the pattern
   * wraps each answer with who gave it, or which time.
   */
  private def itemShape(ctx: Context): Shape = Patterns.itemShape(ctx.step)

  private def ask(ctx: Context, name: String, message: String, idSuffix: String): Outcome =
    val worker  = ctx.worker(name)
    val session = s"run:${ctx.ref.runId}:${ctx.step.name}:$name$idSuffix"
    val before  = ctx.w.history(session)
    Turns.answer(ctx.w, session, worker, message, itemShape(ctx), ctx.ref, ctx.step.name) match
      case Turns.Interrupted    => Interrupted
      case Turns.Failed(reason) => Failed(reason)
      case Turns.Answered(result) =>
        val after = ctx.w.history(session)
        Done(
          Produced(
            result,
            Vector(session),
            RunWorker.minus(after.usage, before.usage),
            RunWorker.minus(after.judgmentUsage, before.judgmentUsage),
            Turns.modelCalls(after) - Turns.modelCalls(before)
          )
        )

  /**
   * A work step: one task of the platform's type, carrying the worker's definition, worked by the
   * platform's worker agent on an instance named for the step. The task's id is the step's, so a
   * worker that stops and starts again finds the task it made rather than making another.
   */
  private def work(ctx: Context, name: String, message: String, idSuffix: String): Outcome =
    val worker     = ctx.worker(name)
    val client     = ctx.w.client
    val taskId     = s"run:${ctx.ref.runId}:${ctx.step.name}$idSuffix"
    val instanceId = s"run:${ctx.ref.runId}:${ctx.step.name}$idSuffix"
    val definition = BlueprintTasks.definitionOf(worker, itemShape(ctx), ctx.ref)
    try
      client.tasks
        .create(WorkerAgent.stepType, message)
        .withId(taskId)
        .withDefinition(definition)
        .create(): Unit
    catch
      case e: CommandError if e.code == ErrorCode.Conflict => () // made before the worker stopped
    val assigned = client.forAutonomousAgent(WorkerAgent.componentId, instanceId).assign(taskId)
    // A task already assigned to this instance, or in progress, is not a conflict for the host.
    val notAssigned = assigned.refused.get(taskId).filterNot(_.message.contains("assign"))
    var outcome: Option[Outcome] =
      notAssigned.map(refusal => Failed(s"the task could not be assigned: ${refusal.message}"))
    var polls = 0
    while outcome.isEmpty do
      Thread.sleep(500)
      polls += 1
      val t = client.forTask(taskId).get()
      if t.status.terminal then
        outcome = Some(t.status match
          case TaskStatus.Completed =>
            val result = t.result.flatMap(r => Json.parse(r).toOption).getOrElse(Json.Null)
            Done(Produced(result, Vector(s"task:$taskId"), t.usage, TokenUsage.zero, t.iterations))
          case TaskStatus.Cancelled => Interrupted
          case _                    => Failed(t.reason.getOrElse("the task failed")))
      else if polls % 4 == 0 then
        val latest = ctx.w.read()
        if latest.cancelRequested.isDefined || latest.deadline.exists(
            _ <= System.currentTimeMillis()
          )
        then
          try client.forTask(taskId).cancel(s"run '${ctx.ref.runId}' ended"): Unit
          catch case _: CommandError => ()
          outcome = Some(Interrupted)
    outcome.get

  private def judge(
      ctx: Context,
      state: JudgmentState,
      questions: Vector[com.thinkmorestupidless.ankka.agent.judgment.Question[?]]
  ): Either[String, Judgment] =
    if questions.isEmpty then Left("the step asks no judgment question registered for blueprints")
    else
      try Right(ctx.judgments.ask(None, state, questions))
      catch
        case NonFatal(e) =>
          Left(s"the judgment could not be given: ${Option(e.getMessage).getOrElse(e.toString)}")

  /** A judgment's answers as JSON, each with the probabilities behind it. */
  private def render(
      judgment: Judgment,
      questions: Vector[com.thinkmorestupidless.ankka.agent.judgment.Question[?]]
  ): Json =
    Json.Obj(questions.map { q =>
      q.id -> (judgment.answers.get(q.id) match
        case Some(Judgment.Stored.YesNo(p)) =>
          Json.obj(
            "answer"      -> Json.str(if p >= 0.5 then "yes" else "no"),
            "probability" -> Json.num(p)
          )
        case Some(Judgment.Stored.Score(score, ps, confidence)) =>
          Json.obj(
            "score"         -> Json.num(score),
            "probabilities" -> Json.Arr(ps.map(Json.num)),
            "confidence"    -> Json.num(confidence)
          )
        case Some(Judgment.Stored.Choice(key, ps, confidence)) =>
          Json.obj(
            "choice"        -> Json.str(key),
            "probabilities" -> Json.Obj(ps.map((k, v) => k -> Json.num(v))),
            "confidence"    -> Json.num(confidence)
          )
        case None => Json.Null)
    }.toMap)

  extension (json: Json)
    private def asObjectFields: Map[String, Json] = json match
      case Json.Obj(fields) => fields
      case _                => Map.empty
