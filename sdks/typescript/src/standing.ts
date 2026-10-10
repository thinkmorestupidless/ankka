// Where a workflow stood once the effect that recorded a state was applied (protocol 1.15).
//
// A view or a consumer whose source is a workflow is handed, with each state the workflow records,
// its standing: running, paused, completed or failed, the step it is on or waits after, the retries
// of each step and, when it failed, why. `Unknown` is the standing of a state recorded before the
// platform stamped standings. A change from an entity or a topic has none.

import type { WorkflowStanding } from "./_proto/ankka/protocol/v1/payload_pb.ts"

export type StandingStatus = "NotStarted" | "Running" | "Paused" | "Completed" | "Failed" | "Unknown"

export interface Standing {
  readonly status: StandingStatus
  /** The step the workflow is on, or the step a pause names or follows. */
  readonly step?: string
  /** Retries so far, per step. */
  readonly retries: Readonly<Record<string, number>>
  /** Why it failed, when it did. */
  readonly failure?: string
}

export function isTerminal(standing: Standing): boolean {
  return standing.status === "Completed" || standing.status === "Failed"
}

/** The state was recorded before the platform stamped standings. */
export function isUnknown(standing: Standing): boolean {
  return standing.status === "Unknown"
}

/** @internal The standing a request carries, or `undefined` for a change from an entity or a topic. */
export function standingFromProto(standing: WorkflowStanding | undefined): Standing | undefined {
  if (standing === undefined) return undefined
  return Object.freeze({
    status: standing.status as StandingStatus,
    ...(standing.step ? { step: standing.step } : {}),
    retries: Object.freeze({ ...standing.retries }),
    ...(standing.failure ? { failure: standing.failure } : {}),
  })
}

/** @internal The version whose runtimes read a workflow as a source. */
export const WORKFLOW_SOURCE_PROTOCOL: readonly [number, number] = [1, 15]
