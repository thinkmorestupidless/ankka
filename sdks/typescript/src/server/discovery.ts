// Discovery: the first conversation. The sidecar asks what we host; we answer with the Spec the
// registry renders. `ReportError` is how the sidecar tells us why it refused to start — logged, and kept
// so a service can expose it (the conformance reference does, at GET /conformance/problems).

import type { ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { Discovery, type Spec } from "../_proto/ankka/protocol/v1/discovery_pb.ts"
import { EmptySchema } from "../_proto/ankka/protocol/v1/payload_pb.ts"

/** Every problem the sidecar has reported to this process, in order. */
export const problems: string[] = []

export function discoveryRoutes(router: ConnectRouter, spec: () => Spec, log: (message: string) => void = console.error): void {
  router.service(Discovery, {
    async discover(info) {
      log(`ankka: discovery from sidecar (protocol ${info.protocolVersion}, runtime ${info.runtimeVersion})`)
      return spec()
    },
    async reportError(problem) {
      log(`ankka: the sidecar refused to start: ${problem.message}`)
      problems.push(problem.message)
      return create(EmptySchema)
    },
  })
}
