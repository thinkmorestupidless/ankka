// Autonomous agents: a durable agent that is handed a task and works it on its own, iteration by
// iteration, until it completes the task with a result its type's rules accept, fails it, or spends
// its budget. This process declares the agent — what it is for, its tools and guardrails, the task
// types it accepts — and the sidecar runs the loop, the model and every record. The process is asked
// to run a tool, check a guardrail or check a task rule, each naming the task it is for.
//
//   export const ANSWER = taskType("answer", "Answer a question", { result: Answer, rules: [taskRule("cites-sources", (a) => a.sources.length ? accepted() : rejected("cite something"))] })
//   export class Answerer extends AutonomousAgent {
//     static readonly componentId = "answerer"
//     static readonly description = "Answers questions"
//     static readonly tools = { lookup: tool("lookup", "Looks a topic up", Topic, (a: Answerer, t) => a.lookup(t)) }
//     static readonly accepts = [taskAcceptance(ANSWER, { maxIterations: 5 })]
//   }
//
// A tool may run more than once for one request of the model — after a crash, the last recorded
// request's tools run again — so a tool with a side effect should tolerate a repeat.

import type { ComponentClient } from "./client.ts"
import type { GuardrailRef, ToolRef } from "./handlers.ts"
import { decodeJsonValue, reviver } from "./json.ts"
import type { Schema } from "./schema.ts"
import { toJsonSchema } from "./schema.ts"

type MaybePromise<T> = T | Promise<T>

export const COMPLETE_TASK = "complete_task"
export const FAIL_TASK = "fail_task"

// ── Declaring ────────────────────────────────────────────────────────────────

export interface Accepted {
  readonly verdict: "accepted"
}
export interface Rejected {
  readonly verdict: "rejected"
  readonly reason: string
}
export const accepted = (): Accepted => Object.freeze({ verdict: "accepted" })
export const rejected = (reason: string): Rejected => Object.freeze({ verdict: "rejected", reason })

/** A check a result must pass. Rules run in declaration order; the first rejection is reported. */
export interface TaskRule<R> {
  readonly name: string
  readonly check: (result: R) => MaybePromise<Accepted | Rejected>
}

export function taskRule<R>(name: string, check: (result: R) => MaybePromise<Accepted | Rejected>): TaskRule<R> {
  if (typeof name !== "string" || name.trim() === "") throw new TypeError("a task rule needs a name")
  return Object.freeze({ name, check })
}

/** A kind of work. `name` is the wire name, written into every task of this type. */
export interface TaskType<R> {
  readonly name: string
  readonly description: string
  /** The result's shape; absent, the result is text. */
  readonly result: Schema<R> | undefined
  readonly rules: readonly TaskRule<R>[]
}

export function taskType<R = string>(name: string, description: string, options: { result?: Schema<R>; rules?: readonly TaskRule<R>[] } = {}): TaskType<R> {
  if (typeof name !== "string" || name.trim() === "") throw new TypeError("a task type needs a name")
  if (typeof description !== "string" || description.trim() === "") throw new TypeError(`task type ${name} needs a description`)
  const rules = options.rules ?? []
  const names = rules.map((r) => r.name)
  if (new Set(names).size !== names.length) throw new TypeError(`task type ${name} declares a rule twice`)
  return Object.freeze({ name, description, result: options.result, rules: Object.freeze([...rules]) })
}

/** Decodes a stored result as the task type's. */
export function decodeResult<R>(type: TaskType<R>, stored: string): R {
  const json = JSON.parse(stored, reviver) as unknown
  return type.result ? decodeJsonValue(type.result, json) : (json as R)
}

export interface TaskAcceptance {
  readonly taskType: TaskType<any>
  readonly maxIterations: number
}

export function taskAcceptance(type: TaskType<any>, options: { maxIterations?: number } = {}): TaskAcceptance {
  return Object.freeze({ taskType: type, maxIterations: options.maxIterations ?? 10 })
}

export interface AutonomousSettings {
  readonly approachingBudgetAt?: number
  readonly repeatedFailureAt?: number
  readonly maxConsecutiveFailures?: number
  readonly dependencyStuckAfterMillis?: number
}

export abstract class AutonomousAgent {
  #sessionId = ""
  #client: ComponentClient | undefined

  /** The task a tool, a guardrail or a rule is running for. */
  get taskId(): string {
    return this.#sessionId.startsWith("task:") ? this.#sessionId.slice("task:".length) : this.#sessionId
  }

