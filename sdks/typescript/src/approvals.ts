// Approvals: a tool that waits for a person's decision before it runs.
//
// A tool declared with `approval` is never run when the model calls it. The sidecar records an approval
// request and answers the caller with it instead of an answer; the turn goes on when a person decides,
// through `decide`. Approved, the tool runs once and the model is told its result; refused, the model is
// told who refused it and why, and the tool never runs. Who decided is required and recorded; who *may*
// decide is the ACL of whatever route sends the decision.

import type { ApprovalAwaited as ApprovalAwaitedProto, ApprovalRequest as ApprovalRequestProto } from "./_proto/ankka/protocol/v1/client_pb.ts"

/** The protocol version that carries approvals: a runtime before it cannot record a decision. */
export const APPROVALS_SINCE = "1.11"

/** That a tool, or every tool of an MCP server, waits for a person; with `withinMs`, the platform refuses it when that much time passes undecided. */
export type Approval = true | { readonly withinMs: number }

/** A problem with an approval as declared, or undefined. */
export function approvalProblem(approval: Approval | undefined): string | undefined {
  if (approval === undefined || approval === true) return undefined
  if (!Number.isFinite(approval.withinMs) || approval.withinMs <= 0) return `a time limit for approval must be positive, not ${approval.withinMs}ms`
  return undefined
}

/** The discovery form: absent for none, empty for no time limit. */
export function approvalToProto(approval: Approval | undefined): { withinMillis?: bigint } | undefined {
  if (approval === undefined) return undefined
  return approval === true ? {} : { withinMillis: BigInt(Math.trunc(approval.withinMs)) }
}

/** What an agent records when the model calls a tool that requires approval: the id a decision names, the tool and the model's arguments. */
export interface ApprovalRequest {
  readonly id: string
  readonly tool: string
  readonly arguments: unknown
  readonly requestedAt: number
  readonly expiresAt?: number
}

export function requestFromProto(pb: ApprovalRequestProto): ApprovalRequest {
  return Object.freeze({
    id: pb.id,
    tool: pb.tool,
    arguments: pb.argumentsJson ? JSON.parse(pb.argumentsJson) : {},
    requestedAt: Number(pb.requestedAtMillis),
    ...(pb.expiresAtMillis !== undefined ? { expiresAt: Number(pb.expiresAtMillis) } : {}),
  })
}

/** A request as an autonomous agent's record holds it. */
export function requestFromJson(body: Record<string, any>): ApprovalRequest {
  return Object.freeze({
    id: String(body.id),
    tool: String(body.tool),
    arguments: body.arguments ?? {},
    requestedAt: Number(body.requestedAt ?? 0),
    ...(body.expiresAt !== undefined ? { expiresAt: Number(body.expiresAt) } : {}),
  })
}

/** What a turn that may wait came to: the model's answer, or the requests it waits on. */
export type AgentOutcome<R> = { readonly kind: "answered"; readonly value: R } | { readonly kind: "awaiting-approval"; readonly requests: readonly ApprovalRequest[] }

export function awaitingOf(pb: ApprovalAwaitedProto): AgentOutcome<never> {
  return Object.freeze({ kind: "awaiting-approval", requests: pb.requests.map(requestFromProto) })
}

/**
 * Thrown by the calls that answer only with a value — `invoke` and `stream` — when the turn waits. Use
 * `ask`, `streamParts` or `decide` to be answered with the requests instead.
 */
export class ApprovalAwaited extends Error {
  readonly requests: readonly ApprovalRequest[]

  constructor(requests: readonly ApprovalRequest[]) {
    super(`approval awaited for ${requests.map((r) => `'${r.tool}' (${r.id})`).join(", ")}`)
    this.name = "ApprovalAwaited"
    this.requests = requests
  }
}

/** A person's decision: `by` names who decided and is required; a refusal may carry a note the model is told. */
export interface DecisionInput {
  readonly approved: boolean
  readonly by: string
  readonly note?: string
}
