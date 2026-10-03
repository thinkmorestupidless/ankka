// Discovery: the first conversation. The sidecar asks what we host; we answer with the Spec the
// registry renders. `ReportError` is how the sidecar tells us why it refused to start — logged, and kept
// so a service can expose it (the conformance reference does, at GET /conformance/problems).

import { Code, ConnectError, type ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { Discovery, type Spec } from "../_proto/ankka/protocol/v1/discovery_pb.ts"
import { EmptySchema } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import { PROTOCOL_VERSION } from "../spec.ts"
import { olderThanStartPositions } from "../startFrom.ts"

/**
 * A sidecar older than 1.4 would ignore where a topic source starts and its version: a consumer
 * declared `latest` would read everything, and a raised version would rebuild nothing. Refused,
 * naming what declares them, rather than served wrong.
 */
export function refusal(spec: Spec, sidecarProtocol: string): string | undefined {
  if (!olderThanStartPositions(sidecarProtocol)) return undefined
  const declaring = spec.components
    .filter((c) => {
      const d = c.detail
      if (d.case !== "view" && d.case !== "consumer") return false
      return d.value.source?.startFrom !== undefined || d.value.version !== undefined
    })
    .map((c) => c.id)
  if (declaring.length === 0) return undefined
  return (
    `${declaring.join(", ")} declare where a topic source starts or its version, which the sidecar ignores: ` +
    `it speaks protocol ${sidecarProtocol}, and this SDK ${PROTOCOL_VERSION}. Run a sidecar speaking 1.4 or later.`
  )
}

/** Every problem the sidecar has reported to this process, in order. */
export const problems: string[] = []

export function discoveryRoutes(router: ConnectRouter, spec: () => Spec, log: (message: string) => void = console.error): void {
  router.service(Discovery, {
    async discover(info) {
      log(`ankka: discovery from sidecar (protocol ${info.protocolVersion}, runtime ${info.runtimeVersion})`)
      const answer = spec()
      const why = refusal(answer, info.protocolVersion)
      if (why !== undefined) {
        log(`ankka: ${why}`)
        problems.push(why)
        throw new ConnectError(why, Code.FailedPrecondition)
      }
      return answer
    },
    async reportError(problem) {
      log(`ankka: the sidecar refused to start: ${problem.message}`)
      problems.push(problem.message)
      return create(EmptySchema)
    },
  })
}
