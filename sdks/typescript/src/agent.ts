// An agent: this process declares the instructions, the tools and the guardrails; the sidecar runs the
// loop — the model call, tool dispatch, session memory, compaction and token accounting. The process
// is asked to plan an interaction, to run a tool and to check a guardrail, and nothing else.
//
//   static readonly tools = { lookup: tool("lookup", "Looks up a cart by id.", CartLookup, (a: Assistant, input) => a.lookup(input.cartId)) }
//   static readonly guardrails = { noSecrets: guardrail("no-secrets", (stage, text) => text.includes("sk-") ? "a key leaked" : null) }
//   static readonly handlers = { ask: command("ask", s.string, s.string, (a: Assistant, q) => a.ask(q)), chat: stream("chat", s.string, (a: Assistant, q) => a.ask(q)) }

import type { Metadata } from "./effects/common.ts"
import type { ComponentClient } from "./client.ts"
import type { GuardrailRef, HandlerTable, ToolRef } from "./handlers.ts"
import { AgentEffects } from "./effects/agent.ts"

export abstract class Agent {
  readonly effects: AgentEffects = new AgentEffects()

  #sessionId = ""
  #metadata: Metadata = {}
  #client: ComponentClient | undefined

  /** The session this request belongs to. Captured when the plan is made; tools run after the handler has returned. */
  get sessionId(): string {
    return this.#sessionId
  }

  get metadata(): Metadata {
    return this.#metadata
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside a handler, a tool or a guardrail")
    return this.#client
  }

  /** @internal */
  get _kind(): "agent" {
    return "agent"
  }

  /** @internal */
  _bind(sessionId: string, metadata: Metadata, client: ComponentClient): void {
    this.#sessionId = sessionId
    this.#metadata = metadata
    this.#client = client
  }
}

export interface AgentClass<C extends Agent = Agent> {
  new (): C
  readonly componentId: string
  /** A description of the agent, for the console and the model's system context. */
  readonly role?: string
  /** How many tool calls one turn may make; 100 by default. */
  readonly maxToolCallSteps?: number
  readonly tools?: Readonly<Record<string, ToolRef<C, any>>>
  readonly guardrails?: Readonly<Record<string, GuardrailRef>>
  readonly handlers: HandlerTable<C>
}
