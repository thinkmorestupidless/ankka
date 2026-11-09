// One workflow waiting for another: onboarding starts a KYC check and waits for its end from a step.
import { Done, done, Workflow, command, jsonCodec, query, s, step, type Infer } from "ankka"

export const Kyc = s.record("Kyc", { applicant: s.string, decision: s.option(s.string) })
export type Kyc = Infer<typeof Kyc>

/** Decides on an applicant: approved unless the name says otherwise. */
export class KycWorkflow extends Workflow<Kyc> {
  static readonly componentId = "kyc"
  static readonly state = jsonCodec(Kyc, "kyc")

  static readonly handlers = {
    start: command("start", s.string, Done, (w: KycWorkflow, applicant) =>
      w.effects.updateState({ applicant, decision: null }).thenTransitionTo("decide").thenReply(() => done),
    ),
    status: query("status", Kyc, (w: KycWorkflow) => w.effects.reply(w.state)),
  }

  static readonly steps = {
    decide: step("decide", (w: KycWorkflow) =>
      w.stepEffects.updateState({ ...w.state, decision: w.state.applicant.includes("fraud") ? "declined" : "approved" }).thenEnd(),
    ),
  }

  emptyState(): Kyc {
    return { applicant: "", decision: null }
  }
}

export const Onboarding = s.record("Onboarding", { applicant: s.string, status: s.string })
export type Onboarding = Infer<typeof Onboarding>

export class OnboardingWorkflow extends Workflow<Onboarding> {
  static readonly componentId = "onboarding"
  static readonly state = jsonCodec(Onboarding, "onboarding")
  static readonly VerifyTimeout = 60_000

  static readonly handlers = {
    start: command("start", s.string, Done, (w: OnboardingWorkflow, applicant) =>
      w.effects.updateState({ applicant, status: "verifying" }).thenTransitionTo("verify").thenReply(() => done),
    ),
    status: query("status", Onboarding, (w: OnboardingWorkflow) => w.effects.reply(w.state)),
  }

  static readonly steps = {
    verify: step("verify", (w: OnboardingWorkflow) => w.verifyStep()),
    welcome: step("welcome", (w: OnboardingWorkflow) => w.stepEffects.updateState({ ...w.state, status: "welcomed" }).thenEnd()),
    decline: step("decline", (w: OnboardingWorkflow) => w.stepEffects.updateState({ ...w.state, status: "declined" }).thenEnd()),
  }

  emptyState(): Onboarding {
    return { applicant: "", status: "new" }
  }

  // docs:start step-awaits
  async verifyStep() {
    const kyc = await this.client
      .of(KycWorkflow, `kyc-${this.entityId}`)
      .call(KycWorkflow.handlers.start)
      .thenAwaitEnd<Kyc>(OnboardingWorkflow.VerifyTimeout)
      .invoke(this.state.applicant)
    return this.stepEffects.transitionTo(kyc.decision === "approved" ? "welcome" : "decline")
  }
  // docs:end step-awaits
}
