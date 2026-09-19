package planner.application

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import planner.domain.*

import scala.concurrent.duration.DurationInt

/**
 * Orchestrates the specialists into one plan.
 *
 * Three patterns in one workflow, which is the point of the sample:
 *
 *   - **dynamic** — the first step *asks* which specialists are needed rather than hard-coding
 *     them, so adding a specialist changes no orchestration code;
 *   - **parallel** — the second consults all of them at once with `invokeAsync`, because they do
 *     not depend on each other;
 *   - **durable** — each step's result is journalled before the next begins, so a crash between
 *     consulting and summarising resumes rather than re-consulting.
 *
 * Every agent is addressed with the *same session id* — the workflow's own id — so they accumulate
 * one shared conversation that the summariser can read back.
 */
final class PlannerWorkflow(context: WorkflowContext) extends Workflow[PlanState]:

  private val client  = context.componentClient
  private val session = SessionId(context.workflowId)

  def emptyState: PlanState = PlanState.empty

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .timeout(5.minutes)
      // Model calls are slow, and a tool loop can legitimately take a while.
      .defaultStepTimeout(90.seconds)
      .defaultRecovery(RecoverStrategy.maxRetries(1))
      .build

  def start(request: PlannerWorkflow.Start): Effect[Done] =
    if request.destination.isBlank then effects.error("a destination is required")
    else if currentState.status != PlanState.NotStarted then
      effects.error("this plan has already started", ErrorCode.Conflict)
    else
      effects
        .updateState(
          PlanState(request.userId, request.destination, PlanState.Selecting, None, Nil, None)
        )
        .transitionTo(PlannerWorkflow.selectSpecialists.withInput(request.destination))
        .thenReply(Done)

  /** Asks the selector which specialists this request needs. */
  def selectSpecialistsStep(destination: String): StepEffect =
    val selection = client
      .forAgent(session)
      .call(SelectorAgent.select)
      .invoke(s"Plan a trip to $destination")

    // Ignore anything the model invented that is not a real specialist.
    val known = selection.specialists.filter(Specialist.All.contains)
    val chosen =
      if known.nonEmpty then known
      // A selector that names nothing usable should not stall the plan.
      else List(Specialist.Activity)

    stepEffects
      .updateState(
        currentState.copy(
          status = PlanState.Consulting,
          selection = Some(selection.copy(specialists = chosen))
        )
      )
      .thenTransitionTo(PlannerWorkflow.consultSpecialists)

  /**
   * Consults every chosen specialist at once.
   *
   * `invokeAsync` rather than `invoke`: the specialists are independent, so waiting for each in
   * turn would make the step as slow as the sum of the model calls instead of the slowest one.
   */
  def consultSpecialistsStep: StepEffect =
    val state  = currentState
    val chosen = state.selection.map(_.specialists).getOrElse(Nil)

    val pending = chosen.map { specialist =>
      val answer = specialist match
        case Specialist.Weather =>
          client.forAgent(session).call(WeatherAgent.consult).invokeAsync(state.destination)
        case Specialist.Activity =>
          client
            .forAgent(session)
            .call(ActivityAgent.consult)
            .invokeAsync(ActivityAgent.Request(state.userId, state.destination))
        case Specialist.Budget =>
          client.forAgent(session).call(BudgetAgent.consult).invokeAsync(state.destination)
        case other =>
          scala.concurrent.Future.successful(s"no specialist named '$other'")
      specialist -> answer
    }

    val contributions = pending.map { (specialist, pendingAnswer) =>
      Contribution(
        specialist,
        ComponentClient.await(pendingAnswer, 90.seconds)
      )
    }

    stepEffects
      .updateState(currentState.copy(contributions = contributions))
      .thenTransitionTo(PlannerWorkflow.summarise)

  /** Combines the contributions, reading them back from the shared session. */
  def summariseStep: StepEffect =
    val brief = client
      .forAgent(session)
      .call(SummaryAgent.summarise)
      .invoke(currentState.destination)

    stepEffects
      .updateState(currentState.copy(status = PlanState.Completed, summary = Some(brief)))
      .thenEnd

  def plan: ReadOnlyEffect[PlanState] = effects.reply(currentState)

object PlannerWorkflow
    extends Workflow.Companion[PlannerWorkflow, PlanState](
      componentId = ComponentId("planner"),
      stateSerializer = Codecs.serializer[PlanState]("plan-state")
    ):

  final case class Start(userId: String, destination: String)

  given Serializer[Start]     = Codecs.serializer[Start]("plan-start")
  given Serializer[PlanState] = Codecs.serializer[PlanState]("plan-state")

  def create(context: WorkflowContext) = new PlannerWorkflow(context)

  val selectSpecialists  = step("select-specialists")(_.selectSpecialistsStep)
  val consultSpecialists = step("consult-specialists")(_.consultSpecialistsStep)
  val summarise          = step("summarise")(_.summariseStep)

  val start = command("start")(_.start)
  val plan  = query("plan")(_.plan)
