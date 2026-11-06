package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.runtime.{Database, SqlFragment}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.nio.file.Files
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Feature 042, T049: who the keyring admits, and the record of whom it refused. */
class AdmissionSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit  = null
  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(Seq.empty)
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def database: Database        = Database()(using kit.service.system)
  private def await[A](f: Future[A]): A = Await.result(f, 30.seconds)

  private val root = Files.createTempDirectory("grants")
  Files.createDirectories(root.resolve("brand"))
  Files.writeString(
    root.resolve("brand").resolve("grants.json"),
    """{"project":"brand","grants":[
      |{"id":"g1","grantee":"service:analytics/reader","kind":"topic","topic":"players","right":"consume","decrypt":true},
      |{"id":"g2","grantee":"service:analytics/counter","kind":"topic","topic":"players","right":"consume"},
      |{"id":"g3","grantee":"machine:acme/warehouse","kind":"topic","topic":"players","right":"consume","decrypt":true},
      |{"id":"g4","grantee":"machine:acme/other","kind":"topic","topic":"players","right":"consume"}
      |]}""".stripMargin
  ): Unit

  private lazy val admission =
    Admission(
      ProjectGrants.fromDirectory(root),
      KeyStore(database),
      p => if p == "brand" then Set("south") else Set.empty
    )

  import Admission.Request.*
  private def service(p: String, s: String) = Admission.Who.Service(Caller.Service(p, s))
  private def machine(o: String, n: String) = Admission.Who.Machine(Caller.Machine(o, n))

  private def admitted(who: Admission.Who, request: Admission.Request) =
    await(admission.admit(who, request)).isRight

  test("a service of the project reads and makes its keys, its lookup key and its notices") {
    assert(admitted(service("brand", "players"), Key("brand", "player/8c1f", create = true)))
    assert(admitted(service("brand", "players"), LookupKey("brand")))
    assert(admitted(service("brand", "players"), Notices("brand")))
    assert(!admitted(service("other", "players"), LookupKey("brand")))
    assert(!admitted(service("other", "players"), Notices("brand")))
  }

  test(
    "a consumer in another project reads a key with a grant to consume with decrypt, and only reads"
  ) {
    assert(admitted(service("analytics", "reader"), Key("brand", "player/8c1f", create = false)))
    assert(!admitted(service("analytics", "reader"), Key("brand", "player/8c1f", create = true)))
    assert(!admitted(service("analytics", "counter"), Key("brand", "player/8c1f", create = false)))
    assert(!admitted(service("analytics", "nobody"), Key("brand", "player/8c1f", create = false)))
  }

  test("the control plane orders erasures and reads their status, and is refused a key") {
    val cp = Admission.Who.Service(Admission.ControlPlane)
    assert(admitted(cp, Destroy("brand", "player/8c1f")))
    assert(admitted(cp, Resend(1)))
    assert(admitted(cp, Status(1)))
    assert(!admitted(cp, Key("brand", "player/8c1f", create = false)))
    assert(!admitted(service("brand", "players"), Destroy("brand", "player/8c1f")))
  }

  test(
    "a relayed installation reads for a project whose home admits it; a machine only decrypts under its grant"
  ) {
    assert(admitted(Admission.Who.Relayed("south"), Key("brand", "player/8c1f", create = false)))
    assert(!admitted(Admission.Who.Relayed("south"), Key("brand", "player/8c1f", create = true)))
    assert(!admitted(Admission.Who.Relayed("east"), Key("brand", "player/8c1f", create = false)))
    assert(admitted(machine("acme", "warehouse"), Decrypt("brand", "g3")))
    assert(!admitted(machine("acme", "warehouse"), Decrypt("brand", "g1")))
    assert(!admitted(machine("acme", "other"), Decrypt("brand", "g4")))
    assert(!admitted(machine("acme", "warehouse"), Key("brand", "player/8c1f", create = false)))
    assert(
      !admitted(Admission.Who.Service(Caller.Gateway), Key("brand", "player/8c1f", create = false))
    )
  }

  test("every refusal is recorded, with the caller and why") {
    await(database.execute(SqlFragment.raw("DELETE FROM keyring.fetch_refusal"))).toInt: Unit
    assert(!admitted(service("analytics", "counter"), Key("brand", "player/9d2e", create = false)))
    assert(
      !admitted(
        Admission.Who.Service(Admission.ControlPlane),
        Key("brand", "player/9d2e", create = false)
      )
    )
    val rows = await(
      database.query(
        SqlFragment.raw(
          "SELECT caller, project, subject, reason FROM keyring.fetch_refusal ORDER BY at"
        )
      )(r =>
        (
          r.get("caller", classOf[String]),
          r.get("project", classOf[String]),
          r.get("subject", classOf[String]),
          r.get("reason", classOf[String])
        )
      )
    )
    assertEquals(
      rows.map(r => (r._1, r._2, r._3)),
      Vector(
        ("service:analytics/counter", "brand", "player/9d2e"),
        ("service:platform/controlplane", "brand", "player/9d2e")
      )
    )
    assert(rows.forall(_._4.nonEmpty))
  }

  test("the cluster's ConfigMaps give each project's grants, and a failed list keeps the last") {
    val grants =
      """{\"project\":\"brand\",\"grants\":[{\"id\":\"g1\",\"grantee\":\"service:analytics/reader\",\"kind\":\"topic\",\"topic\":\"players\",\"right\":\"consume\",\"decrypt\":true}]}"""
    val list =
      s"""{"kind":"ConfigMapList","items":[
         |{"metadata":{"namespace":"ankka-brand","name":"ankka-project"},"data":{"topics.json":"{}","grants.json":"$grants"}},
         |{"metadata":{"namespace":"ankka-empty","name":"ankka-project"},"data":{"topics.json":"{}"}},
         |{"metadata":{"namespace":"ankka-bad","name":"ankka-project"},"data":{"grants.json":"not json"}}
         |]}""".stripMargin
    var answer: () => String = () => list
    var now                  = 0L
    val k8s                  = KubernetesGrants(() => answer(), 30.seconds, () => now)
    assert(k8s.decrypts("brand", "service:analytics/reader"))
    assertEquals(k8s.of("empty"), Vector.empty)
    answer = () => throw IllegalStateException("the API server is away")
    now = 31.seconds.toNanos
    assert(k8s.decrypts("brand", "service:analytics/reader"))
  }
