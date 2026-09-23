package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{
  CommandHandle,
  ComponentClient,
  HandlerBinding,
  NoArgHandle
}

import scala.collection.mutable

/**
 * A component that carries out a task by talking to a model.
 *
 * Hosted as a sharded, event-sourced instance keyed by *session id* rather than by an arbitrary
 * entity id. That is the load-bearing choice: it makes the session single-writer, so a tool loop
 * that runs for thirty seconds cannot be interleaved with a second request on the same
 * conversation, and it makes the loop durable — a crash mid-loop resumes from journalled history
 * instead of replaying side effects.
 */
abstract class Agent:

  private var contextOpt: Option[AgentContext] = None

  final type Effect[R] = AgentEffect[R]

  /** Return type for a handler that streams its reply. */
  final type StreamEffect = AgentStreamEffect

  /** The declarative interaction API. */
  protected final val effects: AgentEffects = new AgentEffects(() => sessionContext.defaultModel)

  protected final def sessionContext: AgentContext =
    contextOpt.getOrElse(
      throw IllegalStateException("sessionContext is only available inside a command handler")
    )

  /** The conversation this request belongs to. */
  protected final def sessionId: SessionId = sessionContext.sessionId

  protected final def componentClient: ComponentClient = sessionContext.componentClient

  private[ankka] def _setContext(ctx: Option[AgentContext]): Unit = contextOpt = ctx

/** What an agent is handed when the runtime instantiates it. */
trait AgentContext:
  def sessionId: SessionId
  def componentId: ComponentId
  def componentClient: ComponentClient

  /** The service-wide default model, if one is configured. */
  def defaultModel: Option[ModelProvider]

private[ankka] final case class SimpleAgentContext(
    sessionId: SessionId,
    componentId: ComponentId,
    componentClient: ComponentClient,
    defaultModel: Option[ModelProvider]
) extends AgentContext

object Agent:

  /**
   * Declares an agent to the runtime.
   *
   * {{{
   * object WeatherAgent extends Agent.Companion[WeatherAgent](ComponentId("weather-agent")):
   *   def create(ctx: AgentContext) = new WeatherAgent(ctx)
   *   val ask = command("ask")(_.ask)
   * }}}
   */
  abstract class Companion[A <: Agent](val componentId: ComponentId):

    private val bindings = mutable.ListBuffer.empty[HandlerBinding[A]]
    private val streams  = mutable.ListBuffer.empty[StreamHandle[A, ?]]

    def create(ctx: AgentContext): A

    /**
     * A role for this agent, for memory filtering in multi-agent sessions.
     *
     * Defaults to the component id, which is what a single-agent session wants.
     */
    def role: String = componentId

    /** How many tool round-trips one request may take before the loop gives up. */
    def maxToolCallSteps: Int = 100

    protected final def command[I, O](name: String)(
        f: A => I => AgentEffect[O]
    )(using in: Serializer[I], out: Serializer[O]): CommandHandle[A, I, O] =
      add(
        new CommandHandle[A, I, O](
          componentId,
          MethodName(name),
          readOnly = false,
          in,
          out,
          (a, i) => f(a)(i)
        )
      )

    protected final def command[O](name: String)(
        f: A => AgentEffect[O]
    )(using out: Serializer[O]): NoArgHandle[A, O] =
      add(new NoArgHandle[A, O](componentId, MethodName(name), readOnly = false, out, f))

    /**
     * Registers a handler that streams its reply.
     *
     * Kept separate from `command` because the two are reached by different call sites —
     * `.call(...)` versus `.stream(...)` — and conflating them would let a caller await a single
     * value from a handler that produces many.
     */
    protected final def stream[I](name: String)(
        f: A => I => AgentStreamEffect
    )(using in: Serializer[I]): StreamHandle[A, I] =
      val handle =
        new StreamHandle[A, I](componentId, MethodName(name), in, (a, i) => f(a)(i))
      streams += handle
      handle

    private def add[H <: HandlerBinding[A]](handle: H): H =
      bindings += handle
      handle

    /** A `def`, not a `val` — see the note on `EventSourcedEntity.Companion`. */
    final def descriptor: AgentDescriptor[A] =
      val clashes = (bindings.map(_.name) ++ streams.map(_.name)).groupBy(identity).collect {
        case (name, bs) if bs.sizeIs > 1 => s"handler '$name' registered ${bs.size} times"
      }
      if clashes.nonEmpty then
        throw IllegalArgumentException(
          clashes.mkString(s"invalid agent '$componentId':\n  - ", "\n  - ", "")
        )
      if maxToolCallSteps <= 0 then
        throw IllegalArgumentException(s"agent '$componentId' needs a positive maxToolCallSteps")

      AgentDescriptor(
        componentId,
        role,
        maxToolCallSteps,
        create,
        bindings.map(b => b.name -> b).toMap,
        streams.map(h => h.name -> h).toMap
      )

/** The registered form of an agent. */
final case class AgentDescriptor[A <: Agent](
    componentId: ComponentId,
    role: String,
    maxToolCallSteps: Int,
    create: AgentContext => A,
    handlers: Map[MethodName, HandlerBinding[A]],
    streams: Map[MethodName, StreamHandle[A, ?]]
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.Agent

  private[ankka] def handler(name: MethodName): Option[HandlerBinding[A]] = handlers.get(name)

  private[ankka] def streamHandler(name: MethodName): Option[StreamHandle[A, ?]] =
    streams.get(name)

/**
 * A typed reference to a streaming handler.
 *
 * Mirrors `CommandHandle`, but its call site returns a `Source` rather than a value.
 */
final class StreamHandle[A, I] private[agent] (
    val componentId: ComponentId,
    val name: MethodName,
    private[agent] val inputSerializer: Serializer[I],
    private[agent] val run: (A, I) => AgentStreamEffect
):
  private[agent] def decodeAndInvoke(agent: A, payload: Array[Byte]): AgentStreamEffect =
    run(agent, inputSerializer.fromBytes(payload))

  override def toString: String = s"$componentId#$name (streaming)"

object StreamHandle:
  /**
   * A streaming handler whose input is decoded elsewhere — the sidecar's remote agent, whose
   * handler runs in another process and sees the bytes as they were sent.
   */
  private[ankka] def raw[A](
      componentId: ComponentId,
      name: MethodName,
      run: (A, Array[Byte]) => AgentStreamEffect
  ): StreamHandle[A, Array[Byte]] =
    new StreamHandle[A, Array[Byte]](componentId, name, Serializer.bytes, run)
