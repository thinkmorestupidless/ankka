// Helpers for the servicer tests: a started server with an in-process Connect transport to it.
import { createClient, type Transport } from "@connectrpc/connect"
import { createGrpcTransport } from "@connectrpc/connect-node"
import { create } from "@bufbuild/protobuf"
import type { ServiceBuilder } from "../src/service.ts"
import type { Server } from "../src/server/server.ts"
import { EventSourced, EventSourcedInSchema, type EventSourcedIn, type EventSourcedOut } from "../src/_proto/ankka/protocol/v1/event_sourced_pb.ts"
import { Discovery } from "../src/_proto/ankka/protocol/v1/discovery_pb.ts"
import { Http } from "../src/_proto/ankka/protocol/v1/endpoint_pb.ts"
import type { Payload } from "../src/_proto/ankka/protocol/v1/payload_pb.ts"
import { AsyncQueue } from "../src/server/queue.ts"
import { encodePayload, EMPTY_PAYLOAD } from "../src/server/payloads.ts"
import { codecFor, type Shape } from "../src/codec.ts"

export interface Started {
  server: Server
  transport: Transport
  port: number
  discovery: ReturnType<typeof createClient<typeof Discovery>>
  eventSourced: ReturnType<typeof createClient<typeof EventSourced>>
  http: ReturnType<typeof createClient<typeof Http>>
  stop(): Promise<void>
}

export async function startServer(service: ServiceBuilder, host = "127.0.0.1"): Promise<Started> {
  const server = service.server({ host, port: 0, log: () => {} })
  const { port } = await server.start()
  const transport = createGrpcTransport({ baseUrl: `http://127.0.0.1:${port}` })
  return {
    server,
    transport,
    port,
    discovery: createClient(Discovery, transport),
    eventSourced: createClient(EventSourced, transport),
    http: createClient(Http, transport),
    stop: () => server.stop(),
  }
}

/** One event sourced conversation: push messages, read replies one at a time. */
export class Conversation {
  readonly #requests = new AsyncQueue<EventSourcedIn>()
  readonly #replies: AsyncIterator<EventSourcedOut>
  #nextId = 1n

  constructor(started: Started) {
    this.#replies = started.eventSourced.handle(this.#requests)[Symbol.asyncIterator]()
  }

  init(componentId: string, entityId: string, snapshot?: { sequence: bigint; payload: Payload }): void {
    this.#requests.push(create(EventSourcedInSchema, { message: { case: "init", value: { componentId, entityId, snapshot } } }))
  }

  event(sequence: bigint, payload: Payload): void {
    this.#requests.push(create(EventSourcedInSchema, { message: { case: "event", value: { sequence, payload } } }))
  }

  /** Sends a command and returns its id. */
  send(name: string, payload: Payload = EMPTY_PAYLOAD, snapshotRequested = false, metadata: Record<string, string> = {}): bigint {
    const id = this.#nextId++
    this.#requests.push(
      create(EventSourcedInSchema, {
        message: { case: "command", value: { id, name, payload, metadata: { entries: Object.entries(metadata).map(([key, value]) => ({ key, value })) }, snapshotRequested } },
      }),
    )
    return id
  }

  async reply(): Promise<EventSourcedOut> {
    const r = await this.#replies.next()
    if (r.done) throw new Error("the stream ended")
    return r.value
  }

  /** Sends a command and awaits its reply. */
  async call(name: string, payload?: Payload, snapshotRequested = false): Promise<EventSourcedOut> {
    this.send(name, payload, snapshotRequested)
    return this.reply()
  }

  close(): void {
    this.#requests.close()
  }

  /** True once the server has ended its side (after `close`). */
  async ended(): Promise<boolean> {
    const r = await this.#replies.next()
    return r.done === true
  }
}

export function payload<T>(shape: Shape<T>, value: T): Payload {
  return encodePayload(codecFor(shape), value)
}

export function decode<T>(shape: Shape<T>, p: Payload | undefined): T {
  return codecFor(shape).decode(p?.data ?? new Uint8Array())
}

export function replyOf(out: EventSourcedOut) {
  if (out.message.case !== "reply") throw new Error(`expected a reply, got ${out.message.case}: ${JSON.stringify(out.message.value)}`)
  return out.message.value
}

export function failureOf(out: EventSourcedOut) {
  if (out.message.case !== "failure") throw new Error(`expected a failure, got ${out.message.case}`)
  return out.message.value
}
