// Effects for workflows: a command may change state and start a step; a step changes state and says
// what happens next. Timeouts and recovery are declared in `settings` and enforced by the sidecar's
// engine, since the process cannot enforce them.

import type { Duration } from "../time.ts"
import { ErrorCode, Outcome, type EffectLike, type ErrorDetail, type Metadata, type ReadOnlyLike } from "./common.ts"

/** A step to run, by wire name, with its input if it takes one. The step's declared shape encodes the input. */
export interface StepRef {
  readonly step: string
  readonly input: unknown
}

export type StepOutcome =
  | { readonly kind: "transition"; readonly ref: StepRef }
  | { readonly kind: "pause"; readonly after: Duration | null; readonly onTimeout: StepRef | null }
  | { readonly kind: "end" }
  | { readonly kind: "fail"; readonly error: ErrorDetail }

/** A command handler's effect: a new state, a transition, and a reply. */
export interface WorkflowEffect<S, R> extends EffectLike<R> {
  readonly kind: "workflow"
  readonly newState: S | null
  readonly transition: StepRef | null
  readonly outcome: Outcome<S, R>
}

export interface WorkflowReadOnlyEffect<S, R> extends ReadOnlyLike<R> {
  readonly kind: "read-only"
  readonly newState: null
  readonly transition: null
  readonly outcome: Outcome<S, R>
}

export type WorkflowCommandEffect<S, R> = WorkflowEffect<S, R> | WorkflowReadOnlyEffect<S, R>

/** A step's effect: a new state and what happens next. */
export interface StepEffect<S> extends EffectLike<never> {
  readonly kind: "step"
  readonly newState: S | null
  readonly next: StepOutcome
}

export interface PauseOptions {
  /** How long to pause before `onTimeout` runs; absent, the pause waits for a command. */
  readonly after?: Duration
  /** The step to run when the pause times out. */
  readonly onTimeout?: string
  readonly onTimeoutInput?: unknown
}

export class WorkflowUpdateBuilder<S> {
  readonly #newState: S | null
  readonly #transition: StepRef | null

  constructor(newState: S | null, transition: StepRef | null = null) {
    this.#newState = newState
    this.#transition = transition
  }

  /** Start `step` once this command's state is journaled. */
  thenTransitionTo(step: string, input?: unknown): WorkflowUpdateBuilder<S> {
    return new WorkflowUpdateBuilder(this.#newState, { step, input })
  }

  thenReply<R>(compute: (state: S) => R, metadata?: Metadata): WorkflowEffect<S, R> {
    return Object.freeze({ kind: "workflow", newState: this.#newState, transition: this.#transition, outcome: Outcome.reply(compute, metadata) })
  }

  thenReplyState(): WorkflowEffect<S, S> {
    return this.thenReply((state) => state)
  }

  thenNoReply(): WorkflowEffect<S, never> {
    return Object.freeze({ kind: "workflow", newState: this.#newState, transition: this.#transition, outcome: Outcome.noReply() })
  }
}

/** Inside a command handler: `this.effects`. */
export class WorkflowEffects<S> {
  updateState(state: S): WorkflowUpdateBuilder<S> {
    return new WorkflowUpdateBuilder<S>(state)
  }

  transitionTo(step: string, input?: unknown): WorkflowUpdateBuilder<S> {
    return new WorkflowUpdateBuilder<S>(null, { step, input })
  }

  reply<R>(value: R, metadata?: Metadata): WorkflowReadOnlyEffect<S, R> {
    return Object.freeze({ kind: "read-only", newState: null, transition: null, outcome: Outcome.reply(() => value, metadata) })
  }

  error(message: string, code: ErrorCode = ErrorCode.BadRequest): WorkflowReadOnlyEffect<S, never> {
    return Object.freeze({ kind: "read-only", newState: null, transition: null, outcome: Outcome.fail(message, code) })
  }
}

export class StepUpdateBuilder<S> {
  readonly #newState: S | null

  constructor(newState: S | null) {
    this.#newState = newState
  }

  thenTransitionTo(step: string, input?: unknown): StepEffect<S> {
    return Object.freeze({ kind: "step", newState: this.#newState, next: { kind: "transition", ref: { step, input } } })
  }

  /** Pause until a command moves the workflow on, or `after` passes and `onTimeout` runs. */
  thenPause(options: PauseOptions = {}): StepEffect<S> {
    const onTimeout = options.onTimeout ? { step: options.onTimeout, input: options.onTimeoutInput } : null
    return Object.freeze({ kind: "step", newState: this.#newState, next: { kind: "pause", after: options.after ?? null, onTimeout } })
  }

  thenEnd(): StepEffect<S> {
    return Object.freeze({ kind: "step", newState: this.#newState, next: { kind: "end" } })
  }

  /** End the workflow as failed. This is what the step answered on purpose; a *thrown* error is retried and failed over as declared. */
  thenFail(message: string, code: ErrorCode = ErrorCode.Internal): StepEffect<S> {
    return Object.freeze({ kind: "step", newState: this.#newState, next: { kind: "fail", error: { message, code } } })
  }
}

/** Inside a step: `this.stepEffects`. */
export class StepEffects<S> {
  updateState(state: S): StepUpdateBuilder<S> {
    return new StepUpdateBuilder<S>(state)
  }

  transitionTo(step: string, input?: unknown): StepEffect<S> {
    return new StepUpdateBuilder<S>(null).thenTransitionTo(step, input)
  }

  pause(options: PauseOptions = {}): StepEffect<S> {
    return new StepUpdateBuilder<S>(null).thenPause(options)
  }

  end(): StepEffect<S> {
    return new StepUpdateBuilder<S>(null).thenEnd()
  }

  fail(message: string, code: ErrorCode = ErrorCode.Internal): StepEffect<S> {
    return new StepUpdateBuilder<S>(null).thenFail(message, code)
  }
}

/** What the sidecar does when a step throws or times out: retry it, then fail over to a step that takes no input, or fail the workflow. */
export interface Recovery {
  readonly maxRetries: number
  readonly failoverTo?: string
}

export interface StepSettings {
  readonly timeout?: Duration
  readonly recovery?: Recovery
}

/** Timeouts and recovery, enforced by the sidecar's engine. Absent values are the engine's defaults: no overall limit, 30 seconds a step, a failed step fails the workflow. */
export interface WorkflowSettings {
  readonly timeout?: Duration
  readonly defaultStepTimeout?: Duration
  readonly defaultRecovery?: Recovery
  readonly steps?: Readonly<Record<string, StepSettings>>
}

/** Declares a workflow's settings; the registry checks every step it names against the declared steps. */
export function workflowSettings(settings: WorkflowSettings): WorkflowSettings {
  return Object.freeze({ ...settings, steps: settings.steps ? Object.freeze({ ...settings.steps }) : undefined })
}
