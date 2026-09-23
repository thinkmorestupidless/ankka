package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.agent.{
  AgentGrpc,
  AgentPlan,
  GuardrailRequest,
  GuardrailResult,
  PlanReply,
  PlanRequest,
  ToolRequest,
  ToolResult
}
import ankka.protocol.v1.consumer.{ConsumerEffect, ConsumerGrpc, ConsumerRequest}
import ankka.protocol.v1.discovery.*
import ankka.protocol.v1.endpoint.{HttpGrpc, HttpReply, HttpRequest, HttpResponse, StreamFrame}
import ankka.protocol.v1.event_sourced.{EventSourcedGrpc, EventSourcedIn, EventSourcedOut}
import ankka.protocol.v1.key_value.{KeyValueGrpc, KeyValueIn, KeyValueOut}
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.timed_action.{TimedActionEffect, TimedActionGrpc, TimedActionRequest}
import ankka.protocol.v1.view.{ViewEffect, ViewGrpc, ViewRequest}
import ankka.protocol.v1.workflow.{
  StepOutcome as PbStepOutcome,
  StepRef as PbStepRef,
  WorkflowGrpc,
  WorkflowIn,
  WorkflowOut
}
import com.google.protobuf.ByteString
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import io.grpc.Server

import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.Try

/**
 * A Scala process speaking the sidecar protocol: what an SDK in another language would be, made
 * scriptable so the sidecar can be proven under every behaviour a well-written SDK cannot produce —
 * a late reply, the wrong command id, a snapshot nobody asked for, a handler that never answers, a
 * process that restarts mid-conversation.
 *
 * Entities fold their events into a `Vector[String]` of event texts; a snapshot is that vector as a
 * JSON array. Every message received is recorded, so a test can assert what the sidecar sent.
 */
