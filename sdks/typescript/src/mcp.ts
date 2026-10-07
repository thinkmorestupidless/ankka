// MCP servers an agent offers the model, and the guardrails that check what they answer.
//
// The sidecar connects to each server when it starts, reads its tools and offers them to the model as
// `mcp__<server>__<tool>`. This process is never asked to run one, and never sees a credential: a
// header's value is read from a variable on the sidecar, and only `ANKKA_MCP_` variables reach it. What a
// server's tool answers is put through the agent's result guardrails, which this process answers,
// before the model is told it.
//
//   static readonly mcpServers = { tickets: mcpServer("tickets", { headers: { Authorization: "ANKKA_MCP_TICKETS_TOKEN" } }) }
//   static readonly resultGuardrails = { noInstructions: resultGuardrail("no-instructions", (tool, text) => null) }

import { approvalProblem, approvalToProto, type Approval } from "./approvals.ts"

export const VARIABLE_PREFIX = "ANKKA_MCP_"
export const TOOL_PREFIX = "mcp__"
const NAME = /^[a-z0-9-]+$/

type MaybePromise<T> = T | Promise<T>

export interface McpServerOptions {
  /** Where the server is; `ANKKA_MCP_<NAME>_URL` replaces it, and is required when neither this nor `service` is given. */
  readonly url?: string
  /** An ankka service, called as one, at `path` (by default `/mcp`), in `project` (by default this one). */
  readonly service?: string
  readonly project?: string
  readonly path?: string
  /** A header's name and the `ANKKA_MCP_` variable its value is read from. */
  readonly headers?: Readonly<Record<string, string>>
  /** Every tool of the server waits for a person. */
  readonly approval?: Approval
}

export interface McpServerRef extends McpServerOptions {
  readonly name: string
}

/** An MCP server the agent lists under `name`, lower-case letters, digits and hyphens. */
export function mcpServer(name: string, options: McpServerOptions = {}): McpServerRef {
  return Object.freeze({ ...options, name })
}

/** Checks what an MCP server's tool answered before the model is told it: a reason to withhold it, or `null`. */
export interface ResultGuardrailRef {
  readonly name: string
  readonly check: (tool: string, text: string) => MaybePromise<string | null>
}

export function resultGuardrail(name: string, check: (tool: string, text: string) => MaybePromise<string | null>): ResultGuardrailRef {
  if (typeof name !== "string" || name.trim() === "") throw new TypeError("a result guardrail needs a name")
  return Object.freeze({ name, check })
}

/** The name a server's tool is offered to the model under. */
export function toolName(server: string, tool: string): string {
  return `${TOOL_PREFIX}${server}__${tool}`
}

/** What is wrong with one server, in the sidecar's words. */
export function serverProblems(server: McpServerRef): string[] {
  const found: string[] = []
  const name = server.name
  if (!NAME.test(name)) found.push(`an MCP server's name is lower-case letters, digits and hyphens ([a-z0-9-]), not '${name}'`)
  if (server.url !== undefined && server.service !== undefined) found.push(`MCP server '${name}' has both a URL and a service; give one`)
  if (server.url !== undefined && server.url.trim() === "") found.push(`MCP server '${name}' needs a URL`)
  for (const [header, variable] of Object.entries(server.headers ?? {})) {
    if (header.trim() === "") found.push(`MCP server '${name}' has a header with no name`)
    else if (!variable.startsWith(VARIABLE_PREFIX)) {
      found.push(
        `MCP server '${name}': the header '${header}' takes its value from '${variable}', which must start ${VARIABLE_PREFIX} — only those variables reach the platform's program, which is what connects to the server`,
      )
    }
  }
  const approval = approvalProblem(server.approval)
  if (approval) found.push(`MCP server '${name}': ${approval}`)
  return found
}

/** The discovery form of a server. */
export function serverToProto(server: McpServerRef) {
  const approval = approvalToProto(server.approval)
  const address =
    server.url !== undefined
      ? { case: "url" as const, value: server.url }
      : server.service !== undefined
        ? { case: "service" as const, value: { name: server.service, path: server.path ?? "/mcp", ...(server.project !== undefined ? { project: server.project } : {}) } }
        : { case: undefined }
  return {
    name: server.name,
    address,
    headers: Object.entries(server.headers ?? {}).map(([name, variable]) => ({ name, variable })),
    ...(approval !== undefined ? { approval } : {}),
  }
}
