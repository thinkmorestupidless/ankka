package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.http.{Acl, RequestContext, RequestScope}
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

  def definition(endpoint: GrpcEndpoint, admission: Admission): ServerServiceDefinition =
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
        builder.addMethod(method, handlerFor(endpoint, declared, admission))
      }
      .build()

  /**
   * A call to a method nothing declares. When its service definition is one an endpoint serves,
   * that endpoint's ACL judges the call first, so a closed endpoint does not disclose which of its
   * methods exist by answering some unimplemented and the rest refused. A definition nothing serves
   * is grpc-java's to answer, `UNIMPLEMENTED`.
   */
  def fallback(endpoints: Vector[GrpcEndpoint], admission: Admission): HandlerRegistry =
    val byDefinition = endpoints.map(e => e.service.getName -> e).toMap
    new HandlerRegistry:
      override def lookupMethod(fullName: String, authority: String): ServerMethodDefinition[?, ?] =
        byDefinition
          .get(MethodDescriptor.extractFullServiceName(fullName))
          .map { endpoint =>
            val method = MethodDescriptor
              .newBuilder(bytes, bytes)
              .setType(MethodDescriptor.MethodType.UNKNOWN)
              .setFullMethodName(fullName)
              .build()
            ServerMethodDefinition.create(method, unimplemented(endpoint, fullName, admission))
          }
          .orNull

  private def unimplemented(
      endpoint: GrpcEndpoint,
      fullName: String,
      admission: Admission
  ): ServerCallHandler[Array[Byte], Array[Byte]] =
    new ServerCallHandler[Array[Byte], Array[Byte]]:
      def startCall(
          call: ServerCall[Array[Byte], Array[Byte]],
          headers: Metadata
      ): ServerCall.Listener[Array[Byte]] =
        admitted(call, headers, fullName, endpoint.acl, admission) match
          case Left((status, trailers)) => call.close(status, trailers)
          case Right(_) =>
            call.close(
              Status.UNIMPLEMENTED.withDescription(s"Method not found: $fullName"),
              Metadata()
            )
        new ServerCall.Listener[Array[Byte]] {}

  /**
   * The context a call is handled in, or the refusal that ends it — decided before the call reads a
   * single message, so a refused call runs no handler and parses no request.
   */
  private def admitted(
      call: ServerCall[?, ?],
      headers: Metadata,
      fullName: String,
      acl: Acl,
      admission: Admission
  ): Either[(Status, Metadata), RequestContext] =
    admission
      .contextFor(call, headers, fullName)
      .left
      .map(_ -> Metadata())
      .flatMap(context => admission.decide(acl, context))

  private def handlerFor(
      endpoint: GrpcEndpoint,
      declared: DeclaredMethod,
      admission: Admission
  ): ServerCallHandler[Array[Byte], Any] = declared.handler match
    case Handler.Unary(run) => unary(endpoint, declared, admission, run)

  private def unary(
      endpoint: GrpcEndpoint,
      declared: DeclaredMethod,
      admission: Admission,
      run: Any => Any
  ): ServerCallHandler[Array[Byte], Any] =
    new ServerCallHandler[Array[Byte], Any]:
      def startCall(
          call: ServerCall[Array[Byte], Any],
          headers: Metadata
      ): ServerCall.Listener[Array[Byte]] =
        admitted(
          call,
          headers,
          declared.fullName,
          declared.acl.getOrElse(endpoint.acl),
          admission
        ) match
          case Left((status, trailers)) =>
            call.close(status, trailers)
            new ServerCall.Listener[Array[Byte]] {}
          case Right(context) =>
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
                      // On a thread of its own, not the call's: a handler that blocks must not hold
                      // the thread grpc-java delivers this call's cancellation on.
                      AnkkaExecutors.virtual.execute(() =>
                        answer(call, declared, context, message, run)
                      )

  private def answer(
      call: ServerCall[Array[Byte], Any],
      declared: DeclaredMethod,
      context: RequestContext,
      message: Array[Byte],
      run: Any => Any
  ): Unit =
    parse(declared, message) match
      case Left(status) => call.close(status, Metadata())
      case Right(request) =>
        try
          val reply = RequestScope.withContext(context)(run(request))
          call.sendHeaders(Metadata())
          call.sendMessage(reply)
          call.close(Status.OK, Metadata())
        catch case NonFatal(failure) => call.close(statusOf(declared, failure), trailersOf(failure))

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
