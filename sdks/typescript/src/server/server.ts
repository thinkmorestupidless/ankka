// The process side of the protocol: an HTTP/2 server on loopback carrying Connect's adapter, speaking
// gRPC to the sidecar. HTTP/2 because the gRPC protocol and the per-instance bidirectional streams
// need it; loopback because the protocol is never reachable from outside the pod, and `0.0.0.0` only for
// the integration testkit, whose sidecar reaches this process from a container.

import { createServer, type Http2Server, type ServerHttp2Session } from "node:http2"
import type { AddressInfo } from "node:net"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import type { ConnectRouter } from "@connectrpc/connect"
import type { Registry } from "../service.ts"
import type { ComponentClient } from "../client.ts"
import { renderSpec } from "../spec.ts"
import { discoveryRoutes } from "./discovery.ts"
import { eventSourcedRoutes } from "./eventSourced.ts"
import { keyValueRoutes } from "./keyValue.ts"
import { workflowRoutes } from "./workflow.ts"
import { statelessRoutes } from "./stateless.ts"
import { agentRoutes } from "./agent.ts"
import { httpRoutes } from "./http.ts"

export interface ServerOptions {
  /** `127.0.0.1` by default; `0.0.0.0` for the testkit; anything else is refused. */
  readonly host?: string
  /** `ANKKA_PROCESS_PORT`, default 9010; `0` for an ephemeral port. */
  readonly port?: number
  /** The largest message accepted from the sidecar; 64 MiB by default. */
  readonly readMaxBytes?: number
  readonly log?: (message: string) => void
}

/** What every servicer needs. */
export interface ServerContext {
  readonly registry: Registry
  readonly client: ComponentClient
  readonly log: (message: string) => void
}

const LOOPBACK = new Set(["127.0.0.1", "localhost", "::1", "0.0.0.0", "::"])

export class Server {
  readonly #context: ServerContext
  readonly #options: ServerOptions
  readonly #shutdown = new AbortController()
  readonly #sessions = new Set<ServerHttp2Session>()
  #http2: Http2Server | undefined
  #address: { host: string; port: number } | undefined
  #resolveClosed!: () => void
  readonly closed: Promise<void> = new Promise((resolve) => {
    this.#resolveClosed = resolve
  })

  constructor(registry: Registry, client: ComponentClient, options: ServerOptions = {}) {
    this.#context = { registry, client, log: options.log ?? ((m) => console.error(m)) }
    this.#options = options
  }

  /** Binds and starts serving. Resolves with the bound address, which matters when `port` was 0. */
  async start(): Promise<{ host: string; port: number }> {
    if (this.#http2) throw new Error("the server is already started")
    const host = this.#options.host ?? "127.0.0.1"
    if (!LOOPBACK.has(host)) {
      throw new Error(`ankka serves the protocol on loopback only (or 0.0.0.0 for the testkit); refusing to bind ${host}`)
    }
    const port = this.#options.port ?? Number(process.env.ANKKA_PROCESS_PORT ?? 9010)
    if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error(`not a port: ${String(port)}`)

    const handler = connectNodeAdapter({
      routes: (router: ConnectRouter) => {
        discoveryRoutes(router, () => renderSpec(this.#context.registry), this.#context.log)
        eventSourcedRoutes(router, this.#context)
        keyValueRoutes(router, this.#context)
        workflowRoutes(router, this.#context)
        statelessRoutes(router, this.#context)
        agentRoutes(router, this.#context)
        httpRoutes(router, this.#context)
      },
      shutdownSignal: this.#shutdown.signal,
      readMaxBytes: this.#options.readMaxBytes ?? 64 * 1024 * 1024,
      grpcWeb: false,
      connect: false,
    })
    const server = createServer({ maxSessionMemory: 64 }, handler)
    this.#http2 = server
    // `server.close()` waits for every session to end, and a client keeps an idle session open for
    // minutes; `stop` closes the sessions it knows about so a stop is a stop.
    server.on("session", (session) => {
      this.#sessions.add(session)
      session.once("close", () => this.#sessions.delete(session))
    })
    await new Promise<void>((resolve, reject) => {
      server.once("error", reject)
      server.listen(port, host, () => {
        server.off("error", reject)
        resolve()
      })
    })
    const info = server.address() as AddressInfo
    this.#address = { host, port: info.port }
    server.on("close", () => this.#resolveClosed())
    return this.#address
  }

  /** The bound address; throws before `start`. */
  get address(): { host: string; port: number } {
    if (!this.#address) throw new Error("the server is not started")
    return this.#address
  }

  /** Aborts every stream, closes every session and the listener, and resolves `closed`. */
  async stop(): Promise<void> {
    const server = this.#http2
    if (!server) return
    this.#shutdown.abort()
    const closing = new Promise<void>((resolve) => server.close(() => resolve()))
    for (const session of this.#sessions) session.close()
    const grace = setTimeout(() => {
      for (const session of this.#sessions) session.destroy()
    }, 250)
    await closing
    clearTimeout(grace)
    this.#http2 = undefined
    this.#resolveClosed()
  }
}
