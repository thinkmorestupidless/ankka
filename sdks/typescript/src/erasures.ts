// Asking for an erasure request from code (feature 042, protocol 1.23): `this.erasures.ask({ subject })`
// in an endpoint or a consumer, `ctx.erasures.ask(...)` in the erasure handler. The request is asked of
// the control plane as this service, through the sidecar's `Client.AskErasure`; the service needs the
// `erasure` grant of the project it asks in, its own included, and is named as who asked.
//
//   const asked = await this.erasures.ask({ subject: "customer-42", notBefore: LocalDate.of(2027, 1, 31), reason: "aml-retention" })
//   asked.id, asked.state   // "held", "applying" or "applied"

import { Code, ConnectError } from "@connectrpc/connect"
import { channelOf, type ComponentClient } from "./client.ts"
import { metadataToProto } from "./context.ts"
import { CommandError, ErrorCode } from "./effects/common.ts"
import { errorCodeFromProto } from "./kinds.ts"
import { PROTOCOL_VERSION } from "./spec.ts"
import type { LocalDate } from "./time.ts"

/** The first protocol whose sidecar asks for an erasure request; an older one answers `UNIMPLEMENTED`. */
export const ERASURES_SINCE = "1.23"

/** What to ask: whose personal data, where, and from when. */
export interface AskErasure {
  readonly subject: string
  /** The project to erase in; this service's own when absent. */
  readonly project?: string
  /** Held until this date; sent as ISO-8601 (`2027-01-31`). */
  readonly notBefore?: LocalDate
  readonly reason?: string
  readonly correlation?: string
}

/** An erasure request as asked: its id in its project and its state (held, applying, applied). */
export interface ErasureAsked {
  readonly id: string
  readonly state: string
}

/**
 * Asks for erasure requests as this service. A refusal is a `CommandError` with the control plane's
 * code: `FORBIDDEN` without the `erasure` grant, `CONFLICT` while erasure is off in the project,
 * `BAD_REQUEST` for a subject outside the rule.
 */
export class Erasures {
  readonly #client: ComponentClient | undefined

  /** Asked through `client`'s sidecar; with none, every ask throws. */
  constructor(client?: ComponentClient) {
    this.#client = client
  }

  async ask(request: AskErasure): Promise<ErasureAsked> {
    const channel = this.#client ? channelOf(this.#client) : undefined
    if (!channel) throw new Error("these erasures are not connected to a runtime; a unit test assigns its own")
    const reply = await channel
      .stub()
      .askErasure({
        project: request.project ?? "",
        subject: request.subject,
        ...(request.notBefore !== undefined ? { notBefore: request.notBefore.toString() } : {}),
        ...(request.reason !== undefined ? { reason: request.reason } : {}),
        ...(request.correlation !== undefined ? { correlation: request.correlation } : {}),
        metadata: metadataToProto(channel.metadata),
      })
      .catch((failure: unknown) => {
        if (failure instanceof ConnectError && failure.code === Code.Unimplemented) {
          throw new CommandError({
            message: `the runtime beside this process cannot ask for an erasure, which needs protocol ${ERASURES_SINCE}; this process speaks ${PROTOCOL_VERSION}: ${failure.rawMessage}`,
            code: ErrorCode.Internal,
          })
        }
        throw failure
      })
    switch (reply.reply.case) {
      case "ok":
        return Object.freeze({ id: reply.reply.value.id, state: reply.reply.value.state })
      case "error": {
        const error = reply.reply.value
        throw new CommandError({ message: error.message, code: errorCodeFromProto(error.code), details: { ...(error.details ?? {}) } })
      }
      default:
        throw new CommandError({ message: "the runtime answered an erasure request with nothing", code: ErrorCode.Internal })
    }
  }
}

/** @internal The erasures reached through `client`'s sidecar. */
export function erasuresFor(client: ComponentClient): Erasures {
  return new Erasures(client)
}
