// Unit testkits for the other kinds: key value entities, workflows, views, consumers, timed actions and
// agents. Same rules as the event sourced kit: no sidecar, effects as values, every value round-tripped
// through the component's codecs, the server's reduction.

import { ComponentClient, noClient } from "../client.ts"
import { binaryCodecs, codecFor, type Codec, type Shape } from "../codec.ts"
import { commandContext } from "../context.ts"
import { CommandError, ErrorCode, type ErrorDetail, type Metadata } from "../effects/common.ts"
import type { KeyValueEffect } from "../effects/keyValue.ts"
import type { StepEffect, StepRef, WorkflowCommandEffect } from "../effects/workflow.ts"
import type { ConsumerEffect, TimedActionEffect, ViewEffect } from "../effects/stateless.ts"
import type { AgentEffect } from "../effects/agent.ts"
import type { KeyValueEntity, KeyValueEntityClass } from "../keyValueEntity.ts"
import type { Workflow, WorkflowClass } from "../workflow.ts"
import type { View, ViewClass } from "../view.ts"
import { PROTOCOL_KEY, requireSeveralMessages, type Consumer, type ConsumerClass } from "../consumer.ts"
import { deltaRecords, readDelta, type Delta, type GraphConsumer, type GraphConsumerClass, type GraphEffect } from "../graph.ts"
import { PROTOCOL_VERSION } from "../spec.ts"
import type { TimedAction, TimedActionClass } from "../timedAction.ts"
import type { Agent, AgentClass } from "../agent.ts"
import type { HandlerRef } from "../handlers.ts"
import { materialiseKeyValue, materialiseStep, materialiseWorkflowCommand, type MaterialisedKeyValue, type MaterialisedStep, type MaterialisedWorkflowCommand } from "../materialise.ts"
import { Ankka, type RegisteredAgent, type RegisteredConsumer, type RegisteredKeyValue, type RegisteredTimedAction, type RegisteredView, type RegisteredWorkflow } from "../service.ts"
import { checkPlan, runTool } from "../server/agent.ts"
import { ApprovalAwaited, type AgentOutcome, type ApprovalRequest, type DecisionInput } from "../approvals.ts"
import { TOOL_PREFIX, toolName } from "../mcp.ts"

function roundTrip<T>(codec: Codec<T>, value: T): T {
  return codec.decode(codec.encode(value))
}

function findHandler(handlers: ReadonlyMap<string, HandlerRef<any, any, any, any>>, handler: HandlerRef<any, any, any, any> | string, who: string): HandlerRef<any, any, any, any> {
  const name = typeof handler === "string" ? handler : handler.name
  const found = handlers.get(name)
  if (!found) throw new Error(`${who} has no handler ${JSON.stringify(name)}; declared: ${[...handlers.keys()].sort().join(", ")}`)
  return found
}

function registryFor(cls: unknown, client: ComponentClient) {
  return Ankka.service({ client, log: () => {} })
    .register(cls as never)
    .validate()
}

/** Drives one key value entity instance through its handlers. */
export class KeyValueTestKit<S, C extends KeyValueEntity<S>> {
  readonly #registered: RegisteredKeyValue
  readonly #entity: C
  readonly #client: ComponentClient
  readonly entityId: string
  state: S

  private constructor(cls: KeyValueEntityClass<S, C>, entityId: string, client: ComponentClient) {
    this.#registered = registryFor(cls, client).component(cls.componentId) as RegisteredKeyValue
    this.#entity = new cls()
    this.#entity._bindInstance(entityId)
    this.#client = client
    this.entityId = entityId
    this.state = this.#entity.emptyState()
  }

  static of<S, C extends KeyValueEntity<S>>(cls: KeyValueEntityClass<S, C>, entityId = "test", client: ComponentClient = noClient()): KeyValueTestKit<S, C> {
    return new KeyValueTestKit(cls, entityId, client)
  }

