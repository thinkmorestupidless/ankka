// The process side of the protocol: an HTTP/2 server on loopback carrying Connect's adapter, speaking
// gRPC to the sidecar. HTTP/2 because the gRPC protocol and the per-instance bidirectional streams
// need it; loopback because the protocol is never reachable from outside the pod, and `0.0.0.0` only for
// the integration testkit, whose sidecar reaches this process from a container.

import { createServer, type Http2Server, type ServerHttp2Session } from "node:http2"
import type { AddressInfo } from "node:net"
import { connectNodeAdapter } from "@connectrpc/connect-node"
import type { ConnectRouter } from "@connectrpc/connect"
import { Registry, type RegisteredEndpoint } from "../service.ts"
import type { ComponentClient } from "../client.ts"
import { _setProcessStorage, chooseStorage, DirectoryBuckets, type Buckets } from "../storage.ts"
import { DirectoryStorageEndpoint } from "./storageEndpoint.ts"
import { renderSpec } from "../spec.ts"
import { discoveryRoutes } from "./discovery.ts"
import { eventSourcedRoutes } from "./eventSourced.ts"
import { keyValueRoutes } from "./keyValue.ts"
import { workflowRoutes } from "./workflow.ts"
import { statelessRoutes } from "./stateless.ts"
import { agentRoutes } from "./agent.ts"
import { httpRoutes } from "./http.ts"
import { mcpRoutes } from "./mcp.ts"
import { erasureRoutes } from "./erasure.ts"
import { randomUUID } from "node:crypto"
import { channelOf } from "../client.ts"
import { currentKeyring, followNotices, installKeyring, Keyring, type KeyReply } from "../keyring.ts"
import { olderThan } from "../startFrom.ts"
import { PERSONAL_SINCE } from "./discovery.ts"

export interface ServerOptions {
  /** `127.0.0.1` by default; `0.0.0.0` for the testkit; anything else is refused. */
  readonly host?: string
  /** `ANKKA_PROCESS_PORT`, default 9010; `0` for an ephemeral port. */
  readonly port?: number
  /** The largest message accepted from the sidecar; 64 MiB by default. */
  readonly readMaxBytes?: number
  readonly log?: (message: string) => void
  /**
   * The service's buckets, in place of the store `ANKKA_STORAGE_BACKEND` and the `ANKKA_S3_*` variables
   * choose: the integration testkit's in-memory store.
   */
  readonly storage?: Buckets
  /** Where `service.json` is read from for the buckets it declares; the working directory by default. */
  readonly serviceDirectory?: string
  /** How long a subject key is waited for from the sidecar; 10 seconds by default. */
  readonly keyTimeoutMillis?: number
}

/** What every servicer needs. */
export interface ServerContext {
  readonly registry: Registry
  readonly client: ComponentClient
  readonly log: (message: string) => void
  /** The buckets every endpoint instance is given; the process's store when absent. */
  readonly storage?: Buckets
}

/** The registry with the directory store's endpoint, which serves the URLs that store signs. */
function withStorageEndpoint(registry: Registry): Registry {
  const cls = DirectoryStorageEndpoint
  const endpoint: RegisteredEndpoint = Object.freeze({ id: cls.name, cls, prefix: cls.prefix, acl: cls.acl, routes: new Map(Object.entries(cls.routes)) })
  return new Registry(registry.components, new Map([...registry.endpoints, [endpoint.id, endpoint]]), registry.mcpEndpoints, registry.erasureHandler)
}

const LOOPBACK = new Set(["127.0.0.1", "localhost", "::1", "0.0.0.0", "::"])

export class Server {
  #context: ServerContext
  readonly #options: ServerOptions
  readonly #shutdown = new AbortController()
  readonly #sessions = new Set<ServerHttp2Session>()
  #http2: Http2Server | undefined
  #address: { host: string; port: number } | undefined
  #keyring: Keyring | undefined
  #following = false
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

    // The service's buckets: the store given, or the one the environment chooses. A directory store's
    // URLs are answered by an endpoint this process declares at /_ankka/storage.
    const given = this.#options.storage
    const chosen = given ? { backend: "given", buckets: given } : chooseStorage(process.env, { ...(this.#options.serviceDirectory ? { serviceDirectory: this.#options.serviceDirectory } : {}) })
    _setProcessStorage(chosen.buckets)
    const registry = chosen.buckets instanceof DirectoryBuckets ? withStorageEndpoint(this.#context.registry) : this.#context.registry
    this.#context = { ...this.#context, registry, storage: chosen.buckets }
    this.#installKeyring()
    this.#context.log(`ankka: object storage is ${chosen.backend}${chosen.buckets instanceof DirectoryBuckets ? ` at ${chosen.buckets.root}` : ""}`)

    const handler = connectNodeAdapter({
      routes: (router: ConnectRouter) => {
        discoveryRoutes(router, () => renderSpec(this.#context.registry), this.#context.log, {
          users: () => this.#context.registry.personalUsers,
          discovered: (protocol) => this.#followNotices(protocol),
        })
        eventSourcedRoutes(router, this.#context)
        keyValueRoutes(router, this.#context)
        workflowRoutes(router, this.#context)
        statelessRoutes(router, this.#context)
        agentRoutes(router, this.#context)
        httpRoutes(router, this.#context)
        mcpRoutes(router, this.#context)
        erasureRoutes(router, this.#context)
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

  /** The process's keyring: subject keys asked of the sidecar this server's client dials (feature 042). */
  #installKeyring(): void {
    const channel = channelOf(this.#context.client)
    if (!channel) return
    const timeoutMs = this.#options.keyTimeoutMillis ?? 10_000
    // The project is learned from the sidecar's answers (`SubjectKeyReply.project`), never configured.
    this.#keyring = new Keyring({
      fetch: async (project, subject, create, home = ""): Promise<KeyReply> => {
        const reply = await channel.stub().getSubjectKey({ subject, create, project, home }, { timeoutMs })
        switch (reply.reply.case) {
          case "key":
            return { project: reply.project, answer: { kind: "key", key: reply.reply.value } }
          case "erased":
            return { project: reply.project, answer: { kind: "erased" } }
          case "error":
            throw new Error(reply.reply.value.message)
          default:
            throw new Error("the sidecar answered a subject key request with nothing")
        }
      },
    })
    installKeyring(this.#keyring)
  }

  /** Holds the sidecar's subject notices once a runtime speaking 1.23 has discovered a service with personal fields. */
  #followNotices(protocol: string): void {
    const keyring = this.#keyring
    // Discovered, by a sidecar that may be new: one that stopped before it handed on a notice hands
    // it never again, and this process may not yet have seen the old notice stream end. Every key
    // held is forgotten, so an erased subject is asked for again and reads as erased.
    keyring?.dropAll()
    const channel = channelOf(this.#context.client)
    if (this.#following || !keyring || !channel || olderThan(protocol, PERSONAL_SINCE) || this.#context.registry.personalUsers.length === 0) return
    this.#following = true
    const instance = randomUUID()
    void followNotices(
      keyring,
      (signal) => channel.stub().subjectNotices({ instance }, { signal }),
      async (seq) => {
        const reply = await channel.stub().ackSubjectNotice({ seq, instance })
        if (reply.error) this.#context.log(`ankka: the sidecar refused the ack of subject notice ${seq}: ${reply.error.message}`)
      },
      this.#shutdown.signal,
      this.#context.log,
    )
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
    if (this.#keyring && currentKeyring() === this.#keyring) installKeyring(undefined)
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
