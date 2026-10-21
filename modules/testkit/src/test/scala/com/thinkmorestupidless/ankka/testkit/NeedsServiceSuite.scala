package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.http.{
  Acl,
  AuthDecision,
  HttpEndpoint,
  HttpServer,
  NeedsService,
  RequestContext
}
import com.thinkmorestupidless.ankka.runtime.AnkkaService

import java.util.concurrent.atomic.AtomicReference

/**
 * An authenticator that needs the running service (feature 054): the server binds every one it
 * finds in an ACL tree before it binds its port, and a refusal of the bind fails the start with its
 * words.
 */
class NeedsServiceSuite extends munit.FunSuite with LogCapturing:

  private final class Bound(answer: Either[String, Unit])
      extends (RequestContext => AuthDecision)
      with NeedsService:
    val service = AtomicReference[AnkkaService]()
    def bind(service: AnkkaService): Either[String, Unit] =
      this.service.set(service)
      answer
    def apply(context: RequestContext): AuthDecision =
      if service.get() == null then AuthDecision.Unavailable("not bound")
      else AuthDecision.Unauthenticated("""realm="bound"""")

  private final class Keyed(authenticator: Bound) extends HttpEndpoint("/keyed"):
    // Inside a tree, and on a route: the server must look everywhere an ACL can be.
    val acl: Acl = Acl.anyOf(Acl.DenyAll, Acl.Authenticate(authenticator))
    get("/x")(() => "x")

  test("an authenticator that needs the service is bound before the server binds") {
    val authenticator = Bound(Right(()))
    val server        = HttpServer.at("127.0.0.1", 0)(_ => Keyed(authenticator))
    val kit           = AnkkaTestKit.start(Nil, Seq(server))
    try
      assert(authenticator.service.get() != null, "the authenticator was never bound")
      assert(server.boundPort.isDefined)
    finally kit.stop()
  }

  test("a bind that answers Left fails the start with its text") {
    val authenticator = Bound(Left("client keys need McpRuntime(); add it to the service"))
    val server        = HttpServer.at("127.0.0.1", 0)(_ => Keyed(authenticator))
    val failure       = intercept[Throwable](AnkkaTestKit.start(Nil, Seq(server)).stop())
    val text = Iterator
      .iterate[Throwable](failure)(_.getCause)
      .takeWhile(_ != null)
      .map(t => String.valueOf(t.getMessage))
      .mkString("\n")
    assert(text.contains("invalid ankka http configuration"), text)
    assert(text.contains("client keys need McpRuntime(); add it to the service"), text)
  }