  async call<I, R>(handler: HandlerRef<C, I, R, any> | string, input?: I, metadata: Metadata = {}): Promise<MaterialisedKeyValue<S, R>> {
    const ref = findHandler(this.#registered.handlers, handler, this.#registered.cls.name)
    const wireInput = ref.input ? roundTrip(codecFor(ref.input as Shape<I>), input as I) : undefined
    this.#entity._bindCommand(this.state, commandContext(this.#registered.id, this.entityId, 0n, metadata), this.#client.withMetadata(metadata))
    let effect: KeyValueEffect<S, R>
    try {
      effect = (await ref.run(this.#entity, wireInput)) as KeyValueEffect<S, R>
    } finally {
      this.#entity._unbindCommand()
    }
    if (ref.readOnly && effect.kind !== "read-only") throw new Error(`${this.#registered.id}/${ref.name} is a query and returned an updating effect`)
    const m = materialiseKeyValue(effect, this.state)
    if (m.error) return m
    const newState = roundTrip(this.#registered.stateCodec as Codec<S>, m.newState)
    const replyCodec = (ref.reply ? codecFor(ref.reply as Shape<R>) : binaryCodecs.done) as Codec<R>
    const reply = m.noReply ? undefined : roundTrip(replyCodec, m.reply as R)
    this.state = newState
    return Object.freeze({ ...m, newState, reply })
  }
}

/** What a workflow is doing, as the kit tracks it between calls. */
export interface WorkflowProgress {
  /** The step the last effect asked to run next, not yet run. */
  readonly pending: StepRef | null
  /** Set while the workflow is paused; `resume()` runs `onTimeout` as the engine would after `after`. */
  readonly paused: { readonly after: number | null; readonly onTimeout: StepRef | null } | null
  readonly ended: boolean
  readonly failed: ErrorDetail | null
}

/** Drives one workflow instance: commands, steps, and the transitions between them, stopping at a pause. */
export class WorkflowTestKit<S, C extends Workflow<S>> {
  readonly #registered: RegisteredWorkflow
  readonly #cls: WorkflowClass<S, C>
  readonly #client: ComponentClient
  readonly entityId: string
  state: S
  #progress: WorkflowProgress = { pending: null, paused: null, ended: false, failed: null }

  private constructor(cls: WorkflowClass<S, C>, entityId: string, client: ComponentClient) {
    this.#registered = registryFor(cls, client).component(cls.componentId) as RegisteredWorkflow
    this.#cls = cls
    this.#client = client
    this.entityId = entityId
    const instance = new cls()
    instance._bindInstance(entityId)
    this.state = instance.emptyState()
  }

  static of<S, C extends Workflow<S>>(cls: WorkflowClass<S, C>, entityId = "test", client: ComponentClient = noClient()): WorkflowTestKit<S, C> {
    return new WorkflowTestKit(cls, entityId, client)
  }

  get progress(): WorkflowProgress {
    return this.#progress
  }

  #instance(): C {
    const w = new this.#cls()
    w._bindInstance(this.entityId)
    return w
  }

  async call<I, R>(handler: HandlerRef<C, I, R, any> | string, input?: I, metadata: Metadata = {}): Promise<MaterialisedWorkflowCommand<S, R>> {
    const ref = findHandler(this.#registered.handlers, handler, this.#registered.cls.name)
    const wireInput = ref.input ? roundTrip(codecFor(ref.input as Shape<I>), input as I) : undefined
    const w = this.#instance()
    w._bindCommand(this.state, commandContext(this.#registered.id, this.entityId, 0n, metadata), this.#client.withMetadata(metadata))
    let effect: WorkflowCommandEffect<S, R>
    try {
      effect = (await ref.run(w, wireInput)) as WorkflowCommandEffect<S, R>
    } finally {
      w._unbindCommand()
    }
    if (ref.readOnly && effect.kind !== "read-only") throw new Error(`${this.#registered.id}/${ref.name} is a query and returned a changing effect`)
    const m = materialiseWorkflowCommand(effect, this.state)
    if (m.error) return m
    const newState = roundTrip(this.#registered.stateCodec as Codec<S>, m.newState)
    const replyCodec = (ref.reply ? codecFor(ref.reply as Shape<R>) : binaryCodecs.done) as Codec<R>
    const reply = m.noReply ? undefined : roundTrip(replyCodec, m.reply as R)
    this.state = newState
    if (m.transition) {
      this.#checkStep(m.transition)
      this.#progress = { pending: m.transition, paused: null, ended: false, failed: null }
    }
    return Object.freeze({ ...m, newState, reply })
  }

  #checkStep(ref: StepRef): void {
    if (!this.#registered.steps.has(ref.step)) throw new Error(`${this.#registered.id} transitions to ${JSON.stringify(ref.step)}, which is not a declared step`)
  }

  /** Runs one step, by name or declaration, with an input (or the pending transition's). */
  async runStep(step: HandlerRef<C, any, any, any> | string, input?: unknown): Promise<MaterialisedStep<S>> {
    const ref = findHandler(this.#registered.steps, step, this.#registered.cls.name)
    const wireInput = ref.input ? roundTrip(codecFor(ref.input as Shape<unknown>), input) : undefined
    const w = this.#instance()
    w._bindCommand(this.state, commandContext(this.#registered.id, this.entityId, 0n, {}), this.#client)
    let effect: StepEffect<S>
    try {
      effect = (await ref.run(w, wireInput)) as StepEffect<S>
    } finally {
      w._unbindCommand()
    }
    const m = materialiseStep(effect, this.state)
    const newState = roundTrip(this.#registered.stateCodec as Codec<S>, m.newState)
    this.state = newState
    switch (m.next.kind) {
      case "transition":
        this.#checkStep(m.next.ref)
        this.#progress = { pending: m.next.ref, paused: null, ended: false, failed: null }
        break
      case "pause":
        if (m.next.onTimeout) this.#checkStep(m.next.onTimeout)
        this.#progress = { pending: null, paused: { after: m.next.after?.toMillis() ?? null, onTimeout: m.next.onTimeout }, ended: false, failed: null }
        break
      case "end":
        this.#progress = { pending: null, paused: null, ended: true, failed: null }
        break
      case "fail":
        this.#progress = { pending: null, paused: null, ended: true, failed: m.next.error }
        break
    }
    return Object.freeze({ ...m, newState })
  }

  /** Follows transitions until the workflow ends or pauses. Returns the progress reached. */
  async runUntilEnd(limit = 100): Promise<WorkflowProgress> {
    for (let i = 0; i < limit && this.#progress.pending; i++) {
      const ref = this.#progress.pending
      await this.runStep(ref.step, ref.input)
    }
    if (this.#progress.pending) throw new Error(`the workflow did not end or pause within ${limit} steps`)
    return this.#progress
  }

  /** As the engine would when a pause's timeout passes: runs `onTimeout`, then follows transitions. */
  async resume(): Promise<WorkflowProgress> {
    if (!this.#progress.paused) throw new Error("the workflow is not paused")
    const ref = this.#progress.paused.onTimeout
    if (!ref) throw new Error("the pause has no onTimeout step; a command must move the workflow on")
    await this.runStep(ref.step, ref.input)
    return this.runUntilEnd()
  }
}

/** Feeds a view events by source key and keeps the rows as the sidecar would. */
export class ViewTestKit<E, Row, C extends View<E, Row>> {
  readonly #registered: RegisteredView
  readonly #cls: ViewClass<E, Row, C>
  readonly #client: ComponentClient
  readonly rows = new Map<string, Row>()

  private constructor(cls: ViewClass<E, Row, C>, client: ComponentClient) {
    this.#registered = registryFor(cls, client).component(cls.componentId) as RegisteredView
    this.#cls = cls
    this.#client = client
  }

  static of<E, Row, C extends View<E, Row>>(cls: ViewClass<E, Row, C>, client: ComponentClient = noClient()): ViewTestKit<E, Row, C> {
    return new ViewTestKit(cls, client)
  }

  async #apply(key: string, fn: (view: C) => Promise<ViewEffect<Row>> | ViewEffect<Row>, metadata: Metadata): Promise<ViewEffect<Row>> {
    const view = new this.#cls()
    const rowCodec = this.#registered.rowCodec as Codec<Row>
    const current = this.rows.get(key)
    view._bind(current === undefined ? null : roundTrip(rowCodec, current), { "ce-subject": key, ...metadata }, this.#client.withMetadata(metadata))
    const effect = await fn(view)
    switch (effect.kind) {
      case "update-row":
        this.rows.set(key, roundTrip(rowCodec, effect.row))
        break
      case "delete-row":
        this.rows.delete(key)
        break
      case "ignore":
        break
    }
    return effect
  }

  /** A change from source instance `key`: the event, round-tripped through the source's event codec. */
  onChange(key: string, event: E, metadata: Metadata = {}): Promise<ViewEffect<Row>> {
    const wire = roundTrip(this.#registered.eventCodec as Codec<E>, event)
    return this.#apply(key, (v) => v.onChange(wire), metadata)
  }

  /** The source instance `key` was deleted. */
  onDelete(key: string, metadata: Metadata = {}): Promise<ViewEffect<Row>> {
    return this.#apply(key, (v) => v.onDelete(), metadata)
  }

  get(key: string): Row | null {
    return this.rows.get(key) ?? null
  }
}

/** Feeds a consumer messages and collects what it produced. */
export class ConsumerTestKit<M, Out, C extends Consumer<M, Out>> {
  readonly #registered: RegisteredConsumer
  readonly #cls: ConsumerClass<M, Out, C>
  readonly #client: ComponentClient
  /**
   * Everything the consumer produced, one entry per message, round-tripped through the out codec.
   * `key` is the record key a message of `produceAll` named; absent, the message is keyed by its subject.
   */
  readonly produced: { readonly payload: Out; readonly metadata: Metadata; readonly key?: string }[] = []

  private constructor(cls: ConsumerClass<M, Out, C>, client: ComponentClient) {
    this.#registered = registryFor(cls, client).component(cls.componentId) as RegisteredConsumer
    this.#cls = cls
    this.#client = client
  }

  static of<M, Out, C extends Consumer<M, Out>>(cls: ConsumerClass<M, Out, C>, client: ComponentClient = noClient()): ConsumerTestKit<M, Out, C> {
    return new ConsumerTestKit(cls, client)
  }

  async #apply(fn: (c: C) => Promise<ConsumerEffect<Out>> | ConsumerEffect<Out>, metadata: Metadata): Promise<ConsumerEffect<Out>> {
    const consumer = new this.#cls()
    consumer._bind(metadata, this.#client.withMetadata(metadata))
    const effect = await fn(consumer)
    const outCodec = (): Codec<Out> => {
      const codec = this.#registered.outCodec as Codec<Out> | undefined
      if (!codec) throw new Error(`${this.#registered.id} produced a message but declares no out shape`)
      return codec
    }
    if (effect.kind === "produce") {
      this.produced.push({ payload: roundTrip(outCodec(), effect.payload), metadata: effect.metadata })
    } else if (effect.kind === "produceAll" && effect.messages.length > 0) {
      // The server's rule: several messages go only to a runtime that said it accepts them.
      requireSeveralMessages(metadata)
      for (const m of effect.messages) {
        this.produced.push({ payload: roundTrip(outCodec(), m.payload), metadata: m.metadata ?? {}, ...(m.key !== undefined ? { key: m.key } : {}) })
      }
    }
    return effect
  }

  /**
   * A message from source instance `subject`. The metadata says the runtime speaks this SDK's protocol
   * version; pass `"ankka.protocol"` or `"ankka.sequence"` to say otherwise.
   */
  onMessage(message: M, subject = "test", metadata: Metadata = {}): Promise<ConsumerEffect<Out>> {
    const wire = roundTrip(this.#registered.messageCodec as Codec<M>, message)
    return this.#apply((c) => c.onMessage(wire), { "ce-subject": subject, [PROTOCOL_KEY]: PROTOCOL_VERSION, ...metadata })
  }

  onDelete(subject = "test", metadata: Metadata = {}): Promise<ConsumerEffect<Out>> {
    return this.#apply((c) => c.onDelete(), { "ce-subject": subject, [PROTOCOL_KEY]: PROTOCOL_VERSION, ...metadata })
  }
}

/**
 * Feeds a graph consumer changes and answers with the elements it published: each read back from the
 * bytes that would be published, under the key it would be published under, so a test asserts on what
 * a reader of the topic sees.
 */
export class GraphConsumerTestKit<M> {
  readonly #registered: RegisteredConsumer
  readonly #cls: GraphConsumerClass<M>
  readonly #client: ComponentClient

  private constructor(cls: GraphConsumerClass<M>, client: ComponentClient) {
    this.#registered = registryFor(cls, client).component(cls.componentId) as RegisteredConsumer
    this.#cls = cls
    this.#client = client
  }

  static of<M>(cls: GraphConsumerClass<M>, client: ComponentClient = noClient()): GraphConsumerTestKit<M> {
    return new GraphConsumerTestKit(cls, client)
  }

  async #apply(fn: (c: GraphConsumer<M>) => Promise<GraphEffect> | GraphEffect, options: GraphChange): Promise<Delta[]> {
    const metadata: Metadata = {
      "ce-subject": options.subject ?? "test",
      "ankka.sequence": String(options.sequence ?? 1),
      [PROTOCOL_KEY]: PROTOCOL_VERSION,
      ...options.metadata,
    }
    const consumer = new this.#cls()
    consumer._bind(metadata, this.#client.withMetadata(metadata))
    const records = deltaRecords(await fn(consumer), metadata)
    if (records.length > 0) requireSeveralMessages(metadata)
    return records.map((r) => readDelta(r.value, r.key))
  }

  /** A change from source instance `subject` (default `"test"`) at `sequence` (default 1). */
  onMessage(message: M, options: GraphChange = {}): Promise<Delta[]> {
    const wire = roundTrip(this.#registered.messageCodec as Codec<M>, message)
    return this.#apply((c) => c.onMessage(wire), options)
  }

  /** The source instance was deleted, at `sequence`. */
  onDelete(options: GraphChange = {}): Promise<Delta[]> {
    return this.#apply((c) => c.onDelete(), options)
  }
}

/** The change a graph consumer is handed: whose it is and where in that instance's history. */
export interface GraphChange {
  readonly subject?: string
  /** The change's sequence number; `0` is a source with none, as a topic is. */
  readonly sequence?: number | bigint
  readonly metadata?: Metadata
}

/** Invokes a timed action's handlers as the sweeper would. */
export class TimedActionTestKit<C extends TimedAction> {
  readonly #registered: RegisteredTimedAction
  readonly #cls: TimedActionClass<C>
  readonly #client: ComponentClient

  private constructor(cls: TimedActionClass<C>, client: ComponentClient) {
    this.#registered = registryFor(cls, client).component(cls.componentId) as RegisteredTimedAction
    this.#cls = cls
    this.#client = client
  }

  static of<C extends TimedAction>(cls: TimedActionClass<C>, client: ComponentClient = noClient()): TimedActionTestKit<C> {
    return new TimedActionTestKit(cls, client)
  }

  async invoke<I>(action: HandlerRef<C, I, any, any> | string, input?: I, metadata: Metadata = {}): Promise<TimedActionEffect> {
    const ref = findHandler(this.#registered.actions, action, this.#registered.cls.name)
    const wireInput = ref.input ? roundTrip(codecFor(ref.input as Shape<I>), input as I) : undefined
    const instance = new this.#cls()
    instance._bind({ "ankka.timer": "test", "ankka.attempts": "1", ...metadata }, this.#client.withMetadata(metadata))
    return (await ref.run(instance, wireInput)) as TimedActionEffect
  }
}

/** What a scripted model answers next. */
export type ModelResponse = { readonly kind: "text"; readonly text: string } | { readonly kind: "tool-call"; readonly tool: string; readonly argumentsJson: string } | { readonly kind: "refusal"; readonly message: string }

/** What the model saw when it answered: the kit records one per model call. */
export interface ModelCall {
  readonly system: string | null
  readonly messages: readonly { readonly role: "user" | "assistant" | "tool"; readonly text: string }[]
  readonly tools: readonly string[]
}

/** A model that answers from a script and fails loudly when the script runs out. */
export class ScriptedModel {
  readonly #script: ModelResponse[] = []
  readonly calls: ModelCall[] = []

  expectText(text: string): this {
    this.#script.push({ kind: "text", text })
    return this
  }

  expectToolCall(tool: string, args: Record<string, unknown> | string = {}): this {
    this.#script.push({ kind: "tool-call", tool, argumentsJson: typeof args === "string" ? args : JSON.stringify(args) })
    return this
  }

  expectRefusal(message: string): this {
    this.#script.push({ kind: "refusal", message })
    return this
  }

  get remaining(): number {
    return this.#script.length
  }

  /** @internal */
  _next(call: ModelCall): ModelResponse {
    this.calls.push(call)
    const next = this.#script.shift()
    if (!next) throw new Error(`the scripted model ran out of responses after ${this.calls.length} call(s); script more with expectText/expectToolCall/expectRefusal`)
    return next
  }
}

/** Scripted MCP servers: `{ server: { tool: (args) => text } }`, called by the model as `mcp__<server>__<tool>`. */
export type ScriptedMcp = Readonly<Record<string, Readonly<Record<string, (args: Record<string, unknown>) => string | Promise<string>>>>>

type Message = { role: "user" | "assistant" | "tool"; text: string }

interface Turn {
  readonly plan: AgentEffect<unknown>
  readonly messages: Message[]
  steps: number
  pending?: { readonly request: ApprovalRequest; readonly tool: string; readonly argumentsJson: string }
}

/**
 * Runs an agent's turn the way the sidecar does, in process: the plan, input guardrails, the model
 * (scripted), tool calls back into the agent, output guardrails, and the session's history.
 *
 * `mcp` scripts the agent's MCP servers, and its result guardrails check what they answer. A call to a
 * tool that requires approval — the agent's own, or any of a server listed with `approval` — is not run:
 * `outcome` answers with its request, `ask` rejects with `ApprovalAwaited`, and `decide` goes on.
 */
export class AgentTestKit<C extends Agent> {
  readonly #registered: RegisteredAgent
  readonly #cls: AgentClass<C>
  readonly #client: ComponentClient
  readonly #model: ScriptedModel
  readonly #mcp: ScriptedMcp
  readonly sessionId: string
  /** The session's memory: what the user said and the agent answered, turn after turn. */
  readonly history: { role: "user" | "assistant"; text: string }[] = []
  #turn: Turn | undefined
  readonly #decided = new Set<string>()
  #nextId = 0

  private constructor(cls: AgentClass<C>, sessionId: string, model: ScriptedModel, client: ComponentClient, mcp: ScriptedMcp) {
    this.#registered = registryFor(cls, client).component(cls.componentId) as RegisteredAgent
    this.#cls = cls
    this.#client = client
    this.#model = model
    this.#mcp = mcp
    this.sessionId = sessionId
  }

  static of<C extends Agent>(
    cls: AgentClass<C>,
    sessionId: string,
    model: ScriptedModel,
    client: ComponentClient = noClient(),
    options: { readonly mcp?: ScriptedMcp } = {},
  ): AgentTestKit<C> {
    return new AgentTestKit(cls, sessionId, model, client, options.mcp ?? {})
  }

  #agent(): C {
    const a = new this.#cls()
    a._bind(this.sessionId, {}, this.#client)
    return a
  }

  /** One turn: returns the reply text (JSON text for a `thenReplyJson` plan). A refusal or a blocked guardrail rejects with `CommandError`; a turn that waits, with `ApprovalAwaited`. */
  async ask<I>(handler: HandlerRef<C, I, any, any> | string, input?: I): Promise<string> {
    const outcome = await this.outcome(handler, input)
    if (outcome.kind === "awaiting-approval") throw new ApprovalAwaited(outcome.requests)
    return outcome.value
  }

  /** One turn, answered with its outcome: the reply text, or the approval requests the turn waits on. */
  async outcome<I>(handler: HandlerRef<C, I, any, any> | string, input?: I): Promise<AgentOutcome<string>> {
    if (this.#turn?.pending) throw new CommandError({ message: `session '${this.sessionId}' has a turn awaiting a decision`, code: ErrorCode.Conflict })
    const ref = findHandler(this.#registered.handlers, handler, this.#registered.cls.name)
    const wireInput = ref.input ? roundTrip(codecFor(ref.input as Shape<I>), input as I) : undefined
    const agent = this.#agent()
    const plan = (await ref.run(agent, wireInput)) as AgentEffect<unknown>
    if (plan?.kind !== "agent") throw new Error(`${this.#registered.id}/${ref.name} returned something that is not an agent effect`)
    const problem = checkPlan(this.#registered, plan)
    if (problem) throw new Error(problem)
    if (plan.failure) throw new CommandError(plan.failure)

    const user = [...plan.context, plan.user ?? ""].filter((t) => t !== "").join("\n\n")
    await this.#guard(plan, "input", user)
    const messages: Message[] = plan.sessionMemory ? this.history.map((h) => ({ ...h })) : []
    messages.push({ role: "user", text: user })
    this.#turn = { plan, messages, steps: 0 }
    return this.#goOn(this.#turn)
  }

  /**
   * Decides the request the turn waits on, and goes on as the sidecar would: approved, the tool runs and the
   * model is told its result; refused, the model is told who refused it and why.
   */
  async decide(approvalId: string, decision: DecisionInput): Promise<AgentOutcome<string>> {
    if (decision.by.trim() === "") throw new CommandError({ message: "a decision must name who made it", code: ErrorCode.BadRequest })
    if (this.#decided.has(approvalId)) throw new CommandError({ message: `approval request '${approvalId}' is decided`, code: ErrorCode.Conflict })
    const turn = this.#turn
    if (!turn?.pending || turn.pending.request.id !== approvalId) {
      throw new CommandError({ message: `no approval request '${approvalId}' in session '${this.sessionId}'`, code: ErrorCode.NotFound })
    }
    const { tool, argumentsJson } = turn.pending
    turn.pending = undefined
    this.#decided.add(approvalId)
    const result = decision.approved
      ? await this.#run(tool, argumentsJson)
      : `A person (${decision.by}) refused this tool call; the tool did not run.${decision.note ? ` Their note: ${decision.note}` : ""}`
    turn.messages.push({ role: "tool", text: result })
    return this.#goOn(turn)
  }

  async #guard(plan: AgentEffect<unknown>, stage: "input" | "output", text: string): Promise<void> {
    for (const name of plan.guardrailNames) {
      const reason = await this.#registered.guardrails.get(name)!.check(stage, text)
      if (reason) throw new CommandError({ message: `${name}: ${reason}`, code: ErrorCode.Forbidden })
    }
  }

  #mcpTool(name: string): { server: string; tool: string } | undefined {
    if (!name.startsWith(TOOL_PREFIX)) return undefined
    const rest = name.slice(TOOL_PREFIX.length)
    const at = rest.indexOf("__")
    return at < 0 ? undefined : { server: rest.slice(0, at), tool: rest.slice(at + 2) }
  }

  #offered(plan: AgentEffect<unknown>): string[] {
    const mcp = [...this.#registered.mcpServers.keys()].flatMap((server) => Object.keys(this.#mcp[server] ?? {}).map((tool) => toolName(server, tool)))
    return [...plan.toolNames, ...mcp]
  }

  #requiresApproval(name: string): boolean {
    const mcp = this.#mcpTool(name)
    if (mcp) return this.#registered.mcpServers.get(mcp.server)?.approval !== undefined
    return this.#registered.tools.get(name)?.approval !== undefined
  }

  async #run(name: string, argumentsJson: string): Promise<string> {
    const mcp = this.#mcpTool(name)
    try {
      if (!mcp) return await runTool(this.#registered, this.#agent(), name, argumentsJson)
      const text = await this.#mcp[mcp.server]![mcp.tool]!(JSON.parse(argumentsJson || "{}") as Record<string, unknown>)
      for (const g of [...this.#registered.resultGuardrails.values()].sort((a, b) => a.name.localeCompare(b.name))) {
        const reason = await g.check(name, text)
        if (reason) return `A result guardrail refused what this tool answered, so it is not shown: ${g.name}: ${reason}`
      }
      return text
    } catch (e) {
      return `error: ${e instanceof Error ? e.message : String(e)}`
    }
  }

  async #goOn(turn: Turn): Promise<AgentOutcome<string>> {
    const offered = this.#offered(turn.plan)
    while (turn.steps <= this.#registered.maxToolCallSteps) {
      const answer = this.#model._next({ system: turn.plan.system, messages: [...turn.messages], tools: offered })
      switch (answer.kind) {
        case "refusal":
          this.#turn = undefined
          throw new CommandError({ message: answer.message, code: ErrorCode.BadRequest })
        case "tool-call": {
          turn.steps++
          if (!offered.includes(answer.tool)) throw new Error(`the model called ${JSON.stringify(answer.tool)}, which the plan did not offer`)
          turn.messages.push({ role: "assistant", text: `[tool call ${answer.tool} ${answer.argumentsJson}]` })
          if (this.#requiresApproval(answer.tool)) {
            const request: ApprovalRequest = Object.freeze({
              id: `approval-${++this.#nextId}`,
              tool: answer.tool,
              arguments: JSON.parse(answer.argumentsJson || "{}"),
              requestedAt: Date.now(),
            })
            turn.pending = { request, tool: answer.tool, argumentsJson: answer.argumentsJson }
            return { kind: "awaiting-approval", requests: [request] }
          }
          turn.messages.push({ role: "tool", text: await this.#run(answer.tool, answer.argumentsJson) })
          break
        }
        case "text": {
          this.#turn = undefined
          await this.#guard(turn.plan, "output", answer.text)
          if (turn.plan.sessionMemory) this.history.push({ role: "user", text: turn.plan.user ?? "" }, { role: "assistant", text: answer.text })
          return { kind: "answered", value: answer.text }
        }
      }
    }
    this.#turn = undefined
    throw new CommandError({ message: `the agent made more than ${this.#registered.maxToolCallSteps} tool calls`, code: ErrorCode.Internal })
  }

  /** A streaming handler's turn, token by token: the reply split on spaces, as a model streams words. */
  async *stream<I>(handler: HandlerRef<C, I, any, any> | string, input?: I): AsyncIterable<string> {
    const text = await this.ask(handler, input)
    const parts = text.split(/(?<= )/)
    for (const part of parts) yield part
  }
}