object ProcessDouble:

  /** What a scripted handler answers. */
  enum Effect:
    case Persist(
        events: Vector[String],
        reply: Option[String],
        retention: Option[pb.Retention] = None
    )
    case Reply(text: String)
    case NoReply
    case Refuse(message: String, code: pb.ErrorCode = pb.ErrorCode.BAD_REQUEST)
    case Throw(message: String)

  final case class Handler(readOnly: Boolean, run: (Vector[String], String) => Effect)

  final case class Entity(
      id: String,
      handlers: Map[String, Handler],
      snapshotEvery: Int = 0,
      eventManifest: String = "double-event",
      stateManifest: String = "double-state"
  )

  final case class Route(
      id: String,
      method: String,
      template: String,
      hasBody: Boolean = false,
      streaming: Boolean = false,
      handler: HttpRequest => Either[Throwable, HttpResponse] = _ =>
        Right(HttpResponse(200, "text/plain", ByteString.copyFromUtf8("ok"))),
      frames: HttpRequest => Vector[String] = _ => Vector.empty
  )

  final case class Endpoint(
      id: String,
      prefix: String,
      routes: Vector[Route],
      acl: ankka.protocol.v1.discovery.Endpoint.Acl =
        ankka.protocol.v1.discovery.Endpoint.Acl.ALLOW_ALL
  )

  // ── The other kinds, scripted the same way ──────────────────────────────

  enum KvEffect:
    case Set(state: String, reply: Option[String])
    case Reply(text: String)
    case Delete(reply: Option[String])
    case Expire(millis: Long, reply: Option[String])
    case Refuse(message: String, code: pb.ErrorCode = pb.ErrorCode.BAD_REQUEST)
    case Throw(message: String)

  final case class KvHandler(readOnly: Boolean, run: (Option[String], String) => KvEffect)

  /** A key value entity whose state is one text. */
  final case class KeyValue(
      id: String,
      handlers: Map[String, KvHandler],
      stateManifest: String = "double-kv"
  )

  /** The conformance-style key value entity: a name, set, read, deleted. */
  def profile(id: String = "profile"): KeyValue =
    KeyValue(
      id,
      Map(
        "set" -> KvHandler(
          readOnly = false,
          (_, input) =>
            if input.isEmpty then KvEffect.Refuse("a name is needed")
            else KvEffect.Set(input, Some("done"))
        ),
        "get" -> KvHandler(readOnly = true, (state, _) => KvEffect.Reply(state.getOrElse("none"))),
        "delete" -> KvHandler(readOnly = false, (_, _) => KvEffect.Delete(Some("done"))),
        "expire" -> KvHandler(
          readOnly = false,
          (_, input) => KvEffect.Expire(input.trim.toLong, Some("done"))
        ),
        "misbehave" -> KvHandler(readOnly = false, (_, _) => KvEffect.Throw("boom"))
      )
    )

  enum Next:
    case TransitionTo(step: String, input: Option[String] = None)
    case Pause(afterMillis: Option[Long] = None, onTimeout: Option[String] = None)
    case End
    case Fail(message: String)

  /** What a step answers: a state change, if any, and what happens next. */
  final case class StepResult(newState: Option[String], next: Next)

  enum WfEffect:
    case Update(
        newState: Option[String],
        transition: Option[(String, Option[String])],
        reply: Option[String]
    )
    case Reply(text: String)
    case Refuse(message: String, code: pb.ErrorCode = pb.ErrorCode.BAD_REQUEST)
    case Throw(message: String)

  final case class WfHandler(readOnly: Boolean, run: (Option[String], String) => WfEffect)

  /**
   * A workflow whose state is one text. A step takes the state and the transition's input and
   * answers a result — or throws, which is the failure the engine's recovery is for.
   */
  final case class Flow(
      id: String,
      handlers: Map[String, WfHandler],
      steps: Map[String, (Option[String], Option[String]) => StepResult],
      settings: Option[WorkflowDetail.Settings] = None,
      stateManifest: String = "double-wf"
  )

  enum ViewAnswer:
    case UpdateRow(json: String)
    case DeleteRow
    case Ignore

  /** A view over a declared component or a topic; `onChange` sees the current row, if any. */
  final case class ViewOf(
      id: String,
      sourceComponent: Option[(Kind, String)],
      sourceTopic: Option[String],
      onChange: (Option[String], String, pb.Metadata) => ViewAnswer,
      onDelete: Option[String] => ViewAnswer = _ => ViewAnswer.DeleteRow,
      rowManifest: String = "double-row",
      queries: Vector[String] = Vector("get", "all")
  )

  enum ConsumerAnswer:
    case Produce(text: String)
    case Done
    case Ignore

  final case class ConsumerOf(
      id: String,
      sourceComponent: Option[(Kind, String)],
      sourceTopic: Option[String],
      producesTo: Option[String],
      onMessage: (String, pb.Metadata) => ConsumerAnswer,
      onDelete: pb.Metadata => ConsumerAnswer = _ => ConsumerAnswer.Ignore
  )

  /**
   * An agent: each handler answers a plan for the input text, each tool takes the model's arguments
   * as JSON text, each guardrail sees the stage and the text and may block with a reason.
   */
  final case class AgentOf(
      id: String,
      handlers: Map[String, String => AgentPlan],
      streams: Map[String, String => AgentPlan] = Map.empty,
      tools: Map[String, (String, String) => Either[String, String]] = Map.empty,
      guardrails: Map[String, (GuardrailRequest.Stage, String) => Option[String]] = Map.empty,
      role: String = "",
      maxToolCallSteps: Int = 0
  )

  /** A plan naming the sidecar's default model. */
  def plan(
      system: String,
      user: String,
      tools: Vector[String] = Vector.empty,
      guardrails: Vector[String] = Vector.empty,
      memory: Boolean = true
  ): AgentPlan =
    AgentPlan(
      model = None,
      system = Some(system),
      user = Some(user),
      context = Vector.empty,
      memory = if memory then AgentPlan.Memory.SESSION else AgentPlan.Memory.NONE,
      tools = tools,
      responseShape = None,
      guardrails = guardrails,
      failure = None
    )

  /**
   * A timed action: each handler sees the payload text and the metadata, and fails with a message.
   */
  final case class Action(
      id: String,
      handlers: Map[String, (String, pb.Metadata) => Either[String, Unit]]
  )

  final case class DoubleSpec(
      entities: Vector[Entity] = Vector.empty,
      endpoints: Vector[Endpoint] = Vector.empty,
      protocolVersion: String = "1.0",
      extraComponents: Vector[Component] = Vector.empty,
      keyValues: Vector[KeyValue] = Vector.empty,
      flows: Vector[Flow] = Vector.empty,
      views: Vector[ViewOf] = Vector.empty,
      consumers: Vector[ConsumerOf] = Vector.empty,
      actions: Vector[Action] = Vector.empty,
      agents: Vector[AgentOf] = Vector.empty
  )

  /** The misbehaviours a test can switch on. */
  final class Knobs:
    @volatile var replyDelay: FiniteDuration   = Duration.Zero
    @volatile var wrongCommandId: Boolean      = false
    @volatile var unrequestedSnapshot: Boolean = false
    @volatile var neverReply: Boolean          = false
    @volatile var failInsteadOfReply: Boolean  = false
    @volatile var neverReplyStep: Boolean      = false

  /** The conformance-style entity every suite can start from. */
  def recorder(id: String = "conformance", snapshotEvery: Int = 0): Entity =
    def text(s: String) = s
    Entity(
      id,
      snapshotEvery = snapshotEvery,
      handlers = Map(
        "record" -> Handler(
          readOnly = false,
          (_, input) =>
            Effect.Persist(Vector(s"""{"type":"Recorded","input":${quote(input)}}"""), Some("done"))
        ),
        "record-many" -> Handler(
          readOnly = false,
          (_, input) =>
            Effect.Persist(
              Vector.tabulate(input.trim.toInt)(i => s"""{"type":"Recorded","i":$i}"""),
              Some("done")
            )
        ),
        "count" -> Handler(readOnly = true, (state, _) => Effect.Reply(state.size.toString)),
        "refuse" -> Handler(
          readOnly = false,
          (_, _) => Effect.Refuse("refused on purpose", pb.ErrorCode.CONFLICT)
        ),
        "no-reply" -> Handler(
          readOnly = false,
          (_, _) => Effect.Persist(Vector("""{"type":"Recorded","silent":true}"""), None)
        ),
        "delete" -> Handler(
          readOnly = false,
          (_, _) =>
            Effect.Persist(
              Vector.empty,
              Some("done"),
              Some(pb.Retention(pb.Retention.Retention.DeleteNow(pb.Retention.DeleteNow())))
            )
        ),
        "expire" -> Handler(
          readOnly = false,
          (_, input) =>
            Effect.Persist(
              Vector.empty,
              Some("done"),
              Some(
                pb.Retention(
                  pb.Retention.Retention.ExpireAfter(pb.Retention.ExpireAfter(input.trim.toLong))
                )
              )
            )
        ),
        "misbehave" -> Handler(readOnly = false, (_, _) => Effect.Throw("boom")),
        "query-persists" -> Handler(
          readOnly = true,
          (_, _) => Effect.Persist(Vector("""{"type":"Illegal"}"""), Some("done"))
        ),
        "text" -> Handler(readOnly = true, (_, _) => Effect.Reply(text("plain text reply")))
      )
    )

  private def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** What one running double has seen. */
  final case class Received(streamId: Int, message: Any)

