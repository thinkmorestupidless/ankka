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
    harness.member("cy", "brand-org", "ada")

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

  private def held(subject: String): String =
    val (status, body) =
      ask(s"""{"subject":"$subject","notBefore":"2099-01-01T00:00:00Z","reason":"aml-retention"}""")
    assertEquals(status, 201, body)
    Rid.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))

  test("a member replaces a held request's date and reason; one not held is not replaced") {
    val rid            = held("player/r1")
    val (status, body) = harness.send(
      "PUT",
      s"/projects/brand/erasures/$rid",
      Some("""{"notBefore":"2099-06-01T00:00:00Z","reason":"chargeback window"}"""),
      "cy"
    )
    assertEquals(status, 200, body)
    assert(body.contains(""""notBefore":"2099-06-01T00:00:00Z""""), body)
    assert(body.contains(""""reason":"chargeback window""""), body)
    assert(body.contains(""""state":"held""""), body)
    harness.send("POST", s"/projects/brand/erasures/$rid/withdraw", None, "ada"): Unit
    val (again, why) = harness.send(
      "PUT",
      s"/projects/brand/erasures/$rid",
      Some("""{"notBefore":"2099-07-01T00:00:00Z"}"""),
      "ada"
    )
    assertEquals(again, 409, why)
  }

  test("any member withdraws a held request; an applied one cannot be withdrawn") {
    val rid            = held("player/w1")
    val (status, body) = harness.send("POST", s"/projects/brand/erasures/$rid/withdraw", None, "cy")
    assertEquals(status, 200, body)
    assert(body.contains(""""state":"withdrawn""""), body)

    val (_, asked) = ask("""{"subject":"player/w2"}""")
    val now        = Rid.findFirstMatchIn(asked).map(_.group(1)).getOrElse(fail(asked))
    harness.eventually("the request applied") {
      val (_, r) = harness.send("GET", s"/projects/brand/erasures/$now", as = "ada")
      Option.when(r.contains(""""state":"applied""""))(r)
    }: Unit
    val (refused, why) =
      harness.send("POST", s"/projects/brand/erasures/$now/withdraw", None, "ada")
    assertEquals(refused, 409, why)
    assert(why.contains("applied"), why)
  }

  test("an owner overrides a hold with a reason and it is applied; a member cannot") {
    val rid           = held("player/o1")
    val (member, why) = harness.send(
      "POST",
      s"/projects/brand/erasures/$rid/override",
      Some("""{"reason":"asked twice"}"""),
      "cy"
    )
    assertEquals(member, 403, why)
    val (empty, none) =
      harness.send(
        "POST",
        s"/projects/brand/erasures/$rid/override",
        Some("""{"reason":" "}"""),
        "ada"
      )
    assertEquals(empty, 400, none)
    assert(none.contains("an override needs a reason"), none)
    val (status, body) = harness.send(
      "POST",
      s"/projects/brand/erasures/$rid/override",
      Some("""{"reason":"the hold was filed in error"}"""),
      "ada"
    )
    assertEquals(status, 200, body)
    assert(body.contains(""""holdOverride":{"owner":"ada""""), body)
    harness.eventually("the overridden request applied") {
      val (_, r) = harness.send("GET", s"/projects/brand/erasures/$rid", as = "ada")
      Option.when(r.contains(""""state":"applied""""))(r)
    }: Unit
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

  test("the erasure log is read by the keyring alone, after a sequence") {
    import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
    import com.thinkmorestupidless.ankka.http.Caller
    val (_, asked) = ask("""{"subject":"player/77aa"}""")
    val rid        = Rid.findFirstMatchIn(asked).map(_.group(1)).getOrElse(fail(asked))
    harness.eventually("the request applied") {
      val (_, now) = harness.send("GET", s"/projects/brand/erasures/$rid", as = "ada")
      Option.when(now.contains(""""state":"applied""""))(now)
    }: Unit
    val keyring                       = Caller.Service("platform", "keyring")
    def read(as: Caller, after: Long) =
      harness.raw(
        "GET",
        s"/internal/erasure-log?after=$after",
        None,
        headers = Map(harness.callerHeader(as))
      )
    val (status, body, _) = read(keyring, 0)
    assertEquals(status, 200, body)
    val mine = readFromString[ErasureLogPage](body).entries
      .find(_.rid == rid)
      .getOrElse(fail(s"no entry for $rid in $body"))
    assertEquals((mine.project, mine.subject), ("brand", "player/77aa"))
    assert(!read(keyring, mine.seq)._2.contains(rid))
    assertEquals(read(Caller.Service("brand", "players"), 0)._1, 403)
  }
