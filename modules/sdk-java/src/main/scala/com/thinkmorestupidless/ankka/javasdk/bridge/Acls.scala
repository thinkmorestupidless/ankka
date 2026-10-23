package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.http
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ServiceClients, ViewClient}
import com.thinkmorestupidless.ankka.javasdk.http.*
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage

import java.util.Optional
import java.util.function.{Function, Predicate}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The Java ACLs, each holding the Scala `Acl` the server applies. */
object Acls:

  private[bridge] final case class Held(scala: http.Acl) extends Acl

  def denyAll(): Acl  = Held(http.Acl.DenyAll)
  def allowAll(): Acl = Held(http.Acl.AllowAll)

  /** A Scala ACL as a Java one: what a conformance target that already built one hands over. */
  private[ankka] def fromScala(acl: http.Acl): Acl = Held(acl)

  def allowIf(test: Predicate[Request]): Acl =
    Held(http.Acl.AllowIf(ctx => test.test(JavaRequest(ctx))))

  def authenticate(decide: Function[Request, AuthDecision]): Acl =
    Held(http.Acl.Authenticate(ctx => toScala(decide.apply(JavaRequest(ctx)))))

  def allowCallers(services: Array[String]): Acl =
    Held(http.Acl.AllowCallers(services.toVector.map(http.CallerMatcher.NamedService(None, _))))

  private[bridge] final case class HeldMatcher(scala: http.CallerMatcher) extends CallerMatcher

  def allowCallers(matchers: Array[CallerMatcher]): Acl =
    Held(http.Acl.AllowCallers(matchers.toVector.map {
      case HeldMatcher(m) => m
      case other => throw IllegalArgumentException(s"$other is not a caller built by Callers")
    }))

  def internet(): CallerMatcher            = HeldMatcher(http.Callers.internet)
  def service(name: String): CallerMatcher = HeldMatcher(http.Callers.service(name))
  def service(project: String, name: String): CallerMatcher =
    HeldMatcher(http.Callers.service(project, name))
  def anyInProject(): CallerMatcher = HeldMatcher(http.Callers.anyInProject)
  def self(): CallerMatcher         = HeldMatcher(http.Callers.self)
  def anyService(): CallerMatcher   = HeldMatcher(http.Callers.anyService)
  def granted(): CallerMatcher      = HeldMatcher(http.Callers.granted)

  def anyOf(acls: Array[Acl]): Acl = Held(http.Acl.AnyOf(acls.toVector.map(scalaOf)))

  def clientKeys(): Acl = Held(com.thinkmorestupidless.ankka.mcp.ClientKeys.authenticate())

  def oidc(): Acl = Held(com.thinkmorestupidless.ankka.auth.oidc.Oidc.authenticate())

  private[bridge] def scalaOf(acl: Acl): http.Acl = acl match
    case Held(scala) => scala
    case null =>
      throw IllegalStateException("acl() returned null; return Acl.denyAll() to admit no one")
    case other => throw IllegalStateException(s"$other is not an Acl built by Acl's factories")

  private def toScala(decision: AuthDecision): http.AuthDecision = decision match
    case allow: AuthDecision.Allow       => http.AuthDecision.Allow(principal(allow.principal))
    case u: AuthDecision.Unauthenticated => http.AuthDecision.Unauthenticated(u.challenge)
    case f: AuthDecision.Forbidden       => http.AuthDecision.Forbidden(f.reason)
    case null => http.AuthDecision.Forbidden("the authenticator decided nothing")

  private def principal(p: Principal): http.Principal =
    http.Principal(
      p.subject,
      p.name.toScala,
      p.email.toScala,
      p.emailVerified,
      p.roles.asScala.toSet,
      p.claims.asScala.toMap,
      p.issuer.toScala
    )

  private[bridge] def javaPrincipal(p: http.Principal): Principal =
    Principal(
      p.subject,
      p.name.toJava,
      p.email.toJava,
      p.emailVerified,
      p.roles.asJava,
      p.claims.asJava,
      p.issuer.toJava
    )

  /** A Java caller as the server names it; a test kit makes a request arrive as one. */
  private[ankka] def scalaCaller(c: Caller): http.Caller = c match
    case _: Caller.Gateway => http.Caller.Gateway
    case s: Caller.Service => http.Caller.Service(s.project, s.name)
    case m: Caller.Machine => http.Caller.Machine(m.organization, m.name)
    case _: Caller.Local   => http.Caller.Local

  private[bridge] def javaCaller(c: http.Caller): Caller = c match
    case http.Caller.Gateway                => Caller.Gateway()
    case http.Caller.Service(project, name) => Caller.Service(project, name)
    case http.Caller.Machine(org, name)     => Caller.Machine(org, name)
    case http.Caller.Local                  => Caller.Local()

/** A Scala request as a Java endpoint reads it. */
private[bridge] final class JavaRequest(ctx: http.RequestContext) extends Request:
  def method(): String                               = ctx.method
  def path(): String                                 = ctx.path
  def query(name: String): Optional[String]          = ctx.query.raw(name).toJava
  def queryAll(name: String): java.util.List[String] = ctx.query.rawAll(name).asJava
  def requiredQuery(name: String): String =
    ctx.query
      .raw(name)
      .getOrElse(throw ProblemException(Problem.badRequest(s"query parameter '$name' is required")))
  def flag(name: String): Boolean            = ctx.query.flag(name)
  def header(name: String): Optional[String] = ctx.header(name).toJava
  def headers(): java.util.List[java.util.Map.Entry[String, String]] =
    ctx.headers.map((n, v) => java.util.Map.entry(n, v)).asJava
  def caller(): Caller                 = Acls.javaCaller(ctx.caller)
  def principal(): Optional[Principal] = ctx.principal.map(Acls.javaPrincipal).toJava

/** What a Java endpoint reads: the request on this thread, and its client. */
final class EndpointCell private[bridge] (
    client: ComponentClient,
    serviceClients: ServiceClients = Services.unavailable,
    views: ViewClient = JavaViews.unavailable,
    secretStore: SecretStore = Stores.noSecrets,
    objectStorage: ObjectStorage = Stores.noStorage,
    keys: com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeys = Mcp.noKeys,
    timerz: () => com.thinkmorestupidless.ankka.javasdk.timer.Timers = () => JavaTimers.unavailable
):
  def timers(): com.thinkmorestupidless.ankka.javasdk.timer.Timers = timerz()

  def clientKeys(): com.thinkmorestupidless.ankka.javasdk.mcp.ClientKeys = keys

  def secrets(): SecretStore = secretStore

  def storage(): ObjectStorage = objectStorage

  def viewClient(): ViewClient = views

  def request(): Request =
    http.RequestScope.currentContext match
      case Some(ctx) => JavaRequest(ctx)
      case None =>
        throw IllegalStateException("request() is only available inside a route, on its own thread")

  def componentClient(): ComponentClient = client

  def services(): ServiceClients = serviceClients

object EndpointCell:
  private val constructing = ThreadLocal[EndpointCell]()

  /** The cell for the endpoint being constructed on this thread, or one with no client. */
  def take(): EndpointCell =
    val cell = constructing.get()
    if cell == null then EndpointCell(Clients.unavailable)
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: EndpointCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()
