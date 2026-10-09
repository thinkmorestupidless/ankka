package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.{Observability, SpanOutcome}
import com.thinkmorestupidless.ankka.runtime.RotatingTls.Identity
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * `Callers.granted` (feature 040): the matcher's rule over a set of grants, and the router applying
 * it to the route a request selected — the route's method and whole path as a template, and nothing
 * a grant on another route could open. Refusals are recorded as refusals.
 */
class GrantedCallerSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "granted-caller-suite")
  private given ExecutionContext             = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private val self       = Identity("spinvibe", "wallet")
  private val merchant   = Caller.Service("payments", "merchant")
  private val gateway    = Caller.Service("payments", "psp-gateway")
  private val network    = Caller.Machine("eitheror", "affiliate-network")
  private val deposits   = GrantTarget.Route("POST", "/v1/wallets/{player}/{currency}/deposits")
  private val balance    = GrantTarget.Route("GET", "/v1/wallets/{player}/{currency}")
  private val deposit    = GrantTarget.Method("ankka.wallet.WalletService/Deposit")
  private val getBalance = GrantTarget.Method("ankka.wallet.WalletService/GetBalance")

  private val grants = Grants.of(
    GrantEntry(merchant, deposits),
    GrantEntry(merchant, GrantTarget.Method("WalletService/Deposit")),
    GrantEntry(network, GrantTarget.Route("GET", "/v1/wallets/{id}/{currency}"))
  )

  // ── the matcher rule ─────────────────────────────────────────────────────────────────────

  test("a granted caller is admitted on the route its grant names and on no other") {
    val m = Callers.granted
    assert(m.admits(merchant, self, Some(deposits), grants))
    assert(!m.admits(merchant, self, Some(balance), grants), "a grant opens one route")
    assert(!m.admits(gateway, self, Some(deposits), grants), "a grant names one service")
    assert(!m.admits(Caller.Gateway, self, Some(deposits), grants), "the internet holds no grant")
  }

  test("a route is the same route whatever its parameters are called") {
    assert(Callers.granted.admits(network, self, Some(balance), grants))
  }

  test("a method granted by its service and name opens the method the server sees, and no other") {
    assert(Callers.granted.admits(merchant, self, Some(deposit), grants))
    assert(!Callers.granted.admits(merchant, self, Some(getBalance), grants))
    assert(
      !Callers.granted
        .admits(merchant, self, Some(GrantTarget.Method("other.WalletServiceX/Deposit")), grants),
      "a suffix of another definition's name is not the same definition"
    )
  }

  test("every matcher admits Local, granted too; and with no target a grant opens nothing") {
    assert(Callers.granted.admits(Caller.Local, self, Some(deposits), Grants.none))
    assert(!Callers.granted.admits(merchant, self, None, grants))
    assert(!Callers.granted.admits(merchant, self), "the two-argument form knows no route")
  }

  test("a machine is admitted by the internet, as a request from it that proved who it is") {
    assert(Callers.internet.admits(network, self))
    assert(!Callers.service("payments", "merchant").admits(network, self))
    assert(!Callers.anyInProject.admits(network, self))
  }

  test("a machine encodes and decodes as a grant names it") {
    assertEquals(Caller.encode(network), "machine:eitheror/affiliate-network")
    assertEquals(Caller.decode("machine:eitheror/affiliate-network"), Some(network))
    assertEquals(Caller.decode("machine:eitheror"), None)
  }

  test("a grant set tells its listeners when it changes, and not when it is set to itself") {
    val held    = Grants.of(GrantEntry(merchant, deposits))
    var changes = 0
    held.onChange(() => changes += 1)
    held.set(Vector(GrantEntry(merchant, deposits)))
    assertEquals(changes, 0)
    held.set(Vector.empty)
    assertEquals(changes, 1)
    assert(!held.admits(merchant, deposits))
  }

  // ── the router ───────────────────────────────────────────────────────────────────────────

  private final class Wallets extends HttpEndpoint("/v1/wallets"):
    val acl: Acl = Acl.allowCallers(Callers.granted)

    post("/{player}/{currency}/deposits")((player: String, currency: String) =>
      s"${Caller.encode(caller)} deposits for $player in $currency"
    )
    get("/{player}/{currency}")((player: String, currency: String) => s"$currency of $player")

    withAcl(Acl.allowCallers(Callers.service("spinvibe", "lobby"))) {
      get("/{player}")((player: String) => s"wallet of $player")
    }

  private def router(held: Grants) =
    Router(Vector(new Wallets), 5.seconds, new CallerSource(self, tls = false), None, held)

  private def as(r: Router, caller: Caller, method: HttpMethod, path: String): (Int, String) =
    val request = HttpRequest(method, uri = path)
      .withHeaders(RawHeader.apply.tupled(LocalCallers.header(caller)))
    val response = Await.result(r.handle(request), 10.seconds)
    val body     = Await.result(response.entity.toStrict(5.seconds), 5.seconds).data.utf8String
    (response.status.intValue, body)

  test(
    "the router admits a granted caller on its route, and refuses it on another, saying nothing"
  ) {
    val r = router(grants)
    assertEquals(
      as(r, merchant, HttpMethods.POST, "/v1/wallets/p1/eur/deposits"),
      (200, "service:payments/merchant deposits for p1 in eur")
    )
    // A path no route serves is decided by the endpoint's ACL, with no route to grant: refused, so
    // a closed endpoint does not say which of its paths exist.
    assertEquals(as(r, merchant, HttpMethods.POST, "/v1/wallets/p1/eur/withdrawals")._1, 403)
    assertEquals(as(r, merchant, HttpMethods.GET, "/v1/wallets/p1/eur")._1, 403)
    val refused = as(r, gateway, HttpMethods.POST, "/v1/wallets/p1/eur/deposits")
    assertEquals(refused._1, 403)
    assert(!refused._2.contains("grant") && !refused._2.contains("merchant"), refused._2)
  }

  test("a grant on a route whose ACL does not name granted callers opens nothing") {
    val onLobby = Grants.of(GrantEntry(merchant, GrantTarget.Route("GET", "/v1/wallets/{player}")))
    assertEquals(as(router(onLobby), merchant, HttpMethods.GET, "/v1/wallets/p1")._1, 403)
  }

  test("a revoked grant refuses the next request, with no new router") {
    val held = Grants.of(GrantEntry(merchant, deposits))
    val r    = router(held)
    assertEquals(as(r, merchant, HttpMethods.POST, "/v1/wallets/p1/eur/deposits")._1, 200)
    held.set(Vector.empty)
    assertEquals(as(r, merchant, HttpMethods.POST, "/v1/wallets/p1/eur/deposits")._1, 403)
  }

  test("a refused request is recorded as refused, never as failed") {
    val recorder = Observability(system).recorder
    val before   = recorder.snapshot().toSet
    val _        = as(router(grants), gateway, HttpMethods.POST, "/v1/wallets/p1/eur/deposits")
    val spans    = recorder.snapshot().filterNot(before.contains)
    assert(spans.exists(_.outcome == SpanOutcome.Refused), spans.toString)
    assert(!spans.exists(_.outcome == SpanOutcome.Failed), spans.toString)
  }

  test("the routes a server lists say which are grantable") {
    val endpoint = new Wallets
    assert(Acl.namesGranted(endpoint.acl))
    assert(!Acl.namesGranted(Acl.allowCallers(Callers.internet)))
    assert(!Acl.namesGranted(Acl.AllowAll))
  }
