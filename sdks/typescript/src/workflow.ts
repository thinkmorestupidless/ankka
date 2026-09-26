// A workflow: a durable multi-step process. Commands change state and start steps; steps run in this
// process, one at a time per instance, and say what happens next. The sidecar journals every
// transition and drives the steps, retries and timeouts — a step that throws is retried and eventually
// compensated as `settings` declares.
//
//   static readonly steps = { reserve: step("reserve", (w: CheckoutWorkflow) => w.reserve()), charge: step("charge", s.int, (w, q) => w.charge(q)) }
//   static readonly settings = workflowSettings({ steps: { charge: { recovery: { maxRetries: 1, failoverTo: "compensate" } } } })

import type { Shape } from "./codec.ts"
import type { CommandContext } from "./context.ts"
import type { HandlerTable } from "./handlers.ts"
import type { ComponentClient } from "./client.ts"
import { StepEffects, WorkflowEffects, type WorkflowSettings } from "./effects/workflow.ts"

export abstract class Workflow<S> {
  /** Inside a command handler. */
  readonly effects: WorkflowEffects<S> = new WorkflowEffects<S>()
  /** Inside a step. */
  readonly stepEffects: StepEffects<S> = new StepEffects<S>()

  #state: S | undefined
  #entityId: string | undefined
  #context: CommandContext | undefined
  #client: ComponentClient | undefined
  #bound = false

  get state(): S {
    if (!this.#bound) throw new Error("state is only available inside a handler or a step")
    return this.#state as S
  }

  get entityId(): string {
    if (this.#entityId === undefined) throw new Error("entityId is only available once the workflow is bound to an instance")
    return this.#entityId
  }

  get context(): CommandContext {
    if (!this.#context) throw new Error("context is only available inside a handler or a step")
    return this.#context
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside a handler or a step")
    return this.#client
  }

  /** @internal */
  get _kind(): "workflow" {
    return "workflow"
  }

  abstract emptyState(): S

  /** @internal */
  _bindInstance(entityId: string): void {
    this.#entityId = entityId
  }

  /** @internal */
  _bindCommand(state: S, context: CommandContext, client: ComponentClient): void {
    this.#state = state
    this.#context = context
    this.#client = client
    this.#bound = true
  }

  /** @internal */
  _unbindCommand(): void {
    this.#state = undefined
    this.#context = undefined
    this.#client = undefined
    this.#bound = false
  }
}

export interface WorkflowClass<S = unknown, C extends Workflow<S> = Workflow<S>> {
  new (): C
  readonly componentId: string
  readonly state: Shape<S>
  readonly handlers: HandlerTable<C>
  readonly steps: HandlerTable<C>
  readonly settings?: WorkflowSettings
}
