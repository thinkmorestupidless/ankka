// The trip planner of the documentation: a selector that says which specialists a request needs, the
// specialists, a summariser that reads the shared session back, and the workflow that coordinates them.
// Session memory, the loop and the model are the sidecar's.
import { Ankka, Agent, Done, done, Duration, ErrorCode, KeyValueEntity, Workflow, command, jsonCodec, query, s, step, tool, workflowSettings, type Infer } from "ankka"

export const Preferences = s.record("Preferences", {
  userId: s.string,
  likes: s.list(s.string),
  dislikes: s.list(s.string),
  maxBudget: s.int,
})
export type Preferences = Infer<typeof Preferences>

export function summary(p: Preferences): string {
  if (p.likes.length === 0 && p.dislikes.length === 0) return "no stated preferences"
  const liked = p.likes.length === 0 ? "" : `likes ${p.likes.join(", ")}`
  const disliked = p.dislikes.length === 0 ? "" : `dislikes ${p.dislikes.join(", ")}`
  return [liked, disliked, `budget ${p.maxBudget}`].filter((part) => part !== "").join("; ")
}

/** A user's stated preferences, so an agent can be given context it did not ask for. */
export class PreferencesEntity extends KeyValueEntity<Preferences> {
  static readonly componentId = "preferences"
  static readonly state = jsonCodec(Preferences, "preferences")

  static readonly handlers = {
    set: command("set", Preferences, Done, (p: PreferencesEntity, preferences) =>
      preferences.maxBudget < 0
        ? p.effects.error("budget cannot be negative")
        : p.effects.updateState({ ...preferences, userId: p.entityId }).thenReply(() => done),
    ),
    get: query("get", Preferences, (p: PreferencesEntity) => p.effects.reply(p.state)),
  }

  emptyState(): Preferences {
    return { userId: this.entityId, likes: [], dislikes: [], maxBudget: 0 }
  }
}

/** The specialists a plan may consult, by the role each agent declares. */
export const Specialist = { Weather: "weather", Activity: "activity", Budget: "budget", All: ["weather", "activity", "budget"] } as const

export const AgentSelection = s.record("AgentSelection", { specialists: s.list(s.string), reason: s.string })
export type AgentSelection = Infer<typeof AgentSelection>

// docs:start selector
export class SelectorAgent extends Agent {
  static readonly componentId = "selector-agent"

  static readonly handlers = {
    select: command("select", s.string, AgentSelection, (a: SelectorAgent, request) => a.select(request)),
  }

  select(request: string) {
    if (request.trim() === "") return this.effects.error("nothing to plan", ErrorCode.BadRequest)
    return (
      this.effects
        .systemMessage(
          `You route planning requests to specialists.
Available specialists: ${Specialist.All.join(", ")}.
Reply with JSON: {"specialists":["..."],"reason":"..."}.
Choose only the specialists the request genuinely needs.`,
        )
        .userMessage(request)
        // The selector's own deliberation is not part of the conversation the specialists and
        // summariser share.
        .memory(false)
        .thenReplyJson<AgentSelection>()
    )
  }
}
// docs:end selector

const GetForecast = s.record("GetForecast", { destination: s.string })

/** The weather specialist, with a tool: it takes two model turns. */
export class ForecastAgent extends Agent {
  static readonly componentId = "weather-agent"
  static readonly role = Specialist.Weather

  static readonly tools = {
    getForecast: tool("get_forecast", "The forecast for a destination.", GetForecast, (_a: ForecastAgent, input) => `${input.destination}: mild, occasional rain`),
  }

  static readonly handlers = {
    consult: command("consult", s.string, s.string, (a: ForecastAgent, destination) =>
      a.effects
        .systemMessage("You are a concise weather specialist. Use the forecast tool, then answer in one sentence.")
        .userMessage(`What is the weather like in ${destination}?`)
        .tools("get_forecast")
        .thenReply(),
    ),
  }
}

// docs:start activity
const ActivityRequest = s.record("ActivityRequest", { userId: s.string, destination: s.string })
export type ActivityRequest = Infer<typeof ActivityRequest>

export class ActivityAgent extends Agent {
  static readonly componentId = "activity-agent"
  static readonly role = Specialist.Activity

  static readonly handlers = {
    consult: command("consult", ActivityRequest, s.string, (a: ActivityAgent, request) => a.consult(request)),
  }

  async consult(request: ActivityRequest) {
    // `client` is inherited from `Agent`; no constructor plumbing needed.
    const preferences = await this.client.of(PreferencesEntity, request.userId).call(PreferencesEntity.handlers.get).invoke()

    return this.effects
      .systemMessage("You are a concise activity specialist. Suggest two activities, in one sentence.")
      .userMessage(`What should I do in ${request.destination}?`)
      // Preferences are context, not something the user said — so memory records the
      // question, not the whole assembled prompt.
      .withContext(`Traveller preferences: ${summary(preferences)}`)
      .thenReply()
  }
}
// docs:end activity

export class BudgetAgent extends Agent {
  static readonly componentId = "budget-agent"
  static readonly role = Specialist.Budget

  static readonly handlers = {
    consult: command("consult", s.string, s.string, (a: BudgetAgent, destination) =>
      a.effects.systemMessage("You are a concise budget specialist. Give a daily figure in one sentence.").userMessage(`What does a day in ${destination} cost?`).thenReply(),
    ),
  }
}

// docs:start summary
export class SummaryAgent extends Agent {
  static readonly componentId = "summary-agent"

