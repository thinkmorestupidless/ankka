package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.http.{
  Acl,
  AuthDecision,
  Caller,
  LocalCallers,
  QueryParams,
  RequestContext,
  SimpleRequestContext
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import io.grpc.{Grpc, Metadata, ServerCall, Status}

import java.security.cert.X509Certificate
import scala.util.Try

/**
 * Who a call came from, and whether the ACL that governs it lets it through.
 *
 * The same decision an HTTP endpoint makes, over the same types: the caller is read from the client
 * certificate under TLS and from nothing the call says about itself, and the ACL is handed a
 * `RequestContext` whose path is `/<service definition>/<method>` and whose headers are the call's
 * text metadata — so an authenticator written for an HTTP endpoint, reading `Authorization`, admits
 * a gRPC call carrying `authorization` metadata unchanged.
 *
 * Outside TLS every caller is `Caller.Local`, or the caller a test names with the process's local
 * token, exactly as on the HTTP server.
 */
private[grpc] final class Admission(val tls: Boolean, val self: RotatingTls.Identity):

  /** The context a handler and an ACL both see, or the refusal of a certificate naming nobody. */
  def contextFor(
      call: ServerCall[?, ?],
      headers: Metadata,
      fullName: String
  ): Either[Status, SimpleRequestContext] =
    callerOf(call, headers).map { caller =>
      SimpleRequestContext(
        method = "POST",
        path = s"/$fullName",
        query = QueryParams.empty,
        headers = CallMetadata.of(headers).toSeq,
        remoteAddress = None,
        caller = caller
      )
    }

  private def callerOf(call: ServerCall[?, ?], headers: Metadata): Either[Status, Caller] =
    if tls then
      Option(call.getAttributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
        .flatMap(session => Try(session.getPeerCertificates.toVector).toOption)
        .flatMap(_.headOption)
        .collect { case certificate: X509Certificate => certificate } match
        case Some(certificate) =>
          Caller.fromCertificate(certificate).left.map(Status.PERMISSION_DENIED.withDescription)
        case None => Left(Status.PERMISSION_DENIED.withDescription("no client certificate"))
    else
      Right(CallMetadata.localCaller(headers).map(LocalCallers.callerFrom).getOrElse(Caller.Local))

  /**
   * The context to dispatch with — carrying the principal, when the ACL established one — or the
   * status and trailers that refuse the call.
   */
  def decide(acl: Acl, context: SimpleRequestContext): Either[(Status, Metadata), RequestContext] =
    def refused(reason: String) = Left(
      Status.PERMISSION_DENIED.withDescription(reason) -> Metadata()
    )
    acl match
      case Acl.DenyAll  => refused(Admission.NotPermitted)
      case Acl.AllowAll => Right(context)
      case Acl.AllowIf(predicate) =>
        if predicate(context) then Right(context) else refused(Admission.NotPermitted)
      case Acl.AllowCallers(matchers) =>
        // The same text as every other refusal: naming who would have been admitted tells an
        // unauthorised caller whose certificate to go looking for.
        if matchers.exists(_.admits(context.caller, self)) then Right(context)
        else refused(Admission.NotPermitted)
      case Acl.Authenticate(decide) =>
        decide(context) match
          case AuthDecision.Allow(principal) => Right(context.copy(principal = Some(principal)))
          case AuthDecision.Unauthenticated(challenge) =>
            val trailers = Metadata()
            trailers.put(Admission.Challenge, s"Bearer $challenge")
            Left(Status.UNAUTHENTICATED.withDescription("authentication required") -> trailers)
          case AuthDecision.Forbidden(reason) => refused(reason)
          case AuthDecision.Unavailable(reason) =>
            Left(Status.UNAVAILABLE.withDescription(reason) -> Metadata())

  /** Whether any ACL here names callers, which outside a cluster cannot be enforced. */
  def namesCallers(endpoints: Vector[GrpcEndpoint]): Boolean =
    endpoints.exists(e =>
      (e.acl +: e.methods.flatMap(_.acl)).exists {
        case Acl.AllowCallers(_) => true
        case _                   => false
      }
    )

private[grpc] object Admission:

  val NotPermitted: String = "not permitted by this endpoint's acl"

  /** Where an authenticator's challenge goes, as HTTP's `WWW-Authenticate` header carries it. */
  val Challenge: Metadata.Key[String] =
    Metadata.Key.of("www-authenticate", Metadata.ASCII_STRING_MARSHALLER)

  /**
   * Outside a cluster this service has no certificate and so no identity of its own; `local/local`
   * is what `Callers.self` and `Callers.anyInProject` compare against, as on the HTTP server.
   */
  val LocalIdentity: RotatingTls.Identity = RotatingTls.Identity("local", "local")

  val local: Admission = Admission(tls = false, LocalIdentity)

  def under(tls: RotatingTls): Admission =
    Admission(
      tls = true,
      tls.identity.getOrElse(
        throw IllegalStateException(
          s"the service certificate in ${tls.directory} names no ankka:// identity"
        )
      )
    )
