package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.secrets.DerivedIds
import com.typesafe.config.ConfigFactory

/**
 * The secret store on the Secret Manager backend, against the test kit's fake: the shared scenarios
 * of `secret-store.feature`, and `secret-manager.feature`'s — the value is in Secret Manager under
 * its derived id, and the service's database holds nothing of it.
 *
 * Every case that could pass on the wrong backend says what the fake was asked, so a kit that
 * quietly kept its secrets in the database is red here.
 */
final class SecretManagerSecretStoreSuite extends SecretStoreBehaviours:

  private lazy val fake = FakeSecretManager.start()

  private val Project = "spinvibe"
  private val Service = "payments"

  protected def backend(): SecretBackendChoice =
    SecretBackendChoice.secretManager(fake, Project, Service)

  override def afterAll(): Unit =
    super.afterAll()
    fake.stop()

  private def idOf(name: String) = DerivedIds.service(Project, Service, name)

  protected def held(name: String, rows: Vector[(String, String)]): Int =
    assertEquals(
      rows.count((table, row) => table == "ankka_secrets" && row.contains(s"\"name\":\"$name\"")),
      0,
      s"the database holds a row for '$name' on the Secret Manager backend"
    )
    if fake.snapshot.get(idOf(name)).exists(_ > 0) then 1 else 0

  protected def storedAsExpected(rows: Vector[String]): Unit =
    assertEquals(rows, Vector.empty, "the database holds the secret on the Secret Manager backend")
    assert(fake.snapshot.get(idOf("dump")).exists(_ > 0), "Secret Manager does not hold it")

  test(
    "a service secret kept by one component is read by another, and the database holds nothing of it"
  ) {
    secrets.put("acme", "sk-acme-1")
    assertEquals(fake.latestValue(idOf("acme")), Some("sk-acme-1"))
    assertEquals(
      fake.annotationsOf(idOf("acme")).flatMap(_.get(DerivedIds.NameAnnotation)),
      Some("acme")
    )
    val rows = dump().filter(_._1 == "ankka_secrets")
    assertEquals(rows, Vector.empty)
    assert(
      fake.calls.exists(_.identity.contains(FakeSecretManager.Identity.Service(Project, Service)))
    )
  }

  test("a service secret kept again is read by every instance with no descriptor applied again") {
    secrets.put("again", "sk-acme-1")
    val peer = testKit.startPeer(Seq.empty)
    try
      secrets.put("again", "sk-acme-2")
      assertEquals(peer.service.secrets.get("again"), Some("sk-acme-2"))
      assertEquals(secrets.get("again"), Some("sk-acme-2"))
    finally peer.stop()
  }

  test("a removed service secret is read as none and no version of it remains") {
    secrets.put("gone", "sk-1")
    secrets.put("gone", "sk-2")
    secrets.delete("gone")
    assertEquals(secrets.get("gone"), None)
    assertEquals(fake.versionsOf(idOf("gone")), Vector.empty)
    assertEquals(fake.annotationsOf(idOf("gone")), None)
  }

  test(
    "a name or a value that breaks the rules of the secret store is refused before Secret Manager is called"
  ) {
    fake.clearCalls()
    for (name, value) <- Vector(
        "provider acme" -> "sk-1",
        ""              -> "sk-1",
        "acme"          -> "",
        "acme"          -> "x" * 65537
      )
    do refused(secrets.put(name, value))
    assertEquals(fake.calls, Vector.empty)
  }

  test(
    "a secret kept a hundred times keeps no more than the kept count, and every read is the newest"
  ) {
    val id = idOf("rotated-often")
    for i <- 1 to 100 do
      secrets.put("rotated-often", s"sk-$i")
      assertEquals(secrets.get("rotated-often"), Some(s"sk-$i"))
      assert(
        fake.snapshot.getOrElse(id, 0) <= 2,
        s"${fake.versionsOf(id).count(_._2 == "ENABLED")} enabled after $i"
      )
    val states = fake.versionsOf(id)
    assertEquals(states.takeRight(2).map(_._2), Vector("ENABLED", "ENABLED"))
    assert(states.dropRight(2).forall(_._2 == "DESTROYED"), states.toString)
    assertEquals(states.map(_._1), (1L to 100L).toVector)
  }

  test("a service on the Secret Manager backend needs no secret key") {
    val key = testKit.secretKey
    try
      testKit.restartService(secretKey = None)
      secrets.put("keyless", "sk-1")
      assertEquals(secrets.get("keyless"), Some("sk-1"))
    finally testKit.restartService(secretKey = key)
  }

  test(
    "a service that declares no database still has a secret store on the Secret Manager backend"
  ) {
    val kit = AnkkaTestKit.start(
      Seq.empty,
      settings = ConfigFactory.parseString("ankka.database = none"),
      secretBackend = SecretBackendChoice.secretManager(fake, Project, "reports")
    )
    try
      kit.secrets.put("feed", "sk-feed")
      assertEquals(kit.secrets.get("feed"), Some("sk-feed"))
      assertEquals(
        fake.latestValue(DerivedIds.service(Project, "reports", "feed")),
        Some("sk-feed")
      )
    finally kit.stop()
  }

  test("an unreachable Secret Manager is Unavailable, with nothing read from anywhere else") {
    secrets.put("outage", "sk-1")
    fake.unreachable(true)
    try
      val error = intercept[com.thinkmorestupidless.ankka.core.CommandError](secrets.get("outage"))
      assertEquals(error.code, com.thinkmorestupidless.ankka.core.ErrorCode.Unavailable)
    finally fake.unreachable(false)
    assertEquals(secrets.get("outage"), Some("sk-1"))
  }
