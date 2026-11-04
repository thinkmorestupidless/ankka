package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.GrantsHarness
import com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings
import com.thinkmorestupidless.ankka.testkit.{InMemoryKeyring, LogCapturing}

import scala.concurrent.duration.*

/** Feature 042, T040: a project's erasure routes, as a member and as an outsider. */
class ErasureEndpointSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val keyring      = InMemoryKeyring()
  private val admin        = TestKeyringAdmin(keyring)
  private val bucket       = MemoryErasureLogBucket()
  private val plane        = ErasurePlane(admin, Some(bucket))
  @volatile private var on = true
  private val harness      = GrantsHarness(
    erasure = () => ErasureSettings(on, 2),
    erasurePlane = Some(plane),
    keyring = keyring
  )

  override def beforeAll(): Unit =
    harness.start()
    harness.organization("brand-org", "ada")
    harness.project("brand", "brand-org", "ada")
    harness.organization("other-org", "bo")

  override def afterAll(): Unit = harness.stop()

  private val Rid = """"id":"([0-9A-Z]{26})"""".r

  private def ask(body: String, as: String = "ada") =
    harness.send("POST", "/projects/brand/erasures", Some(body), as)

  test("a member asks; the request is applied at once and its certificate names no value") {
    val (status, body) = ask("""{"subject":"player/8c1f","correlation":"ticket-41"}""")
    assertEquals(status, 201, body)
    assert(body.contains(""""state":"applying""""), body)
    val rid = Rid.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))
    harness.eventually("the request applied") {
      val (_, now) = harness.send("GET", s"/projects/brand/erasures/$rid", as = "ada")
      Option.when(now.contains(""""state":"applied""""))(now)
    }: Unit
    assertEquals(admin.ordered.map(_.subject), Vector("player/8c1f"))
    val (certified, certificate) =
      harness.send("GET", s"/projects/brand/erasures/$rid/certificate", as = "ada")
    assertEquals(certified, 200, certificate)
    assert(certificate.contains(s""""request":"brand/$rid""""), certificate)
    assert(certificate.contains(""""keyDestroyedAt":"""), certificate)
    assert(certificate.contains(""""askedBy":"ada""""), certificate)

    // The view follows the request; wait for the last event, then assert the rest.
    val history = harness.eventually("the erasure in the project's history") {
      val (_, h) = harness.send("GET", "/projects/brand/history", as = "ada")
      Option.when(h.contains(""""kind":"erasure-key-destroyed""""))(h)
    }
    assert(history.contains(""""kind":"erasure-asked""""), history)
    assert(history.contains(""""kind":"erasure-key-destroyed""""), history)
    assert(history.contains(s"erasure $rid of player/8c1f"), history)
  }

  test("a held request is listed by its state, and has no certificate yet") {
    val (status, body) =
      ask(
        """{"subject":"player/41d2","notBefore":"2099-01-01T00:00:00Z","reason":"aml-retention"}"""
      )
    assertEquals(status, 201, body)
    assert(body.contains(""""state":"held""""), body)
    val rid            = Rid.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))
    val (refused, why) =
      harness.send("GET", s"/projects/brand/erasures/$rid/certificate", as = "ada")
    assertEquals(refused, 409, why)
    val listed = harness.eventually("the held request listed") {
      val (_, list) = harness.send("GET", "/projects/brand/erasures?state=held", as = "ada")
      Option.when(list.contains(rid))(list)
    }
    assert(!listed.contains("player/8c1f"), listed)
    val (_, bySubject) =
      harness.send("GET", "/projects/brand/erasures?subject=player/41d2", as = "ada")
    assert(bySubject.contains(rid), bySubject)
    assertEquals(harness.send("GET", "/projects/brand/erasures?state=gone", as = "ada")._1, 400)
  }

  test("a subject outside the rule is refused, naming it") {
    val (status, body) = ask("""{"subject":"player 8c1f"}""")
    assertEquals(status, 400, body)
    assert(body.contains("1 to 253 characters"), body)
  }

  test("someone outside the project can neither ask nor read") {
    assert(Set(403, 404).contains(ask("""{"subject":"player/9"}""", as = "bo")._1))
    assert(Set(403, 404).contains(harness.send("GET", "/projects/brand/erasures", as = "bo")._1))
  }

  test("every write is refused while the installation's erasure is off") {
    on = false
    try
      val (status, body) = ask("""{"subject":"player/77"}""")
      assertEquals(status, 409, body)
      assert(body.contains("erasure is off on this installation"), body)
    finally on = true
  }
