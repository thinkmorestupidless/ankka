package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.http.{Acl, RequestContext, RequestScope}
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.runtime.{
  AnkkaExecutors,
  Observability,
  Span,
  SpanKind,
  SpanOutcome,
  Trace,
  TraceContext,
  Traceparent
}
import io.grpc.*
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

import java.io.{ByteArrayInputStream, InputStream}
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/**
 * What a server lends the calls it serves: a materializer for the streams handlers return, and the
 * recorder their spans go to. A suite serving with neither gets no streams and records nothing.
 */
private[grpc] final case class Hosting(
    materializer: Option[Materializer] = None,
    observability: Option[Observability] = None,
    /** The calls in progress, which a stopping server ends itself once their grace is spent. */
    live: Option[Live] = None
)

/** The calls a server has in progress. */
private[grpc] final class Live:
  private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet[Outgoing]()

  def add(call: Outgoing): Unit    = calls.add(call): Unit
  def remove(call: Outgoing): Unit = calls.remove(call): Unit

  /**
   * Ends every call still in progress as unavailable, so a caller is told the service stopped and
   * may retry elsewhere — rather than the `CANCELLED` grpc-java's forced shutdown would tell it.
   */
  def endAll(): Unit =
    calls.forEach(
      _.close(Status.UNAVAILABLE.withDescription("the service is stopping"), Metadata())
    )

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
 *
 * Every kind of method goes through one path: admitted before a message is read, then handed to a
 * virtual thread of its own — never the call's, whose thread delivers cancellation and readiness —
 * with one guarded outgoing half that every writer shares and that records how the call ended.
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
      hosting: Hosting = Hosting()
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
        builder.addMethod(method, handlerFor(endpoint, declared, admission, hosting))
      }
      .build()

  /**
   * A call to a method nothing declares. When its service definition is one an endpoint serves,
   * that endpoint's ACL judges the call first, so a closed endpoint does not disclose which of its
   * methods exist by answering some unimplemented and the rest refused. A definition nothing serves
   * is grpc-java's to answer, `UNIMPLEMENTED`. Neither is recorded: a caller can invent method
   * names without limit, and only declared names are ever interned.
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
      hosting: Hosting
  ): ServerCallHandler[Array[Byte], Any] =
    val acl   = declared.acl.getOrElse(endpoint.acl)
    val spans = Spans(hosting.observability, declared.fullName)
    declared.handler match
      case Handler.Unary(run) =>
        calls(declared, acl, admission, spans, hosting) { (call, context, out, _, listener, span) =>
          // Two, not one, as grpc-java's own unary handler asks: a second message is how a client
          // that sent too many is noticed.
          call.request(2)
          listener.onRequest { message =>
            span.within {
              parse(declared, message) match
                case Left(status) => out.close(status, Metadata())
                case Right(request) =>
                  try
                    val reply = RequestScope.withContext(context)(run(request))
                    out.send(reply)
                    out.close(Status.OK, Metadata())
                  catch case NonFatal(failure) => endWith(declared, out, failure)
            }
          }
        }
      case Handler.ServerStream(run) =>
        calls(declared, acl, admission, spans, hosting) {
          (call, context, out, readiness, listener, span) =>
            call.request(2)
            listener.onRequest { message =>
              span.within {
                parse(declared, message) match
                  case Left(status) => out.close(status, Metadata())
                  case Right(request) =>
                    answerWith(declared, out, readiness, listener, hosting)(
                      RequestScope.withContext(context)(run(request))
                    )
              }
            }
        }
      case Handler.ClientStream(run) =>
        calls(declared, acl, admission, spans, hosting) { (call, context, out, _, listener, span) =>
          val inbound = listener.inbound(Streams.Inbound(call, out, parse(declared, _)))
          AnkkaExecutors.virtual.execute { () =>
            span.within {
              try
                val reply = RequestScope.withContext(context)(run(inbound))
                out.send(reply)
                out.close(Status.OK, Metadata())
              catch case NonFatal(failure) => endWith(declared, out, failure)
            }
          }
        }
      case Handler.BidiStream(run) =>
        calls(declared, acl, admission, spans, hosting) {
          (call, context, out, readiness, listener, span) =>
            val inbound = listener.inbound(Streams.Inbound(call, out, parse(declared, _)))
            AnkkaExecutors.virtual.execute { () =>
              span.within {
                answerWith(declared, out, readiness, listener, hosting)(
                  RequestScope.withContext(context)(run(inbound))
                )
              }
            }
        }

  /** What a call's listener does, filled in by the kind of method it serves. */
  private final class CallListener(readiness: Readiness, out: Outgoing)
      extends ServerCall.Listener[Array[Byte]]:
    @volatile var cancelled                         = false
    private var single: Option[Array[Byte] => Unit] = None
    private var request: Option[Array[Byte]]        = None
    private var requests: Option[Streams.Inbound]   = None
    private var tooMany                             = false

    def onRequest(handle: Array[Byte] => Unit): Unit = single = Some(handle)

    def inbound(stream: Streams.Inbound): Streams.Inbound =
      requests = Some(stream)
      stream

    override def onMessage(message: Array[Byte]): Unit =
      requests match
        case Some(stream) => stream.onMessage(message)
        case None =>
          if request.isDefined then
            tooMany = true
            out.close(
              Status.INTERNAL.withDescription("more than one request for this method"),
              Metadata()
            )
          else request = Some(message)

    override def onHalfClose(): Unit =
      requests match
        case Some(stream) => stream.onHalfClose()
        case None =>
          if !tooMany then
            (single, request) match
              case (Some(handle), Some(message)) =>
                AnkkaExecutors.virtual.execute(() => handle(message))
              case _ =>
                out.close(Status.INTERNAL.withDescription("no request for this method"), Metadata())

    override def onCancel(): Unit =
      cancelled = true
      requests.foreach(_.onCancel("the caller cancelled the call or went away"))
      out.cancelled()
      readiness.signal()

    override def onReady(): Unit = readiness.signal()

  /**
   * One call of any kind: admitted before anything is read — a refusal recorded and answered there
   * — then handed to `begin` with the pieces it needs, on the call's own thread.
   */
  private def calls(
      declared: DeclaredMethod,
      acl: Acl,
      admission: Admission,
      spans: Spans,
      hosting: Hosting
  )(
      begin: (
          ServerCall[Array[Byte], Any],
          RequestContext,
          Outgoing,
          Readiness,
          CallListener,
          Spans.Call
      ) => Unit
  ): ServerCallHandler[Array[Byte], Any] =
    new ServerCallHandler[Array[Byte], Any]:
      def startCall(
          call: ServerCall[Array[Byte], Any],
          headers: Metadata
      ): ServerCall.Listener[Array[Byte]] =
        // A call that carries a trace context is continued, refused or not; one that carries none,
        // or one that cannot be read, starts a trace of its own.
        val parent = Option(headers.get(Spans.TraceparentKey)).flatMap(Traceparent.parse)
        admitted(call, headers, declared.fullName, acl, admission) match
          case Left((status, trailers)) =>
            spans.refused(parent)
            call.close(status, trailers)
            new ServerCall.Listener[Array[Byte]] {}
          case Right(context) =>
            val span = spans.call(parent)
            lazy val out: Outgoing = Outgoing(
              call,
              status =>
                hosting.live.foreach(_.remove(out))
                span.complete(status)
            )
            hosting.live.foreach(_.add(out))
            val readiness = Readiness()
            val listener  = CallListener(readiness, out)
            begin(call, context, out, readiness, listener, span)
            listener

  /** Builds the handler's stream and sends it, ending the call however the stream ends. */
  private def answerWith(
      declared: DeclaredMethod,
      out: Outgoing,
      readiness: Readiness,
      listener: CallListener,
      hosting: Hosting
  )(build: => Source[Any, ?]): Unit =
    try
      val source = build
      hosting.materializer match
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

