// A timed action: a call the platform makes later. Scheduled through `client.timers`; the sidecar's
// sweeper delivers it here, retrying a failure with backoff.
//
//   static readonly actions = { remind: action("remind", s.string, (t: Reminder, id) => t.remind(id)) }

import type { Metadata } from "./effects/common.ts"
import { secretsFor, type ComponentClient, type Secrets } from "./client.ts"
import type { HandlerTable } from "./handlers.ts"
import { TimedActionEffects } from "./effects/stateless.ts"
import { servicesFor, type Services } from "./services.ts"

export abstract class TimedAction {
  readonly effects: TimedActionEffects = new TimedActionEffects()

  #metadata: Metadata = {}
  #client: ComponentClient | undefined

  /** `ankka.timer` is the timer's id, `ankka.attempts` how many times it has fired, `ankka.due` the due time in epoch milliseconds. */
  get metadata(): Metadata {
    return this.#metadata
  }

  /**
   * The due time this run is for, from `ankka.due`: the same on every retry of one due time, and for a
   * recurring timer a whole number of periods after the one before. `undefined` on a runtime older
   * than protocol 1.12, which does not send it.
   */
  get dueTime(): Date | undefined {
    const raw = this.#metadata["ankka.due"]
    if (raw === undefined || !/^-?\d+$/.test(raw.trim())) return undefined
    const date = new Date(Number(raw.trim()))
    return Number.isNaN(date.getTime()) ? undefined : date
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside an action")
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
  get _kind(): "timed-action" {
    return "timed-action"
  }

  /** @internal */
  _bind(metadata: Metadata, client: ComponentClient): void {
    this.#metadata = metadata
    this.#client = client
  }
}

export interface TimedActionClass<C extends TimedAction = TimedAction> {
  new (): C
  readonly componentId: string
  readonly actions: HandlerTable<C>
}