  static readonly handlers = {
    summarise: command("summarise", s.string, s.string, (a: SummaryAgent, destination) =>
      a.effects
        .systemMessage("You write short trip briefs. Combine what the specialists said into two sentences.")
        .userMessage(`Write a brief for a trip to ${destination}.`)
        // The session is shared with the specialists, so their contributions are the history this
        // turn reads; the selector kept its routing out of it with `memory(false)`.
        .memory(true)
        .thenReply(),
    ),
  }
}
// docs:end summary

export const Contribution = s.record("Contribution", { specialist: s.string, text: s.string })
export type Contribution = Infer<typeof Contribution>
export const PlanState = s.record("PlanState", {
  userId: s.string,
  destination: s.string,
  status: s.string,
  selection: s.option(AgentSelection),
  contributions: s.list(Contribution),
  summary: s.option(s.string),
})
export type PlanState = Infer<typeof PlanState>
export const Start = s.record("Start", { userId: s.string, destination: s.string })
export type Start = Infer<typeof Start>

export class PlannerWorkflow extends Workflow<PlanState> {
  static readonly componentId = "planner"
  static readonly state = jsonCodec(PlanState, "plan-state")

  // docs:start settings
  static readonly settings = workflowSettings({
    timeout: Duration.ofMinutes(5),
    // Model calls are slow, and a tool loop can legitimately take a while.
    defaultStepTimeout: Duration.ofSeconds(90),
    defaultRecovery: { maxRetries: 1 },
  })
  // docs:end settings

  static readonly handlers = {
    start: command("start", Start, Done, (w: PlannerWorkflow, start) =>
      w.effects.updateState({ ...w.state, userId: start.userId, destination: start.destination, status: "selecting" }).thenTransitionTo("select").thenReply(() => done),
    ),
    plan: query("plan", PlanState, (w: PlannerWorkflow) => w.effects.reply(w.state)),
  }

  static readonly steps = {
    select: step("select", (w: PlannerWorkflow) => w.selectSpecialistsStep()),
    consult: step("consult", (w: PlannerWorkflow) => w.consultSpecialistsStep()),
    summarise: step("summarise", (w: PlannerWorkflow) => w.summariseStep()),
  }

  emptyState(): PlanState {
    return { userId: "", destination: "", status: "new", selection: null, contributions: [], summary: null }
  }

  // docs:start session
  /** Every agent in a plan is addressed with the same session id, the workflow's own id. */
  get session(): string {
    return this.entityId
  }
  // docs:end session

  // docs:start select
  /** Asks the selector which specialists this request needs. */
  async selectSpecialistsStep() {
    const selection = await this.client
      .of(SelectorAgent, this.session)
      .call(SelectorAgent.handlers.select)
      .invoke(`Plan a trip to ${this.state.destination}`)

    // Ignore anything the model invented that is not a real specialist.
    const known = selection.specialists.filter((name) => (Specialist.All as readonly string[]).includes(name))
    // A selector that names nothing usable should not stall the plan.
    const chosen = known.length > 0 ? known : [Specialist.Activity]

    return this.stepEffects
      .updateState({ ...this.state, status: "consulting", selection: { ...selection, specialists: chosen } })
      .thenTransitionTo("consult")
  }
  // docs:end select

  // docs:start parallel
  /**
   * Consults every chosen specialist at once.
   *
   * `Promise.all` rather than one `await` after another: the specialists are independent, so waiting
   * for each in turn would make the step as slow as the sum of the model calls instead of the slowest.
   */
  async consultSpecialistsStep() {
    const { userId, destination } = this.state
    const chosen = this.state.selection?.specialists ?? []

    const answer = (specialist: string): Promise<string> => {
      switch (specialist) {
        case Specialist.Weather:
          return this.client.of(ForecastAgent, this.session).call(ForecastAgent.handlers.consult).invoke(destination)
        case Specialist.Activity:
          return this.client.of(ActivityAgent, this.session).call(ActivityAgent.handlers.consult).invoke({ userId, destination })
        case Specialist.Budget:
          return this.client.of(BudgetAgent, this.session).call(BudgetAgent.handlers.consult).invoke(destination)
        default:
          return Promise.resolve(`no specialist named '${specialist}'`)
      }
    }
    const answers = await Promise.all(chosen.map(answer))
    const contributions = chosen.map((specialist, i) => ({ specialist, text: answers[i]! }))

    return this.stepEffects.updateState({ ...this.state, contributions }).thenTransitionTo("summarise")
  }
  // docs:end parallel

  // docs:start summarise
  /** Combines the contributions, reading them back from the shared session. */
  async summariseStep() {
    const brief = await this.client
      .of(SummaryAgent, this.session)
      .call(SummaryAgent.handlers.summarise)
      .invoke(this.state.destination)

    return this.stepEffects.updateState({ ...this.state, status: "completed", summary: brief }).thenEnd()
  }
  // docs:end summarise
}

// docs:start registration
// The agents are registered as any component is. The model is the sidecar's: `ANTHROPIC_API_KEY` or
// `OPENAI_API_KEY` in the descriptor's environment configures its provider, and no key lives here.
export function plannerService() {
  return Ankka.service()
    .register(PreferencesEntity)
    .register(PlannerWorkflow)
    .register(SelectorAgent)
    .register(ForecastAgent)
    .register(ActivityAgent)
    .register(BudgetAgent)
    .register(SummaryAgent)
}

if (import.meta.main) {
  await plannerService().listen()
}
// docs:end registration
