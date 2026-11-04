// The `Erasure` service (protocol 1.23, feature 042): the sidecar asks this process to run its erasure
// handler for one erased data subject. A handler that throws answers an `Error`, which the platform
// records as a failed run and runs again on the next pass; it is never a gRPC status.

import type { ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { Erasure, ErasureReplySchema, type ErasureCall, type ErasureReply } from "../_proto/ankka/protocol/v1/erasure_pb.ts"
import { secretsFor } from "../client.ts"
import { metadataFromProto } from "../context.ts"
import { CommandError, ErrorCode } from "../effects/common.ts"
import { finalityToProto, type ErasureHandler } from "../erasure.ts"
import { errorCodeToProto } from "../kinds.ts"
import { subjectProblem, type DataSubject } from "../personal.ts"
import { processStorage } from "../storage.ts"
import type { ServerContext } from "./server.ts"

function failed(message: string, code: ErrorCode = ErrorCode.Internal): ErasureReply {
  return create(ErasureReplySchema, { objectsErased: 0n, finality: "", error: { message, code: errorCodeToProto(code) } })
}

/** Runs the service's handler for one call; the Connect servicer and the unit tests both call it. */
export async function invokeErasure(handler: ErasureHandler | undefined, call: ErasureCall, ctx: ServerContext): Promise<ErasureReply> {
  if (!handler) return failed("this service declares no erasure handler", ErrorCode.NotFound)
  const problem = subjectProblem(call.subject)
  if (problem !== undefined) return failed(problem, ErrorCode.BadRequest)
  const metadata = metadataFromProto(call.metadata)
  const client = ctx.client.withMetadata(metadata)
  try {
    const report =
      (await handler(
        { client, secrets: secretsFor(client), storage: ctx.storage ?? processStorage(), erasureSeq: call.erasureSeq, rerun: call.rerun, metadata },
        call.subject as DataSubject,
      )) ?? {}
    const count = report.objectsErased ?? 0
    return create(ErasureReplySchema, { objectsErased: BigInt(count), finality: finalityToProto(report.finality) })
  } catch (e) {
    ctx.log(`ankka: the erasure handler failed for '${call.subject}': ${e instanceof Error ? e.message : String(e)}`)
    return failed(e instanceof Error ? e.message : String(e), e instanceof CommandError ? e.code : ErrorCode.Internal)
  }
}

export function erasureRoutes(router: ConnectRouter, ctx: ServerContext): void {
  router.service(Erasure, {
    invoke: (call) => invokeErasure(ctx.registry.erasureHandler, call, ctx),
  })
}
