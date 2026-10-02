package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.http.{
  Acl,
  Caller,
  QueryParams,
  RequestScope,
  SimpleRequestContext
}
import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors
import io.grpc.*
import org.slf4j.LoggerFactory

import java.io.{ByteArrayInputStream, InputStream}
import scala.util.control.NonFatal

/**
 * An endpoint as grpc-java serves it.
 *
 * Every method is re-bound with a request marshaller that passes the bytes through untouched, and
 * ankka parses them itself with the method's own marshaller. grpc-java answers a marshaller that
 * throws with `UNKNOWN: Application error processing RPC`, which tells a caller nothing; parsed
 * here, a request that is not one is `INVALID_ARGUMENT` and no handler runs. The descriptor is
 * rebuilt rather than patched, keeping the original's name and schema descriptor (which reflection
 * reads), because a `ServerServiceDefinition` insists its methods are the descriptor's own
 * instances. This is what grpc-java's own `ServerInterceptors.useMarshalledMessages` does.
 */
private[grpc] object Binding:

  private val log = LoggerFactory.getLogger("com.thinkmorestupidless.ankka.grpc.GrpcServer")

  /** The bytes as they arrived; parsing is the handler's first step. */
  val bytes: MethodDescriptor.Marshaller[Array[Byte]] =
    new MethodDescriptor.Marshaller[Array[Byte]]:
      def stream(value: Array[Byte]): InputStream = ByteArrayInputStream(value)
      def parse(stream: InputStream): Array[Byte] = stream.readAllBytes()

  def definition(endpoint: GrpcEndpoint): ServerServiceDefinition =
    val rebound = endpoint.methods.map { declared =>
      declared -> declared.descriptor
        .toBuilder(bytes, declared.descriptor.getResponseMarshaller)
        .build()
    }
    val service = rebound
      .foldLeft(
        ServiceDescriptor
          .newBuilder(endpoint.service.getName)
          .setSchemaDescriptor(endpoint.service.getSchemaDescriptor)
      )((builder, pair) => builder.addMethod(pair._2))
      .build()
    rebound
      .foldLeft(ServerServiceDefinition.builder(service)) { case (builder, (declared, method)) =>
        builder.addMethod(method, handlerFor(endpoint, declared))
      }
      .build()

  private def handlerFor(
      endpoint: GrpcEndpoint,
      declared: DeclaredMethod
  ): ServerCallHandler[Array[Byte], Any] = declared.handler match
    case Handler.Unary(run) => unary(endpoint, declared, run)

  private def unary(
      endpoint: GrpcEndpoint,
      declared: DeclaredMethod,
      run: Any => Any
  ): ServerCallHandler[Array[Byte], Any] =
    new ServerCallHandler[Array[Byte], Any]:
      def startCall(
          call: ServerCall[Array[Byte], Any],
          headers: Metadata
      ): ServerCall.Listener[Array[Byte]] =
        // Two, not one, as grpc-java's own unary handler asks: a second message is how a client
        // that sent too many is noticed.
        call.request(2)
        new ServerCall.Listener[Array[Byte]]:
          private var request: Option[Array[Byte]] = None
          private var refused                      = false

          override def onMessage(message: Array[Byte]): Unit =
            if request.isDefined then
              refused = true
              call.close(
                Status.INTERNAL.withDescription("more than one request for a unary method"),
                Metadata()
              )
            else request = Some(message)

          override def onHalfClose(): Unit =
            if !refused then
              request match
                case None =>
                  call.close(
                    Status.INTERNAL.withDescription("no request for a unary method"),
                    Metadata()
                  )
                case Some(message) =>
                  // On a thread of its own, not the call's: a handler that blocks must not hold the
                  // thread grpc-java delivers this call's cancellation on.
                  AnkkaExecutors.virtual.execute(() =>
                    answer(call, endpoint, declared, message, run)
                  )

  private def answer(
      call: ServerCall[Array[Byte], Any],
      endpoint: GrpcEndpoint,
      declared: DeclaredMethod,
      message: Array[Byte],
      run: Any => Any
  ): Unit =
    admit(endpoint, declared) match
      case Some(refusal) => call.close(refusal, Metadata())
      case None =>
        parse(declared, message) match
          case Left(status) => call.close(status, Metadata())
          case Right(request) =>
            try
              val reply = RequestScope.withContext(contextFor(declared))(run(request))
              call.sendHeaders(Metadata())
              call.sendMessage(reply)
              call.close(Status.OK, Metadata())
            catch
              case NonFatal(failure) => call.close(statusOf(declared, failure), trailersOf(failure))

  /**
   * Who may call, until the ACLs are wired: a method open to all is served and every other is
   * refused. Closed by default, never open.
   */
  private def admit(endpoint: GrpcEndpoint, declared: DeclaredMethod): Option[Status] =
    declared.acl.getOrElse(endpoint.acl) match
      case Acl.AllowAll => None
      case _ =>
        Some(Status.PERMISSION_DENIED.withDescription("not permitted by this endpoint's acl"))

  private def contextFor(declared: DeclaredMethod): SimpleRequestContext =
    SimpleRequestContext(
      method = "POST",
      path = s"/${declared.fullName}",
      query = QueryParams.empty,
      headers = Vector.empty,
      remoteAddress = None,
      caller = Caller.Local
    )

  private[grpc] def parse(declared: DeclaredMethod, message: Array[Byte]): Either[Status, Any] =
    try Right(declared.descriptor.getRequestMarshaller.parse(ByteArrayInputStream(message)))
    catch
      case NonFatal(failure) =>
        Left(
          Status.INVALID_ARGUMENT.withDescription(
            s"the request is not a ${declared.descriptor.getFullMethodName} request: ${failure.getMessage}"
          )
        )

  /**
   * How a handler's failure reaches the caller: a refusal as its status, a status the handler chose
   * as itself, and anything else as a fault the caller is told nothing about.
   */
  private[grpc] def statusOf(declared: DeclaredMethod, failure: Throwable): Status =
    CommandError.from(failure) match
      case Some(error) => GrpcStatus.toStatus(error)
      case None =>
        failure match
          case e: StatusRuntimeException => e.getStatus
          case e: StatusException        => e.getStatus
          case other =>
            log.error(s"unhandled failure in ${declared.fullName}", other)
            GrpcStatus.internal

  private[grpc] def trailersOf(failure: Throwable): Metadata = failure match
    case e: StatusRuntimeException if CommandError.from(e).isEmpty =>
      Option(e.getTrailers).getOrElse(Metadata())
    case e: StatusException if CommandError.from(e).isEmpty =>
      Option(e.getTrailers).getOrElse(Metadata())
    case _ => Metadata()
