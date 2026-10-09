package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.TopologyReader
import com.thinkmorestupidless.ankka.operator.ProjectConfig
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

/**
 * `features/cross-project/listing.feature`, offline: the shipped control plane in this JVM, each
 * service's status reported as the operator would, and each one's topology scripted per scenario —
 * what its running instances would say of their routes. Grants are made and read over HTTP as the
 * people the scenarios name.
 *
 * The project's history of grants is its grants listing, which keeps every grant with who made it
 * and who ended it, and when; the control plane has no other history of a project.
 */
class CrossProjectListingFeatures
    extends GherkinSuite("../features/cross-project/listing.feature")
    with LogCapturing:

  override val munitTimeout = 5.minutes

  /** Each service's route handlers, by `project/service`, as its instances would report them. */
  private val routes = AtomicReference(Map.empty[(String, String), Vector[TopologyHandler]])

  private val reader = new TopologyReader:
    def read(projectId: String, service: String) =
      routes.get
        .get((projectId, service))
        .toVector
        .map(handlers =>
          InstanceTopology(s"$service-0", InstanceStatus.Ok, None) -> Some(
            InstanceTopologyDocument(
              TopologyService(service, "0.0.0", s"$service-0", "2026-10-09T10:00:00Z"),
              TopologyWindow(60, "2026-10-09T10:00:00Z", 0),
              Vector(TopologyNode(s"endpoint:$service", "Endpoint", 0, platform = false, handlers)),
              Vector.empty,
              Vector.empty
            )
          )
        )

  private val cp = GrantsHarness(topology = Some(reader))

  private var started                           = false
  private var organizations                     = Set.empty[String]
  private var projects                          = Set.empty[String]
  private var services                          = Set.empty[(String, String)]
  private var serviceProject                    = Map.empty[String, String]
  private var listed: (Int, String)             = (0, "")
  private var held: Vector[ReceivedGrantDetail] = Vector.empty
  private var made: Vector[GrantDetail]         = Vector.empty

  override def beforeAll(): Unit =
    cp.start()
    started = true

  override def afterAll(): Unit = if started then cp.stop()

  override def beforeEach(context: BeforeEach): Unit =
    // Every scenario's grants are its own: the last scenario's are ended first.
    for g <- made if g.state == GrantState.Accepted || g.state == GrantState.Pending do
      cp.send("DELETE", s"/projects/${projectOfGrant(g)}/grants/${g.id}", as = "ada"): Unit
    made = Vector.empty
    listed = (0, "")
    routes.set(Map.empty)

  private var grantProject                   = Map.empty[String, String]
  private def projectOfGrant(g: GrantDetail) = grantProject.getOrElse(g.id, "spinvibe")

  // ── the setting ───────────────────────────────────────────────────────────

  private def organization(id: String, owner: String): Unit =
    if !organizations(id) then
      cp.organization(id, owner)
      organizations += id

  private def project(id: String, organization: String, owner: String): Unit =
    if !projects(id) then
      cp.project(id, organization, owner)
      projects += id

  private def deploy(
      service: String,
      project: String,
      hosting: Option[String] = None,
      grants: Option[String] = Some("mounted")
  ): Unit =
    serviceProject += service -> project
    if !services((service, project)) then
      val web = hosting.contains("web")
      val body =
        if web then
          s"""{"name":"$service","service":{"image":"$service:1","hosting":"web","processPort":8080}}"""
        else s"""{"name":"$service","service":{"image":"$service:1"}}"""
      val (status, answer) = cp.send("PUT", s"/services/$project/$service", Some(body), as = "ada")
      assert(status == 200, s"$status $answer")
      services += ((service, project))
    cp.observe(
      project,
      service,
      ready = 1,
      grants = if hosting.contains("web") then None else grants
    )

  private def script(project: String, service: String, handlers: TopologyHandler*): Unit =
    routes.updateAndGet(m =>
      m.updated((project, service), m.getOrElse((project, service), Vector.empty) ++ handlers)
    ): Unit

  private def route(text: String, grantable: Boolean) =
    TopologyHandler(text, "route", grantable = Option.when(grantable)(true))

  private def grant(
      project: String,
      grantee: String,
      target: String,
      as: String = "ada"
  ): GrantDetail =
    val (status, body) = cp.send(
      "POST",
      s"/projects/$project/grants",
      Some(s"""{"grantee":"$grantee","target":$target}"""),
      as
    )
    assertEquals(status, 200, body)
    val detail = readFromString[GrantDetail](body)
    made :+= detail
    grantProject += detail.id -> project
    detail

  private def routeTarget(route: String, service: String): String =
    val (method, path) = route.trim.span(_ != ' ')
    s"""{"kind":"route","service":"$service","method":"$method","path":"${path.trim}"}"""

  private def topicTarget(topic: String, decrypt: Boolean = false): String =
    s"""{"kind":"topic","topic":"$topic","right":"consume","decrypt":$decrypt}"""

  private def grantsOf(project: String, as: String = "cy"): Vector[GrantDetail] =
    listed = cp.send("GET", s"/projects/$project/grants", as = as)
    assertEquals(listed._1, 200, listed._2)
    readFromString[Vector[GrantDetail]](listed._2)

  private def received(path: String): Vector[ReceivedGrantDetail] =
    cp.eventually(s"the grants held at $path") {
      val (status, body) = cp.send("GET", path, as = "cy")
      Option
        .when(status == 200)(readFromString[Vector[ReceivedGrantDetail]](body))
        .filter(rows => made.forall(g => rows.exists(_.id == g.id) || !path.contains(holderOf(g))))
    }

  private def holderOf(g: GrantDetail): String = g.grantee.text.split('/').head.split(':').last

  // ═══ Given ════════════════════════════════════════════════════════════════

  Given("the projects {string} and {string} of the organization {string}") {
    (a: String, b: String, organizationId: String) =>
      if !organizations(organizationId) then
        organization(organizationId, "ada")
        cp.member("cy", organizationId, owner = "ada")
      project(a, organizationId, "ada")
      project(b, organizationId, "ada")
  }

  Given("{string} is an owner of {string}") { (person: String, organizationId: String) =>
    assertEquals((person, organizationId), ("ada", "eitheror"))
  }

  Given(
    "a deployed service {string} in {string} with an HTTP endpoint whose ACL admits granted callers, with the route {string}"
  ) { (service: String, project: String, text: String) =>
    deploy(service, project)
    script(project, service, route(text, grantable = true))
  }

  Given("a deployed service {string} in {string}")((service: String, project: String) =>
    deploy(service, project)
  )

  Given("{string} has granted the service {string} of {string} the route {string} of {string}") {
    (_: String, grantee: String, granteeProject: String, text: String, service: String) =>
      grant(
        serviceProject(service),
        s"service:$granteeProject/$grantee",
        routeTarget(text, service)
      ): Unit
  }

  Given("{string} has registered {string} as a machine of {string}") {
    (owner: String, name: String, organizationId: String) =>
      val (status, body) =
        cp.send(
          "POST",
          s"/organizations/$organizationId/machines",
          Some(s"""{"name":"$name"}"""),
          as = owner
        )
      assert(status == 200 || status == 409, s"$status $body")
  }

  Given(
    "{string} has granted the registered machine {string} of {string} the route {string} of {string}"
  ) { (_: String, name: String, organizationId: String, text: String, service: String) =>
    grant(
      serviceProject(service),
      s"machine:$organizationId/$name",
      routeTarget(text, service)
    ): Unit
  }

  Given(
    "{string} has granted the registered machine {string} of {string} to consume the topic {string} of {string}"
  ) { (_: String, name: String, organizationId: String, topic: String, project: String) =>
    grant(project, s"machine:$organizationId/$name", topicTarget(topic)): Unit
  }

  Given(
    "{string} has granted the service {string} of {string} to consume the topic {string} of {string}"
  ) { (_: String, grantee: String, granteeProject: String, topic: String, project: String) =>
    grant(project, s"service:$granteeProject/$grantee", topicTarget(topic)): Unit
  }

  Given(
    "{string} has granted the service {string} of {string} to consume the topic {string} of {string}, allowing decryption"
  ) { (_: String, grantee: String, granteeProject: String, topic: String, project: String) =>
    grant(project, s"service:$granteeProject/$grantee", topicTarget(topic, decrypt = true)): Unit
  }

  Given(
    "{string} has granted the service {string} of {string} the right to ask for the erasure of the data subjects of {string}"
  ) { (_: String, grantee: String, granteeProject: String, project: String) =>
    grant(project, s"service:$granteeProject/$grantee", """{"kind":"erasure"}"""): Unit
  }

  // The situations of the outline.

  Given("{string} has no route {string} yet")((service: String, _: String) =>
    assert(serviceProject.contains(service))
  )

  Given("{string} has the route {string} whose ACL admits only the service {string}") {
    (service: String, text: String, _: String) =>
      script(serviceProject(service), service, route(text, grantable = false))
  }

  Given("{string} has a web-hosted service {string}") { (project: String, service: String) =>
    deploy(service, project, hosting = Some("web"))
  }

  Given("{string} was deployed by a platform too old to hand grants to a running instance") {
    (service: String) =>
      cp.observe(serviceProject(service), service, ready = 1, grants = None)
  }

  Given(
    "the installation does not expose its broker, and the topic {string} is declared on {string}"
  ) { (topic: String, project: String) =>
    cp.topic(project, topic, owner = "ada")
  }

  Given("the topic {string} is declared on {string}")((topic: String, project: String) =>
    cp.topic(project, topic, owner = "ada")
  )

  Given("the organization {string} has the project {string} with a deployed service {string}") {
    (organizationId: String, projectId: String, service: String) =>
      organization(organizationId, "bo")
      project(projectId, organizationId, "bo")
      serviceProject += service -> projectId
  }

  Given("{string} has since revoked the grant") { (owner: String) =>
    val g = made.lastOption.getOrElse(fail("no grant was made"))
    assertEquals(
      cp.send("DELETE", s"/projects/${projectOfGrant(g)}/grants/${g.id}", as = owner)._1,
      204
    )
  }

  Given("a person who is not a member of the organization {string} is in") { (_: String) =>
    cp.tokenOf("eve"): Unit
  }

  // ═══ When ═════════════════════════════════════════════════════════════════

  When("a member of {string} reads the grants of {string}") { (_: String, project: String) =>
    grantsOf(project): Unit
  }

  When("a member of {string} reads the grants held by the project {string}") {
    (_: String, project: String) =>
      held = received(s"/projects/$project/grants/received")
  }

  When("a member of {string} reads the grants held by the organization {string}") {
    (_: String, organizationId: String) =>
      held = received(s"/organizations/$organizationId/grants")
  }

  When("that person reads the grants of {string}") { (project: String) =>
    listed = cp.send("GET", s"/projects/$project/grants", as = "eve")
  }

  When("a member of {string} reads the history of {string}") { (_: String, project: String) =>
    grantsOf(project): Unit
  }

  // ═══ Then ═════════════════════════════════════════════════════════════════

  Then(
    "the member is shown the grant to the service {string} of {string} on the route {string} of {string}, granted by {string}, with when it was granted"
  ) { (grantee: String, granteeProject: String, text: String, service: String, owner: String) =>
    val rows   = readFromString[Vector[GrantDetail]](listed._2)
    val (m, p) = text.trim.span(_ != ' ')
    val found = rows.find(g =>
      g.grantee.text == s"service:$granteeProject/$grantee" && g.target.service.contains(service) &&
        g.target.method.contains(m) && g.target.path.contains(p.trim)
    )
    assert(
      found.exists(g => g.granted.by.exists(_.contains(owner)) && g.granted.at.isDefined),
      rows.toString
    )
  }

  Then("the grant is shown as in effect") { () =>
    val g = made.last
    // The status the effect is read from is reported asynchronously: retried until it says so.
    val seen = scala.util.Try(
      cp.eventually("the grant in effect")(
        grantsOf(projectOfGrant(g)).find(_.id == g.id).filter(_.effect == "in effect")
      )
    )
    assert(
      seen.isSuccess,
      s"the grant is shown as ${grantsOf(projectOfGrant(g)).find(_.id == g.id).map(_.effect)}"
    )
  }

  Then("the grant is shown as not in effect, {string}") { (why: String) =>
    val g = made.last
    val shown = cp.eventually(s"the grant shown as $why")(
      grantsOf(projectOfGrant(g)).find(_.id == g.id).filter(_.effect == why)
    )
    assertEquals(shown.effect, why)
  }

  Then(
    "the member is shown the grant to the service {string} of {string} on the route {string} of {string} of {string}, granted by {string}"
  ) {
    (
        grantee: String,
        granteeProject: String,
        text: String,
        _: String,
        granting: String,
        owner: String
    ) =>
      shownHeld(s"service:$granteeProject/$grantee", text, granting, owner)
  }

  Then(
    "the member is shown the grant to the registered machine {string} of {string} on the route {string} of {string} of {string}, granted by {string}"
  ) {
    (
        name: String,
        organizationId: String,
        text: String,
        _: String,
        granting: String,
        owner: String
    ) =>
      shownHeld(s"machine:$organizationId/$name", text, granting, owner)
  }

  private def shownHeld(grantee: String, text: String, granting: String, owner: String): Unit =
    val (m, p) = text.trim.span(_ != ' ')
    assert(
      held.exists(r =>
        r.grantee.text == grantee && r.grantingProject == granting &&
          r.target.method.contains(m) && r.target.path.contains(p.trim) &&
          r.changes.exists(_.by.exists(_.contains(owner)))
      ),
      held.toString
    )

  Then("that person is told that there is no project {string}") { (project: String) =>
    assertEquals(listed._1, 404, listed._2)
    assert(listed._2.contains(project), listed._2)
  }

  Then(
    "the history shows that {string} granted it and that {string} revoked it, each with when it was done"
  ) { (granter: String, revoker: String) =>
    val g = made.last
    val shown =
      readFromString[Vector[GrantDetail]](listed._2).find(_.id == g.id).getOrElse(fail(listed._2))
    assertEquals(shown.state, GrantState.Revoked)
    assert(
      shown.granted.by.exists(_.contains(granter)) && shown.granted.at.isDefined,
      shown.toString
    )
    assert(
      shown.ended.exists(e => e.by.exists(_.contains(revoker)) && e.at.isDefined),
      shown.toString
    )
  }

  Then("nothing in the history holds a credential or a machine token") { () =>
    for word <- Vector("secret", "token", "password", "eyJ") do
      assert(!listed._2.toLowerCase.contains(word.toLowerCase), listed._2)
  }

  Then(
    "the member is shown the grant to consume {string} as allowing decryption, and the erasure grant, each granted by {string}"
  ) { (topic: String, owner: String) =>
    val rows       = grantsOf("spinvibe")
    val decrypting = rows.find(g => g.target.topic.contains(topic) && g.target.decrypt)
    val erasure    = rows.find(_.target.kind == GrantTarget.Erasure)
    assert(decrypting.exists(_.granted.by.exists(_.contains(owner))), rows.toString)
    assert(erasure.exists(_.granted.by.exists(_.contains(owner))), rows.toString)
  }

  Then("the grants held by the project {string} show both") { (project: String) =>
    val rows = received(s"/projects/$project/grants/received")
    assert(rows.exists(r => r.target.decrypt && r.target.kind == GrantTarget.Topic), rows.toString)
    assert(rows.exists(_.target.kind == GrantTarget.Erasure), rows.toString)
  }

  Then(
    "neither grant opens a route of {string}, and nothing of {string} decrypts or erases anything for {string}"
  ) { (project: String, _: String, _: String) =>
    // What reaches the cluster: both are held in the project's resource, and the grants file every
    // service of the project reads keeps neither as a route or a method, so no ACL is opened by them.
    val spec = cp.eventually("the project's resource holding both grants") {
      cp.cluster.project(s"ankka-$project", project).filter(_.grants.size >= 2)
    }
    assert(spec.grants.forall(g => g.kind == "topic" || g.kind == "erasure"), spec.grants.toString)
    val rendered = ProjectConfig.renderGrants(spec)
    assert(rendered.contains("\"decrypt\":true") && rendered.contains("\"erasure\""), rendered)
    assert(!rendered.contains("\"route\"") && !rendered.contains("\"method\""), rendered)
  }
