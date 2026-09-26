// One reduction per kind, shared by the server and the unit testkit, so a test and a deployment cannot
// disagree about what an effect means. This is the SDK's copy of the rule that puts
// `EventSourcedEffect.materialise` in `modules/core`: the reply is computed from the state *after* the
// events, and a refusal leaves everything as it was.

import type { ErrorDetail, Outcome, Retention } from "./effects/common.ts"
import type { EventSourcedEffect } from "./effects/eventSourced.ts"
import type { KeyValueEffect } from "./effects/keyValue.ts"
import type { StepEffect, StepOutcome, StepRef, WorkflowCommandEffect } from "./effects/workflow.ts"

/** What an effect amounts to, as values a test can assert on and the server can encode. */
export interface Materialised<S, E, R> {
  readonly events: readonly E[]
  readonly newState: S
  readonly retention: Retention | null
  /** The reply, when the outcome was a reply. */
  readonly reply: R | undefined
  /** The refusal, when the outcome was one. */
  readonly error: ErrorDetail | undefined
  /** True when the handler answered nothing. */
  readonly noReply: boolean
}

function outcomeOf<S, R>(outcome: Outcome<S, R>, state: S): Pick<Materialised<S, unknown, R>, "reply" | "error" | "noReply"> {
  switch (outcome.kind) {
    case "reply":
      return { reply: outcome.compute(state), error: undefined, noReply: false }
    case "no-reply":
      return { reply: undefined, error: undefined, noReply: true }
    case "fail":
      return { reply: undefined, error: outcome.error, noReply: false }
  }
}

export function materialiseEventSourced<S, E, R>(
  effect: EventSourcedEffect<S, E, R>,
  state: S,
  applyEvent: (state: S, event: E) => S,
): Materialised<S, E, R> {
  switch (effect.kind) {
    case "persist": {
      const after = effect.events.reduce(applyEvent, state)
      return Object.freeze({ events: effect.events, newState: after, retention: effect.retention, ...outcomeOf(effect.outcome, after) })
    }
    case "read-only":
      return Object.freeze({ events: [], newState: state, retention: effect.retention, ...outcomeOf(effect.outcome, state) })
    default:
      throw new TypeError(`not an event sourced effect: ${JSON.stringify((effect as { kind?: unknown }).kind)}`)
  }
}

/** A key value effect: `newState` is the state to keep (unchanged when the effect only replied or deleted). `changed` says whether to send it. */
export interface MaterialisedKeyValue<S, R> extends Materialised<S, never, R> {
  readonly changed: boolean
}

export function materialiseKeyValue<S, R>(effect: KeyValueEffect<S, R>, state: S): MaterialisedKeyValue<S, R> {
  switch (effect.kind) {
    case "update": {
      const after = effect.newState === null ? state : effect.newState
      return Object.freeze({ events: [], newState: after, changed: effect.newState !== null, retention: effect.retention, ...outcomeOf(effect.outcome, after) })
    }
    case "read-only":
      return Object.freeze({ events: [], newState: state, changed: false, retention: null, ...outcomeOf(effect.outcome, state) })
    default:
      throw new TypeError(`not a key value effect: ${JSON.stringify((effect as { kind?: unknown }).kind)}`)
  }
}

export interface MaterialisedWorkflowCommand<S, R> extends Materialised<S, never, R> {
  readonly changed: boolean
  readonly transition: StepRef | null
}

export function materialiseWorkflowCommand<S, R>(effect: WorkflowCommandEffect<S, R>, state: S): MaterialisedWorkflowCommand<S, R> {
  switch (effect.kind) {
    case "workflow": {
      const after = effect.newState === null ? state : effect.newState
      return Object.freeze({ events: [], newState: after, changed: effect.newState !== null, transition: effect.transition, retention: null, ...outcomeOf(effect.outcome, after) })
    }
    case "read-only":
      return Object.freeze({ events: [], newState: state, changed: false, transition: null, retention: null, ...outcomeOf(effect.outcome, state) })
    default:
      throw new TypeError(`not a workflow effect: ${JSON.stringify((effect as { kind?: unknown }).kind)}`)
  }
}

export interface MaterialisedStep<S> {
  readonly newState: S
  readonly changed: boolean
  readonly next: StepOutcome
}

export function materialiseStep<S>(effect: StepEffect<S>, state: S): MaterialisedStep<S> {
  if (effect.kind !== "step") throw new TypeError(`not a step effect: ${JSON.stringify((effect as { kind?: unknown }).kind)}`)
  return Object.freeze({ newState: effect.newState === null ? state : effect.newState, changed: effect.newState !== null, next: effect.next })
}
