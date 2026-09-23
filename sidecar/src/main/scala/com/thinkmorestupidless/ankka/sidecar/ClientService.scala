package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName,
  Serializer
}
import com.thinkmorestupidless.ankka.runtime.remote.PayloadKeys
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaService,
  Database,
  EntityProtocol,
  MetaEntry,
  ViewQueries
}
import com.thinkmorestupidless.ankka.sdk.{DeferredCall, TimerScheduler, ViewDescriptor}
import io.grpc.stub.StreamObserver
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * The callback service: what a developer's process calls *back* for — other components, view rows,
 * timers. Served by the sidecar on loopback only.
 *
 * `Invoke` goes straight to the sharding transport with the bytes it was given, so a call from the
 * process is routed exactly as a call from a Scala endpoint would be, wherever the target instance
 * lives. The trace and span ids in `metadata` are the ones the SDK received with the command or
 * request it is handling, which is what makes the nested call a child span.
 */
final class ClientService(
    service: AnkkaService,
    settings: Settings,
    timers: () => Option[TimerScheduler]
)(using system: ActorSystem[?])
    extends ClientGrpc.Client:

  private given ec: ExecutionContext = system.executionContext
  private val transport              = service.componentClient.transportRef
  private val database               = Database()

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
      bytes <- transport.ask(
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
      )).recover {
      case e: CommandError => InvokeReply(InvokeReply.Result.Error(error(e)))
      case e: IllegalArgumentException =>
        InvokeReply(InvokeReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.BAD_REQUEST)))
      case e => InvokeReply(InvokeReply.Result.Error(pb.Error(e.getMessage, pb.ErrorCode.INTERNAL)))
    }

  def invokeStream(request: InvokeRequest, out: StreamObserver[StreamToken]): Unit =
    (
      ComponentId.parse(request.componentId),
      EntityId.parse(request.entityId),
      MethodName.parse(request.name)
    ) match
      case (Right(componentId), Right(entityId), Right(method)) =>
        val tokens: ActorRef[EntityProtocol.StreamToken] = system.systemActorOf(
          Behaviors.receiveMessage[EntityProtocol.StreamToken] {
            case EntityProtocol.Token(text) =>
              out.onNext(StreamToken(StreamToken.Token.Text(text)))
              Behaviors.same
            case EntityProtocol.StreamCompleted =>
              out.onNext(StreamToken(StreamToken.Token.Completed(pb.Empty())))
              out.onCompleted()
              Behaviors.stopped
            case failed: EntityProtocol.StreamFailed =>
              out.onNext(StreamToken(StreamToken.Token.Failed(error(failed.toCommandError))))
              out.onCompleted()
              Behaviors.stopped
          },
          s"callback-stream-${java.util.UUID.randomUUID()}"
        )
        transport.tell(
          componentId,
          entityId,
          EntityProtocol.InvokeStream(
            method,
            payload(request.payload),
            MetaEntry.from(metadata(request.metadata)),
            tokens
          )
        )
      case _ =>
        out.onNext(
          StreamToken(
            StreamToken.Token.Failed(
              pb.Error("invalid component, entity or handler name", pb.ErrorCode.BAD_REQUEST)
            )
          )
        )
        out.onCompleted()

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
            ViewDescriptor.tableFor(viewId),
            Serializer.bytes,
            database,
            settings.commandTimeout
          )
        val rows: Future[Vector[Array[Byte]]] = request.name match
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
      case None =>
        Future.failed(
          io.grpc.Status.UNAVAILABLE.withDescription("timers are not running").asRuntimeException()
        )
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
            Future.failed(
              io.grpc.Status.INVALID_ARGUMENT
                .withDescription("invalid component or handler name")
                .asRuntimeException()
            )

  def cancel(request: CancelRequest): Future[pb.Empty] =
    timers() match
      case None => Future.successful(pb.Empty())
      case Some(scheduler) =>
        scheduler.delete(request.timerId)
        Future.successful(pb.Empty())

/** The loopback-only gRPC server for `client.proto`. */
object CallbackServer:
  private val log = LoggerFactory.getLogger(getClass)

  def start(service: ClientService, bind: String, port: Int)(using ec: ExecutionContext): Server =
    val server = NettyServerBuilder
      .forAddress(new InetSocketAddress(bind, port))
      .addService(ClientGrpc.bindService(service, ec))
      .build()
      .start()
    log.info("callback server listening on {}:{}", bind, server.getPort)
    server

private[sidecar] object Translate:
  def toCode(code: ErrorCode): pb.ErrorCode = code match
    case ErrorCode.BadRequest   => pb.ErrorCode.BAD_REQUEST
    case ErrorCode.Unauthorized => pb.ErrorCode.UNAUTHORIZED
    case ErrorCode.Forbidden    => pb.ErrorCode.FORBIDDEN
    case ErrorCode.NotFound     => pb.ErrorCode.NOT_FOUND
    case ErrorCode.Conflict     => pb.ErrorCode.CONFLICT
    case ErrorCode.Timeout      => pb.ErrorCode.TIMEOUT
    case ErrorCode.Unavailable  => pb.ErrorCode.UNAVAILABLE
    case ErrorCode.Internal     => pb.ErrorCode.INTERNAL
