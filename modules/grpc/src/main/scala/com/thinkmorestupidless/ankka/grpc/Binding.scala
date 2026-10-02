package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.http.{Acl, RequestContext, RequestScope}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
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

  def definition(
      endpoint: GrpcEndpoint,
      admission: Admission,
      materializer: Option[Materializer] = None
  ): ServerServiceDefinition =
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
        builder.addMethod(method, handlerFor(endpoint, declared, admission, materializer))
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
      admission: Admission,
      materializer: Option[Materializer]
  ): ServerCallHandler[Array[Byte], Any] =
    val acl = declared.acl.getOrElse(endpoint.acl)
    declared.handler match
      case Handler.Unary(run) => unary(endpoint, declared, admission, run)
      case Handler.ServerStream(run) =>
        streaming(declared, acl, admission) { (call, context, out, readiness, listener) =>
          // One request, as for a unary method, then the stream.
          call.request(2)
          listener.onRequest { message =>
            parse(declared, message) match
              case Left(status) => out.close(status, Metadata())
              case Right(request) =>
                answerWith(declared, out, readiness, listener, materializer)(
                  RequestScope.withContext(context)(run(request))
                )
          }
        }
      case Handler.ClientStream(run) =>
        streaming(declared, acl, admission) { (call, context, out, _, listener) =>
          val inbound = listener.inbound(Streams.Inbound(call, out, parse(declared, _)))
          AnkkaExecutors.virtual.execute { () =>
            try
              val reply = RequestScope.withContext(context)(run(inbound))
              out.send(reply)
              out.close(Status.OK, Metadata())
            catch case NonFatal(failure) => endWith(declared, out, failure)
          }
        }
      case Handler.BidiStream(run) =>
        streaming(declared, acl, admission) { (call, context, out, readiness, listener) =>
          val inbound = listener.inbound(Streams.Inbound(call, out, parse(declared, _)))
          AnkkaExecutors.virtual.execute { () =>
            answerWith(declared, out, readiness, listener, materializer)(
              RequestScope.withContext(context)(run(inbound))
            )
          }
        }

  /** What a streaming call's listener does, filled in by the kind of method it serves. */
  private final class StreamListener(readiness: Readiness) extends ServerCall.Listener[Array[Byte]]:
    @volatile var cancelled                             = false
    private var single: Option[Array[Byte] => Unit]     = None
    private var request: Option[Array[Byte]]            = None
    private var requests: Option[Streams.Inbound]       = None
    private var tooMany                                 = false
    @volatile private var onEnd: Option[Status => Unit] = None

    def onRequest(handle: Array[Byte] => Unit): Unit = single = Some(handle)

    def inbound(stream: Streams.Inbound): Streams.Inbound =
      requests = Some(stream)
      stream

    def ending(handle: Status => Unit): Unit = onEnd = Some(handle)

    override def onMessage(message: Array[Byte]): Unit =
      requests match
        case Some(stream) => stream.onMessage(message)
        case None =>
          if request.isDefined then
            tooMany = true
            onEnd.foreach(
              _(Status.INTERNAL.withDescription("more than one request for this method"))
            )
          else request = Some(message)

    override def onHalfClose(): Unit =
      requests match
        case Some(stream) => stream.onHalfClose()
        case None =>
          if !tooMany then
            (single, request) match
              case (Some(handle), Some(message)) =>
                // On a thread of its own, not the call's: a handler that blocks must not hold the
                // thread grpc-java delivers this call's cancellation and readiness on.
                AnkkaExecutors.virtual.execute(() => handle(message))
              case _ =>
                onEnd.foreach(_(Status.INTERNAL.withDescription("no request for this method")))

    override def onCancel(): Unit =
      cancelled = true
      requests.foreach(_.onCancel("the caller cancelled the call or went away"))
      readiness.signal()

    override def onReady(): Unit = readiness.signal()

  /**
   * A streaming call: admitted before anything is read, then handed to `begin` with the pieces it
   * needs, on the call's own thread.
   */
  private def streaming(declared: DeclaredMethod, acl: Acl, admission: Admission)(
      begin: (
          ServerCall[Array[Byte], Any],
          RequestContext,
          Outgoing,
          Readiness,
          StreamListener
      ) => Unit
  ): ServerCallHandler[Array[Byte], Any] =
    new ServerCallHandler[Array[Byte], Any]:
      def startCall(
          call: ServerCall[Array[Byte], Any],
          headers: Metadata
      ): ServerCall.Listener[Array[Byte]] =
        admitted(call, headers, declared.fullName, acl, admission) match
          case Left((status, trailers)) =>
            call.close(status, trailers)
            new ServerCall.Listener[Array[Byte]] {}
          case Right(context) =>
            val out       = Outgoing(call)
            val readiness = Readiness()
            val listener  = StreamListener(readiness)
            listener.ending(status => out.close(status, Metadata()))
            begin(call, context, out, readiness, listener)
            listener

  /** Builds the handler's stream and sends it, ending the call however the stream ends. */
  private def answerWith(
      declared: DeclaredMethod,
      out: Outgoing,
      readiness: Readiness,
      listener: StreamListener,
      materializer: Option[Materializer]
  )(build: => Source[Any, ?]): Unit =
    try
      val source = build
      materializer match
        case Some(m) =>
          Streams.drain(
            source,
            out,
            readiness,
            listener.cancelled,
            m,
            failure => statusOf(declared, failure) -> trailersOf(failure)
          )
        case None =>
          log.error(
            s"${declared.fullName} answers with a stream, and this server has no materializer"
          )
          out.close(GrpcStatus.internal, Metadata())
    catch case NonFatal(failure) => endWith(declared, out, failure)

  /** Ends the call for a handler's failure — or not at all, when the call already ended. */
  private def endWith(declared: DeclaredMethod, out: Outgoing, failure: Throwable): Unit =
    failure match
      case _: CallCancelled =>
        out.close(Status.CANCELLED.withDescription(failure.getMessage), Metadata())
      case other => out.close(statusOf(declared, other), trailersOf(other))

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
