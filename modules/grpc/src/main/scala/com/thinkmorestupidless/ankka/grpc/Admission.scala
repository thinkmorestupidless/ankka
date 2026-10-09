package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.http.{
  Acl,
  AuthDecision,
  Caller,
  GrantTarget,
  Grants,
  LocalCallers,
  MachineTokens,
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
private[grpc] final class Admission(
    val tls: Boolean,
    val self: RotatingTls.Identity,
    val grants: Grants = Grants.none,
    val machines: Option[MachineTokens] = None
):

  /** The same admission, reading `grants` for `Callers.granted` (feature 040). */
  def withGrants(grants: Grants): Admission = Admission(tls, self, grants, machines)

  /** The same admission, reading a machine's token on a call through the gateway (feature 040). */
  def withMachines(machines: Option[MachineTokens]): Admission =
    Admission(tls, self, grants, machines)

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
          // Read with this service's own identity, as the HTTP server reads it, so a certificate
          // means one caller whichever port it is presented on.
          Caller
            .fromCertificate(certificate, Some(self))
            .map {
              // A call from outside may say which machine it is; between services the
              // certificate is the caller (feature 040).
              case Caller.Gateway => machineOf(headers).getOrElse(Caller.Gateway)
              case other          => other
            }
            .left
            .map(Status.PERMISSION_DENIED.withDescription)
        case None => Left(Status.PERMISSION_DENIED.withDescription("no client certificate"))
    else
      Right(CallMetadata.localCaller(headers).map(LocalCallers.callerFrom).getOrElse(Caller.Local))

  /** The machine a bearer token in the call's `authorization` metadata proves, if any. */
  private def machineOf(headers: Metadata): Option[Caller] =
    val authorization =
      Option(headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))
    for
      tokens <- machines
      bearer <- authorization
        .map(_.trim)
        .filter(_.regionMatches(true, 0, "Bearer ", 0, 7))
        .map(_.drop(7).trim)
        .filter(_.nonEmpty)
      if tokens.issuerOf(bearer).contains(tokens.issuer)
      machine <- tokens.verify(bearer).toOption
    yield machine

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
        // A grant opens one method, by the full name the call's path carries (feature 040).
        val target = Some(GrantTarget.Method(context.path.stripPrefix("/")))
        if matchers.exists(_.admits(context.caller, self, target, grants)) then Right(context)
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
