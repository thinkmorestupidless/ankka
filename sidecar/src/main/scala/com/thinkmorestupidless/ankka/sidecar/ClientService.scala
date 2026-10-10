package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.AnkkaService
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import io.grpc.stub.StreamObserver
import io.grpc.{Server, Status}
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import scala.concurrent.{ExecutionContext, Future}

/**
 * The callback service a developer's process calls over gRPC, served by the sidecar on loopback
 * only: `ClientLogic` behind the generated service, with its refusals as gRPC statuses.
 *
 * `Invoke` goes straight to the sharding transport with the bytes it was given, so a call from the
 * process is routed exactly as a call from a Scala endpoint would be, wherever the target instance
 * lives. The trace and span ids in `metadata` are the ones the SDK received with the command or
 * request it is handling, which is what makes the nested call a child span.
 */
final class ClientService(
    service: AnkkaService,
    settings: Settings,
    timers: () => Option[TimerScheduler],
    declaredProtocol: Option[String] = None
)(using system: ActorSystem[?])
    extends ClientGrpc.Client:

  private given ExecutionContext = system.executionContext
  private val logic              = ClientLogic(service, settings, timers, declaredProtocol)

  def invoke(request: InvokeRequest): Future[InvokeReply] = logic.invoke(request)

  def invokeStream(request: InvokeRequest, out: StreamObserver[StreamToken]): Unit =
    logic.invokeStream(
      request,
      token =>
        out.onNext(token)
        if token.token.isCompleted || token.token.isFailed || token.token.isApproval then
          out.onCompleted()
    )

  def query(request: QueryRequest): Future[QueryReply] = logic.query(request)

  def schedule(request: ScheduleRequest): Future[pb.Empty] =
    logic.schedule(request).recoverWith(status)

  def cancel(request: CancelRequest): Future[pb.Empty] = logic.cancel(request).recoverWith(status)

  // A refusal travels in the reply, as for the secret store; this never fails the gRPC call.
  def scheduleRecurring(request: ScheduleRecurringRequest): Future[ScheduleRecurringReply] =
    logic.scheduleRecurring(request)

  // Refusals travel in the reply, as for `invoke`; these never fail the gRPC call.
  def getSecret(request: GetSecretRequest): Future[GetSecretReply] = logic.getSecret(request)
  def putSecret(request: PutSecretRequest): Future[PutSecretReply] = logic.putSecret(request)
  def deleteSecret(request: DeleteSecretRequest): Future[DeleteSecretReply] =
    logic.deleteSecret(request)
  def request(request: ServiceRequest): Future[ServiceReply] = logic.request(request)
  def decide(request: DecideRequest): Future[InvokeReply]    = logic.decide(request)

  // STUB(048 T034/T052): replaced by ClientLogic.awaitEnd and awaitEndStream.
  def awaitEnd(request: AwaitEndRequest): Future[InvokeReply] =
    Future.failed(Status.UNIMPLEMENTED.asRuntimeException())
  def awaitEndStream(request: AwaitEndRequest, out: StreamObserver[StreamToken]): Unit =
    out.onError(Status.UNIMPLEMENTED.asRuntimeException())

  private val status: PartialFunction[Throwable, Future[pb.Empty]] = { case e: CommandError =>
    val s = e.code match
      case ErrorCode.Unavailable => Status.UNAVAILABLE
      case ErrorCode.BadRequest  => Status.INVALID_ARGUMENT
      case _                     => Status.INTERNAL
    Future.failed(s.withDescription(e.message).asRuntimeException())
  }

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
