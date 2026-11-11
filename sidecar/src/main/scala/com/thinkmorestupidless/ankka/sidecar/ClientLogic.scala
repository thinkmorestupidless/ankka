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
  DataSubject,
  EntityId,
  ErrorCode,
  KeyAnswer,
  Keyring,
  KeyringUnavailable,
  Metadata,
  MethodName,
  PersonalJson,
  PersonalScope,
  Serializer
}
import ankka.protocol.v1.wasm.{
  KeyringOpenReply,
  KeyringOpenRequest,
  KeyringSealReply,
  KeyringSealRequest
}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException
import com.thinkmorestupidless.ankka.runtime.keyring.ServiceKeyring
import com.thinkmorestupidless.ankka.runtime.replication.RoutedKeyring
import scala.util.control.NonFatal
import com.thinkmorestupidless.ankka.runtime.remote.{
  PayloadKeys,
  RemoteDescriptor,
  RemoteKeyedViewDescriptor,
  RemoteViewDescriptor
}
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaExecutors,
  AnkkaService,
  CheckedQuery,
  Database,
  EntityProtocol,
  MetaEntry,
  Observability,
  QueryCheck,
  Trace,
  ViewQueries
}
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.sdk.{
  DeferredCall,
  WatchEnded,
  WatchEvent,
  Watching,
  ServiceIdentityMismatch,
  ServiceResponse,
  ServiceUnanswered,
  ServiceUnresolvable,
  TimerScheduler,
  ViewDescriptor
}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Source

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
  // Lazily: a service with no database (feature 037) never asks for a view's rows.
  private lazy val database = Database()

  // Each discovered view's declared queries, checked once: discovery has already refused a service
  // whose statements the check would not pass.
  private lazy val declared: Map[ComponentId, Vector[CheckedQuery]] =
    service.registry.components
      .collect {
        case view: RemoteViewDescriptor      => view.componentId -> view.declaredQueries
        case view: RemoteKeyedViewDescriptor => view.componentId -> view.declaredQueries
      }
      .map((id, queries) => id -> QueryCheck.checkedAll(id, ViewDescriptor.tableFor(id), queries))
      .toMap

  private def payload(p: Option[pb.Payload]): Array[Byte] =
    p.map(_.data.toByteArray).getOrElse(Array.emptyByteArray)
  private def manifest(p: Option[pb.Payload]): String    = p.map(_.manifest).getOrElse("")
  private def metadata(m: Option[pb.Metadata]): Metadata =
    Metadata(m.toSeq.flatMap(_.entries).map(e => e.key -> e.value).toVector)
  private def error(e: CommandError): pb.Error =
    pb.Error(e.message, Translate.toCode(e.code), e.details)
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
        val _       = system.scheduler.scheduleOnce(
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
      case e: CommandError             => InvokeReply(InvokeReply.Result.Error(error(e)))
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
      case e: CommandError             => InvokeReply(InvokeReply.Result.Error(error(e)))
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

  // ── Waiting for a workflow's end (protocol 1.18) ─────────────────────────────

  /** The workflow, the id and the caller's time a wait names, or why it cannot be waited for. */
  private def waitFor(
      request: AwaitEndRequest
  ): Either[pb.Error, (ComponentId, EntityId, FiniteDuration)] =
    (ComponentId.parse(request.componentId), EntityId.parse(request.entityId)) match
      case (Right(componentId), Right(entityId)) if request.timeoutMillis > 0 =>
        Right((componentId, entityId, request.timeoutMillis.millis))
      case (Right(_), Right(_)) =>
        Left(
          pb.Error(
            s"a wait for a workflow's end needs a timeout of more than zero, not ${request.timeoutMillis} ms",
            pb.ErrorCode.BAD_REQUEST
          )
        )
      case _ => Left(pb.Error("invalid workflow or id", pb.ErrorCode.BAD_REQUEST))

  /** The wait itself, made as the handler the process was running. */
  private def waiting(
      componentId: ComponentId,
      entityId: EntityId,
      timeout: FiniteDuration,
      request: AwaitEndRequest
  ): Future[pb.Payload] =
    val carried = metadata(request.metadata)
    asCaller(carried)(transport.awaitEnd(componentId, entityId, timeout, carried)).map {
      (bytes, replyMetadata) =>
        // The engine names the state's manifest and content type: a process's workflow's are the
        // process's own, as it wrote them.
        pb.Payload(
          replyMetadata.get(PayloadKeys.ContentType).getOrElse("application/json"),
          replyMetadata.get(PayloadKeys.Manifest).getOrElse(""),
          ByteString.copyFrom(bytes)
        )
    }

  private def failure(e: Throwable): pb.Error = e match
    case refused: CommandError => error(refused)
    case other                 =>
      pb.Error(Option(other.getMessage).getOrElse(other.getClass.getName), pb.ErrorCode.INTERNAL)

  /**
   * Waits for a workflow's end and answers it: the state it ended with, or a `WORKFLOW_FAILED` with
   * the step and the reason in its details, or `TIMEOUT` when the caller's time passed first. The
   * hold and the asking again are the runtime's; the process sees one call.
   */
  def awaitEnd(request: AwaitEndRequest): Future[InvokeReply] =
    waitFor(request) match
      case Left(refusal) => Future.successful(InvokeReply(InvokeReply.Result.Error(refusal)))
      case Right((componentId, entityId, timeout)) =>
        waiting(componentId, entityId, timeout, request)
          .map(state => InvokeReply(InvokeReply.Result.Reply(pb.Outcome.Reply(Some(state), None))))
          .recover(e => InvokeReply(InvokeReply.Result.Error(failure(e))))

  // ── A workflow's standing and how a copy stands (protocol 1.24) ─────────────────

  /**
   * A workflow's standing: the engine's own reserved query (`ankka:lifecycle`), which runs no
   * handler and journals nothing, asked as the Scala client asks it. A refusal is the runtime's,
   * with its code; a status the protocol has no value for is `INTERNAL`, naming it.
   */
  def lifecycle(request: LifecycleRequest): Future[LifecycleReply] =
    read(request.componentId, request.workflowId, sdk.WorkflowLifecycle.Method)(bytes =>
      ClientLogic.lifecycleOf(sdk.WorkflowLifecycle.serializer.fromBytes(bytes))
    ).map {
      case Right(lifecycle) => LifecycleReply(LifecycleReply.Result.Lifecycle(lifecycle))
      case Left(refusal)    => LifecycleReply(LifecycleReply.Result.Error(refusal))
    }(using ExecutionContext.parasitic)

  /**
   * How the copy of a replicated entity at this installation stands: the host's own reserved query
   * (`ankka:replication`), asked as the Scala client asks it. An entity that is not replicated is
   * refused by the host, with its code.
   */
  def replication(request: ReplicationRequest): Future[ReplicationReply] =
    read(request.componentId, request.entityId, sdk.ReplicationStatus.Method)(bytes =>
      Right(ClientLogic.replicationOf(sdk.ReplicationStatus.serializer.fromBytes(bytes)))
    ).map {
      case Right(status) => ReplicationReply(ReplicationReply.Result.Status(status))
      case Left(refusal) => ReplicationReply(ReplicationReply.Result.Error(refusal))
    }(using ExecutionContext.parasitic)

  /** One reserved read of `entityId`, its answer decoded, or the refusal as the protocol's. */
  private def read[A](componentId: String, entityId: String, method: MethodName)(
      decode: Array[Byte] => Either[pb.Error, A]
  ): Future[Either[pb.Error, A]] =
    (ComponentId.parse(componentId), EntityId.parse(entityId)) match
      case (Right(component), Right(entity)) =>
        transport
          .askQuery(component, entity, method, Array.emptyByteArray, Metadata.empty)
          .map(decode)
          .recover(e => Left(failure(e)))
      case _ =>
        Future.successful(Left(pb.Error("invalid component or id", pb.ErrorCode.BAD_REQUEST)))

  /**
   * The same wait as a stream: a `heartbeat` token every `Heartbeat.interval` while it goes on,
   * then one `ended` or `failed` token, after which nothing more is emitted. For a process's
   * endpoint serving the wait as server-sent events past the idle timeout. Returns what stops the
   * heartbeat when the process goes away first; the wait itself ends at its own deadline.
   */
  def awaitEndStream(request: AwaitEndRequest, emit: StreamToken => Unit): () => Unit =
    waitFor(request) match
      case Left(refusal) =>
        emit(StreamToken(StreamToken.Token.Failed(refusal)))
        () => ()
      case Right((componentId, entityId, timeout)) =>
        val lock                                          = new Object
        var ended                                         = false
        def once(token: StreamToken, last: Boolean): Unit = lock.synchronized {
          if !ended then
            if last then ended = true
            emit(token)
        }
        val every = com.thinkmorestupidless.ankka.http.Heartbeat.interval(system.settings.config)
        val beat  = system.scheduler.scheduleAtFixedRate(every, every)(() =>
          once(StreamToken(StreamToken.Token.Heartbeat(pb.Empty())), last = false)
        )
        waiting(componentId, entityId, timeout, request).onComplete { result =>
          beat.cancel()
          val token = result match
            case scala.util.Success(state) => StreamToken(StreamToken.Token.Ended(state))
            case scala.util.Failure(e)     => StreamToken(StreamToken.Token.Failed(failure(e)))
          once(token, last = true)
        }
        () =>
          beat.cancel()
          lock.synchronized { ended = true }

  def query(request: QueryRequest): Future[QueryReply] =
    ComponentId.parse(request.viewId) match
      case Left(msg) =>
        Future.successful(
          QueryReply(QueryReply.Result.Error(pb.Error(msg, pb.ErrorCode.BAD_REQUEST)))
        )
      case Right(viewId) =>
        // Rows are the process's own JSON under its row manifest; the sidecar hands them back as
        // they are stored. Query names: `get` (payload: the key as text), `all` (no payload), and
        // any query the view declared, asked with its values.
        val queries =
          ViewQueries(
            viewId.toString,
            ViewDescriptor.tableFor(viewId),
            Serializer.bytes,
            database,
            settings.commandTimeout,
            declared.getOrElse(viewId, Vector.empty)
          )
        // Asked as the handler the process was running, as a call is: the query is counted when it
        // is made, on this thread.
        val rows: Future[Vector[Array[Byte]]] = asCaller(metadata(request.metadata)) {
          request.name match
            case "get" | "by-id" | "by-key" =>
              queries.getAsync(String(payload(request.payload), "UTF-8")).map(_.toVector)
            case "all" => queries.allAsync(ViewQueries.DefaultLimit)
            case other =>
              queries.askNamed(
                other,
                request.values,
                request.limit.fold(ViewQueries.DefaultLimit)(_.toInt)
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
            case e               =>
              QueryReply(QueryReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL)))
          }

  /** A view's queries as the sidecar reads them: rows as stored, the watches the service's own. */
  private def viewQueries(viewId: ComponentId): ViewQueries[Array[Byte]] =
    ViewQueries(
      viewId.toString,
      ViewDescriptor.tableFor(viewId),
      Serializer.bytes,
      database,
      settings.commandTimeout,
      declared.getOrElse(viewId, Vector.empty),
      Some(service.viewClient.watchRegistry)
    )

  private def failedFrame(failure: Throwable): RowFrame = failure match
    case e: CommandError => RowFrame(RowFrame.Frame.Failed(error(e)))
    case e               =>
      RowFrame(RowFrame.Frame.Failed(pb.Error(String.valueOf(e.getMessage), pb.ErrorCode.INTERNAL)))

  private def rowFrame(key: String, row: Array[Byte]): RowFrame =
    RowFrame(
      RowFrame.Frame.Row(
        WatchedRow(key, Some(pb.Payload("application/json", "row", ByteString.copyFrom(row))))
      )
    )

  /**
   * A query as a stream of frames (1.17): every row of `all` or a declared query, as the database
   * yields it, with no limit unless the request gives one; a refusal or a fault as the last frame.
   * Asked as the handler the process was running, as a whole query is.
   */
  def queryStream(request: QueryRequest): Source[RowFrame, NotUsed] =
    servesViewStreams.orElse(ComponentId.parse(request.viewId).left.toOption.map(BadRequest)) match
      case Some(refused) => Source.single(failedFrame(refused))
      case None          =>
        val queries = viewQueries(ComponentId.parse(request.viewId).toOption.get)
        val limit   = request.limit.map(_.toInt)
        val rows    = asCaller(metadata(request.metadata)) {
          request.name match
            case "all"                      => queries.allStream(limit)
            case "get" | "by-id" | "by-key" =>
              queries.allStream(None).take(0) ++
                Source
                  .future(queries.getAsync(String(payload(request.payload), "UTF-8")))
                  .mapConcat(_.toList)
            case other => queries.askNamedStream(other, request.values, limit)
        }
        // The row's key is not in a plain row stream; a process reads only the rows.
        rows.map(row => rowFrame("", row)).recover { case e => failedFrame(e) }

  /**
   * A watch as frames (1.17): the rows now, `caught_up`, then rows and removals; a reason it ended
   * as `ended`, a refusal or a fault as `failed`, each the last frame.
   */
  def watch(request: WatchRequest): Source[RowFrame, NotUsed] =
    servesViewStreams.orElse(ComponentId.parse(request.viewId).left.toOption.map(BadRequest)) match
      case Some(refused) => Source.single(failedFrame(refused))
      case None          =>
        val queries  = viewQueries(ComponentId.parse(request.viewId).toOption.get)
        val watching = Watching(
          unread = request.unreadBound.map(_.toInt),
          overflow = request.overflow match
            case Overflow.DROP_TAIL => sdk.Overflow.DropTail
            case Overflow.DROP_NEW  => sdk.Overflow.DropNew
            case Overflow.DROP_ALL  => sdk.Overflow.DropAll
            case Overflow.FAIL      => sdk.Overflow.Fail
            case _                  => sdk.Overflow.DropHead
        )
        val events = asCaller(metadata(request.metadata)) {
          request.target match
            case WatchRequest.Target.Named(named) =>
              queries.watchNamed(named.name, named.values, watching)
            case WatchRequest.Target.Key(key) => queries.watchRow(key, watching)
            case WatchRequest.Target.Empty    =>
              Source.failed(
                CommandError("a watch names a declared query or a row key", ErrorCode.BadRequest)
              )
        }
        events
          .map {
            case WatchEvent.Row(key, row) => rowFrame(key, row)
            case WatchEvent.Removed(key)  => RowFrame(RowFrame.Frame.Removed(Removed(key)))
            case WatchEvent.CaughtUp      => RowFrame(RowFrame.Frame.CaughtUp(pb.Empty()))
          }
          .recover {
            case WatchEnded(reason) => RowFrame(RowFrame.Frame.Ended(Ended(reason.wire)))
            case e                  => failedFrame(e)
          }

  private def BadRequest(message: String): CommandError =
    CommandError(message, ErrorCode.BadRequest)

  /**
   * The minor that introduced view streams: a process declaring an earlier one cannot ask for one.
   */
  private val ViewStreamsSince = "1.17"

  private def servesViewStreams: Option[CommandError] =
    declaredProtocol.filterNot(ClientLogic.servesViewStreams).map { declared =>
      CommandError(
        s"this process declared protocol $declared, and a view stream needs protocol " +
          s"$ViewStreamsSince; the sidecar speaks ${Discovery.ProtocolVersion}",
        ErrorCode.BadRequest
      )
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
      case None            => Future.successful(pb.Empty())
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
        // A personal field's keyring could not be reached: UNAVAILABLE, never INTERNAL.
        case e: KeyringUnavailable => refused(error(e.error))
        case e: Throwable          => refused(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL))
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

  // ── Personal data (protocol 1.23) ──────────────────────────────────────────
  //
  // The key, never a value: the process seals and opens its envelopes itself. Asked of the
  // runtime's keyring client and its cache, as the service's own identity; a read of a subject no
  // key was made for reads as erased, as it does in the runtime. A replicated entity's keys are its
  // home's (FR-032): a request naming another installation as the home is asked through the keyring
  // relay, as `RoutedKeyring` asks it for an entity in process.

  /**
   * An erasure request asked by the process (feature 042, 1.23), as this service: the control plane
   * names it as who asked and needs the `erasure` grant of the project, its own included.
   */
  def askErasure(request: AskErasureRequest): Future[AskErasureReply] =
    onStore {
      val subject = DataSubject
        .from(request.subject)
        .fold(why => throw CommandError(why, ErrorCode.BadRequest), identity)
      val notBefore = request.notBefore.filter(_.nonEmpty).map(ClientLogic.notBefore)
      val asked     = service.erasures.ask(
        subject,
        Option(request.project).filter(_.nonEmpty),
        notBefore,
        request.reason,
        request.correlation
      )
      AskErasureReply(AskErasureReply.Reply.Ok(ErasureAsked(asked.id, asked.state)))
    }(e => AskErasureReply(AskErasureReply.Reply.Error(e)))

  /**
   * The keyring a request is answered with: the service's own, or, for a replicated entity whose
   * home is another installation, that home's through the relay (FR-032). Read only there: a
   * follower never makes a key, and a relay that cannot be reached is `Unavailable`, never erased.
   */
  private def keyringFor(home: Option[String]): Keyring =
    val own = ServiceKeyring.of(service.system)
    (service.replication, home.filter(_.nonEmpty)) match
      case (Some(r), Some(h)) if h != r.self =>
        val routed = RoutedKeyring(own, r.self, r.relay)
        routed.home = Some(h)
        routed
      case _ => own

  def getSubjectKey(request: SubjectKeyRequest): Future[SubjectKeyReply] =
    onStore {
      val keyring = keyringFor(Some(request.home))
      val subject = DataSubject
        .from(request.subject)
        .fold(why => throw CommandError(why, ErrorCode.BadRequest), identity)
      val project = if request.project.isEmpty then keyring.project else request.project
      val answer  = keyring.key(project, subject, request.create) match
        case KeyAnswer.Key(bytes) =>
          SubjectKeyReply(
            SubjectKeyReply.Reply.Key(ByteString.copyFrom(IArray.genericWrapArray(bytes).toArray))
          )
        case KeyAnswer.Destroyed => SubjectKeyReply(SubjectKeyReply.Reply.Erased(true))
        // Another project's key, refused without a grant to read it with decryption: erased to a
        // read, and a write under it is refused.
        case KeyAnswer.Refused(_) if !request.create =>
          SubjectKeyReply(SubjectKeyReply.Reply.Erased(true))
        case KeyAnswer.Refused(why) =>
          throw CommandError(
            s"the keyring refused the key of '${request.subject}': $why; nothing was written",
            ErrorCode.Forbidden
          )
        case KeyAnswer.Absent if !request.create =>
          SubjectKeyReply(SubjectKeyReply.Reply.Erased(true))
        case KeyAnswer.Absent =>
          throw CommandError(
            s"the keyring made no key for '${request.subject}' when asked to; nothing was written",
            ErrorCode.Unavailable
          )
      answer.withProject(project)
    }(e => SubjectKeyReply(SubjectKeyReply.Reply.Error(e)))

  /**
   * A module's personal field sealed (protocol 1.23): by the runtime's keyring and core's own
   * codec, under the record's manifest, so a module writes the bytes a Scala field does. `home` is
   * the replicated entity's home the call is for, as the host knows it (FR-032).
   */
  def keyringSeal(request: KeyringSealRequest, home: Option[String] = None): KeyringSealReply =
    personal(e => KeyringSealReply(KeyringSealReply.Reply.Error(e))) {
      val problem = PersonalJson.subjectProblem(request.subject)
      if problem != null then throw CommandError(problem, ErrorCode.BadRequest)
      val keyring = keyringFor(home)
      val value   = if request.erased then null else request.value.toByteArray
      val written =
        if request.project.isEmpty || request.project == keyring.project then
          Keyring.binding(keyring)(
            PersonalScope.within(request.manifest)(PersonalJson.write(request.subject, value))
          )
        else
          ClientLogic.keptUnder(keyring, request.project, request.subject, request.manifest, value)
      KeyringSealReply(KeyringSealReply.Reply.Envelope(ByteString.copyFrom(written)))
    }

  /** A module's personal field read back (protocol 1.23): its JSON, or erased. */
  def keyringOpen(request: KeyringOpenRequest, home: Option[String] = None): KeyringOpenReply =
    personal(e => KeyringOpenReply(KeyringOpenReply.Reply.Error(e))) {
      val read = Keyring.binding(keyringFor(home))(
        PersonalScope.within(request.manifest)(PersonalJson.read(request.envelope.toByteArray))
      )
      if read.value == null then KeyringOpenReply(KeyringOpenReply.Reply.Erased(true))
      else KeyringOpenReply(KeyringOpenReply.Reply.Value(ByteString.copyFrom(read.value)))
    }

  /** `work` with the service's keyring current; a refusal or a fault as the reply's `Error`. */
  private def personal[A](refused: pb.Error => A)(work: => A): A =
    try ServiceKeyring.binding(service.system)(work)
    catch
      case e: CommandError => refused(error(e))
      // The keyring could not be reached: UNAVAILABLE with `personal` = `unavailable`, as the
      // runtime answers a Scala service's caller, never INTERNAL.
      case e: KeyringUnavailable  => refused(error(e.error))
      case e: JsonReaderException =>
        refused(pb.Error(s"not a personal field: ${e.getMessage}", pb.ErrorCode.BAD_REQUEST))
      case NonFatal(e) => refused(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL))

  // ── Client keys (protocol 1.20) ─────────────────────────────────────────────
  //
  // The service's client keys, kept by its MCP runtime: what a Scala handler reaches as
  // `clients.clientKeys`. A refusal or a fault is an Error in the reply, as for the secret store;
  // a service whose MCP endpoints admit no key has no MCP runtime, and every call is refused
  // saying so. The key is in an issue's reply and nowhere else: not logged, not in any span.

  private def clientKeys: com.thinkmorestupidless.ankka.http.ClientKeys =
    service
      .extension[com.thinkmorestupidless.ankka.mcp.McpRuntime]
      .map(_.clientKeys)
      .getOrElse(com.thinkmorestupidless.ankka.http.ClientKeys.unavailable)

  def issueClientKey(request: IssueClientKeyRequest): Future[IssueClientKeyReply] =
    import com.thinkmorestupidless.ankka.http.{ClientKeyRequest, Expiry}
    import IssueClientKeyRequest.Expiry as Wire
    onStore {
      val expiry = request.expiry match
        case Wire.AfterMillis(millis)  => Some(Expiry.After(millis.millis))
        case Wire.AtEpochMillis(epoch) => Some(Expiry.At(java.time.Instant.ofEpochMilli(epoch)))
        case Wire.Never(true)          => Some(Expiry.Never)
        case _                         => None
      val issued = clientKeys.issueRequest(
        ClientKeyRequest(
          request.subject,
          expiry,
          request.endpoint.filter(_.nonEmpty),
          request.scope.toSet,
          request.attributes,
          request.label.filter(_.nonEmpty)
        )
      )
      IssueClientKeyReply(
        IssueClientKeyReply.Reply.Issued(IssueClientKeyReply.Issued(issued.id, issued.clientKey))
      )
    }(e => IssueClientKeyReply(IssueClientKeyReply.Reply.Error(e)))

  def listClientKeys(
      @scala.annotation.unused request: ListClientKeysRequest
  ): Future[ListClientKeysReply] =
    onStore(
      ListClientKeysReply(
        clientKeys
          .list()
          .map(k =>
            ClientKeySummary(
              id = k.id,
              subject = k.subject,
              attributes = k.attributes,
              label = k.label,
              endpoint = k.endpoint,
              scope = k.scope.toSeq.sorted,
              issuedAtMillis = k.issuedAt.toEpochMilli,
              expiresAtMillis = k.expiresAt.map(_.toEpochMilli),
              lastUsedMillis = k.lastUsed.map(_.toEpochMilli),
              revokedAtMillis = k.revokedAt.map(_.toEpochMilli)
            )
          )
      )
    )(e => ListClientKeysReply(error = Some(e)))

  def revokeClientKey(request: RevokeClientKeyRequest): Future[RevokeClientKeyReply] =
    onStore { clientKeys.revoke(request.id); RevokeClientKeyReply() }(e =>
      RevokeClientKeyReply(Some(e))
    )

  // ── Object storage, for a module (protocol 1.21) ────────────────────────────
  //
  // A process speaks S3 itself; only a module's `storage` import reaches this. One operation a
  // call, on the service's own storage client, with every body bounded as a call's is: a put above
  // the bound is refused before anything is kept, and a get above it before a byte is read.

  def storage(request: StorageRequest): Future[StorageReply] =
    onStore(answerStorage(request))(e => StorageReply(StorageReply.Reply.Error(e)))

  private def answerStorage(r: StorageRequest): StorageReply =
    import StorageReply.Reply
    val bound  = ClientLogic.MaxBodyBytes
    val bucket = r.bucket.fold(service.storage.default)(service.storage.bucket)
    def tooLarge(length: Long, what: String) =
      CommandError(
        s"$what through the storage import is at most $bound bytes; this one is $length",
        ErrorCode.BadRequest
      )
    def stamp(at: java.time.Instant) = at.toString
    r.op match
      case StorageRequest.Op.PUT =>
        val body = r.body.getOrElse(com.google.protobuf.ByteString.EMPTY)
        if body.size > bound then throw tooLarge(body.size.toLong, "an object kept")
        bucket.putBytes(r.name, body.toByteArray, r.contentType)
        StorageReply(Reply.Done(pb.Empty()))
      case StorageRequest.Op.GET =>
        bucket.stat(r.name) match
          case None                              => StorageReply(Reply.Done(pb.Empty()))
          case Some(info) if info.length > bound => throw tooLarge(info.length, "an object read")
          case Some(_)                           =>
            bucket.get(r.name) match
              case None        => StorageReply(Reply.Done(pb.Empty()))
              case Some(found) =>
                scala.util.Using.resource(found) { o =>
                  // Replaced between the stat and the read: still never more than the bound.
                  val bytes = o.body.readNBytes(bound + 1)
                  if bytes.length > bound then throw tooLarge(o.length, "an object read")
                  StorageReply(
                    Reply.Object(
                      StoredObject(
                        o.contentType,
                        com.google.protobuf.ByteString.copyFrom(bytes),
                        bytes.length.toLong,
                        stamp(o.lastKept)
                      )
                    )
                  )
                }
      case StorageRequest.Op.DELETE =>
        bucket.delete(r.name)
        StorageReply(Reply.Done(pb.Empty()))
      case StorageRequest.Op.LIST =>
        val after = r.continuation.getOrElse("")
        val page  = bucket
          .list(r.name)
          .dropWhile(e => after.nonEmpty && e.name <= after)
          .take(ClientLogic.ListPage + 1)
          .toVector
        val shown = page.take(ClientLogic.ListPage)
        StorageReply(
          Reply.Entries(
            ObjectEntries(
              shown.map(e => ObjectEntry(e.name, e.length, stamp(e.lastKept))),
              Option.when(page.size > ClientLogic.ListPage)(shown.last.name)
            )
          )
        )
      case StorageRequest.Op.STAT =>
        bucket.stat(r.name) match
          case None    => StorageReply(Reply.Done(pb.Empty()))
          case Some(i) =>
            StorageReply(Reply.Info(ObjectInfo(i.name, i.contentType, i.length, stamp(i.lastKept))))
      case StorageRequest.Op.SIGNED_URL =>
        val access =
          if r.access == StorageRequest.Access.KEEP then sdk.SignedAccess.Keep
          else sdk.SignedAccess.Read
        val span =
          if r.expiresSeconds == 0 then sdk.ObjectRules.DefaultSignedUrl
          else (r.expiresSeconds.toLong & 0xffffffffL).seconds
        StorageReply(Reply.Url(bucket.signedUrl(r.name, access, span).toString))
      case StorageRequest.Op.URL =>
        StorageReply(Reply.Url(bucket.url(r.name).toString))
      case StorageRequest.Op.ERASE =>
        val subject = com.thinkmorestupidless.ankka.core.DataSubject
          .from(r.name)
          .fold(why => throw CommandError(why, ErrorCode.BadRequest), identity)
        val erased = bucket.erase(subject)
        StorageReply(Reply.Erased(ErasedObjects(erased.count, erased.finality.wire)))
      case StorageRequest.Op.Unrecognized(n) =>
        throw CommandError(
          s"storage operation $n is not one this runtime knows",
          ErrorCode.BadRequest
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
    val metadata              = this.metadata(request.metadata)
    val origin                = observability.declared.origin(metadata)
    val trace                 = Trace.traceIdOf(metadata).zip(Trace.parentSpanIdOf(metadata))
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
      case e: Throwable    =>
        ServiceReply(
          ServiceReply.Result.Error(
            pb.Error(Option(e.getMessage).getOrElse(e.getClass.getName), pb.ErrorCode.INTERNAL)
          )
        )

  private def failure(reason: ServiceFailure.Reason, detail: String): ServiceReply =
    ServiceReply(ServiceReply.Result.Failure(ServiceFailure(reason, detail)))

object ClientLogic:

  /** The runtime's standing as the protocol's message; a status it has no value for is refused. */
  private[sidecar] def lifecycleOf(l: sdk.WorkflowLifecycle): Either[pb.Error, WorkflowLifecycle] =
    val status = l.status match
      case "NotStarted" => Some(WorkflowStatus.NOT_STARTED)
      case "Running"    => Some(WorkflowStatus.RUNNING)
      case "Paused"     => Some(WorkflowStatus.PAUSED)
      case "Completed"  => Some(WorkflowStatus.COMPLETED)
      case "Failed"     => Some(WorkflowStatus.FAILED)
      case _            => None
    status
      .map(st => WorkflowLifecycle(st, l.pendingStep, l.retries, l.failure))
      .toRight(
        pb.Error(
          s"a workflow's standing '${l.status}' has no protocol value",
          pb.ErrorCode.INTERNAL
        )
      )

  /** How a copy stands, as the protocol's message: the replication set sorted. */
  private[sidecar] def replicationOf(r: sdk.ReplicationStatus): ReplicationStatus =
    ReplicationStatus(
      r.home,
      r.replicationSet.toVector.sorted,
      r.pendingHandover,
      r.conflict,
      r.position
    )

  /** The protocol that added `Request`. */
  val RequestSince: String = "1.8"

  /** The largest body, either way, a call through the sidecar carries: under gRPC's 4 MiB. */
  val MaxBodyBytes: Int = 4_000_000

  /** The most entries one `LIST` through the storage import answers; `next` says where to go on. */
  val ListPage: Int = 1000

  private val NamePattern = "[A-Za-z0-9._-]+".r

  private[sidecar] def isName(value: String): Boolean = NamePattern.matches(value)

  /** Whether a process that declared `declared` may ask for a view stream or a watch. */
  private[sidecar] def servesViewStreams(declared: String): Boolean =
    declared.split('.').toList match
      case _ :: minor :: _ => minor.toIntOption.exists(_ >= 17)
      case _               => false

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

  /**
   * An erasure request's not-before, as a process or a module sends it (protocol 1.23): exactly an
   * ISO-8601 date, which is what every SDK sends, or a full instant at midnight UTC, which is how a
   * date reads when the control plane's wire carries it as an instant. Anything else is refused, a
   * date with trailing text or an instant with a time of day included, rather than read as a date
   * it does not say (decided 2026-11-05).
   */
  private[sidecar] def notBefore(text: String): java.time.LocalDate =
    import java.time.{Instant, LocalDate, LocalTime, ZoneOffset}
    scala.util
      .Try(LocalDate.parse(text))
      .orElse(
        scala.util
          .Try(Instant.parse(text).atOffset(ZoneOffset.UTC))
          .filter(_.toLocalTime == LocalTime.MIDNIGHT)
          .map(_.toLocalDate)
      )
      .getOrElse(
        throw CommandError(
          s"not_before is an ISO-8601 date (2027-01-31), or an instant at midnight UTC " +
            s"(2027-01-31T00:00:00Z), not '$text'",
          ErrorCode.BadRequest
        )
      )

  /**
   * A field read from another project's envelope, written again (FR-027): sealed under that
   * project's key, asked with `create` false so this service never makes one there, and written
   * erased when the key is no longer given (erased there, or a revoked grant), as the Scala codec
   * does for such a field, rather than stalling the write.
   */
  private[sidecar] def keptUnder(
      keyring: Keyring,
      producing: String,
      subject: String,
      manifest: String,
      value: Array[Byte]
  ): Array[Byte] =
    val kept =
      try
        keyring.key(producing, DataSubject(subject), create = false) match
          case key: KeyAnswer.Key => Some(key)
          case _                  => None
      catch case e: CommandError if e.code == ErrorCode.Unavailable => throw KeyringUnavailable(e)
    val under = new Keyring:
      def project: String                                                        = producing
      def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
        kept.filter(_ => project == producing).getOrElse(KeyAnswer.Destroyed)
      override def toString: String = s"the keyring of $producing, read only"
    Keyring.binding(under)(
      PersonalScope.within(manifest)(
        PersonalJson.write(subject, if kept.isEmpty then null else value)
      )
    )
