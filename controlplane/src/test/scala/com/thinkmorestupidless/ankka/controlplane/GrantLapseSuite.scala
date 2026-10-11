package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt

/**
 * A deleted grantee lapses its grants (feature 040): `ProjectTrigger` on a project's deletion, for
 * its services' grants, and `MachineLifecycleTrigger` on a machine's, each on the granting project
 * with the deleter's attribution. The acceptance feature reads the machine's from both sides; this
 * suite holds the project's, and a grant to another grantee left alone by either.
 */
class GrantLapseSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val cp = GrantsHarness()

  override def beforeAll(): Unit =
    cp.start()
    cp.organization("eitheror", "ada")
    cp.project("spinvibe", "eitheror", "ada")
    cp.project("payments", "eitheror", "ada")
    cp.project("rewards", "eitheror", "ada")
    cp.topic("spinvibe", "affiliates.attribution", owner = "ada")

  override def afterAll(): Unit = cp.stop()

  private def grant(grantee: String): GrantDetail =
    val (status, body) = cp.send(
      "POST",
      "/projects/spinvibe/grants",
      Some(
        s"""{"grantee":"$grantee","target":{"kind":"topic","topic":"affiliates.attribution","right":"consume"}}"""
      ),
      as = "ada"
    )
    assertEquals(status, 200, body)
    readFromString[GrantDetail](body)

  private def shown(id: String): GrantDetail =
    readFromString[Vector[GrantDetail]](cp.send("GET", "/projects/spinvibe/grants", as = "ada")._2)
      .find(_.id == id)
      .getOrElse(fail(s"grant $id is not listed"))

  test("deleting a project lapses every grant its services held, with who deleted it") {
    val held  = grant("service:payments/merchant")
    val other = grant("service:rewards/ledger")
    assertEquals(shown(held.id).state, GrantState.Accepted)
    cp.eventually("payments told of the grant") {
      val (_, body) = cp.send("GET", "/projects/payments/grants/received", as = "ada")
      Option.when(body.contains(held.id))(())
    }
    assertEquals(cp.send("DELETE", "/projects/payments", as = "ada")._1, 204)
    val lapsed = cp.eventually("the grant lapsed") {
      Some(shown(held.id)).filter(_.state == GrantState.Lapsed)
    }
    assert(
      lapsed.ended.exists(e => e.by.exists(_.contains("ada")) && e.at.isDefined),
      lapsed.toString
    )
    assertEquals(shown(other.id).state, GrantState.Accepted)
  }

  test("deleting a machine lapses its grants and no one else's") {
    assertEquals(
      cp.send(
        "POST",
        "/organizations/eitheror/machines",
        Some("""{"name":"network"}"""),
        as = "ada"
      )._1,
      200
    )
    val held  = grant("machine:eitheror/network")
    val other = grant("service:rewards/auditor")
    assertEquals(cp.send("DELETE", "/organizations/eitheror/machines/network", as = "ada")._1, 204)
    val lapsed = cp.eventually("the machine's grant lapsed") {
      Some(shown(held.id)).filter(_.state == GrantState.Lapsed)
    }
    assert(lapsed.ended.exists(_.by.exists(_.contains("ada"))), lapsed.toString)
    assertEquals(shown(other.id).state, GrantState.Accepted)
  }
