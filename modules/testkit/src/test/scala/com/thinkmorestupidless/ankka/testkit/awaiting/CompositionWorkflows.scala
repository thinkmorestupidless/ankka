package com.thinkmorestupidless.ankka.testkit.awaiting

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

final case class Kyc(applicant: String, steps: Vector[String], decision: Option[String])

/**
 * A check of an applicant over two steps, `documents` and `decision`, each taking what the
 * applicant's `StepScript` says.
 */
final class KycWorkflow(context: WorkflowContext) extends Workflow[Kyc]:

  private val id = context.workflowId.toString

  def emptyState: Kyc = Kyc("", Vector.empty, None)

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(60.seconds).build

  def start(applicant: String): Effect[Done] =
    effects
      .updateState(currentState.copy(applicant = applicant))
      .transitionTo(KycWorkflow.documents)
      .thenReply(Done)

  def documentsStep: StepEffect =
    StepScript.perform(id, "documents")
    stepEffects
      .updateState(currentState.copy(steps = currentState.steps :+ "documents"))
      .thenTransitionTo(KycWorkflow.decision)

  def decisionStep: StepEffect =
    StepScript.perform(id, "decision")
    val decision = if currentState.applicant.startsWith("refused") then "declined" else "approved"
    stepEffects
      .updateState(
        currentState.copy(steps = currentState.steps :+ "decision", decision = Some(decision))
      )
      .thenEnd

object KycWorkflow
    extends Workflow.Companion[KycWorkflow, Kyc](ComponentId("kyc"), Codecs.serializer[Kyc]("kyc")):
  def create(context: WorkflowContext) = new KycWorkflow(context)

  val documents = step("documents")(_.documentsStep)
  val decision  = step("decision")(_.decisionStep)
  val start     = command("start")(_.start)

final case class Onboarding(applicant: String, status: String)

/**
 * Onboards an applicant: its `verify` step starts a `kyc` workflow for the applicant and waits for
 * its end within the step's own timeout, then goes on to the step the kyc's decision chooses.
 */
final class OnboardingWorkflow(context: WorkflowContext) extends Workflow[Onboarding]:

  private val id = context.workflowId.toString

  def emptyState: Onboarding = Onboarding("", "not-started")

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(60.seconds)
      .stepTimeout(OnboardingWorkflow.verify, OnboardingWorkflow.VerifyTimeout)
      .build

  def start(applicant: String): Effect[Done] =
    effects
      .updateState(Onboarding(applicant, "verifying"))
      .transitionTo(OnboardingWorkflow.verify)
      .thenReply(Done)

  // docs:start step-awaits
  def verifyStep: StepEffect =
    val kyc = context.componentClient
      .forWorkflow(EntityId(s"kyc-$id"))
      .call(KycWorkflow.start)
      .thenAwaitEnd(OnboardingWorkflow.VerifyTimeout)
      .invoke(currentState.applicant)
    if kyc.decision.contains("approved") then
      stepEffects.thenTransitionTo(OnboardingWorkflow.welcome)
    else stepEffects.thenTransitionTo(OnboardingWorkflow.decline)
  // docs:end step-awaits

  def welcomeStep: StepEffect =
    stepEffects.updateState(currentState.copy(status = "welcomed")).thenEnd

  def declineStep: StepEffect =
    stepEffects.updateState(currentState.copy(status = "declined")).thenEnd

  def status: ReadOnlyEffect[Onboarding] = effects.reply(currentState)

object OnboardingWorkflow
    extends Workflow.Companion[OnboardingWorkflow, Onboarding](
      ComponentId("onboarding"),
      Codecs.serializer[Onboarding]("onboarding")
    ):
  val VerifyTimeout: FiniteDuration = 2.seconds

  def create(context: WorkflowContext) = new OnboardingWorkflow(context)

  val verify  = step("verify")(_.verifyStep)
  val welcome = step("welcome")(_.welcomeStep)
  val decline = step("decline")(_.declineStep)
  val start   = command("start")(_.start)
  val status  = query("status")(_.status)
