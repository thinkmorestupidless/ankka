package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls.Identity
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * Caller-naming ACLs: the matcher rules, and the router applying them — driven through the local
 * caller header, which is how a test names a caller where there is no certificate to read. The TLS
 * path that supplies the caller in a cluster is `TlsServerSuite`'s.
 */
class CallerAclSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "caller-acl-suite")
  private given ExecutionContext             = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private val self = Identity("checkout", "carts")

  // ── the matcher rules ────────────────────────────────────────────────────────────────────

  test("every matcher admits Local, and each admits exactly the callers it names") {
    val gateway = Caller.Gateway
    val orders  = Caller.Service("checkout", "orders")
    val foreign = Caller.Service("billing", "orders")
    val itself  = Caller.Service("checkout", "carts")
    val matchers = Vector(
      Callers.internet,
      Callers.service("orders"),
      Callers.service("billing", "orders"),
      Callers.anyInProject,
      Callers.self,
      Callers.anyService
    )
    matchers.foreach(m => assert(m.admits(Caller.Local, self), s"$m must admit Local"))

    def admitted(m: CallerMatcher) =
      Vector(gateway, orders, foreign, itself).filter(m.admits(_, self))
    assertEquals(admitted(Callers.internet), Vector(gateway))
    assertEquals(admitted(Callers.service("orders")), Vector(orders))
    assertEquals(admitted(Callers.service("billing", "orders")), Vector(foreign))
    assertEquals(admitted(Callers.anyInProject), Vector(orders, itself))
    assertEquals(admitted(Callers.self), Vector(itself))
    assertEquals(admitted(Callers.anyService), Vector(orders, foreign, itself))
  }

  test("a caller encodes and decodes for the local header, and garbage decodes to nothing") {
    for c <- Vector(Caller.Gateway, Caller.Local, Caller.Service("p", "s")) do
      assertEquals(Caller.decode(Caller.encode(c)), Some(c))
    assertEquals(Caller.decode("service:p"), None)
    assertEquals(Caller.decode("admin"), None)
  }

  // ── the router ───────────────────────────────────────────────────────────────────────────

  private final class Carts extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.allowCallers(Callers.internet, Callers.service("orders"))

    get("/whoami")(() => Caller.encode(caller))
    get("/headers")(() => request.headers.map(_._1.toLowerCase).sorted.mkString(","))
    sse("/events")(() => Source.single("tick"))

    withAcl(Acl.allowCallers(Callers.self)) {
      get("/self")(() => "self")
    }

    withAcl(Acl.AllowIf(_.caller == Caller.Gateway)) {
      get("/predicate")(() => "predicate")
    }

    withAcl(
      Acl.Authenticate(ctx =>
        ctx
          .header("x-who")
          .fold(AuthDecision.Unauthenticated("t"))(w => AuthDecision.Allow(Principal(w)))
      )
    ) {
      get("/both")(() => s"${Caller.encode(caller)} for ${principal.subject}")
    }

  private val router = Router(Vector(new Carts), 5.seconds, new CallerSource(self, tls = false))

  private def as(caller: Option[Caller], path: String, extra: (String, String)*): (Int, String) =
    val headers = (caller.map(LocalCallers.header).toList ++ extra).map((k, v) => RawHeader(k, v))
    val response =
      Await.result(router.handle(HttpRequest(uri = path).withHeaders(headers)), 10.seconds)
    val body = Await.result(response.entity.toStrict(5.seconds), 5.seconds).data.utf8String
    (response.status.intValue, body)

  test("with no caller header every request is Local, and a caller-naming ACL admits it") {
    assertEquals(as(None, "/carts/whoami"), (200, "local"))
    assertEquals(as(None, "/carts/self"), (200, "self"))
  }

  test(
    "a named caller is admitted where listed and refused where not, without saying who would be"
  ) {
    assertEquals(as(Some(Caller.Gateway), "/carts/whoami"), (200, "gateway"))
    assertEquals(
      as(Some(Caller.Service("checkout", "orders")), "/carts/whoami"),
      (200, "service:checkout/orders")
    )
    val (status, body) = as(Some(Caller.Service("checkout", "payments")), "/carts/whoami")
    assertEquals(status, 403)
    assert(!body.contains("orders") && !body.contains("internet"), body)
    // Named service means this project's unless a project is named.
    assertEquals(as(Some(Caller.Service("billing", "orders")), "/carts/whoami")._1, 403)
  }

  test("a route's own caller ACL replaces the endpoint's") {
    assertEquals(as(Some(Caller.Gateway), "/carts/self")._1, 403)
    assertEquals(as(Some(Caller.Service("checkout", "carts")), "/carts/self"), (200, "self"))
  }

  test("an AllowIf predicate can read the caller") {
    assertEquals(as(Some(Caller.Gateway), "/carts/predicate"), (200, "predicate"))
    assertEquals(as(Some(Caller.Service("checkout", "orders")), "/carts/predicate")._1, 403)
  }

  test("a caller and a principal reach the same handler") {
    assertEquals(
      as(Some(Caller.Service("checkout", "orders")), "/carts/both", "x-who" -> "dana"),
      (200, "service:checkout/orders for dana")
    )
  }

  test("a streaming route under a caller ACL is refused before the stream opens") {
    assertEquals(as(Some(Caller.Service("checkout", "payments")), "/carts/events")._1, 403)
    assertEquals(as(Some(Caller.Gateway), "/carts/events")._1, 200)
  }

  test("a header with the wrong token is Local, not the caller it names") {
    val forged = LocalCallers.Header -> s"not-the-token ${Caller.encode(Caller.Gateway)}"
    assertEquals(as(None, "/carts/whoami", forged), (200, "local"))
  }

  test("the local caller header never reaches a handler") {
    val (_, headers) = as(Some(Caller.Gateway), "/carts/headers", "x-other" -> "1")
    assert(!headers.contains(LocalCallers.Header.toLowerCase), headers)
    assert(headers.contains("x-other"), headers)
  }