final class ProcessDouble(spec: ProcessDouble.DoubleSpec)(using ec: ExecutionContext):
  import ProcessDouble.*

  val knobs                                    = new Knobs
  val received: CopyOnWriteArrayList[Received] = new CopyOnWriteArrayList()
  val problems: CopyOnWriteArrayList[String]   = new CopyOnWriteArrayList()
  @volatile private var server: Option[Server] = None
  @volatile private var boundPort: Int         = 0
  private val streamIds                        = new java.util.concurrent.atomic.AtomicInteger(0)
  @volatile var liveStreams: Int               = 0

  def port: Int = boundPort

  def messagesOf[A](pf: PartialFunction[Any, A]): Vector[A] =
    received.asScala.toVector.map(_.message).collect(pf)

  def start(port: Int = 0): Int =
    val s = NettyServerBuilder
      .forAddress(new InetSocketAddress("127.0.0.1", port))
      .addService(DiscoveryGrpc.bindService(discovery, ec))
      .addService(EventSourcedGrpc.bindService(eventSourced, ec))
      .addService(KeyValueGrpc.bindService(keyValue, ec))
      .addService(WorkflowGrpc.bindService(workflow, ec))
      .addService(ViewGrpc.bindService(view, ec))
      .addService(ConsumerGrpc.bindService(consumer, ec))
      .addService(TimedActionGrpc.bindService(timedAction, ec))
      .addService(AgentGrpc.bindService(agent, ec))
      .addService(HttpGrpc.bindService(http, ec))
      .build()
      .start()
    server = Some(s)
    boundPort = s.getPort
    boundPort

  def stop(): Unit =
    server.foreach(_.shutdownNow())
    server = None

  /** Drops every conversation and listens again on the same port: the process restarted. */
  def restart(): Unit =
    val p = boundPort
    stop()
    Thread.sleep(100)
    val _ = start(p)

  // ── Discovery ──────────────────────────────────────────────────────────────

  def toSpec: Spec =
    Spec(
      spec.protocolVersion,
      Some(SdkInfo("process-double", "test")),
      spec.entities.map { e =>
        Component(
          Kind.EVENT_SOURCED_ENTITY,
          e.id,
          e.handlers.toVector.sortBy(_._1).map((n, h) => Handler_(n, h.readOnly)),
          Component.Detail.EventSourced(EventSourcedDetail(e.snapshotEvery))
        )
      } ++ spec.keyValues.map { e =>
        Component(
          Kind.KEY_VALUE_ENTITY,
          e.id,
          e.handlers.toVector.sortBy(_._1).map((n, h) => Handler_(n, h.readOnly)),
          Component.Detail.KeyValue(KeyValueDetail())
        )
      } ++ spec.flows.map { f =>
        Component(
          Kind.WORKFLOW,
          f.id,
          f.handlers.toVector.sortBy(_._1).map((n, h) => Handler_(n, h.readOnly)),
          Component.Detail.Workflow(WorkflowDetail(f.steps.keys.toVector.sorted, f.settings))
        )
      } ++ spec.views.map { v =>
        Component(
          Kind.VIEW,
          v.id,
          Vector.empty,
          Component.Detail.View(
            ViewDetail(sourceOf(v.sourceComponent, v.sourceTopic), v.rowManifest, v.queries)
          )
        )
      } ++ spec.consumers.map { c =>
        Component(
          Kind.CONSUMER,
          c.id,
          Vector.empty,
          Component.Detail.Consumer(
            ConsumerDetail(sourceOf(c.sourceComponent, c.sourceTopic), c.producesTo)
          )
        )
      } ++ spec.actions.map { a =>
        Component(
          Kind.TIMED_ACTION,
          a.id,
          a.handlers.keys.toVector.sorted.map(Handler_(_, readOnly = false)),
          Component.Detail.TimedAction(TimedActionDetail())
        )
      } ++ spec.agents.map { a =>
        Component(
          Kind.AGENT,
          a.id,
          a.handlers.keys.toVector.sorted.map(Handler_(_, readOnly = false)) ++
            a.streams.keys.toVector.sorted.map(n =>
              ankka.protocol.v1.discovery.Handler(n, readOnly = false, streaming = true)
            ),
          Component.Detail.Agent(
            AgentDetail(
              a.role,
              a.maxToolCallSteps,
              a.tools.keys.toVector.sorted.map(n =>
                Tool(
                  n,
                  s"the $n tool",
                  """{"type":"object","properties":{"id":{"type":"string"}}}"""
                )
              ),
              a.guardrails.keys.toVector.sorted
            )
          )
        )
      } ++ spec.extraComponents,
      spec.endpoints.map { e =>
        ankka.protocol.v1.discovery.Endpoint(
          e.id,
          e.prefix,
          e.acl,
          e.routes.map(r =>
            ankka.protocol.v1.discovery.Route(r.id, r.method, r.template, r.hasBody, r.streaming)
          )
        )
      }
    )

  private def Handler_(name: String, readOnly: Boolean): ankka.protocol.v1.discovery.Handler =
    ankka.protocol.v1.discovery.Handler(name, readOnly, streaming = false)

  private def sourceOf(component: Option[(Kind, String)], topic: Option[String]): Option[Source] =
    component
      .map((kind, id) => Source(Source.Source.Component(Source.ComponentRef(kind, id))))
      .orElse(topic.map(t => Source(Source.Source.Topic(t))))

  private val discovery = new DiscoveryGrpc.Discovery:
    def discover(request: SidecarInfo): Future[Spec] =
      received.add(Received(0, request))
      Future.successful(toSpec)
    def reportError(request: Problem): Future[pb.Empty] =
      problems.add(request.message)
      Future.successful(pb.Empty())

  // ── Event sourced ──────────────────────────────────────────────────────────

  private val eventSourced = new EventSourcedGrpc.EventSourced:
    def handle(out: StreamObserver[EventSourcedOut]): StreamObserver[EventSourcedIn] =
      val streamId = streamIds.incrementAndGet()
      liveStreams += 1
      var entity: Option[Entity] = None
      var state: Vector[String]  = Vector.empty
      new StreamObserver[EventSourcedIn]:
        def onNext(in: EventSourcedIn): Unit =
          received.add(Received(streamId, in))
          in.message match
            case EventSourcedIn.Message.Init(init) =>
              entity = spec.entities.find(_.id == init.componentId)
              state = init.snapshot
                .map(s => decodeState(s.getPayload.data.toStringUtf8))
                .getOrElse(Vector.empty)
            case EventSourcedIn.Message.Event(event) =>
              state = state :+ event.getPayload.data.toStringUtf8
            case EventSourcedIn.Message.Command(cmd) =>
              val e = entity.getOrElse(throw IllegalStateException("command before init"))
              if knobs.neverReply then ()
              else
                val answer: EventSourcedOut =
                  if knobs.failInsteadOfReply then
                    EventSourcedOut(
                      EventSourcedOut.Message.Failure(
                        pb.Failure(
                          cmd.id,
                          Some(pb.Error("failed on purpose", pb.ErrorCode.INTERNAL))
                        )
                      )
                    )
                  else
                    e.handlers.get(cmd.name) match
                      case None =>
                        EventSourcedOut(
                          EventSourcedOut.Message.Failure(
                            pb.Failure(
                              cmd.id,
                              Some(pb.Error(s"no handler ${cmd.name}", pb.ErrorCode.NOT_FOUND))
                            )
                          )
                        )
                      case Some(h) =>
                        val input = cmd.payload.map(_.data.toStringUtf8).getOrElse("")
                        try
                          h.run(state, input) match
                            case Effect.Throw(message) => throw RuntimeException(message)
                            case Effect.Refuse(message, code) =>
                              reply(
                                cmd,
                                e,
                                state,
                                Vector.empty,
                                pb.Outcome(pb.Outcome.Outcome.Error(pb.Error(message, code))),
                                None
                              )
                            case Effect.Reply(text) =>
                              reply(cmd, e, state, Vector.empty, replyOutcome(text), None)
                            case Effect.NoReply =>
                              reply(
                                cmd,
                                e,
                                state,
                                Vector.empty,
                                pb.Outcome(pb.Outcome.Outcome.NoReply(pb.Outcome.NoReply())),
                                None
                              )
                            case Effect.Persist(events, r, retention) =>
                              state = state ++ events
                              reply(
                                cmd,
                                e,
                                state,
                                events,
                                r.map(replyOutcome)
                                  .getOrElse(
                                    pb.Outcome(pb.Outcome.Outcome.NoReply(pb.Outcome.NoReply()))
                                  ),
                                retention
                              )
                        catch
                          case NonFatal(t) =>
                            EventSourcedOut(
                              EventSourcedOut.Message.Failure(
                                pb.Failure(
                                  cmd.id,
                                  Some(pb.Error(t.getMessage, pb.ErrorCode.INTERNAL))
                                )
                              )
                            )
                if knobs.replyDelay > Duration.Zero then
                  val _ = Future {
                    Thread.sleep(knobs.replyDelay.toMillis)
                    try out.onNext(answer)
                    catch case NonFatal(_) => ()
                  }
                else out.onNext(answer)
            case EventSourcedIn.Message.Empty => ()
        def onError(t: Throwable): Unit = liveStreams -= 1
        def onCompleted(): Unit =
          liveStreams -= 1
          out.onCompleted()

      end new

  private def replyOutcome(text: String): pb.Outcome =
    pb.Outcome(
      pb.Outcome.Outcome.Reply(
        pb.Outcome
          .Reply(Some(pb.Payload("text/plain", "string", ByteString.copyFromUtf8(text))), None)
      )
    )

  private def reply(
      cmd: EventSourcedIn.Command,
      e: Entity,
      state: Vector[String],
      events: Vector[String],
      outcome: pb.Outcome,
      retention: Option[pb.Retention]
  ): EventSourcedOut =
    val id = if knobs.wrongCommandId then cmd.id + 1000 else cmd.id
    val snapshot =
      if cmd.snapshotRequested || knobs.unrequestedSnapshot then
        Some(
          pb.Payload(
            "application/json",
            e.stateManifest,
            ByteString.copyFromUtf8(encodeState(state))
          )
        )
      else None
    EventSourcedOut(
      EventSourcedOut.Message.Reply(
        EventSourcedOut.Reply(
          id,
          events.map(ev =>
            pb.Payload("application/json", e.eventManifest, ByteString.copyFromUtf8(ev))
          ),
          retention,
          Some(outcome),
          snapshot
        )
      )
    )

  private def encodeState(state: Vector[String]): String = state.mkString("[", ",", "]")
  private def decodeState(json: String): Vector[String] =
    // Events are JSON objects with no nested arrays, so a shallow split is enough for a double.
    val body = json.trim.stripPrefix("[").stripSuffix("]").trim
    if body.isEmpty then Vector.empty
    else
      val out   = Vector.newBuilder[String]
      var depth = 0
      val cur   = new StringBuilder
      body.foreach { ch =>
        ch match
          case '{'               => depth += 1; cur += ch
          case '}'               => depth -= 1; cur += ch
          case ',' if depth == 0 => out += cur.toString.trim; cur.clear()
          case _                 => cur += ch
      }
      if cur.nonEmpty then out += cur.toString.trim
      out.result()

  // ── Key value ──────────────────────────────────────────────────────────────

  private def text(t: String): pb.Payload =
    pb.Payload("text/plain", "string", ByteString.copyFromUtf8(t))
  private def json(manifest: String, t: String): pb.Payload =
    pb.Payload("application/json", manifest, ByteString.copyFromUtf8(t))
  private def outcomeOf(reply: Option[String]): pb.Outcome =
    reply.map(replyOutcome).getOrElse(pb.Outcome(pb.Outcome.Outcome.NoReply(pb.Outcome.NoReply())))
  private def refusal(message: String, code: pb.ErrorCode): pb.Outcome =
    pb.Outcome(pb.Outcome.Outcome.Error(pb.Error(message, code)))
  private def failure(id: Long, message: String, code: pb.ErrorCode): pb.Failure =
    pb.Failure(id, Some(pb.Error(Option(message).getOrElse("failed"), code)))

  private val keyValue = new KeyValueGrpc.KeyValue:
    def handle(out: StreamObserver[KeyValueOut]): StreamObserver[KeyValueIn] =
      val streamId = streamIds.incrementAndGet()
      liveStreams += 1
      var entity: Option[KeyValue] = None
      var state: Option[String]    = None
      def reply(
          id: Long,
          newState: Option[pb.Payload],
          retention: Option[pb.Retention],
          outcome: pb.Outcome
      ): KeyValueOut =
        KeyValueOut(
          KeyValueOut.Message.Reply(KeyValueOut.Reply(id, newState, retention, Some(outcome)))
        )
      new StreamObserver[KeyValueIn]:
        def onNext(in: KeyValueIn): Unit =
          received.add(Received(streamId, in))
          in.message match
            case KeyValueIn.Message.Init(init) =>
              entity = spec.keyValues.find(_.id == init.componentId)
              state = init.state.map(_.data.toStringUtf8)
            case KeyValueIn.Message.Command(cmd) =>
              val e = entity.getOrElse(throw IllegalStateException("command before init"))
              if knobs.neverReply then ()
              else
                val answer = e.handlers.get(cmd.name) match
                  case None =>
                    KeyValueOut(
                      KeyValueOut.Message
                        .Failure(failure(cmd.id, s"no handler ${cmd.name}", pb.ErrorCode.NOT_FOUND))
                    )
                  case Some(h) =>
                    val input = cmd.payload.map(_.data.toStringUtf8).getOrElse("")
                    try
                      h.run(state, input) match
                        case KvEffect.Throw(message) => throw RuntimeException(message)
                        case KvEffect.Refuse(message, code) =>
                          reply(cmd.id, None, None, refusal(message, code))
                        case KvEffect.Reply(t) => reply(cmd.id, None, None, replyOutcome(t))
                        case KvEffect.Set(s, r) =>
                          state = Some(s)
                          reply(cmd.id, Some(json(e.stateManifest, s)), None, outcomeOf(r))
                        case KvEffect.Delete(r) =>
                          state = None
                          reply(
                            cmd.id,
                            None,
                            Some(
                              pb.Retention(
                                pb.Retention.Retention.DeleteNow(pb.Retention.DeleteNow())
                              )
                            ),
                            outcomeOf(r)
                          )
                        case KvEffect.Expire(millis, r) =>
                          reply(
                            cmd.id,
                            None,
                            Some(
                              pb.Retention(
                                pb.Retention.Retention.ExpireAfter(pb.Retention.ExpireAfter(millis))
                              )
                            ),
                            outcomeOf(r)
                          )
                    catch
                      case NonFatal(t) =>
                        KeyValueOut(
                          KeyValueOut.Message
                            .Failure(failure(cmd.id, t.getMessage, pb.ErrorCode.INTERNAL))
                        )
                out.onNext(answer)
            case KeyValueIn.Message.Empty => ()
        def onError(t: Throwable): Unit = liveStreams -= 1
        def onCompleted(): Unit =
          liveStreams -= 1
          out.onCompleted()

  // ── Workflow ───────────────────────────────────────────────────────────────

  private def toPb(next: Next): PbStepOutcome = next match
    case Next.TransitionTo(step, input) =>
      PbStepOutcome(PbStepOutcome.Outcome.TransitionTo(PbStepRef(step, input.map(text))))
    case Next.Pause(after, onTimeout) =>
      PbStepOutcome(
        PbStepOutcome.Outcome.Pause(PbStepOutcome.Pause(after, onTimeout.map(PbStepRef(_, None))))
      )
    case Next.End => PbStepOutcome(PbStepOutcome.Outcome.End(PbStepOutcome.End()))
    case Next.Fail(message) =>
      PbStepOutcome(PbStepOutcome.Outcome.Fail(pb.Error(message, pb.ErrorCode.INTERNAL)))

  private val workflow = new WorkflowGrpc.Workflow:
    def handle(out: StreamObserver[WorkflowOut]): StreamObserver[WorkflowIn] =
      val streamId = streamIds.incrementAndGet()
      liveStreams += 1
      var flow: Option[Flow]              = None
      @volatile var state: Option[String] = None
      def wfFailure(id: Long, message: String, code: pb.ErrorCode): WorkflowOut =
        WorkflowOut(WorkflowOut.Message.Failure(failure(id, message, code)))
      new StreamObserver[WorkflowIn]:
        def onNext(in: WorkflowIn): Unit =
          received.add(Received(streamId, in))
          in.message match
            case WorkflowIn.Message.Init(init) =>
              flow = spec.flows.find(_.id == init.componentId)
              state = init.state.map(_.data.toStringUtf8)
            case WorkflowIn.Message.Command(cmd) =>
              val f = flow.getOrElse(throw IllegalStateException("command before init"))
              if knobs.neverReply then ()
              else
                val answer = f.handlers.get(cmd.name) match
                  case None => wfFailure(cmd.id, s"no handler ${cmd.name}", pb.ErrorCode.NOT_FOUND)
                  case Some(h) =>
                    val input = cmd.payload.map(_.data.toStringUtf8).getOrElse("")
                    def reply(
                        newState: Option[String],
                        transition: Option[(String, Option[String])],
                        outcome: pb.Outcome
                    ) =
                      WorkflowOut(
                        WorkflowOut.Message.Reply(
                          WorkflowOut.Reply(
                            cmd.id,
                            newState.map(json(f.stateManifest, _)),
                            transition.map((step, i) => PbStepRef(step, i.map(text))),
                            Some(outcome)
                          )
                        )
                      )
                    try
                      h.run(state, input) match
                        case WfEffect.Throw(message) => throw RuntimeException(message)
                        case WfEffect.Refuse(message, code) =>
                          reply(None, None, refusal(message, code))
                        case WfEffect.Reply(t) => reply(None, None, replyOutcome(t))
                        case WfEffect.Update(newState, transition, r) =>
                          newState.foreach(s => state = Some(s))
                          reply(newState, transition, outcomeOf(r))
                    catch case NonFatal(t) => wfFailure(cmd.id, t.getMessage, pb.ErrorCode.INTERNAL)
                out.onNext(answer)
            case WorkflowIn.Message.RunStep(run) =>
              val f = flow.getOrElse(throw IllegalStateException("step before init"))
              if knobs.neverReplyStep then ()
              else
                // Off the stream's thread, as a process would run a step: the stream stays free.
                val _ = Future {
                  val answer = f.steps.get(run.step) match
                    case None => wfFailure(run.id, s"no step ${run.step}", pb.ErrorCode.NOT_FOUND)
                    case Some(step) =>
                      try
                        val result = step(state, run.input.map(_.data.toStringUtf8))
                        result.newState.foreach(s => state = Some(s))
                        WorkflowOut(
                          WorkflowOut.Message.StepReply(
                            WorkflowOut.StepReply(
                              run.id,
                              result.newState.map(json(f.stateManifest, _)),
                              Some(toPb(result.next))
                            )
                          )
                        )
                      catch
                        case NonFatal(t) => wfFailure(run.id, t.getMessage, pb.ErrorCode.INTERNAL)
                  try out.onNext(answer)
                  catch case NonFatal(_) => ()
                }
            case WorkflowIn.Message.Empty => ()
        def onError(t: Throwable): Unit = liveStreams -= 1
        def onCompleted(): Unit =
          liveStreams -= 1
          out.onCompleted()

  // ── View, consumer, timed action ───────────────────────────────────────────

  private def notFound(what: String): Throwable =
    io.grpc.Status.NOT_FOUND.withDescription(what).asRuntimeException()

  private val view = new ViewGrpc.View:
    def handle(request: ViewRequest): Future[ViewEffect] =
      received.add(Received(0, request))
      spec.views.find(_.id == request.componentId) match
        case None => Future.failed(notFound(s"unknown view ${request.componentId}"))
        case Some(v) =>
          Future.fromTry(Try {
            val row = request.row.map(_.data.toStringUtf8)
            val answer =
              if request.deleted then v.onDelete(row)
              else
                v.onChange(
                  row,
                  request.event.map(_.data.toStringUtf8).getOrElse(""),
                  request.metadata.getOrElse(pb.Metadata())
                )
            answer match
              case ViewAnswer.UpdateRow(row) =>
                ViewEffect(ViewEffect.Effect.UpdateRow(json(v.rowManifest, row)))
              case ViewAnswer.DeleteRow => ViewEffect(ViewEffect.Effect.DeleteRow(pb.Empty()))
              case ViewAnswer.Ignore    => ViewEffect(ViewEffect.Effect.Ignore(pb.Empty()))
          })

  private val consumer = new ConsumerGrpc.Consumer:
    def handle(request: ConsumerRequest): Future[ConsumerEffect] =
      received.add(Received(0, request))
      spec.consumers.find(_.id == request.componentId) match
        case None => Future.failed(notFound(s"unknown consumer ${request.componentId}"))
        case Some(c) =>
          Future.fromTry(Try {
            val metadata = request.metadata.getOrElse(pb.Metadata())
            val answer =
              if request.deleted then c.onDelete(metadata)
              else c.onMessage(request.message.map(_.data.toStringUtf8).getOrElse(""), metadata)
            answer match
              case ConsumerAnswer.Produce(t) =>
                ConsumerEffect(
                  ConsumerEffect.Effect.Produce(
                    ConsumerEffect.Produce(Some(json("double-out", t)), Some(pb.Metadata()))
                  )
                )
              case ConsumerAnswer.Done   => ConsumerEffect(ConsumerEffect.Effect.Done(pb.Empty()))
              case ConsumerAnswer.Ignore => ConsumerEffect(ConsumerEffect.Effect.Ignore(pb.Empty()))
          })

  private val timedAction = new TimedActionGrpc.TimedAction:
    def invoke(request: TimedActionRequest): Future[TimedActionEffect] =
      received.add(Received(0, request))
      spec.actions.find(_.id == request.componentId).flatMap(_.handlers.get(request.name)) match
        case None =>
          Future.failed(notFound(s"unknown timed action ${request.componentId}/${request.name}"))
        case Some(h) =>
          Future.fromTry(Try {
            h(
              request.payload.map(_.data.toStringUtf8).getOrElse(""),
              request.metadata.getOrElse(pb.Metadata())
            ) match
              case Right(()) => TimedActionEffect(TimedActionEffect.Effect.Done(pb.Empty()))
              case Left(message) =>
                TimedActionEffect(
                  TimedActionEffect.Effect.Fail(pb.Error(message, pb.ErrorCode.INTERNAL))
                )
          })

  // ── Agent ──────────────────────────────────────────────────────────────────

  private val agent = new AgentGrpc.Agent:
    def plan(request: PlanRequest): Future[PlanReply] =
      received.add(Received(0, request))
      val input = request.payload.map(_.data.toStringUtf8).getOrElse("")
      spec.agents.find(_.id == request.componentId) match
        case None => Future.failed(notFound(s"unknown agent ${request.componentId}"))
        case Some(a) =>
          a.handlers.get(request.name).orElse(a.streams.get(request.name)) match
            case None =>
              Future.successful(
                PlanReply(
                  PlanReply.Message
                    .Failure(failure(0L, s"no handler ${request.name}", pb.ErrorCode.NOT_FOUND))
                )
              )
            case Some(h) =>
              Future.fromTry(Try(PlanReply(PlanReply.Message.Plan(h(input))))).recover {
                case NonFatal(t) =>
                  PlanReply(
                    PlanReply.Message.Failure(failure(0L, t.getMessage, pb.ErrorCode.INTERNAL))
                  )
              }

    def invokeTool(request: ToolRequest): Future[ToolResult] =
      received.add(Received(0, request))
      spec.agents.find(_.id == request.componentId).flatMap(_.tools.get(request.tool)) match
        case None => Future.failed(notFound(s"unknown tool ${request.tool}"))
        case Some(t) =>
          Future.successful(
            Try(t(request.sessionId, request.argumentsJson)).toEither.left
              .map(e => Option(e.getMessage).getOrElse("failed"))
              .flatten match
              case Right(ok) => ToolResult(ToolResult.Result.Ok(ok))
              case Left(err) => ToolResult(ToolResult.Result.Error(err))
          )

    def checkGuardrail(request: GuardrailRequest): Future[GuardrailResult] =
      received.add(Received(0, request))
      spec.agents
        .find(_.id == request.componentId)
        .flatMap(_.guardrails.get(request.guardrail)) match
        case None => Future.failed(notFound(s"unknown guardrail ${request.guardrail}"))
        case Some(g) =>
          Future.successful(
            g(request.stage, request.text) match
              case Some(reason) => GuardrailResult(GuardrailResult.Result.Block(reason))
              case None         => GuardrailResult(GuardrailResult.Result.Pass(pb.Empty()))
          )

  // ── HTTP ───────────────────────────────────────────────────────────────────

  private val http = new HttpGrpc.Http:
    def handle(request: HttpRequest): Future[HttpReply] =
      received.add(Received(0, request))
      route(request) match
        case None =>
          Future.successful(
            HttpReply(
              HttpReply.Message.Failure(
                pb.Failure(
                  0,
                  Some(pb.Error(s"no route ${request.routeId}", pb.ErrorCode.NOT_FOUND))
                )
              )
            )
          )
        case Some(r) =>
          if knobs.neverReply then Future.never
          else
            r.handler(request) match
              case Right(response) =>
                Future.successful(HttpReply(HttpReply.Message.Response(response)))
              case Left(t) =>
                Future.successful(
                  HttpReply(
                    HttpReply.Message.Failure(
                      pb.Failure(0, Some(pb.Error(t.getMessage, pb.ErrorCode.INTERNAL)))
                    )
                  )
                )

    def handleStream(request: HttpRequest, out: StreamObserver[StreamFrame]): Unit =
      received.add(Received(0, request))
      route(request) match
        case None =>
          out.onNext(
            StreamFrame(
              StreamFrame.Frame.Failed(
                pb.Error(s"no route ${request.routeId}", pb.ErrorCode.NOT_FOUND)
              )
            )
          )
          out.onCompleted()
        case Some(r) =>
          r.frames(request).foreach(f => out.onNext(StreamFrame(StreamFrame.Frame.Text(f))))
          out.onNext(StreamFrame(StreamFrame.Frame.Completed(pb.Empty())))
          out.onCompleted()

  private def route(request: HttpRequest): Option[Route] =
    spec.endpoints.find(_.id == request.endpointId).flatMap(_.routes.find(_.id == request.routeId))
