package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.http.{Acl, Caller, Principal, RequestContext, RequestScope}
import io.grpc.{MethodDescriptor, ServiceDescriptor}

import scala.collection.mutable

/**
 * A gRPC endpoint: the outermost layer of a service, turning calls to the methods of one service
 * definition into calls to the service's components, as an `HttpEndpoint` turns requests to its
 * routes.
 *
 * The service definition is written first, in a `.proto` file, and the endpoint is declared against
 * the descriptors ScalaPB generates from it — `CartServiceGrpc.SERVICE`,
 * `CartServiceGrpc.METHOD_GET_CART`. A method's wire name is the `.proto`'s, so renaming a Scala
 * method changes nothing a caller sees. Methods are declared in the constructor body and collected
 * as they are declared, so the endpoint's shape is a value the server checks at startup: a method
 * of the definition with no handler is a service that does not start, not a call that fails later.
 *
 * Handlers block. Each runs on a virtual thread, so a blocking `ComponentClient` call inside one is
 * free, exactly as in an HTTP handler.
 */
abstract class GrpcEndpoint(val service: ServiceDescriptor):

  /**
   * Who may call this endpoint. Abstract on purpose, as on an HTTP endpoint: nobody ships one
   * without having decided.
   */
  def acl: Acl

  private val collected = mutable.ListBuffer.empty[DeclaredMethod]

  // Declarations run in the constructor body, which is single-threaded.
  private var scopedAcl: Option[Acl] = None

  private[ankka] def methods: Vector[DeclaredMethod] = collected.toVector

  /**
   * Declares methods that answer to `acl` rather than to the endpoint's. A method's ACL replaces
   * the endpoint's for that method alone; scopes nest, and the innermost wins — exactly as
   * `HttpEndpoint.withAcl` scopes routes.
   */
  protected def withAcl(acl: Acl)(declare: => Unit): Unit =
    val enclosing = scopedAcl
    scopedAcl = Some(acl)
    try declare
    finally scopedAcl = enclosing

  /**
   * The call being handled, as an ACL saw it: method `POST`, path `/<service definition>/<method>`,
   * the call's text metadata as headers.
   *
   * Available only on the handler's own thread, as `HttpEndpoint.request` is. Read what you need
   * before handing work to another thread.
   */
  protected def call: RequestContext =
    RequestScope.currentContext.getOrElse(
      throw IllegalStateException(
        "call is only available inside a method handler, on the handler's own thread"
      )
    )

  /** Which workload sent this call; see `Caller`. Always present. */
  protected def caller: Caller = call.caller

  /** The names and values sent with the call beside its request. */
  protected def metadata: CallMetadata = CallMetadata(call.headers)

  /**
   * Who is calling, as the `Acl.Authenticate` that admitted the call established it.
   *
   * Throws when there is none: a method whose ACL does not authenticate has no business asking, and
   * the mistake should fail on the first call in a test rather than hand `None` into a permission
   * check.
   */
  protected def principal: Principal =
    val current = call
    current.principal.getOrElse(
      throw IllegalStateException(
        s"'${current.path.drop(1)}' asked for a principal, but the acl that admitted it does not " +
          "authenticate callers"
      )
    )

  /** Answers each call to `method` with one reply. */
  protected def unary[Req, Res](method: MethodDescriptor[Req, Res])(handler: Req => Res): Unit =
    collected += DeclaredMethod(
      method.asInstanceOf[MethodDescriptor[Any, Any]],
      MethodKind.Unary,
      scopedAcl,
      Handler.Unary(request => handler(request.asInstanceOf[Req]))
    )

/** The four shapes a method can have, as its descriptor states them. */
private[ankka] enum MethodKind:
  case Unary, ServerStream, ClientStream, BidiStream

  /** What the method is declared with, for a message that names it. */
  def declaration: String = this match
    case Unary        => "unary"
    case ServerStream => "serverStream"
    case ClientStream => "clientStream"
    case BidiStream   => "bidiStream"

private[ankka] object MethodKind:
  def of(method: MethodDescriptor[?, ?]): MethodKind = method.getType match
    case MethodDescriptor.MethodType.UNARY            => Unary
    case MethodDescriptor.MethodType.SERVER_STREAMING => ServerStream
    case MethodDescriptor.MethodType.CLIENT_STREAMING => ClientStream
    case _                                            => BidiStream

/**
 * A handler with its types erased: the binding parses and encodes with the method's own
 * marshallers.
 */
private[ankka] enum Handler:
  case Unary(run: Any => Any)

/** One method an endpoint declared, as the server sees it. */
private[ankka] final case class DeclaredMethod(
    descriptor: MethodDescriptor[Any, Any],
    kind: MethodKind,
    /** Declared by `withAcl`; `None` means the endpoint's. */
    acl: Option[Acl],
    handler: Handler
):
  /** `<service definition>/<method>`, the name a call names and a span is recorded under. */
  def fullName: String = descriptor.getFullMethodName
