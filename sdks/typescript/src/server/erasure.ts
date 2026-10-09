// Runs the service's erasure handler for one application of an erasure (protocol 1.15).

import type { ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { Erasure, ErasureHandleReplySchema, type ErasureHandleReply, type ErasureHandleRequest } from "../_proto/ankka/protocol/v1/erasure_pb.ts"
import type { ErasureContext } from "../erasure.ts"
import type { ServerContext } from "./server.ts"

export function erasureRoutes(router: ConnectRouter, ctx: ServerContext): void {
  const handler = ctx.registry.erasureHandler
  if (handler === undefined) return
  router.service(Erasure, {
    async handle(request: ErasureHandleRequest): Promise<ErasureHandleReply> {
      const client = ctx.client.withMetadata({ ...request.metadata })
      const context: ErasureContext = {
        subject: request.subject,
        erasureId: request.erasureId,
        reapply: request.reapply,
        objects: { erase: () => client.eraseObjects(request.subject) },
        client,
      }
      try {
        const outcome = await handler(context)
        if (outcome.kind === "failed") return create(ErasureHandleReplySchema, { outcome: { case: "failed", value: { reason: outcome.reason } } })
        return create(ErasureHandleReplySchema, {
          outcome: {
            case: "done",
            value: {
              detail: outcome.detail ?? "",
              ...(outcome.objects ? { objects: { count: BigInt(outcome.objects.count), finalAtMillis: BigInt(outcome.objects.finalAt.getTime()) } } : {}),
            },
          },
        })
      } catch (e) {
        // A handler that throws is a failed application, run again on the next one.
        ctx.log(`ankka: the erasure handler threw for ${request.erasureId}: ${(e as Error).message}`)
        return create(ErasureHandleReplySchema, { outcome: { case: "failed", value: { reason: `the erasure handler threw: ${(e as Error).message}` } } })
      }
    },
  })
}
