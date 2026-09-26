// Effects for agents: a description of one interaction with a model — which model, what instructions,
// which tools and guardrails, what memory — as data. Building one calls no model; the sidecar's loop
// interprets it, calling back for tools and guardrails. The model's key never lives in this process.

import { ErrorCode, type EffectLike, type ErrorDetail } from "./common.ts"

export interface AgentEffect<R = string> extends EffectLike<R> {
  readonly kind: "agent"
  /** A model by the name the sidecar configured; absent, the sidecar's default. */
  readonly model: string | null
  readonly system: string | null
  readonly user: string | null
  readonly context: readonly string[]
  readonly sessionMemory: boolean
  readonly toolNames: readonly string[]
  readonly guardrailNames: readonly string[]
  readonly jsonReply: boolean
  readonly schemaHint: string
  /** `effects.error(...)`: a refusal before any model call. */
  readonly failure: ErrorDetail | null
  withModel(name: string): AgentEffect<R>
  systemMessage(text: string): AgentEffect<R>
  userMessage(text: string): AgentEffect<R>
  /** Context the user did not type — retrieved documents, an entity's state — kept apart from the user message so memory records what the user said. */
  withContext(text: string): AgentEffect<R>
  /** `false`: no memory at all, for a one-shot classification. */
  memory(session: boolean): AgentEffect<R>
  tools(...names: string[]): AgentEffect<R>
  guardrails(...names: string[]): AgentEffect<R>
  /** Reply with the model's text. */
  thenReply(): AgentEffect<string>
  /** Reply with the model's JSON, decoded by the caller's reply shape `R`. The schema is not sent to the model — say what you want in the system message. */
  thenReplyJson<J = unknown>(schemaHint?: string): AgentEffect<J>
}

type Fields = Omit<AgentEffect<unknown>, "kind" | "withModel" | "systemMessage" | "userMessage" | "withContext" | "memory" | "tools" | "guardrails" | "thenReply" | "thenReplyJson" | "_reply">

const EMPTY: Fields = {
  model: null,
  system: null,
  user: null,
  context: [],
  sessionMemory: true,
  toolNames: [],
  guardrailNames: [],
  jsonReply: false,
  schemaHint: "",
  failure: null,
}

function make<R>(fields: Fields): AgentEffect<R> {
  const f = { ...fields, context: Object.freeze([...fields.context]), toolNames: Object.freeze([...fields.toolNames]), guardrailNames: Object.freeze([...fields.guardrailNames]) }
  return Object.freeze({
    kind: "agent" as const,
    ...f,
    withModel: (name: string) => make<R>({ ...f, model: name }),
    systemMessage: (text: string) => make<R>({ ...f, system: text }),
    userMessage: (text: string) => make<R>({ ...f, user: text }),
    withContext: (text: string) => make<R>({ ...f, context: [...f.context, text] }),
    memory: (session: boolean) => make<R>({ ...f, sessionMemory: session }),
    tools: (...names: string[]) => make<R>({ ...f, toolNames: [...f.toolNames, ...names] }),
    guardrails: (...names: string[]) => make<R>({ ...f, guardrailNames: [...f.guardrailNames, ...names] }),
    thenReply: () => make<string>({ ...f, jsonReply: false }),
    thenReplyJson: <J = unknown>(schemaHint = "JSON") => make<J>({ ...f, jsonReply: true, schemaHint }),
  }) as AgentEffect<R>
}

/** Inside an agent's handler: `this.effects`. */
export class AgentEffects {
  model(name: string): AgentEffect<string> {
    return make<string>({ ...EMPTY, model: name })
  }
  systemMessage(text: string): AgentEffect<string> {
    return make<string>({ ...EMPTY, system: text })
  }
  userMessage(text: string): AgentEffect<string> {
    return make<string>({ ...EMPTY, user: text })
  }
  /** Reject the request without calling a model. */
  error(message: string, code: ErrorCode = ErrorCode.BadRequest): AgentEffect<never> {
    return make<never>({ ...EMPTY, failure: { message, code } })
  }
}
