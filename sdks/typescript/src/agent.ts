// An agent: this process declares the instructions, the tools and the guardrails; the sidecar runs the
// loop — the model call, tool dispatch, session memory, compaction and token accounting. The process
// is asked to plan an interaction, to run a tool and to check a guardrail, and nothing else.
//
//   static readonly tools = { lookup: tool("lookup", "Looks up a cart by id.", CartLookup, (a: Assistant, input) => a.lookup(input.cartId)) }
//   static readonly guardrails = { noSecrets: guardrail("no-secrets", (stage, text) => text.includes("sk-") ? "a key leaked" : null) }
//   static readonly handlers = { ask: command("ask", s.string, s.string, (a: Assistant, q) => a.ask(q)), chat: stream("chat", s.string, (a: Assistant, q) => a.ask(q)) }

import type { Metadata } from "./effects/common.ts"
import { secretsFor, type ComponentClient, type Secrets } from "./client.ts"
import type { GuardrailRef, HandlerTable, ToolRef } from "./handlers.ts"
import { AgentEffects } from "./effects/agent.ts"
import { servicesFor, type Services } from "./services.ts"

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

  #secrets: Secrets | undefined

  /** The service's secret store: values kept encrypted in the service's own database, never in a journal or a view. */
  get secrets(): Secrets {
    return this.#secrets ?? secretsFor(this.client)
  }

  /** A unit test's store in place of the runtime's: `component.secrets = new InMemorySecrets()`. */
  set secrets(store: Secrets) {
    this.#secrets = store
  }

  #services: Services | undefined

  /** Other services, called as this one, through the runtime: `this.services.service("orders")`. */
  get services(): Services {
    return this.#services ?? servicesFor(this.client)
  }

  /** A unit test's in place of the runtime's: `component.services = new ScriptedServices()`. */
  set services(services: Services) {
    this.#services = services
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
