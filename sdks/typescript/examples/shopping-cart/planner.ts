// The trip planner of the documentation: a user's preferences, and a specialist that reads them to
// enrich its own context. Session memory, the loop and the model are the sidecar's.
import { Ankka, Agent, Done, done, KeyValueEntity, command, jsonCodec, query, s, type Infer } from "ankka"

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

// docs:start activity
const ActivityRequest = s.record("ActivityRequest", { userId: s.string, destination: s.string })

export class ActivityAgent extends Agent {
  static readonly componentId = "activity-agent"
  static readonly role = "activity"

  static readonly handlers = {
    consult: command("consult", ActivityRequest, s.string, (a: ActivityAgent, request) => a.consult(request)),
  }

  async consult(request: Infer<typeof ActivityRequest>) {
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

// docs:start registration
// The agents are registered as any component is. The model is the sidecar's: `ANTHROPIC_API_KEY` or
// `OPENAI_API_KEY` in the descriptor's environment configures its provider, and no key lives here.
export function plannerService() {
  return Ankka.service().register(PreferencesEntity).register(ActivityAgent)
}

if (import.meta.main) {
  await plannerService().listen()
}
// docs:end registration
