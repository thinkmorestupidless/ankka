package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import com.google.protobuf.ByteString
import ankka.protocol.v1.endpoint.{HttpRequest as PbHttpRequest, HttpResponse as PbHttpResponse}
import com.thinkmorestupidless.ankka.agent.{Approvals, Decision}
import com.thinkmorestupidless.ankka.agent.autonomous.HostProtocol
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentKind,
  HandlerKind,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName,
  Serializer
}
import com.thinkmorestupidless.ankka.runtime.remote.{PayloadKeys, RemoteDescriptor}
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaExecutors,
  AnkkaService,
  Database,
  EntityProtocol,
  MetaEntry,
  Observability,
  Trace,
  ViewQueries
}
import com.thinkmorestupidless.ankka.sdk.{
  DeferredCall,
  ServiceIdentityMismatch,
  ServiceResponse,
  ServiceUnanswered,
  ServiceUnresolvable,
  TimerScheduler,
  ViewDescriptor
}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * What a service's own code calls *back* for — other components, view rows, timers, its secret
 * store, and other services — whichever way it reaches the runtime: a process over the loopback
 * gRPC server (`ClientService`), a module through its host imports (`wasm.HostImports`). One
 * implementation, so the two cannot differ.
 *
 * `Invoke` goes straight to the sharding transport with the bytes it was given, so a call from the
 * process is routed exactly as a call from a Scala endpoint would be, wherever the target instance
 * lives. The trace and span ids in `metadata` are the ones the SDK received with the command or
 * request it is handling, which is what makes the nested call a child span.
 */
/**
 * The one call of `ClientLogic` a module's `request` import makes, apart from the rest so that a
 * suite can stand in for it with no service running.
 */
trait ServiceCalls:
  def request(request: ServiceRequest): Future[ServiceReply]

