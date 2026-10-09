package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.{ControlPlane, TestIdentity}
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  ErasureLogBucket,
  ErasureSettings,
  ErasureSweeper,
  KeyringCaller
}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.keyring.*
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, ServiceIdentity}
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringClient
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, LocalDate, ZoneOffset}
import scala.concurrent.duration.*

/** A customer with one personal field, in project `brand`. */
final case class Customer(name: Option[Personal[String]], visits: Int)

final class CustomerEntity(context: KeyValueEntityContext) extends KeyValueEntity[Customer]:
  def emptyState: Customer = Customer(None, 0)
  def register(name: String): Effect[Done] =
    effects
      .updateState(
        Customer(
          Some(Personal.present(s"player/${context.entityId}", name)),
          currentState.visits + 1
        )
      )
      .thenReply(_ => Done)
  def name: ReadOnlyEffect[String] =
    effects.reply(currentState.name.flatMap(_.toOption).getOrElse("<erased>"))

object CustomerEntity
    extends KeyValueEntity.Companion[CustomerEntity, Customer](
      componentId = ComponentId("customers"),
      stateSerializer = Codecs.serializer[Customer]("customer")
    ):
  def create(context: KeyValueEntityContext) = new CustomerEntity(context)
  val register                               = command("register")(_.register)
  val name                                   = query("name")(_.name)

/**
 * Erasure requests through the control plane's routes, applied by its sweeper through a real
 * keyring to a real service's channel: asked, applied, certified; held and released by the clock;
 * withdrawn; overridden by an owner and refused to a member; refused when malformed; the log read
 * back.
 */
class ErasureSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)
  private lazy val bob   = identity.token("bob", Some("bob@example.test"), expiresIn = 2.hours)

  private var keyringKit: AnkkaTestKit   = null
  private var controlPlane: AnkkaTestKit = null
  private var players: AnkkaTestKit      = null
  private var base: String               = ""
  private var sweeper: ErasureSweeper    = null
  private val http                       = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    val state =
      KeyringState(Grants.none, LogSources.none, ackWithin = 10.seconds, applySchema = false)
    keyringKit = AnkkaTestKit.start(
      Keyring.components,
      Seq(
        KeyringRuntime(state),
        HttpServer.at("127.0.0.1", 0)(clients => KeyringEndpoint(clients, state))
      ),
      keyring = None
    )
    val keyringUrl = keyringKit.service.boundAddresses.find(_.startsWith("http")).get
    sweeper = ErasureSweeper(
      KeyringCaller.Http(URI.create(keyringUrl), None),
      ErasureLogBucket.none,
      ErasureSettings(sweepInterval = 1.hour, reapplyInterval = 1.hour),
      identity.clock
    )
    val server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        auth = Some(identity.config()),
        clock = identity.clock,
        erasures = Some(sweeper)
      )*
    )
    controlPlane = AnkkaTestKit.start(
      ControlPlane.components,
      Seq(ProjectionRuntime(), server, sweeper),
      keyring = None
    )
    base = s"http://127.0.0.1:${server.boundPort.get}"
    players = AnkkaTestKit.start(
      Seq(CustomerEntity.descriptor),
      serviceIdentity = ServiceIdentity.deployed("brand", "players"),
      keyring = None,
      configure = _.withKeyring(
        KeyringClient(URI.create(keyringUrl.replaceFirst("^http", "ws") + "/channel"), None)
      )
    )
    assertEquals(send("POST", "/organizations/acme", alice, Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(
      send(
        "POST",
        "/projects/brand",
        alice,
        Some("""{"name":"Brand","organizationId":"acme"}""")
      )._1,
      204
    )

  override def afterAll(): Unit =
    Seq(players, controlPlane, keyringKit).filter(_ != null).foreach(_.stop())
    identity.stop()

  private def send(
      method: String,
      path: String,
      token: String,
      body: Option[String] = None
  ): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $token")
    body.fold(builder.method(method, HttpRequest.BodyPublishers.noBody()))(json =>
      builder
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(json))
    ): Unit
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def request(subject: String, extra: String = ""): ErasureRequest =
    val (status, body) =
      send("POST", "/projects/brand/erasures", alice, Some(s"""{"subject":"$subject"$extra}"""))
    assert(status == 201 || status == 200, s"$status $body")
    readFromString[ErasureRequest](body)

  private def read(id: String, token: String = alice): ErasureRequest =
    readFromString[ErasureRequest](send("GET", s"/projects/brand/erasures/$id", token)._2)

  private def customer(id: String) = players.componentClient.forKeyValueEntity(EntityId(id))

  private def swept(id: String, until: ErasureRequest => Boolean): ErasureRequest =
    players.eventually(s"erasure $id") { sweeper.sweep(); Some(read(id)).filter(until) }

  test(
    "an erasure request with no date is applied: the log written, the key destroyed, the service erased, a certificate issued"
  ) {
    customer("p1").call(CustomerEntity.register).invoke("Ada Byron"): Unit
    assertEquals(customer("p1").call(CustomerEntity.name).invoke(), "Ada Byron")
    val asked = request("player/p1")
    assertEquals(asked.state, ErasureState.Applying)
    assertEquals(asked.askedBy.subject, "alice")
    val applied = swept(asked.id, _.state == ErasureState.Final)
    assertEquals(applied.sequence, Some(1L))
    assert(applied.keyDestroyedAt.isDefined)
    assertEquals(customer("p1").call(CustomerEntity.name).invoke(), "<erased>")
    val (status, certificate) =
      send("GET", s"/projects/brand/erasures/${asked.id}/certificate", alice)
    assertEquals(status, 200, certificate)
    assert(certificate.contains("player/p1") && certificate.contains("statement"), certificate)
    assert(!certificate.contains("Ada"), certificate)
    controlPlane.eventually("the listing has the request")(
      Option.when(
        send("GET", "/projects/brand/erasures?subject=player/p1", alice)._2.contains(asked.id)
      )(())
    )
    assertEquals(
      request("player/p1").id,
      asked.id,
      "a second request for an erased subject is answered with the applied one"
    )
  }

  test("a held request waits for its not-before date, then is applied with nobody acting") {
    customer("p2").call(CustomerEntity.register).invoke("Grace Hopper"): Unit
    val until = LocalDate.ofInstant(identity.clock.instant(), ZoneOffset.UTC).plusDays(5)
    val held  = request("player/p2", s""","notBefore":"$until","reason":"aml-retention"""")
    assertEquals(held.state, ErasureState.Held)
    sweeper.sweep()
    assertEquals(read(held.id).state, ErasureState.Held)
    assertEquals(customer("p2").call(CustomerEntity.name).invoke(), "Grace Hopper")
    identity.clock.advanceDays(6)
    swept(held.id, _.state == ErasureState.Final): Unit
    assertEquals(customer("p2").call(CustomerEntity.name).invoke(), "<erased>")
  }

  test("a held request is withdrawn and nothing is destroyed; an applied one cannot be") {
    customer("p3").call(CustomerEntity.register).invoke("Kept"): Unit
    val until          = LocalDate.ofInstant(identity.clock.instant(), ZoneOffset.UTC).plusDays(30)
    val held           = request("player/p3", s""","notBefore":"$until","reason":"aml-retention"""")
    val (status, body) = send("DELETE", s"/projects/brand/erasures/${held.id}", alice)
    assertEquals(status, 200, body)
    assertEquals(readFromString[ErasureRequest](body).state, ErasureState.Withdrawn)
    sweeper.sweep()
    assertEquals(customer("p3").call(CustomerEntity.name).invoke(), "Kept")
    val applied = read(request("player/p1").id)
    assertEquals(send("DELETE", s"/projects/brand/erasures/${applied.id}", alice)._1, 409)
  }

  test("an owner overrides a hold with a reason and it is applied at once; a member is refused") {
    assertEquals(
      send(
        "POST",
        "/organizations/acme/members",
        alice,
        Some("""{"email":"bob@example.test"}""")
      )._1,
      204
    )
    players.eventually("bob is a member")(
      Option.when(send("GET", "/projects/brand", bob)._1 == 200)(())
    )
    customer("p4").call(CustomerEntity.register).invoke("Held Person"): Unit
    val until = LocalDate.ofInstant(identity.clock.instant(), ZoneOffset.UTC).plusDays(400)
    val held  = request("player/p4", s""","notBefore":"$until","reason":"aml-retention"""")
    val (refused, why) = send(
      "POST",
      s"/projects/brand/erasures/${held.id}/override",
      bob,
      Some("""{"reason":"mine"}""")
    )
    assertEquals(refused, 403, why)
    assertEquals(read(held.id).state, ErasureState.Held)
    val (status, body) = send(
      "POST",
      s"/projects/brand/erasures/${held.id}/override",
      alice,
      Some("""{"reason":"regulator order 2028/41"}""")
    )
    assertEquals(status, 200, body)
    val overridden = readFromString[ErasureRequest](body)
    assertEquals(overridden.overridden.map(_.reason), Some("regulator order 2028/41"))
    swept(held.id, _.state == ErasureState.Final): Unit
    assertEquals(customer("p4").call(CustomerEntity.name).invoke(), "<erased>")
  }

  test(
    "a malformed request is refused: a date passed, a hold without a reason, a subject outside the rule"
  ) {
    val yesterday = LocalDate.ofInstant(identity.clock.instant(), ZoneOffset.UTC).minusDays(1)
    assertEquals(
      send(
        "POST",
        "/projects/brand/erasures",
        alice,
        Some(s"""{"subject":"player/x","notBefore":"$yesterday","reason":"r"}""")
      )._1,
      400
    )
    assertEquals(
      send(
        "POST",
        "/projects/brand/erasures",
        alice,
        Some(s"""{"subject":"player/x","notBefore":"${yesterday.plusDays(9)}"}""")
      )._1,
      400
    )
    assertEquals(
      send("POST", "/projects/brand/erasures", alice, Some("""{"subject":"a b"}"""))._1,
      400
    )
  }

  test("requests are listed by subject, by state and by correlation id") {
    val correlated = request("player/p5", ""","correlationId":"closure-4411"""")
    val listed = controlPlane.eventually("the correlated request listed")(
      Some(
        readFromString[Vector[ErasureRequest]](
          send("GET", "/projects/brand/erasures?correlation=closure-4411", alice)._2
        )
      ).filter(_.nonEmpty)
    )
    assertEquals(listed.map(_.id), Vector(correlated.id))
    val withdrawn = controlPlane.eventually("the withdrawn request listed")(
      Some(
        readFromString[Vector[ErasureRequest]](
          send("GET", "/projects/brand/erasures?state=withdrawn", alice)._2
        )
      ).filter(_.nonEmpty)
    )
    assert(withdrawn.forall(_.state == ErasureState.Withdrawn))
  }

  test("the project's history says what happened to it, newest first, and nothing personal") {
    customer("p9").call(CustomerEntity.register).invoke("History Person"): Unit
    val asked = request("player/p9")
    swept(asked.id, _.state == ErasureState.Final): Unit
    val history = controlPlane.eventually("the history has the request") {
      val (status, body) = send("GET", "/projects/brand/history", alice)
      assertEquals(status, 200, body)
      Some(readFromString[Vector[ProjectHistoryEntry]](body))
        .filter(_.exists(e => e.erasureId == asked.id && e.kind == "erasure-applied"))
    }
    val ours = history.filter(_.erasureId == asked.id).map(_.kind)
    assertEquals(ours, Vector("erasure-applied", "erasure-requested"))
    assertEquals(history.map(_.at), history.map(_.at).sorted.reverse, "newest first")
    assert(history.size <= ProjectHistory.Limit)
    assert(!send("GET", "/projects/brand/history", alice)._2.contains("History Person"))
  }

  test("the erasure log holds every applied erasure in order, for the keyring to replay") {
    val (status, body) = send("GET", "/erasures/log", alice)
    assertEquals(status, 200, body)
    assert(body.contains("player/p1") && body.contains("\"sequence\":1"), body)
  }
