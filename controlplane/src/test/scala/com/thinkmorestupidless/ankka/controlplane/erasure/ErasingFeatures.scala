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
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.keyring.*
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment
}
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringClient
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * `features/erasure/erasing.feature`, the whole path: a member asks the control plane, its sweeper
 * applies the erasure through a real keyring, and three services of project `brand` — `players`,
 * `wallet` and `engagement`, each a kit of its own whose channel reaches that keyring — apply it to
 * what they hold. Every scenario writes its own pair of data subjects (the feature's ids with the
 * scenario's suffix), because a subject erased in one scenario stays erased for every later one.
 */
class ErasingFeatures extends GherkinSuite("../features/erasure/erasing.feature") with LogCapturing:

  override val munitTimeout = 4.minutes

  private lazy val identity = TestIdentity()
  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)

  private var keyringKit: AnkkaTestKit   = null
  private var controlPlane: AnkkaTestKit = null
  private var players: AnkkaTestKit      = null
  private var wallet: AnkkaTestKit       = null
  private var engagement: AnkkaTestKit   = null
  private var sweeper: ErasureSweeper    = null
  private var base                       = ""
  private val http                       = HttpClient.newHttpClient()

  private def playersComponents(viewVersion: Int): Seq[ComponentDescriptor] =
    Seq(PlayerAccount.descriptor, PlayerRows(viewVersion).descriptor)

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
    val channel = URI.create(keyringUrl.replaceFirst("^http", "ws") + "/channel")
    def service(name: String, descriptors: Seq[ComponentDescriptor]) =
      AnkkaTestKit.start(
        descriptors,
        Seq(ProjectionRuntime()),
        serviceIdentity = ServiceIdentity.deployed("brand", name),
        keyring = None,
        configure = b =>
          val withKeyring = b.withKeyring(KeyringClient(channel, None))
          if name == "players" then withKeyring.withErasureHandler(PlayersHandler.handler)
          else withKeyring
      )
    players = service("players", playersComponents(1))
    wallet = service("wallet", Seq(Wallet.descriptor))
    engagement = service("engagement", Seq(Engagement.descriptor))
    assertEquals(send("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(
      send("POST", "/projects/brand", Some("""{"name":"Brand","organizationId":"acme"}"""))._1,
      204
    )

  override def afterAll(): Unit =
    Seq(players, wallet, engagement, controlPlane, keyringKit).filter(_ != null).foreach(_.stop())
    identity.stop()

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofSeconds(30))
      .header("Authorization", s"Bearer $alice")
    body.fold(builder.method(method, HttpRequest.BodyPublishers.noBody()))(json =>
      builder
        .header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(json))
    ): Unit
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  // ── This scenario's data subjects, and what was written for them ──

  /** The feature's subject, made this scenario's own. */
  private def subject(named: String): String =
    s"$named-${Integer.toHexString(scenarioId.hashCode)}"

  private def email(s: String)  = s"${s.replace('/', '.')}@example.com"
  private def holder(s: String) = s"Holder of $s"
  private def seen(s: String)   = s"Seen as $s"

  private def write(named: String): Unit =
    val s = subject(named)
    players.componentClient
      .forEventSourcedEntity(EntityId(s))
      .call(PlayerAccount.open)
      .invoke(email(s)): Unit
    wallet.componentClient
      .forEventSourcedEntity(EntityId(s))
      .call(Wallet.fund)
      .invoke(holder(s)): Unit
    engagement.componentClient
      .forKeyValueEntity(EntityId(s))
      .call(Engagement.visit)
      .invoke(seen(s)): Unit

  /** Every personal field of `s` as each service reads it: `None` where it reads as erased. */
  private def personalFields(s: String): Vector[Option[String]] =
    val account =
      players.componentClient.forEventSourcedEntity(EntityId(s)).call(PlayerAccount.read).invoke()
    val balance =
      wallet.componentClient.forEventSourcedEntity(EntityId(s)).call(Wallet.read).invoke()
    val visits =
      engagement.componentClient.forKeyValueEntity(EntityId(s)).call(Engagement.read).invoke()
    Vector(account.email, balance.holder, visits.name).map(_.flatMap(_.toOption))

  private def row(s: String, version: Int): Option[PlayerRow] =
    players.service.viewClient.forView(PlayerRows(version)).get(s)

  // ── Asking ──

  private var asked: ErasureRequest = null

  private def ask(s: String): ErasureRequest =
    val (status, body) =
      send("POST", "/projects/brand/erasures", Some(s"""{"subject":"$s"}"""))
    assert(status == 201 || status == 200, s"$status $body")
    readFromString[ErasureRequest](body)

  private def read(id: String): ErasureRequest =
    readFromString[ErasureRequest](send("GET", s"/projects/brand/erasures/$id")._2)

  private def sweptToFinal(id: String): ErasureRequest =
    players.eventually(s"erasure $id final", 60.seconds) {
      sweeper.sweep()
      Some(read(id)).filter(r => r.state == ErasureState.Final && r.completions.size >= 3)
    }

  private def erase(named: String): Unit =
    asked = sweptToFinal(ask(subject(named)).id)

  // ── Background ──

  Given("a project {string} with the services {string}, {string} and {string}") {
    (_: String, _: String, _: String, _: String) => ()
  }
  Given(
    "each of them has recorded personal fields of the data subject {string} and of the data subject {string}"
  ) { (first: String, second: String) =>
    write(first)
    write(second)
  }
  Given("each of them has recorded fields of {string} that are not personal") { (_: String) =>
    () // the currency, the amount and the visits, written beside each personal field
  }

  // ── Asking for one and applying it ──

  When("a member asks for an erasure request for {string} in {string} with no not-before date") {
    (named: String, _: String) =>
      asked = ask(subject(named))
      assertEquals(asked.state, ErasureState.Applying)
      asked = sweptToFinal(asked.id)
  }
  Then("the keyring holds no subject key for {string}") { (named: String) =>
    val key = keyringKit.componentClient
      .forKeyValueEntity(EntityId(s"brand/${subject(named)}"))
      .call(SubjectKeyEntity.state)
      .invoke()
    assertEquals(key.wrapped, None)
    assert(key.destroyedAt.isDefined, key.toString)
  }
  Then(
    "within {int} seconds every service of {string} reads every personal field of {string} as erased"
  ) { (seconds: Int, _: String, named: String) =>
    players.eventually("every field reads as erased", seconds.seconds)(
      Option.when(personalFields(subject(named)).forall(_.isEmpty))(())
    )
  }
  Then("every service of {string} reads every personal field of {string} as it was written") {
    (_: String, named: String) =>
      val s = subject(named)
      assertEquals(personalFields(s), Vector(Some(email(s)), Some(holder(s)), Some(seen(s))))
  }

  Given("{string} has been erased in {string}")((named: String, _: String) => erase(named))

  // ── Recovery ──

  When("an entity of {string} holding events of {string} is recovered") { (_: String, _: String) =>
    wallet.restartService()
  }
  Then("the entity is recovered") { () =>
    wallet.componentClient
      .forEventSourcedEntity(EntityId(subject("player/8c1f")))
      .call(Wallet.read)
      .invoke(): Unit
  }
  Then("its state holds erased in each personal field of {string}") { (named: String) =>
    val balance = wallet.componentClient
      .forEventSourcedEntity(EntityId(subject(named)))
      .call(Wallet.read)
      .invoke()
    assert(balance.holder.exists(_.isErased), balance.toString)
  }
  Then("every field of its state that is not personal is as it was written") { () =>
    val balance = wallet.componentClient
      .forEventSourcedEntity(EntityId(subject("player/8c1f")))
      .call(Wallet.read)
      .invoke()
    assertEquals(balance.amount, 100L)
  }

  // ── Rebuild ──

  Given("a view of {string} holding rows with personal fields of {string}") {
    (_: String, named: String) =>
      players.eventually("the row")(row(subject(named), 1)): Unit
  }
  When("the view is declared at a higher version and rebuilt") { () =>
    players.restartService(descriptors = playersComponents(2))
  }
  Then("the rebuild finishes") { () =>
    // The other subject's row, read again from its events, is back: the source was read through.
    players.eventually("the rebuilt row of the other subject")(
      row(subject("player/9d2e"), 2).filter(r =>
        r.email.toOption.contains(email(subject("player/9d2e")))
      )
    ): Unit
  }
  Then("its rows hold erased in each personal field of {string}") { (named: String) =>
    val rebuilt = players.eventually("the rebuilt row")(row(subject(named), 2))
    assert(rebuilt.email.isErased, rebuilt.toString)
    assertEquals(rebuilt.currency, "GBP")
  }

  // ── Refused write ──

  private var refusal: Option[CommandError] = None
  private var before                        = 0

  When("a command of {string} records an event with a personal field of {string}") {
    (_: String, named: String) =>
      val s = subject(named)
      before = journalled(s)
      refusal =
        try
          players.componentClient
            .forEventSourcedEntity(EntityId(s))
            .call(PlayerAccount.open)
            .invoke("again@example.com")
          None
        catch case e: CommandError => Some(e)
  }
  Then("the command is refused") { () =>
    assert(refusal.isDefined, "the command was not refused")
  }
  Then("the refusal names {string} as erased") { (named: String) =>
    val message = refusal.map(_.message).getOrElse("")
    assert(message.contains(subject(named)) && message.contains("erased"), message)
  }
  Then("nothing is recorded") { () =>
    assertEquals(journalled(subject("player/8c1f")), before)
  }

  /** How many events `players` has journalled for the account of `s`. */
  private def journalled(s: String): Int =
    given org.apache.pekko.actor.typed.ActorSystem[?] = players.service.system
    scala.concurrent.Await
      .result(
        Database().query(
          SqlFragment.raw(
            s"SELECT count(*) FROM event_journal WHERE persistence_id = 'player-accounts|$s'"
          )
        )(r => r.get(0, classOf[java.lang.Long]).intValue),
        10.seconds
      )
      .head

  // ── The request and its certificate ──

  When("a member reads the erasure request for {string} in {string}") {
    (named: String, _: String) =>
      asked = read(asked.id)
      assertEquals(asked.subject, subject(named))
  }
  Then("the erasure request is applied") { () =>
    assert(asked.state == ErasureState.Final || asked.state == ErasureState.Applied, asked.toString)
    assert(asked.appliedAt.isDefined, asked.toString)
  }
  Then("it says when the subject key was destroyed") { () =>
    assert(asked.keyDestroyedAt.isDefined, asked.toString)
  }
  Then("it names each service of {string} with the time it completed") { (_: String) =>
    assertEquals(asked.completions.map(_.service).toSet, Set("players", "wallet", "engagement"))
  }
  Then("it says when the erasure became final") { () =>
    assert(asked.finalAt.isDefined, asked.toString)
  }

  When("a member reads every table of every service of {string}")((_: String) => ())
  Then(
    "no table holds a value from which a personal field of {string} can be recovered without the destroyed subject key"
  ) { (named: String) =>
    val s = subject(named)
    Seq(players, wallet, engagement).foreach(_.assertNoPersonalValue(email(s), holder(s), seen(s)))
  }

  private var certificate = ""

  When("a member fetches the erasure certificate for {string} in {string}") {
    (_: String, _: String) =>
      val (status, body) = send("GET", s"/projects/brand/erasures/${asked.id}/certificate")
      assertEquals(status, 200, body)
      certificate = body
      asked = readFromString[ErasureCertificate](body).request
  }
  Then(
    "the erasure certificate names the erasure request, the data subject {string} and who asked for it"
  ) { (named: String) =>
    assert(certificate.contains(asked.id), certificate)
    assert(certificate.contains(subject(named)), certificate)
    assertEquals(asked.askedBy.subject, "alice")
  }
  Then("it holds no personal field") { () =>
    val s = subject("player/8c1f")
    Seq(email(s), holder(s), seen(s)).foreach(v => assert(!certificate.contains(v), certificate))
  }

  // ── The erasure handler ──

  private var handled = 0

  Given("{string} has an erasure handler") { (_: String) =>
    handled = PlayersHandler.runs.size
  }
  When("an erasure request for {string} in {string} is applied") { (named: String, _: String) =>
    erase(named)
  }
  When("it is applied again later") { () =>
    assertEquals(send("POST", s"/projects/brand/erasures/${asked.id}/reapply")._1, 200)
    players.eventually("the handler ran again")(
      Option.when(PlayersHandler.runs.size >= handled + 2)(())
    ): Unit
  }
  Then("the erasure handler of {string} ran for {string} each time") { (_: String, named: String) =>
    val runs = PlayersHandler.runs.asScala.toVector.drop(handled)
    assertEquals(runs.map(_.subject), Vector(subject(named), subject(named)))
    assertEquals(runs.map(_.reapply), Vector(false, true))
  }
  Then("the second run, with nothing of its own to do, completed at once") { () =>
    val second = PlayersHandler.runs.asScala.toVector.drop(handled)(1)
    assert(second.nanos < 1.second.toNanos, second.toString)
  }
