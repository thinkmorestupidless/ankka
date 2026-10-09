package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.operator.{
  BrokerSettings,
  MachineDefaults,
  MachineRendering,
  ProjectConfig
}
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import scala.concurrent.duration.DurationInt

/**
 * `features/cross-project/acceptance.feature`, offline: the shipped control plane in this JVM, two
 * organizations and their people. What the broker allows a machine is what the operator renders for
 * it from what the control plane wrote to the cluster — the granting project's resource and the
 * machine's — so "may read" is the broker user's literal entry, read before the broker would hold
 * it. What a service is served is what its project's grants file, rendered the same way, admits.
 *
 * A project's history of grants is its grants listing, which keeps each with who made, answered and
 * ended it and when; an organization's is its listing of what it holds, with every change.
 */
class CrossProjectAcceptanceFeatures
    extends GherkinSuite("../features/cross-project/acceptance.feature")
    with LogCapturing:

  override val munitTimeout = 5.minutes

  private val cp = GrantsHarness()

  private val broker =
    BrokerSettings("ankka-kafka-bootstrap.ankka-broker.svc:9093", "ankka-broker", "ankka")

  private var started                                = false
  private var organizations                          = Set.empty[String]
  private var projects                               = Set.empty[String]
  private var topics                                 = Set.empty[(String, String)]
  private var members                                = Set.empty[(String, String)]
  private var machines                               = Set.empty[String]
  private var deployToken                            = Map.empty[String, String]
  private var current: Option[(String, GrantDetail)] = None
  private var answered: (Int, String)                = (0, "")

  override def beforeAll(): Unit =
    cp.start()
    started = true

  override def afterAll(): Unit = if started then cp.stop()

  override def beforeEach(context: BeforeEach): Unit =
    current.foreach((p, g) => cp.send("DELETE", s"/projects/$p/grants/${g.id}", as = "ada"): Unit)
    current = None
    answered = (0, "")

  // ── helpers ───────────────────────────────────────────────────────────────

  private def grant: (String, GrantDetail) = current.getOrElse(fail("no grant was made"))

  private def make(project: String, grantee: String, target: String): GrantDetail =
    val (status, body) =
      cp.send(
        "POST",
        s"/projects/$project/grants",
        Some(s"""{"grantee":"$grantee","target":$target}"""),
        as = "ada"
      )
    assertEquals(status, 200, body)
    val g = readFromString[GrantDetail](body)
    current = Some(project -> g)
    g

  private def consume(topic: String) = s"""{"kind":"topic","topic":"$topic","right":"consume"}"""

  private def listed(project: String): Vector[GrantDetail] =
    val (status, body) = cp.send("GET", s"/projects/$project/grants", as = "ada")
    assertEquals(status, 200, body)
    readFromString[Vector[GrantDetail]](body)

  private def shown(project: String): GrantDetail =
    listed(project)
      .find(_.id == grant._2.id)
      .getOrElse(fail(s"the grant is not listed by $project"))

  private def offered(organization: String): Vector[ReceivedGrantDetail] =
    val (status, body) = cp.send("GET", s"/organizations/$organization/grants", as = "bo")
    assertEquals(status, 200, body)
    readFromString[Vector[ReceivedGrantDetail]](body)

  private def offeredNow(organization: String): ReceivedGrantDetail =
    cp.eventually(s"the grant offered to $organization") {
      offered(organization).find(_.id == grant._2.id)
    }

  private def answer(organization: String, verb: String, as: String): (Int, String) =
    offeredNow(organization): Unit
    cp.send("POST", s"/organizations/$organization/grants/${grant._2.id}/$verb", as = as)

  /** What the broker would allow the machine: its user, rendered from what the cluster holds. */
  private def mayRead(machine: String, organization: String, topic: String): Boolean =
    val (granting, _) = grant
    val projectSpec   = cp.cluster.project(s"ankka-$granting", granting).toVector
    val machineSpec   = cp.cluster.machine(s"$organization.$machine")
    val user = MachineRendering.user(
      organization,
      machine,
      machineSpec,
      MachineRendering.granted(projectSpec, organization, machine),
      MachineDefaults(),
      broker
    )
    user.getSpec.authorization.acls.exists(r =>
      r.resource.`type` == "topic" && r.resource.name == topic && r.operations.contains("Read")
    )

  // ═══ Given ════════════════════════════════════════════════════════════════

  Given("an organization {string} whose owner is {string}, with the project {string}") {
    (organization: String, owner: String, project: String) =>
      if !organizations(organization) then
        cp.organization(organization, owner)
        organizations += organization
      if !projects(project) then
        cp.project(project, organization, owner)
        projects += project
  }

  Given("the topic {string} is declared on {string}") { (topic: String, project: String) =>
    if !topics((project, topic)) then
      cp.topic(project, topic, owner = "ada")
      topics += ((project, topic))
  }

  Given("an organization {string} whose owner is {string}") {
    (organization: String, owner: String) =>
      if !organizations(organization) then
        cp.organization(organization, owner)
        organizations += organization
  }

  Given("{string} has registered {string} as a machine of {string}") {
    (owner: String, name: String, organization: String) =>
      if !machines(name) then
        val (status, body) =
          cp.send(
            "POST",
            s"/organizations/$organization/machines",
            Some(s"""{"name":"$name"}"""),
            as = owner
          )
        assert(status == 200 || status == 409, s"$status $body")
        machines += name
  }

  Given(
    "{string} has granted the registered machine {string} of {string} to consume the topic {string} of {string}"
  ) { (_: String, name: String, organization: String, topic: String, project: String) =>
    make(project, s"machine:$organization/$name", consume(topic)): Unit
  }

  Given(
    "the grant is offered to {string}, naming the project {string} of {string} and the topic {string}"
  ) { (organization: String, project: String, granting: String, topic: String) =>
    val g = offeredNow(organization)
    assertEquals(
      (g.grantingProject, g.grantingOrganization, g.target.topic),
      (project, granting, Some(topic))
    )
    assertEquals(g.state, GrantState.Pending)
  }

  Given("{string} has accepted the grant") { (owner: String) =>
    val (status, body) = answer("affiliates", "accept", owner)
    assertEquals(status, 204, body)
  }

  Given("{string} has since revoked the grant") { (owner: String) =>
    val (p, g) = grant
    assertEquals(cp.send("DELETE", s"/projects/$p/grants/${g.id}", as = owner)._1, 204)
  }

  Given("{string} is a member of {string}") { (person: String, organization: String) =>
    if !members((person, organization)) then
      cp.member(person, organization, owner = "bo")
      members += ((person, organization))
  }

  Given("a deploy token of {string}") { (organization: String) =>
    if !deployToken.contains(organization) then
      deployToken += organization -> cp.deployToken(organization, owner = "bo")
  }

  Given("the project {string} of {string}") { (project: String, organization: String) =>
    if !projects(project) then
      cp.project(project, organization, "ada")
      projects += project
  }

  Given(
    "a deployed service {string} in {string} with an HTTP endpoint whose ACL admits granted callers, with the route {string}"
  ) { (_: String, _: String, _: String) =>
    // The grants file the service reads is what is checked; whether its instances admit by it is
    // GrantedCallerSuite's and the cluster suites'.
    ()
  }

  Given("a deployed service {string} in {string}")((_: String, _: String) => ())

  // ═══ When ═════════════════════════════════════════════════════════════════

  When(
    "{string} grants the registered machine {string} of {string} to consume the topic {string} of {string}"
  ) { (_: String, name: String, organization: String, topic: String, project: String) =>
    make(project, s"machine:$organization/$name", consume(topic)): Unit
  }

  When("{string} accepts the grant offered to {string} by {string}") {
    (owner: String, organization: String, _: String) =>
      answered = answer(organization, "accept", owner)
  }

  When("{string} declines the grant offered to {string} by {string}") {
    (owner: String, organization: String, _: String) =>
      answered = answer(organization, "decline", owner)
      assertEquals(answered._1, 204, answered._2)
  }

  When("{string} withdraws the grant") { (owner: String) =>
    val (p, g) = grant
    assertEquals(cp.send("DELETE", s"/projects/$p/grants/${g.id}", as = owner)._1, 204)
  }

  When("{string} relinquishes the grant held by {string} from {string}") {
    (owner: String, organization: String, _: String) =>
      answered = answer(organization, "relinquish", owner)
      assertEquals(answered._1, 204, answered._2)
  }

  When("{string} revokes the grant") { (owner: String) =>
    val (p, g) = grant
    assertEquals(cp.send("DELETE", s"/projects/$p/grants/${g.id}", as = owner)._1, 204)
  }

  When("a machine holding the deploy token accepts the grant offered to {string} by {string}") {
    (organization: String, _: String) =>
      offeredNow(organization): Unit
      answered = cp.send(
        "POST",
        s"/organizations/$organization/grants/${grant._2.id}/accept",
        as = deployToken.getOrElse(organization, fail("no deploy token"))
      )
  }

  When("{string} grants the service {string} of {string} the route {string} of {string}") {
    (_: String, service: String, project: String, route: String, target: String) =>
      val (method, path) = route.trim.span(_ != ' ')
      make(
        "spinvibe",
        s"service:$project/$service",
        s"""{"kind":"route","service":"$target","method":"$method","path":"${path.trim}"}"""
      ): Unit
  }

  When("the history of {string} and the history of {string} are read")((_: String, _: String) => ())

  When("{string} deletes the registered machine {string}") { (owner: String, name: String) =>
    assertEquals(cp.send("DELETE", s"/organizations/affiliates/machines/$name", as = owner)._1, 204)
  }

  // ═══ Then ═════════════════════════════════════════════════════════════════

  Then("the grant is shown as {string} in the grants of {string}") {
    (state: String, project: String) =>
      cp.eventually(s"the grant shown as $state") {
        Option.when(shown(project).state.word == state)(())
      }
  }

  Then("the credential of {string} on the broker may not read {string}") {
    (machine: String, topic: String) =>
      cp.eventually(s"$machine's user not reading $topic") {
        Option.when(!mayRead(machine, "affiliates", topic))(())
      }
  }

  Then("the credential of {string} on the broker may read {string}") {
    (machine: String, topic: String) =>
      cp.eventually(s"$machine's user reading $topic") {
        Option.when(mayRead(machine, "affiliates", topic))(())
      }
  }

  Then("within {string} seconds the grant is in effect") { (_: String) =>
    cp.eventually("the grant in effect") {
      Option.when(
        shown(grant._1).effect == "broker not exposed" || shown(grant._1).effect == "in effect"
      )(())
    }
    assertEquals(shown(grant._1).state, GrantState.Accepted)
  }

  Then("within {string} seconds the credential of {string} on the broker may not read {string}") {
    (_: String, machine: String, topic: String) =>
      cp.eventually(s"$machine's user not reading $topic") {
        Option.when(!mayRead(machine, "affiliates", topic))(())
      }
  }

  Then("the grant is no longer offered to {string}") { (organization: String) =>
    cp.eventually(s"the grant no longer live for $organization") {
      offered(organization).find(_.id == grant._2.id).filter(!_.state.live)
    }: Unit
  }

  Then("nobody of {string} acted") { (organization: String) =>
    val g      = shown(grant._1)
    val actors = (Vector(g.granted) ++ g.answered ++ g.ended).flatMap(_.by)
    val (people, other) =
      if organization == "eitheror" then (Set("ada"), "bo") else (Set("bo"), "ada")
    // The grant was ended by the other side alone.
    val after = g.ended.toVector.flatMap(_.by)
    assert(!after.exists(by => people.exists(by.contains)), s"$organization acted: $actors")
    assert(after.exists(_.contains(other)), s"$other did not act: $actors")
  }

  Then("a machine holding the deploy token is refused") { () =>
    assertEquals(answered._1, 403, answered._2)
  }

  Then("{string} is refused") { (_: String) =>
    assertEquals(answered._1, 403, answered._2)
  }

  Then("the grant is still {string}") { (state: String) =>
    assertEquals(shown(grant._1).state.word, state)
  }

  Then("the grant is shown as {string} in the grants of {string}, with nobody having accepted it") {
    (state: String, project: String) =>
      val g = shown(project)
      assertEquals(g.state.word, state)
      assertEquals(g.answered, None)
  }

  Then("within {string} seconds {string} is served that route") { (_: String, service: String) =>
    // The grants file every service of the project reads names the service on the route.
    val spec = cp.eventually("the project's resource holding the grant") {
      cp.cluster.project("ankka-spinvibe", "spinvibe").filter(_.grants.exists(_.id == grant._2.id))
    }
    assert(ProjectConfig.renderGrants(spec).contains(s"service:payments/$service"))
  }

  Then(
    "each shows that {string} granted it, that {string} accepted it and that {string} revoked it, each with when it was done"
  ) { (granter: String, accepter: String, revoker: String) =>
    val g = shown(grant._1)
    assert(g.granted.by.exists(_.contains(granter)) && g.granted.at.isDefined, g.toString)
    assert(g.answered.exists(a => a.by.exists(_.contains(accepter)) && a.at.isDefined), g.toString)
    assert(g.ended.exists(e => e.by.exists(_.contains(revoker)) && e.at.isDefined), g.toString)
    val held = cp.eventually("the organization's record of every change") {
      offered("affiliates").find(r => r.id == grant._2.id && r.changes.size >= 3)
    }
    val changes = held.changes.map(c => c.change -> c.by.getOrElse(""))
    assert(
      changes.exists((c, by) => c == GrantChange.Offered && by.contains(granter)),
      changes.toString
    )
    assert(
      changes.exists((c, by) => c == GrantChange.Accepted && by.contains(accepter)),
      changes.toString
    )
    assert(
      changes.exists((c, by) => c == GrantChange.Revoked && by.contains(revoker)),
      changes.toString
    )
    assert(held.changes.forall(_.at.isDefined), held.toString)
  }

  Then("nothing in either history holds a client secret or a machine token") { () =>
    val texts = Vector(
      cp.send("GET", s"/projects/${grant._1}/grants", as = "ada")._2,
      cp.send("GET", "/organizations/affiliates/grants", as = "bo")._2
    )
    for text <- texts; word <- Vector("secret", "eyJ", "access_token") do
      assert(!text.contains(word), text)
  }

  Then(
    "the history of {string} and the history of {string} each show that the grant lapsed when {string} deleted {string}, with when it was done"
  ) { (_: String, organization: String, deleter: String, _: String) =>
    val g = shown(grant._1)
    assert(g.ended.exists(e => e.by.exists(_.contains(deleter)) && e.at.isDefined), g.toString)
    val held = cp.eventually("the lapse recorded on the organization") {
      offered(organization).find(r =>
        r.id == grant._2.id && r.changes.exists(_.change == GrantChange.Lapsed)
      )
    }
    assert(
      held.changes.exists(c =>
        c.change == GrantChange.Lapsed && c.by.exists(_.contains(deleter)) && c.at.isDefined
      ),
      held.toString
    )
  }

  Then(
    "when {string} registers {string} as a machine of {string} again, the new registered machine holds no grant"
  ) { (owner: String, name: String, organization: String) =>
    val (status, body) =
      cp.send(
        "POST",
        s"/organizations/$organization/machines",
        Some(s"""{"name":"$name"}"""),
        as = owner
      )
    assertEquals(status, 200, body)
    assert(!mayRead(name, organization, s"${grant._1}.affiliates.attribution"))
    assert(
      listed(grant._1).forall(g =>
        g.grantee.text != s"machine:$organization/$name" || !g.state.live
      )
    )
  }
