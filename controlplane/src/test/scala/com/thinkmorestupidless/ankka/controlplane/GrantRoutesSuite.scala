package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{
  GrantChange,
  GrantDetail,
  GrantState,
  ReceivedGrantDetail
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt

/**
 * The grant routes on a project (feature 040), over HTTP against the shipped assembly: who may make
 * and end a grant, what they are told when they may not, and that the grantee's project sees the
 * record the consumer derived.
 */
class GrantRoutesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private val cp = GrantsHarness()

  private val deposits =
    """{"kind":"route","service":"wallet","method":"POST","path":"/v1/wallets/{player}/{currency}/deposits"}"""
  private def grantBody(grantee: String, target: String = deposits) =
    Some(s"""{"grantee":"$grantee","target":$target}""")

  override def beforeAll(): Unit =
    cp.start()
    cp.organization("eitheror", owner = "ada")
    cp.project("spinvibe", "eitheror", owner = "ada")
    cp.project("payments", "eitheror", owner = "ada")
    cp.topic("spinvibe", "affiliates.attribution", owner = "ada")
    cp.member("cy", "eitheror", owner = "ada")
    cp.organization("affiliates", owner = "bo")

  override def afterAll(): Unit = cp.stop()

  test("an owner makes a grant and is answered the grant, in effect within one organization") {
    val (status, body) =
      cp.send("POST", "/projects/spinvibe/grants", grantBody("service:payments/merchant"), "ada")
    assertEquals(status, 200, body)
    val grant = readFromString[GrantDetail](body)
    assertEquals(grant.state, GrantState.Accepted)
    assertEquals(grant.effect, "in effect")
    assert(grant.granted.by.nonEmpty, body)
    val (_, listed) = cp.send("GET", "/projects/spinvibe/grants", as = "cy")
    assert(readFromString[Vector[GrantDetail]](listed).exists(_.id == grant.id), listed)
  }

  test("the same grant made again is the same grant") {
    val target = """{"kind":"erasure"}"""
    val first = readFromString[GrantDetail](
      cp.send(
        "POST",
        "/projects/spinvibe/grants",
        grantBody("service:payments/merchant", target),
        "ada"
      )._2
    )
    val second = readFromString[GrantDetail](
      cp.send(
        "POST",
        "/projects/spinvibe/grants",
        grantBody("service:payments/merchant", target),
        "ada"
      )._2
    )
    assertEquals(second.id, first.id)
  }

  test("a grant to another organization's machine is pending") {
    val target = """{"kind":"topic","topic":"affiliates.attribution","right":"consume"}"""
    val (status, body) =
      cp.send(
        "POST",
        "/projects/spinvibe/grants",
        grantBody("machine:affiliates/network", target),
        "ada"
      )
    assertEquals(status, 200, body)
    val grant = readFromString[GrantDetail](body)
    assertEquals((grant.state, grant.effect), (GrantState.Pending, "pending"))
  }

  test("only the grantee organization's owner answers a grant; a second answer conflicts") {
    val target = """{"kind":"topic","topic":"affiliates.attribution","right":"consume"}"""
    val (_, body) =
      cp.send(
        "POST",
        "/projects/spinvibe/grants",
        grantBody("machine:affiliates/answered", target),
        "ada"
      )
    val grant = readFromString[GrantDetail](body)
    val path  = s"/organizations/affiliates/grants/${grant.id}"
    cp.eventually("the grant offered to affiliates") {
      val (status, listed) = cp.send("GET", "/organizations/affiliates/grants", as = "bo")
      Option.when(status == 200 && listed.contains(grant.id))(())
    }
    // The granting organization's owner is not offered it, and is told there is no such grant.
    assertEquals(
      cp.send("POST", s"/organizations/eitheror/grants/${grant.id}/accept", as = "ada")._1,
      404
    )
    // Nor may the grantor answer for the grantee's organization, of which it is no member.
    assertEquals(cp.send("POST", s"$path/accept", as = "ada")._1, 404)
    assertEquals(cp.send("POST", s"$path/accept", as = "bo")._1, 204)
    cp.eventually("the acceptance recorded on affiliates") {
      val (_, listed) = cp.send("GET", "/organizations/affiliates/grants", as = "bo")
      Option.when(
        readFromString[Vector[ReceivedGrantDetail]](listed).exists(r =>
          r.id == grant.id && r.state == GrantState.Accepted
        )
      )(())
    }
    assertEquals(cp.send("POST", s"$path/accept", as = "bo")._1, 409)
    assertEquals(cp.send("POST", s"$path/decline", as = "bo")._1, 409)
    assertEquals(cp.send("POST", s"$path/relinquish", as = "bo")._1, 204)
  }

  test("a member may list grants and may not make one; a deploy token neither makes nor ends one") {
    assertEquals(
      cp.send("POST", "/projects/spinvibe/grants", grantBody("service:payments/merchant"), "cy")._1,
      403
    )
    val token = cp.deployToken("eitheror", owner = "ada")
    assertEquals(
      cp.send("POST", "/projects/spinvibe/grants", grantBody("service:payments/notifier"), token)
        ._1,
      403
    )
    assertEquals(cp.send("GET", "/projects/spinvibe/grants", as = token)._1, 200)
    assertEquals(cp.send("DELETE", "/projects/spinvibe/grants/whatever", as = token)._1, 403)
  }

  test("someone outside the organization is told there is no project") {
    val (status, body) = cp.send("GET", "/projects/spinvibe/grants", as = "bo")
    assertEquals(status, 404)
    assert(body.contains("no such project 'spinvibe'"), body)
    assertEquals(
      cp.send("POST", "/projects/spinvibe/grants", grantBody("service:payments/merchant"), "bo")._1,
      404
    )
  }

  test(
    "a grantee project that was never created, or an organization that does not exist, is not found"
  ) {
    val (status, body) =
      cp.send("POST", "/projects/spinvibe/grants", grantBody("service:nowhere/merchant"), "ada")
    assertEquals(status, 404, body)
    assert(body.contains("no such project 'nowhere'"), body)
    assertEquals(
      cp.send("POST", "/projects/spinvibe/grants", grantBody("machine:nobody/network"), "ada")._1,
      404
    )
  }

  test("a malformed grant is refused before anything is recorded, with every reason") {
    val (status, body) = cp.send(
      "POST",
      "/projects/spinvibe/grants",
      grantBody("everyone", """{"kind":"route","service":"wallet","method":"FETCH","path":"x"}"""),
      "ada"
    )
    assertEquals(status, 400)
    assert(body.contains("grantee 'everyone'") && body.contains("method is one of"), body)
  }

  test("a topic the project has not declared cannot be granted, and the refusal says so") {
    val (status, body) = cp.send(
      "POST",
      "/projects/spinvibe/grants",
      grantBody(
        "service:payments/merchant",
        """{"kind":"topic","topic":"casino.sessions","right":"consume"}"""
      ),
      "ada"
    )
    assertEquals(status, 404)
    assert(body.contains("'spinvibe' has not declared the topic 'casino.sessions'"), body)
  }

  test(
    "deleting a grant withdraws a pending one and revokes an accepted one, on owners' word only"
  ) {
    val produce = """{"kind":"topic","topic":"affiliates.attribution","right":"produce"}"""
    val pending = readFromString[GrantDetail](
      cp.send(
        "POST",
        "/projects/spinvibe/grants",
        grantBody("machine:affiliates/network", produce),
        "ada"
      )._2
    )
    val accepted = readFromString[GrantDetail](
      cp.send("POST", "/projects/spinvibe/grants", grantBody("service:payments/ledger"), "ada")._2
    )
    assertEquals(cp.send("DELETE", s"/projects/spinvibe/grants/${pending.id}", as = "cy")._1, 403)
    assertEquals(cp.send("DELETE", s"/projects/spinvibe/grants/${pending.id}", as = "ada")._1, 204)
    assertEquals(cp.send("DELETE", s"/projects/spinvibe/grants/${accepted.id}", as = "ada")._1, 204)
    val states = readFromString[Vector[GrantDetail]](
      cp.send("GET", "/projects/spinvibe/grants", as = "ada")._2
    )
      .map(g => g.id -> g.state)
      .toMap
    assertEquals(states(pending.id), GrantState.Withdrawn)
    assertEquals(states(accepted.id), GrantState.Revoked)
    assertEquals(cp.send("DELETE", s"/projects/spinvibe/grants/${accepted.id}", as = "ada")._1, 409)
    assertEquals(cp.send("DELETE", "/projects/spinvibe/grants/missing", as = "ada")._1, 404)
  }

  test("the grantee project lists what it was granted, with each change and who made it") {
    val grant = readFromString[GrantDetail](
      cp.send("POST", "/projects/spinvibe/grants", grantBody("service:payments/auditor"), "ada")._2
    )
    val _ = cp.send("DELETE", s"/projects/spinvibe/grants/${grant.id}", as = "ada")
    val received = cp.eventually("the revocation reaches payments") {
      val (_, body) = cp.send("GET", "/projects/payments/grants/received", as = "cy")
      readFromString[Vector[ReceivedGrantDetail]](body)
        .find(r => r.id == grant.id && r.state == GrantState.Revoked)
    }
    assertEquals(received.grantingProject, "spinvibe")
    assertEquals(received.grantingOrganization, "eitheror")
    assertEquals(received.changes.map(_.change), Vector(GrantChange.Made, GrantChange.Revoked))
    assert(received.changes.forall(_.by.nonEmpty), received.toString)
  }