  get client(): ComponentClient {
    if (!this.#client) throw new Error("client is only available inside a tool, a guardrail or a rule")
    return this.#client
  }

  /** @internal */
  get _kind(): "autonomous-agent" {
    return "autonomous-agent"
  }

  /** @internal */
  _bind(sessionId: string, client: ComponentClient): void {
    this.#sessionId = sessionId
    this.#client = client
  }
}

export interface AutonomousAgentClass<C extends AutonomousAgent = AutonomousAgent> {
  new (): C
  readonly componentId: string
  readonly description: string
  readonly instructions?: string
  /** A model named in the sidecar's configuration; absent, its default. */
  readonly model?: string
  readonly tools?: Readonly<Record<string, ToolRef<C, any>>>
  readonly guardrails?: Readonly<Record<string, GuardrailRef>>
  readonly accepts: readonly TaskAcceptance[]
  readonly settings?: AutonomousSettings
}

/** The discovery form of a result schema. */
export function resultSchemaJson(type: TaskType<any>): string | undefined {
  return type.result ? JSON.stringify(toJsonSchema(type.result)) : undefined
}

// ── Calling ──────────────────────────────────────────────────────────────────

export interface Attachment {
  readonly name: string
  readonly contentType: string
  readonly content?: string
  readonly uri?: string
}

export interface TaskSnapshot<R> {
  readonly id: string
  readonly typeName: string
  readonly status: "pending" | "assigned" | "in-progress" | "result-rejected" | "completed" | "failed" | "cancelled"
  readonly result: R | undefined
  readonly reason: string | undefined
  readonly iterations: number
  readonly assignee: { readonly componentId: string; readonly instanceId: string } | undefined
  readonly record: Record<string, unknown>
}

export interface AgentState {
  readonly phase: "idle" | "working" | "waiting" | "suspended" | "terminated"
  readonly currentTask: string | undefined
  readonly iteration: number
  readonly queued: readonly string[]
}

/** One thing an instance did: `type` names it, and the rest of its fields are as sent. */
export interface Notification {
  readonly type: string
  readonly componentId: string
  readonly instanceId: string
  readonly taskId?: string
  readonly at: number
  readonly [field: string]: unknown
}

const TASK = "ankka-task"
const INSTANCE = "ankka-agent-instance"
const ENDED = new Set(["completed", "failed", "cancelled"])

/** Creates tasks: `await client.tasks.create(ANSWER, "How many?")` answers the new task's id. */
export class Tasks {
  readonly #client: ComponentClient
  constructor(client: ComponentClient) {
    this.#client = client
  }

  /** A dependency that does not exist is refused before anything is written; one that has already ended cancels the new task. */
  async create(type: TaskType<any>, instructions: string, options: { id?: string; attachments?: readonly Attachment[]; dependsOn?: readonly string[] } = {}): Promise<string> {
    const id = options.id ?? crypto.randomUUID()
    const dependencies = [...new Set(options.dependsOn ?? [])]
    for (const dep of dependencies) await this.#client._raw("event-sourced", TASK, dep, "get")
    await this.#client._raw("event-sourced", TASK, id, "create", {
      typeName: type.name,
      instructions,
      attachments: (options.attachments ?? []).map((a) => ({
        name: a.name,
        contentType: a.contentType,
        content: a.uri !== undefined ? { type: "Reference", uri: a.uri } : { type: "Inline", text: a.content ?? "" },
      })),
      dependencies,
    })
    for (const dep of dependencies) {
      const answer = (await this.#client._raw("event-sourced", TASK, dep, "add-dependent", { taskId: id })) as { alreadyEnded?: string } | undefined
      if (answer?.alreadyEnded) {
        await this.#client._raw("event-sourced", TASK, id, "cancel", { reason: `dependency '${dep}' ${answer.alreadyEnded}` })
        break
      }
    }
    return id
  }
}

/** Calls about one task. */
export class TaskCalls {
  readonly #client: ComponentClient
  readonly taskId: string
  constructor(client: ComponentClient, taskId: string) {
    this.#client = client
    this.taskId = taskId
  }