/**
 * The spans of one declared method: each call the root of its own trace, named `grpc` /
 * `<service definition>/<method>`, both interned once when the endpoint is bound.
 *
 * Opened on the handler's own thread and around everything it does, so the component calls it makes
 * record themselves beneath it; a trace set on another thread would leave them roots of traces of
 * their own. Completed when the call ends, with how it ended: a refusal — an ACL's, or a
 * component's, or any status of the refusal table — is `Refused`, never `Failed`.
 */
private[grpc] final class Spans(observability: Option[Observability], fullName: String):

  private val refs = observability.map(o => (o.names.intern("grpc"), o.names.intern(fullName)))

  /** A call its ACL refused: recorded and ended at once, since no handler will run. */
  def refused(parent: Option[TraceContext]): Unit =
    for o <- observability; (component, handler) <- refs do
      o.recorder.complete(Spans.begin(o, component, handler, parent), SpanOutcome.Refused)

  def call(parent: Option[TraceContext]): Spans.Call = Spans.Call(observability, refs, parent)

private[grpc] object Spans:

  /** The metadata key a caller's trace context arrives under. */
  val TraceparentKey: Metadata.Key[String] =
    Metadata.Key.of(Traceparent.Name, Metadata.ASCII_STRING_MARSHALLER)

  /** A call's span: under the caller's span when the call carried one, else a new trace's root. */
  def begin(o: Observability, component: Int, handler: Int, parent: Option[TraceContext]): Span =
    parent match
      case Some(p) =>
        o.recorder.begin(p.traceIdHigh, p.traceId, p.spanId, component, handler, SpanKind.Server)
      case None => o.recorder.beginRoot(component, handler, SpanKind.Server)

  final class Call(
      observability: Option[Observability],
      refs: Option[(Int, Int)],
      parent: Option[TraceContext]
  ):
    @volatile private var span: Option[Span] = None
    private val completed                    = AtomicBoolean(false)

    /** Runs `body` as this call's span, opened here, on the thread that runs it. */
    def within[A](body: => A): A =
      (observability, refs) match
        case (Some(o), Some((component, handler))) =>
          val opened = Spans.begin(o, component, handler, parent)
          span = Some(opened)
          Trace.within(opened)(body)
        case _ => body

    /** Ends the span with how the call ended; once, whichever writer ended it. */
    def complete(status: Status): Unit =
      if completed.compareAndSet(false, true) then
        for o <- observability; s <- span do o.recorder.complete(s, outcomeOf(status))

  /**
   * A refusal is the service working: every status of the refusal table but `INTERNAL`, and the
   * ACL's own answers. A caller that stopped waiting is `TimedOut`. Anything else is a fault.
   */
  def outcomeOf(status: Status): SpanOutcome =
    if status.isOk then SpanOutcome.Ok
    else if status.getCode == Status.Code.CANCELLED then SpanOutcome.TimedOut
    else if GrpcStatus.fromStatus(status).exists(_.code != ErrorCode.Internal) then
      SpanOutcome.Refused
    else SpanOutcome.Failed
