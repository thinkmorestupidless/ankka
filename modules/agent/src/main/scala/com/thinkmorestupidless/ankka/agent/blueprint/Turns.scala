package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.CommandError

/** What a step is given: the run's input and the results it reads, as one JSON object. */
private[blueprint] object Reads:

  /** The message a worker is given for a step: the step's name and what it reads. */
  def message(step: Step, run: RunRecord): String =
    val values = step.reads.map(read => read -> resolve(read, run))
    val body   = if values.isEmpty then "{}" else Json.Obj(values.toMap).render
    s"Step '${step.name}'.\n$body"

  /** `input`, a step's result, or one field of it. */
  def resolve(read: String, run: RunRecord): Json =
    val (source, field) = read.split("\\.", 2) match
      case Array(s, f) => (s, Some(f))
      case _           => (read, None)
    val base =
      if source == "input" then parse(run.input)
      else run.step(source).flatMap(_.result).map(parse).getOrElse(Json.Null)
    field.fold(base)(f => base(f).getOrElse(Json.Null))

  private def parse(text: String): Json = Json.parse(text).getOrElse(Json.Str(text))

/** One ask turn of a worker, with the shape check and the wait for a decision that go with it. */
private[blueprint] object Turns:

  /**
   * How a turn came out. `Interrupted`: the run was cancelled or passed its deadline while the turn
   * waited.
   */
  sealed trait Outcome
  final case class Answered(value: Json)  extends Outcome
  final case class Failed(reason: String) extends Outcome
  case object Interrupted                 extends Outcome

  /** Model calls a session has recorded: one per answer the model gave. */
  def modelCalls(history: SessionHistory): Int =
    history.messages.count {
      case _: SessionMessage.AiMessage => true
      case _                           => false
    }

  /**
   * The answer the session holds for `message`, when the last turn asked it and ended. A turn's
   * messages reach the history only when it ends, so the last assistant message after the user
   * message is the answer; the ones before it asked for tools.
   */
  private def answered(history: SessionHistory, message: String): Option[String] =
    val ms = history.messages
    ms.lastIndexWhere {
      case SessionMessage.UserMessage(_, text, _) => text == message
      case _                                      => false
    } match
      case -1 => None
      case i =>
        ms.drop(i + 1)
          .collect { case SessionMessage.AiMessage(_, text, _, calls) if calls.isEmpty => text }
          .lastOption

  /**
   * The worker's answer for one turn, as JSON of the step's shape. An answer already in the session
   * for this message is taken without a call (R18). An answer not of the shape goes back to the
   * worker with the problems, while the worker's budget of model calls for this turn lasts. A tool
   * that waits for a decision makes the run wait.
   */
  def answer(
      worker: RunWorker,
      session: String,
      definition: Worker,
      message: String,
      shape: Shape,
      ref: RunRef,
      stepName: String
  ): Outcome =
    var asked                    = message
    var attempts                 = 0
    var outcome: Option[Outcome] = None
    while outcome.isEmpty do
      val text: Either[Outcome, String] =
        answered(worker.history(session), asked) match
          case Some(found) => Right(found)
          case None =>
            try
              worker
                .agent(session)
                .ask(AskAgent.turn)
                .invoke(WorkerTurn(definition, asked, shape, ref)) match
                case AgentOutcome.Answered(value) => Right(value)
                case AgentOutcome.AwaitingApproval(requests) =>
                  if worker.waitForDecision(stepName, session, requests) then
                    // The decision resumed the turn on its session; the answer, if the turn ended, is there.
                    answered(worker.history(session), asked)
                      .toRight(Failed("the turn did not end after the decision"))
                  else Left(Interrupted)
            catch case e: CommandError => Left(Failed(e.getMessage))
      text match
        case Left(ended) => outcome = Some(ended)
        case Right(value) =>
          attempts += 1
          Json.parse(value) match
            case Left(_) if attempts < definition.budget =>
              asked =
                s"$message\n\nYour last answer was not JSON. Answer with JSON of the shape asked for."
            case Left(_) => outcome = Some(Failed("the worker did not answer with JSON"))
            case Right(json) =>
              val problems = shape.check(json)
              if problems.isEmpty then outcome = Some(Answered(json))
              else if attempts < definition.budget then
                asked =
                  s"$message\n\nYour last answer did not have the shape asked for:\n${problems.mkString("\n")}\nAnswer again."
              else
                outcome = Some(
                  Failed(
                    s"the worker's answer did not have the step's shape after ${definition.budget} attempts: ${problems.mkString("; ")}"
                  )
                )
    outcome.get
