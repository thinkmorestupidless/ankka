// A small event sourced entity the SDK's own tests drive: a counter whose handlers cover every effect.
import { EventSourcedEntity } from "../../src/eventSourcedEntity.ts"
import { command, query } from "../../src/handlers.ts"
import { jsonCodec } from "../../src/codec.ts"
import { Done, done, s } from "../../src/schema.ts"
import { ErrorCode } from "../../src/effects/common.ts"
import { Duration } from "../../src/time.ts"

export const CounterState = s.record("CounterState", { value: s.int, history: s.list(s.int) })
export type CounterState = typeof CounterState extends { _type?: infer T } ? T : never

export const CounterEvent = s.sumType("CounterEvent", { Incremented: { by: s.int }, Reset: {} })

export class Counter extends EventSourcedEntity<CounterState, typeof CounterEvent extends { _type?: infer T } ? T : never> {
  static readonly componentId = "counter"
  static readonly state = jsonCodec(CounterState, "counter")
  static readonly events = jsonCodec(CounterEvent, "counter-event")
  static readonly snapshotEvery = 3

  static readonly handlers = {
    increment: command("increment", s.int, s.int, (c: Counter, by) => c.increment(by)),
    incrementAsync: command("increment-async", s.int, s.int, async (c: Counter, by) => {
      await new Promise((r) => setTimeout(r, 1))
      return c.increment(by)
    }),
    get: query("get", s.int, (c: Counter) => c.effects.reply(c.state.value)),
    getState: query("get-state", CounterState, (c: Counter) => c.effects.reply(c.state)),
    refuse: command("refuse", Done, (c: Counter) => c.effects.error("no", ErrorCode.Conflict)),
    noReply: command("no-reply", Done, (c: Counter) => c.effects.persist({ type: "Incremented", by: 1 }).thenNoReply()),
    reset: command("reset", CounterState, (c: Counter) => c.effects.persist({ type: "Reset" }).thenReplyState()),
    remove: command("remove", Done, (c: Counter) => c.effects.deleteEntity().thenReply(() => done)),
    expire: command("expire", Done, (c: Counter) => c.effects.expireAfter(Duration.ofSeconds(1)).thenReply(() => done)),
    boom: command("boom", Done, (): never => {
      throw new Error("kaboom")
    }),
    whoAmI: query("who-am-i", s.string, (c: Counter) => c.effects.reply(`${c.context.componentId}/${c.entityId}@${c.context.sequenceNumber}`)),
  }

  emptyState(): CounterState {
    return { value: 0, history: [] }
  }

  applyEvent(state: CounterState, event: { type: "Incremented"; by: number } | { type: "Reset" }): CounterState {
    switch (event.type) {
      case "Incremented":
        return { value: state.value + event.by, history: [...state.history, event.by] }
      case "Reset":
        return { value: 0, history: [] }
    }
  }

  increment(by: number) {
    if (by <= 0) return this.effects.error("by must be positive", ErrorCode.BadRequest)
    return this.effects.persist({ type: "Incremented", by }).thenReply((state) => state.value)
  }
}