  async get<R = unknown>(type?: TaskType<R>): Promise<TaskSnapshot<R>> {
    const record = (await this.#client._raw("event-sourced", TASK, this.taskId, "get")) as Record<string, any>
    if (type && record.typeName !== type.name) throw new TypeError(`task '${this.taskId}' is a '${record.typeName}' task, not '${type.name}'`)
    const stored = record.result as string | undefined
    return Object.freeze({
      id: record.id,
      typeName: record.typeName,
      status: record.status,
      result: stored === undefined || stored === null ? undefined : type ? decodeResult(type, stored) : (JSON.parse(stored) as R),
      reason: record.reason ?? undefined,
      iterations: record.iterations ?? 0,
      assignee: record.assignee ?? undefined,
      record,
    })
  }

  /** Waits until the task has completed, failed or been cancelled. */
  async wait<R = unknown>(type?: TaskType<R>, options: { timeoutMs?: number } = {}): Promise<TaskSnapshot<R>> {
    const deadline = Date.now() + (options.timeoutMs ?? 600_000)
    for (;;) {
      const snapshot = await this.get(type)
      if (ENDED.has(snapshot.status)) return snapshot
      if (Date.now() > deadline) throw new Error(`task '${this.taskId}' had not ended in time; it is ${snapshot.status}`)
      await new Promise((resolve) => setTimeout(resolve, 250))
    }
  }

  /** Cancels the task: at once when waiting, at the end of the current iteration when being worked. */
  async cancel(reason = "cancelled by caller"): Promise<void> {
    await this.#client._raw("event-sourced", TASK, this.taskId, "cancel", { reason })
    const record = (await this.#client._raw("event-sourced", TASK, this.taskId, "get")) as { assignee?: { componentId: string; instanceId: string } }
    if (record.assignee) {
      await this.#client._raw("autonomous-agent", record.assignee.componentId, record.assignee.instanceId, "dequeue", { taskId: this.taskId, reason })
    }
  }
}

/** Calls to one instance of an autonomous agent, by the id the caller chose; with no id, only `runSingleTask`. */
export class AutonomousAgentCalls {
  readonly #client: ComponentClient
  readonly componentId: string
  readonly instanceId: string | undefined
  constructor(client: ComponentClient, componentId: string, instanceId: string | undefined) {
    this.#client = client
    this.componentId = componentId
    this.instanceId = instanceId
  }

  #instance(): string {
    if (!this.instanceId) throw new TypeError("this call needs an instance id: forAutonomousAgent(agent, instanceId)")
    return this.instanceId
  }

  /** Creates the task, starts an instance on it, and answers the task's id at once. */
  async runSingleTask(type: TaskType<any>, instructions: string, options: Parameters<Tasks["create"]>[2] = {}): Promise<string> {
    const taskId = await new Tasks(this.#client).create(type, instructions, options)
    await this.#client._raw("autonomous-agent", this.componentId, crypto.randomUUID(), "run-single-task", { taskId })
    return taskId
  }

  assign(...taskIds: string[]): Promise<{ accepted: string[]; refused: Record<string, { message: string; code: string }> }> {
    return this.#client._raw("autonomous-agent", this.componentId, this.#instance(), "assign", { taskIds }) as Promise<any>
  }

  async suspend(): Promise<void> {
    await this.#client._raw("autonomous-agent", this.componentId, this.#instance(), "suspend")
  }

  async resume(): Promise<void> {
    await this.#client._raw("autonomous-agent", this.componentId, this.#instance(), "resume")
  }

  /** Stops the instance for good; its tasks go back to pending for another to take. */
  async terminate(): Promise<void> {
    await this.#client._raw("autonomous-agent", this.componentId, this.#instance(), "terminate")
  }

  async state(): Promise<AgentState> {
    const r = (await this.#client._raw("event-sourced", INSTANCE, `${this.componentId}/${this.#instance()}`, "get")) as Record<string, any>
    const current = r.current as { taskId: string; iteration: number } | undefined
    const phase = r.terminated ? "terminated" : r.suspended ? "suspended" : current ? "working" : (r.queue ?? []).length > 0 ? "waiting" : "idle"
    return Object.freeze({ phase, currentTask: current?.taskId, iteration: current?.iteration ?? 0, queued: [...(r.queue ?? [])] })
  }

  /** What the instance does from now on, as it happens. Nothing is replayed. */
  async *notifications(): AsyncIterable<Notification> {
    for await (const text of this.#client._rawStream("autonomous-agent", this.componentId, this.#instance(), "notifications")) {
      yield JSON.parse(text) as Notification
    }
  }
}