final class ClientLogic(
    service: AnkkaService,
    settings: Settings,
    timers: () => Option[TimerScheduler],
    /**
     * The protocol version the process declared in discovery: what it may be served. Absent, it is
     * taken to be this sidecar's own.
     */
    declaredProtocol: Option[String] = None
)(using system: ActorSystem[?])
    extends ServiceCalls:

  private given ec: ExecutionContext = system.executionContext
  private val transport              = service.componentClient.transportRef

  /**
   * The read-only handlers of this service's sharded components, which the transport may send again
   * when an answer does not come (`CallTransport.askQuery`). Sharded kinds never share an id.
   */
  private val queries: Set[(ComponentId, MethodName)] =
    service.registry.components
      .collect {
        case d: RemoteDescriptor if d.kind.sharded =>
          d.handlers.values.filter(_.readOnly).map(h => (d.componentId, h.name))
      }
      .flatten
      .toSet
  private val observability = Observability(system)

  /**
   * Makes a call the process asked for as the handler the process was running.
   *
   * The process forwards the metadata its handler was given, and the host that ran that handler put
   * its own name there. That name is whatever the process sent, so it is believed only when it is a
   * component and handler this service declared; then the call is made as that handler, exactly as
   * a Scala handler's is. Anything else is made as nobody, and the transport takes the name out.
   * The trace is left as the metadata has it: this thread is in no trace of its own.
   */
  private def asCaller[A](metadata: Metadata)(call: => A): A =
    observability.declared.origin(metadata) match
      case Some(origin) => Trace.asOrigin(origin)(call)
      case None         => call
  private val database = Database()

  private def payload(p: Option[pb.Payload]): Array[Byte] =
    p.map(_.data.toByteArray).getOrElse(Array.emptyByteArray)
  private def manifest(p: Option[pb.Payload]): String = p.map(_.manifest).getOrElse("")
  private def metadata(m: Option[pb.Metadata]): Metadata =
    Metadata(m.toSeq.flatMap(_.entries).map(e => e.key -> e.value).toVector)
  private def error(e: CommandError): pb.Error =
    pb.Error(e.message, Translate.toCode(e.code))
  private def withKeys(request: InvokeRequest): Metadata =
    request.payload.fold(metadata(request.metadata)) { p =>
      metadata(request.metadata)
        .set(PayloadKeys.Manifest, p.manifest)
        .set(PayloadKeys.ContentType, p.contentType)
    }

  /**
   * `Unavailable` is what a remote host answers a command caught in a shard hand-off — an instance
   * stopping on a node that is leaving during a rollout. The next attempt goes through sharding to
   * the instance's new home. Retried here, in the sidecar, so every SDK gets the same behaviour.
   *
   * A call the hand-off *drops* gets no `Unavailable`, or any answer: that one only a query
   * survives, by `askQuery` sending it again (`queries`).
   */
  private val RetryDelays: Vector[FiniteDuration] = Vector(200.millis, 500.millis, 1.second)

  private def askWithRetry(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      bytes: Array[Byte],
      metadata: Metadata,
      attempt: Int = 0
  ): Future[(Array[Byte], Metadata)] =
    // Each attempt is a call, and is counted as one where it lands or where it goes unanswered.
    asCaller(metadata)(
      // A query may be sent again if it goes unanswered, and never waits for approval; a command is
      // sent once, and its reply's metadata says whether its turn waits.
      if queries.contains((componentId, method)) then
        transport
          .askQuery(componentId, entityId, method, bytes, metadata)
          .map(reply => (reply, Metadata.empty))(using ExecutionContext.parasitic)
      else transport.askWithMetadata(componentId, entityId, method, bytes, metadata)
    ).recoverWith {
      case e: CommandError if e.code == ErrorCode.Unavailable && attempt < RetryDelays.size =>
        val promise = scala.concurrent.Promise[(Array[Byte], Metadata)]()
        val _ = system.scheduler.scheduleOnce(
          RetryDelays(attempt),
          () =>
            promise.completeWith(
              askWithRetry(componentId, entityId, method, bytes, metadata, attempt + 1)
            ): Unit
        )
        promise.future
    }

  def invoke(request: InvokeRequest): Future[InvokeReply] =
    (for
      componentId <- Future.fromTry(
        ComponentId.parse(request.componentId).left.map(IllegalArgumentException(_)).toTry
      )
      entityId <- Future.fromTry(
        EntityId.parse(request.entityId).left.map(IllegalArgumentException(_)).toTry
      )
      method <- Future.fromTry(
        MethodName.parse(request.name).left.map(IllegalArgumentException(_)).toTry
      )
      reply <- askWithRetry(
        componentId,
        entityId,
        method,
        payload(request.payload),
        withKeys(request)
      )
    yield
      // The reply's manifest and content type come back the same way; the transport hands back
      // only bytes, so the remote host's reply metadata is where they are. Absent (an in-process
      // target), the caller gets the bytes under its own manifest.
      val (bytes, replyMetadata) = reply
      awaitingIn(bytes, replyMetadata).getOrElse(
        InvokeReply(
          InvokeReply.Result.Reply(
            pb.Outcome.Reply(
              Some(
                pb.Payload(
                  request.payload.map(_.contentType).getOrElse(""),
                  manifest(request.payload),
                  ByteString.copyFrom(bytes)
                )
              ),
              None
            )
          )
        )
      )
    ).recover {
      case e: CommandError => InvokeReply(InvokeReply.Result.Error(error(e)))
      case e: IllegalArgumentException =>
        InvokeReply(InvokeReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.BAD_REQUEST)))
      case e => InvokeReply(InvokeReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL)))
    }

  /**
   * A decision on an approval request (protocol 1.11), sent as the process's handler.
   *
   * An agent's is answered as the handler whose turn waited would have answered: the answer, under
   * the text manifest a process's agent replies with, or the requests still or newly awaiting. An
   * autonomous agent's is answered once it is recorded. Refusals travel in the reply.
   */
  def decide(request: DecideRequest): Future[InvokeReply] =
    val decision = Decision(
      request.approvalId,
      request.approved,
      request.by,
      request.note.filter(_.nonEmpty),
      System.currentTimeMillis()
    )
    val carried = metadata(request.metadata)
    (for
      componentId <- Future.fromTry(
        ComponentId.parse(request.componentId).left.map(IllegalArgumentException(_)).toTry
      )
      entityId <- Future.fromTry(
        EntityId.parse(request.entityId).left.map(IllegalArgumentException(_)).toTry
      )
      reply <- request.kind match
        case ankka.protocol.v1.discovery.Kind.AUTONOMOUS_AGENT =>
          asCaller(carried)(
            transport.ask(
              componentId,
              entityId,
              HostProtocol.Decide,
              HostProtocol.decide.toBytes(decision),
              carried
            )
          ).map(_ => InvokeReply(InvokeReply.Result.Reply(pb.Outcome.Reply(None, None))))
        case _ =>
          asCaller(carried)(
            transport.askWithMetadata(
              componentId,
              entityId,
              MethodName(Approvals.DecideMethod),
              writeToArray(Approvals.DecideRequest(request.name, decision)),
              carried
            )
          ).map { (bytes, replyMetadata) =>
            awaitingIn(bytes, replyMetadata).getOrElse(
              InvokeReply(
                InvokeReply.Result.Reply(
                  pb.Outcome.Reply(
                    Some(pb.Payload("text/plain", "string", ByteString.copyFrom(bytes))),
                    None
                  )
                )
              )
            )
          }
    yield reply).recover {
      case e: CommandError => InvokeReply(InvokeReply.Result.Error(error(e)))
      case e: IllegalArgumentException =>
        InvokeReply(InvokeReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.BAD_REQUEST)))
      case e => InvokeReply(InvokeReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL)))
    }

  /** The approval requests a reply carries, when its metadata says it is one. */
  private def awaitingIn(bytes: Array[Byte], replyMetadata: Metadata): Option[InvokeReply] =
    Option.when(replyMetadata.get(Approvals.OutcomeKey).contains(Approvals.OutcomeValue))(
      InvokeReply(InvokeReply.Result.Approval(awaited(bytes)))
    )

  private def awaited(bytes: Array[Byte]): ApprovalAwaited =
    ApprovalAwaited(readFromArray[Approvals.Awaiting](bytes).requests.map { r =>
      ApprovalRequest(r.id, r.tool, r.arguments.render, r.requestedAt, r.expiresAt)
    })

  /**
   * Streams a handler's tokens to `emit`, ending with exactly one `completed` or `failed` token,
   * after which nothing more is emitted.
   */
  def invokeStream(request: InvokeRequest, emit: StreamToken => Unit): Unit =
    (
      ComponentId.parse(request.componentId),
      EntityId.parse(request.entityId),
      MethodName.parse(request.name)
    ) match
      case (Right(componentId), Right(entityId), Right(method)) =>
        val tokens: ActorRef[EntityProtocol.StreamToken] = system.systemActorOf(
          Behaviors.receiveMessage[EntityProtocol.StreamToken] {
            case EntityProtocol.Token(text) =>
              emit(StreamToken(StreamToken.Token.Text(text)))
              Behaviors.same
            case EntityProtocol.StreamCompleted =>
              emit(StreamToken(StreamToken.Token.Completed(pb.Empty())))
              Behaviors.stopped
            case failed: EntityProtocol.StreamFailed =>
              emit(StreamToken(StreamToken.Token.Failed(error(failed.toCommandError))))
              Behaviors.stopped
            case awaiting: EntityProtocol.StreamAwaiting =>
              // The stream's last token: what the turn waits on, never an open stream.
              emit(StreamToken(StreamToken.Token.Approval(awaited(awaiting.payload))))
              Behaviors.stopped
          },
          s"callback-stream-${java.util.UUID.randomUUID()}"
        )
        val carried = metadata(request.metadata)
        asCaller(carried)(
          transport.tell(
            componentId,
            entityId,
            EntityProtocol.InvokeStream(
              method,
              payload(request.payload),
              MetaEntry.from(carried),
              tokens
            )
          )
        )
      case _ =>
        emit(
          StreamToken(
            StreamToken.Token.Failed(
              pb.Error("invalid component, entity or handler name", pb.ErrorCode.BAD_REQUEST)
            )
          )
        )

  def query(request: QueryRequest): Future[QueryReply] =
    ComponentId.parse(request.viewId) match
      case Left(msg) =>
        Future.successful(
          QueryReply(QueryReply.Result.Error(pb.Error(msg, pb.ErrorCode.BAD_REQUEST)))
        )
      case Right(viewId) =>
        // Rows are the process's own JSON under its row manifest; the sidecar hands them back as
        // they are stored. Query names: `get` (payload: the key as text), `all` (no payload).
        val queries =
          ViewQueries(
            viewId.toString,
            ViewDescriptor.tableFor(viewId),
            Serializer.bytes,
            database,
            settings.commandTimeout
          )
        // Asked as the handler the process was running, as a call is: the query is counted when it
        // is made, on this thread.
        val rows: Future[Vector[Array[Byte]]] = asCaller(metadata(request.metadata)) {
          request.name match
            case "get" | "by-id" | "by-key" =>
              queries.getAsync(String(payload(request.payload), "UTF-8")).map(_.toVector)
            case "all" => queries.allAsync(1000)
            case other =>
              Future.failed(
                CommandError(
                  s"unknown view query '$other'; a remote view answers 'get' and 'all'",
                  ErrorCode.NotFound
                )
              )
        }
        rows
          .map { rs =>
            val json = rs.map(r => String(r, "UTF-8")).mkString("[", ",", "]")
            QueryReply(
              QueryReply.Result
                .Rows(pb.Payload("application/json", "rows", ByteString.copyFromUtf8(json)))
            )
          }
          .recover {
            case e: CommandError => QueryReply(QueryReply.Result.Error(error(e)))
            case e =>
              QueryReply(QueryReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL)))
          }

  def schedule(request: ScheduleRequest): Future[pb.Empty] =
    timers() match
      case None => Future.failed(CommandError("timers are not running", ErrorCode.Unavailable))
      case Some(scheduler) =>
        (ComponentId.parse(request.componentId), MethodName.parse(request.name)) match
          case (Right(componentId), Right(method)) =>
            // The whole Payload travels through the timer table as bytes, so manifest and content
            // type survive to the timed action (the same trick as a workflow step's input).
            val bytes = request.payload.map(_.toByteArray).getOrElse(Array.emptyByteArray)
            scheduler.createSingleTimer(
              request.timerId,
              request.delayMillis.millis,
              DeferredCall(componentId, method, bytes)
            )
            Future.successful(pb.Empty())
          case _ =>
            Future.failed(CommandError("invalid component or handler name", ErrorCode.BadRequest))

  /**
   * A recurring timer (protocol 1.12). A refusal is the reply's `Error` rather than a failed call,
   * so a module's import can carry it as a process's gRPC reply does: a period out of bounds, an
   * empty id or an oversized payload is the scheduler's own `IllegalArgumentException`, with its
   * message.
   */
  def scheduleRecurring(request: ScheduleRecurringRequest): Future[ScheduleRecurringReply] =
    def refused(code: pb.ErrorCode, message: String) =
      ScheduleRecurringReply(Some(pb.Error(message, code)))
    timers() match
      case None => Future.successful(refused(pb.ErrorCode.UNAVAILABLE, "timers are not running"))
      case Some(scheduler) =>
        (ComponentId.parse(request.componentId), MethodName.parse(request.name)) match
          case (Right(componentId), Right(method)) =>
            Future {
              try
                val bytes = request.payload.map(_.toByteArray).getOrElse(Array.emptyByteArray)
                scheduler.createRecurringTimer(
                  request.timerId,
                  request.delayMillis.millis,
                  request.periodMillis.millis,
                  DeferredCall(componentId, method, bytes)
                )
                ScheduleRecurringReply()
              catch
                case e: IllegalArgumentException => refused(pb.ErrorCode.BAD_REQUEST, e.getMessage)
                case e: CommandError             => ScheduleRecurringReply(Some(error(e)))
                case e: Throwable                => refused(pb.ErrorCode.UNAVAILABLE, e.getMessage)
            }(using AnkkaExecutors.virtual)
          case _ =>
            Future.successful(
              refused(pb.ErrorCode.BAD_REQUEST, "invalid component or handler name")
            )

  def cancel(request: CancelRequest): Future[pb.Empty] =
    timers() match
      case None => Future.successful(pb.Empty())
      case Some(scheduler) =>
        scheduler.delete(request.timerId)
        Future.successful(pb.Empty())

  // ── The secret store (protocol 1.6) ─────────────────────────────────────────
  //
  // The service's own store, on a virtual thread: every call is a blocking database read or write,
  // and the key it decrypts with never leaves this process. A refusal is the reply's `Error`, the same
  // for a process and a module.

  private def onStore[A](work: => A)(refused: pb.Error => A): Future[A] =
    Future {
      try work
      catch
        case e: CommandError => refused(error(e))
        case e: Throwable    => refused(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL))
    }(using AnkkaExecutors.virtual)

  def getSecret(request: GetSecretRequest): Future[GetSecretReply] =
    onStore(
      GetSecretReply(
        service.secrets.get(request.name) match
          case Some(value) => GetSecretReply.Result.Value(value)
          case None        => GetSecretReply.Result.Absent(pb.Empty())
      )
    )(e => GetSecretReply(GetSecretReply.Result.Error(e)))

  def putSecret(request: PutSecretRequest): Future[PutSecretReply] =
    onStore { service.secrets.put(request.name, request.value); PutSecretReply() }(e =>
      PutSecretReply(Some(e))
    )

  def deleteSecret(request: DeleteSecretRequest): Future[DeleteSecretReply] =
    onStore { service.secrets.delete(request.name); DeleteSecretReply() }(e =>
      DeleteSecretReply(Some(e))
    )

  // ── Calls to other services (protocol 1.8) ──────────────────────────────────
  //
  // Made by the runtime's one client for other services, as this service: the resolution, the
  // certificate and the identity check are the ones a Scala handler's call goes through. The
  // process is never given a key. Who asked is read from the metadata the handler was given, and
  // believed only when the service declared it; an entity's handler, and a workflow's command
  // handler, are refused, because a call would hold every other command to them behind another
  // service. A call nothing can tie to a handler is made, and counted from the unknown caller.

  def request(request: ServiceRequest): Future[ServiceReply] =
    Future {
      refusal(request) match
        case Some(refused) => ServiceReply(ServiceReply.Result.Error(refused))
        case None          => call(request)
    }(using AnkkaExecutors.virtual)

  private def refusal(request: ServiceRequest): Option[pb.Error] =
    def bad(message: String) = Some(pb.Error(message, pb.ErrorCode.BAD_REQUEST))
    val metadata             = this.metadata(request.metadata)
    if !ClientLogic.servesRequests(declaredProtocol) then
      bad(
        s"this process declared protocol ${declaredProtocol.getOrElse("")}, and a call to another " +
          s"service needs protocol ${ClientLogic.RequestSince}; the sidecar speaks ${Discovery.ProtocolVersion}"
      )
    else if !ClientLogic.isName(request.service) then
      bad(s"a call to another service names the service: '${request.service}' is not a name")
    else if request.project.exists(p => !ClientLogic.isName(p)) then
      bad(s"'${request.project.getOrElse("")}' is not a project's name")
    else if request.method.isEmpty then bad("a call to another service names its method")
    else if !request.path.startsWith("/") then
      bad(s"a call's path starts with '/': '${request.path}' does not")
    else if request.body.exists(_.size > ClientLogic.MaxBodyBytes) then
      bad(s"a call's body is at most ${ClientLogic.MaxBodyBytes} bytes through the sidecar")
    else
      observability.declared.origin(metadata).flatMap { origin =>
        service.registry.components
          .find(_.componentId.toString == origin.component)
          .flatMap { component =>
            component.kind match
              case ComponentKind.EventSourcedEntity | ComponentKind.KeyValueEntity =>
                bad(
                  s"the ${ClientLogic.kindName(component.kind)} '${origin.component}' may not " +
                    "call another service: its other commands would wait behind the call"
                )
              case ComponentKind.Workflow
                  if !component.declaredHandlers
                    .exists(h => h.name == origin.handler && h.kind == HandlerKind.Step) =>
                bad("a workflow calls another service in a step, not in a command handler")
              case _ => None
          }
      }

  private def call(request: ServiceRequest): ServiceReply =
    val metadata = this.metadata(request.metadata)
    val origin   = observability.declared.origin(metadata)
    val trace    = Trace.traceIdOf(metadata).zip(Trace.parentSpanIdOf(metadata))
    def made: ServiceResponse =
      val clients = service.services
      val client  = request.project.fold(clients(request.service))(clients(_, request.service))
      client.request(
        request.method,
        request.path,
        request.body.map(_.toByteArray),
        request.contentType,
        request.headers.map(h => h.name -> h.value)
      )
    try
      val response = (trace, origin) match
        case (Some((traceId, spanId)), Some(o)) => Trace.within(traceId, spanId, o)(made)
        case (Some((traceId, spanId)), None)    => Trace.within(traceId, spanId)(made)
        case (None, Some(o))                    => Trace.asOrigin(o)(made)
        case (None, None)                       => made
      if response.body.length > ClientLogic.MaxBodyBytes then
        ServiceReply(
          ServiceReply.Result.Error(
            pb.Error(
              s"the answer of ${request.service} is over ${ClientLogic.MaxBodyBytes} bytes, " +
                "more than the sidecar carries",
              pb.ErrorCode.INTERNAL
            )
          )
        )
      else
        ServiceReply(
          ServiceReply.Result.Response(
            PbHttpResponse(
              response.status,
              response.contentType,
              ByteString.copyFrom(response.body),
              response.headers.map((name, value) => PbHttpRequest.Pair(name, value))
            )
          )
        )
    catch
      case e: ServiceUnresolvable =>
        failure(ServiceFailure.Reason.UNRESOLVABLE, e.getMessage)
      case e: ServiceIdentityMismatch =>
        failure(ServiceFailure.Reason.IDENTITY_MISMATCH, e.getMessage)
      case e: ServiceUnanswered =>
        failure(ServiceFailure.Reason.UNANSWERED, e.getMessage)
      case e: CommandError => ServiceReply(ServiceReply.Result.Error(error(e)))
      case e: Throwable =>
        ServiceReply(
          ServiceReply.Result.Error(
            pb.Error(Option(e.getMessage).getOrElse(e.getClass.getName), pb.ErrorCode.INTERNAL)
          )
        )

  private def failure(reason: ServiceFailure.Reason, detail: String): ServiceReply =
    ServiceReply(ServiceReply.Result.Failure(ServiceFailure(reason, detail)))

object ClientLogic:

  /** The protocol that added `Request`. */
  val RequestSince: String = "1.8"

  /** The largest body, either way, a call through the sidecar carries: under gRPC's 4 MiB. */
  val MaxBodyBytes: Int = 4_000_000

  private val NamePattern = "[A-Za-z0-9._-]+".r

  private[sidecar] def isName(value: String): Boolean = NamePattern.matches(value)

  /** Whether a process that declared `declared` may be served `Request`. */
  private[sidecar] def servesRequests(declared: Option[String]): Boolean =
    declared.forall { version =>
      version.split('.').toList match
        case _ :: minor :: _ => minor.toIntOption.exists(_ >= 8)
        case _               => false
    }

  private[sidecar] def kindName(kind: ComponentKind): String = kind match
    case ComponentKind.EventSourcedEntity => "event sourced entity"
    case ComponentKind.KeyValueEntity     => "key value entity"
    case other                            => other.toString
