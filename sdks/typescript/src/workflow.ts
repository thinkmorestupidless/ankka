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
import { secretsFor, type ComponentClient, type Secrets } from "./client.ts"
import { CommandError } from "./effects/common.ts"
import { StepEffects, WorkflowEffects, type WorkflowSettings } from "./effects/workflow.ts"
import { servicesFor, type Services } from "./services.ts"

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

  #inStep = false
  #secrets: Secrets | undefined

  /**
   * The service's secret store, in a step. A command handler is refused: it would put a database read
   * on the workflow's single-writer path, as an entity's would.
   */
  get secrets(): Secrets {
    if (!this.#inStep) {
      throw new CommandError({ message: "a workflow reads and keeps a service secret in a step, not in a command handler", code: "BAD_REQUEST" })
    }
    return this.#secrets ?? secretsFor(this.client)
  }

  /** A unit test's store in place of the runtime's. */
  set secrets(store: Secrets) {
    this.#secrets = store
  }

  #services: Services | undefined

  /**
   * Other services, called as this one, in a step. A command handler is refused, as for the secret
   * store: it would block the workflow's other commands behind another service.
   */
  get services(): Services {
    if (!this.#inStep) {
      throw new CommandError({ message: "a workflow calls another service in a step, not in a command handler", code: "BAD_REQUEST" })
    }
    return this.#services ?? servicesFor(this.client)
  }

  /** A unit test's in place of the runtime's. */
  set services(services: Services) {
    this.#services = services
  }

  /** @internal The server marks a step's binding, which is how a step is told from a command. */
  _enterStep(): void {
    this.#inStep = true
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
    this.#inStep = false
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
