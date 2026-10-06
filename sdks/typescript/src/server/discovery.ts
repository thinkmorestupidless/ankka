// Discovery: the first conversation. The sidecar asks what we host; we answer with the Spec the
// registry renders. `ReportError` is how the sidecar tells us why it refused to start — logged, and kept
// so a service can expose it (the conformance reference does, at GET /conformance/problems).

import { Code, ConnectError, type ConnectRouter } from "@connectrpc/connect"
import { create } from "@bufbuild/protobuf"
import { Discovery, type Spec } from "../_proto/ankka/protocol/v1/discovery_pb.ts"
import { EmptySchema } from "../_proto/ankka/protocol/v1/payload_pb.ts"
import { PROTOCOL_VERSION } from "../spec.ts"
import { olderThanDeclaredQueries, olderThanStartPositions } from "../startFrom.ts"

/**
 * A sidecar older than 1.7 would ignore where a topic source starts and its version: a consumer
 * declared `latest` would read everything, and a raised version would rebuild nothing. Refused,
 * naming what declares them, rather than served wrong.
 */
export function refusal(spec: Spec, sidecarProtocol: string): string | undefined {
  return startPositionRefusal(spec, sidecarProtocol) ?? declaredQueryRefusal(spec, sidecarProtocol)
}

/**
 * A sidecar older than 1.13 would ignore a view's declared queries, would read a keyed view as having
 * no source, and would refuse a version on a view that reads an entity as applying only to a topic.
 * Refused at discovery, naming the views, rather than found later or misreported.
 */
function declaredQueryRefusal(spec: Spec, sidecarProtocol: string): string | undefined {
  if (!olderThanDeclaredQueries(sidecarProtocol)) return undefined
  const declaring = spec.components
    .filter((c) => {
      if (c.detail.case !== "view") return false
      const v = c.detail.value
      // A declared query, a keyed view, or a version on a view that reads an entity.
      return v.declaredQueries.length > 0 || v.sources.length > 0 || (v.version !== undefined && v.source?.source.case === "component")
    })
    .map((c) => c.id)
  if (declaring.length === 0) return undefined
  return (
    `${declaring.join(", ")} declare queries, several sources, or a version on a view that reads an entity, which the sidecar ignores: ` +
    `it speaks protocol ${sidecarProtocol}, and this SDK ${PROTOCOL_VERSION}. Run a sidecar speaking 1.13 or later.`
  )
}

function startPositionRefusal(spec: Spec, sidecarProtocol: string): string | undefined {
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
    `it speaks protocol ${sidecarProtocol}, and this SDK ${PROTOCOL_VERSION}. Run a sidecar speaking 1.7 or later.`
  )
}

/** Every problem the sidecar has reported to this process, in order. */
export const problems: string[] = []

export function discoveryRoutes(router: ConnectRouter, spec: () => Spec, log: (message: string) => void = console.error): void {
  router.service(Discovery, {
    async discover(info) {
      log(`ankka: discovery from sidecar (protocol ${info.protocolVersion}, runtime ${info.runtimeVersion})`)
      const answer = spec()
      const why = refusal(answer, info.protocolVersion) ?? socketRefusal(answer, info.protocolVersion)
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

/** The protocol version whose runtimes serve socket routes. */
export const SOCKETS_SINCE: readonly [number, number] = [1, 9]

/** Why `spec` cannot be declared to a runtime speaking `runtime`, or undefined. A runtime before 1.9
 * does not know a socket route, and would serve it as a plain GET. */
export function socketRefusal(spec: Spec, runtime: string): string | undefined {
  const routes = spec.endpoints.flatMap((e) => e.routes.filter((r) => r.socket).map((r) => `${e.id}.${r.id}`))
  if (routes.length === 0) return undefined
  const [major, minor] = runtime.split(".").map((part) => Number.parseInt(part, 10))
  const speaks = Number.isFinite(major) && Number.isFinite(minor) ? [major!, minor!] : [0, 0]
  if (speaks[0]! > SOCKETS_SINCE[0] || (speaks[0] === SOCKETS_SINCE[0] && speaks[1]! >= SOCKETS_SINCE[1])) return undefined
  return `this runtime speaks protocol ${runtime || "unknown"}; a socket route needs 1.9 (${routes.join(", ")})`
}
