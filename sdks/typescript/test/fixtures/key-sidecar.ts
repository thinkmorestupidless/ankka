// A stand-in for the sidecar's key calls, run as a process of its own: a codec's fetch blocks the
// thread it runs on, so the runtime answering it must not share that thread — as the sidecar never
// does. Prints its port; reads `erase <subject>` lines and pushes each to the event stream; answers
// how many fetches it has had to `count`.

import { createServer } from "node:http2"
import type { AddressInfo } from "node:net"
import { createInterface } from "node:readline"
import { create } from "@bufbuild/protobuf"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import { Client, KeyAnswerSchema, SubjectDestroyedSchema, type KeyFetch, type SubjectDestroyed } from "../../src/_proto/ankka/protocol/v1/client_pb.ts"

const key = new Uint8Array(32).fill(7)
let fetches = 0
const listeners = new Set<(d: SubjectDestroyed) => void>()

const handler = connectNodeAdapter({
  routes: (router) =>
    router.service(Client, {
      async fetchSubjectKey(req: KeyFetch) {
        fetches++
        if (req.subject === "player/gone") return create(KeyAnswerSchema, { out: { case: "destroyed", value: { subject: req.subject, project: "brand", erasureId: "e1" } } })
        return create(KeyAnswerSchema, { out: { case: "key", value: { subject: req.subject, project: "brand", key, expiresMillis: 60_000n } } })
      },
      async *subjectKeyEvents() {
        const queue: SubjectDestroyed[] = []
        let wake: (() => void) | undefined
        const push = (d: SubjectDestroyed) => {
          queue.push(d)
          wake?.()
        }
        listeners.add(push)
        process.stdout.write("listening\n")
        try {
          for (;;) {
            while (queue.length > 0) yield queue.shift()!
            await new Promise<void>((resolve) => (wake = resolve))
          }
        } finally {
          listeners.delete(push)
        }
      },
    }),
  grpcWeb: false,
  connect: false,
})
const server = createServer(handler)
server.listen(0, "127.0.0.1", () => process.stdout.write(`port ${(server.address() as AddressInfo).port}\n`))
createInterface({ input: process.stdin }).on("line", (line) => {
  const [verb, subject] = line.split(" ")
  if (verb === "erase") for (const l of listeners) l(create(SubjectDestroyedSchema, { subject: subject!, project: "brand", erasureId: "e2" }))
  if (verb === "count") process.stdout.write(`count ${fetches}\n`)
})
process.stdin.on("end", () => process.exit(0))
