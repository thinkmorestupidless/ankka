// A timed action: a call the platform makes later. Scheduled through `client.timers`; the sidecar's
// sweeper delivers it here, retrying a failure with backoff.
//
//   static readonly actions = { remind: action("remind", s.string, (t: Reminder, id) => t.remind(id)) }

import type { Metadata } from "./effects/common.ts"
import type { ComponentClient } from "./client.ts"
import type { HandlerTable } from "./handlers.ts"
import { TimedActionEffects } from "./effects/stateless.ts"

export abstract class TimedAction {
  readonly effects: TimedActionEffects = new TimedActionEffects()

  #metadata: Metadata = {}
  #client: ComponentClient | undefined

  /** `ankka.timer` is the timer's id, `ankka.attempts` how many times it has fired. */
  get metadata(): Metadata {
    return this.#metadata
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside an action")
    return this.#client
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
