// The integration testkit: this process, a real sidecar, a real Postgres.
//
// Starts Postgres with the platform's schema (copied out of the sidecar image, so a test can never pass
// against a schema the platform does not have), the `ankka-sidecar` image pointed at this process through
// `host.docker.internal`, and this process's server. `http` talks to the sidecar's HTTP port, where the
// declared routes are served; `restart` replaces the sidecar container against the same database, which
// is how a test proves durability rather than caching.
//
// Needs Docker, and `testcontainers` with `@testcontainers/postgresql` installed (optional peers of this
// package). The image is `$ANKKA_SIDECAR_IMAGE` or `ankka-sidecar:latest`.

import { execFileSync } from "node:child_process"
import { chmodSync, mkdtempSync, readdirSync, rmSync, statSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { StartedNetwork, StartedTestContainer } from "testcontainers"
import type { StartedPostgreSqlContainer } from "@testcontainers/postgresql"
import type { ComponentClient } from "../client.ts"
import type { ServiceBuilder } from "../service.ts"
import type { Server } from "../server/server.ts"

const POSTGRES_IMAGE = "postgres:17-alpine"
const HTTP_PORT = 9000
const CALLBACK_PORT = 9011

export interface AnkkaTestKitOptions {
  /** The sidecar image; `$ANKKA_SIDECAR_IMAGE` or `ankka-sidecar:latest` by default. */
  readonly image?: string
  /** Environment for the sidecar container: `ANKKA_MODEL_SCRIPT` scripts its model. */
  readonly env?: Readonly<Record<string, string>>
  readonly postgresImage?: string
  /** How long to wait for the sidecar to report healthy; 120s by default. */
  readonly readyTimeoutMs?: number
  readonly log?: (message: string) => void
}

/** What `http` answers with. */
export class HttpResponse {
  readonly status: number
  readonly headers: globalThis.Headers
  readonly body: Uint8Array

  constructor(status: number, headers: globalThis.Headers, body: Uint8Array) {
    this.status = status
    this.headers = headers
    this.body = body
    Object.freeze(this)
  }

  text(): string {
    return new TextDecoder().decode(this.body)
  }

  json(): unknown {
    return JSON.parse(this.text())
  }
}

/** A small HTTP client over `fetch` for the sidecar's routes. */
export class Http {
  readonly baseUrl: string

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl
    Object.freeze(this)
  }

  async request(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<HttpResponse> {
    const init: RequestInit = { method, headers: { ...headers } }
    if (body !== undefined) {
      if (body instanceof Uint8Array) init.body = body
      else if (typeof body === "string") {
        init.body = body
        ;(init.headers as Record<string, string>)["content-type"] ??= "text/plain; charset=utf-8"
      } else {
        init.body = JSON.stringify(body)
        ;(init.headers as Record<string, string>)["content-type"] ??= "application/json"
      }
    }
    const r = await fetch(this.baseUrl + path, init)
    return new HttpResponse(r.status, r.headers, new Uint8Array(await r.arrayBuffer()))
  }

  get(path: string, headers?: Record<string, string>): Promise<HttpResponse> {
    return this.request("GET", path, undefined, headers)
  }
  post(path: string, body?: unknown, headers?: Record<string, string>): Promise<HttpResponse> {
    return this.request("POST", path, body, headers)
  }
  put(path: string, body?: unknown, headers?: Record<string, string>): Promise<HttpResponse> {
    return this.request("PUT", path, body, headers)
  }
  delete(path: string, headers?: Record<string, string>): Promise<HttpResponse> {
    return this.request("DELETE", path, undefined, headers)
  }

  /** The frames of an SSE response, each `data:` line decoded from its JSON. */
  async sse(path: string, headers?: Record<string, string>): Promise<string[]> {
    const r = await this.get(path, headers)
    return r
      .text()
      .split("\n")
      .filter((line) => line.startsWith("data:"))
      .map((line) => JSON.parse(line.slice(5).trim()) as string)
  }
}

/** Another service started on this kit's database, from `startBeside`. */
export interface Beside {
  readonly http: Http
  logs(): Promise<string>
  stop(): Promise<void>
}

function sidecarImage(): string {
  return process.env.ANKKA_SIDECAR_IMAGE ?? "ankka-sidecar:latest"
}

/** The DDL as files, from `/opt/docker/ddl` in the sidecar image, without running the image. */
function copyDdl(image: string, into: string): void {
  const id = execFileSync("docker", ["create", image], { encoding: "utf8" }).trim()
  try {
    execFileSync("docker", ["cp", `${id}:/opt/docker/ddl/.`, into], { stdio: "pipe" })
  } finally {
    try {
      execFileSync("docker", ["rm", "-f", id], { stdio: "ignore" })
    } catch {
      // the container may already be gone
    }
  }
  // Readable by the container's own `postgres` user: the entrypoint lists the directory under `set -e`
  // before initdb, and a private directory fails that check on Linux (Docker Desktop hides it).
  chmodSync(into, 0o755)
  for (const f of readdirSync(into)) if (statSync(join(into, f)).isFile()) chmodSync(join(into, f), 0o644)
}

async function logsOf(container: StartedTestContainer | undefined): Promise<string> {
  if (!container) return ""
  try {
    const stream = await container.logs()
    return await new Promise<string>((resolve) => {
      let out = ""
      stream.on("data", (line: string) => (out += line))
      stream.on("end", () => resolve(out))
      stream.on("error", () => resolve(out))
      setTimeout(() => resolve(out), 2000)
    })
  } catch {
    return "(no logs could be read)"
  }
}

export class AnkkaTestKit {
  readonly #service: ServiceBuilder
  readonly #image: string
  readonly #env: Readonly<Record<string, string>>
  readonly #postgresImage: string
  readonly #readyTimeoutMs: number
  readonly #log: (message: string) => void
  #ddlDir: string | undefined
  #network: StartedNetwork | undefined
  #postgres: StartedPostgreSqlContainer | undefined
  #sidecar: StartedTestContainer | undefined
  #server: Server | undefined
  #processPort = 0
  #http: Http | undefined

  /** The component client every handler in `service` uses, pointed at the sidecar's mapped callback port. */
  readonly client: ComponentClient

  private constructor(service: ServiceBuilder, options: AnkkaTestKitOptions) {
    this.#service = service
    this.#image = options.image ?? sidecarImage()
    this.#env = options.env ?? {}
    this.#postgresImage = options.postgresImage ?? POSTGRES_IMAGE
    this.#readyTimeoutMs = options.readyTimeoutMs ?? 120_000
    this.#log = options.log ?? (() => {})
    this.client = service.client
  }

  /** Starts Postgres, this process's server and the sidecar; a start that fails half-way stops what it started. */
  static async start(service: ServiceBuilder, options: AnkkaTestKitOptions = {}): Promise<AnkkaTestKit> {
    const kit = new AnkkaTestKit(service, options)
    try {
      await kit.#start()
    } catch (e) {
      const logs = await logsOf(kit.#sidecar)
      await kit.stop()
      if (logs) throw new Error(`${e instanceof Error ? e.message : String(e)}\n--- sidecar logs ---\n${logs.slice(-4000)}`, { cause: e })
      throw e
    }
    return kit
  }

  /** The sidecar's HTTP surface: the routes this service declared. */
  get http(): Http {
    if (!this.#http) throw new Error("the testkit is not started")
    return this.#http
  }

  get baseUrl(): string {
    return this.http.baseUrl
  }

  /** The JDBC URL of the throwaway database. */
  get jdbcUrl(): string {
    if (!this.#postgres) throw new Error("the testkit is not started")
    return `jdbc:postgresql://${this.#postgres.getHost()}:${this.#postgres.getPort()}/${this.#postgres.getDatabase()}`
  }

  async #start(): Promise<void> {
    const tc = await import("testcontainers")
    const pg = await import("@testcontainers/postgresql")
    this.#ddlDir = mkdtempSync(join(tmpdir(), "ankka-ddl-"))
    copyDdl(this.#image, this.#ddlDir)
    this.#network = await new tc.Network().start()
    this.#postgres = await new pg.PostgreSqlContainer(this.#postgresImage)
      .withDatabase("ankka")
      .withUsername("ankka")
      .withPassword("ankka")
      .withNetwork(this.#network)
      .withNetworkAliases("postgres")
      .withCopyDirectoriesToContainer([{ source: this.#ddlDir, target: "/docker-entrypoint-initdb.d", mode: 0o755 }])
      .start()
    // This process's server, on all interfaces: the sidecar is in a container and dials in.
    this.#server = this.#service.server({ host: "0.0.0.0", port: 0, log: this.#log })
    this.#processPort = (await this.#server.start()).port
    await this.#startSidecar()
  }

  async #startSidecar(): Promise<void> {
    const tc = await import("testcontainers")
    const container = await new tc.GenericContainer(this.#image)
      .withNetwork(this.#network!)
      .withExtraHosts([{ host: "host.docker.internal", ipAddress: "host-gateway" }])
      .withEnvironment({
        ANKKA_PROCESS_ADDRESS: `host.docker.internal:${this.#processPort}`,
        ANKKA_SIDECAR_BIND: "0.0.0.0",
        ANKKA_HTTP_PORT: String(HTTP_PORT),
        ANKKA_DB_HOST: "postgres",
        ANKKA_DB_PORT: "5432",
        ANKKA_DB_NAME: "ankka",
        ANKKA_DB_USER: "ankka",
        ANKKA_DB_PASSWORD: "ankka",
        ...this.#env,
      })
      .withExposedPorts(HTTP_PORT, CALLBACK_PORT)
      .withWaitStrategy(tc.Wait.forHttp("/_ankka/health", HTTP_PORT).forStatusCode(200))
      .withStartupTimeout(this.#readyTimeoutMs)
      .start()
    this.#sidecar = container
    this.client.reconnect(`${container.getHost()}:${container.getMappedPort(CALLBACK_PORT)}`)
    this.#http = new Http(`http://${container.getHost()}:${container.getMappedPort(HTTP_PORT)}`)
  }

  /** Replaces the sidecar against the same database: every instance is gone from memory, so the next read rebuilds from the journal. */
  async restart(): Promise<void> {
    if (this.#sidecar) {
      await this.#sidecar.stop()
      this.#sidecar = undefined
    }
    await this.#startSidecar()
  }

  /** Starts another image against this kit's Postgres — the Scala cart, say, to prove the journal is shared. */
  async startBeside(image: string, env: Readonly<Record<string, string>> = {}, httpPort = HTTP_PORT): Promise<Beside> {
    const tc = await import("testcontainers")
    const container = await new tc.GenericContainer(image)
      .withNetwork(this.#network!)
      .withEnvironment({
        ANKKA_HTTP_PORT: String(httpPort),
        ANKKA_DB_HOST: "postgres",
        ANKKA_DB_PORT: "5432",
        ANKKA_DB_NAME: "ankka",
        ANKKA_DB_USER: "ankka",
        ANKKA_DB_PASSWORD: "ankka",
        ...env,
      })
      .withExposedPorts(httpPort)
      .withWaitStrategy(tc.Wait.forHttp("/_ankka/health", httpPort).forStatusCode(200))
      .withStartupTimeout(this.#readyTimeoutMs)
      .start()
    return {
      http: new Http(`http://${container.getHost()}:${container.getMappedPort(httpPort)}`),
      logs: () => logsOf(container),
      stop: async () => {
        await container.stop()
      },
    }
  }

  /** The sidecar container's log so far. */
  sidecarLogs(): Promise<string> {
    return logsOf(this.#sidecar)
  }

  async stop(): Promise<void> {
    if (this.#server) {
      await this.#server.stop().catch(() => undefined)
      this.#server = undefined
    }
    if (this.#sidecar) {
      await this.#sidecar.stop().catch(() => undefined)
      this.#sidecar = undefined
    }
    if (this.#postgres) {
      await this.#postgres.stop().catch(() => undefined)
      this.#postgres = undefined
    }
    if (this.#network) {
      await this.#network.stop().catch(() => undefined)
      this.#network = undefined
    }
    if (this.#ddlDir) {
      rmSync(this.#ddlDir, { recursive: true, force: true })
      this.#ddlDir = undefined
    }
  }

  async [Symbol.asyncDispose](): Promise<void> {
    await this.stop()
  }
}
