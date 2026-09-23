package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.*
import ankka.protocol.v1.endpoint.{HttpGrpc, HttpReply, HttpRequest, HttpResponse, StreamFrame}
import ankka.protocol.v1.event_sourced.{EventSourcedGrpc, EventSourcedIn, EventSourcedOut}
import ankka.protocol.v1.payload as pb
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

  final case class DoubleSpec(
      entities: Vector[Entity] = Vector.empty,
      endpoints: Vector[Endpoint] = Vector.empty,
      protocolVersion: String = "1.0",
      extraComponents: Vector[Component] = Vector.empty
  )

  /** The misbehaviours a test can switch on. */
  final class Knobs:
    @volatile var replyDelay: FiniteDuration   = Duration.Zero
    @volatile var wrongCommandId: Boolean      = false
    @volatile var unrequestedSnapshot: Boolean = false
    @volatile var neverReply: Boolean          = false
    @volatile var failInsteadOfReply: Boolean  = false

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
