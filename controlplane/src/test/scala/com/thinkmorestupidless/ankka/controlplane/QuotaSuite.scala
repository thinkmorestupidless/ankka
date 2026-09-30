package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.controlplane.api.{OrganizationSummary, Quota, Usage}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.OrganizationEntity
import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.controlplane.domain.RecordService
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Organization quotas over real HTTP (feature 015): a platform administrator caps an organization,
 * the control plane refuses at the door what would exceed the cap, and usage is exactly what exists
 * — through applies, re-applies, pauses, deletes, a lowered quota and a restart.
 *
 * Cases run in order and share one organization, so each reads the usage it expects to find.
 */
class QuotaSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private lazy val identity         = TestIdentity()
  private var testKit: AnkkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private lazy val alice = identity.token("alice", Some("alice@example.test"), expiresIn = 2.hours)
  private lazy val bob   = identity.token("bob", Some("bob@example.test"), expiresIn = 2.hours)
  private lazy val carol = identity.token(
    "carol",
    Some("carol@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 2.hours
  )

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        DeployConfig.default.copy(baseDomain = Some("example.test")),
        auth = Some(identity.config())
      )*
    )
    testKit = AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), server))
    rebind()

  // An ephemeral port is bound afresh by every start, a restart included.
  private def rebind(): Unit =
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit =
    if testKit != null then
      testKit.stop()
      identity.stop()

  private def send(
      method: String,
      path: String,
      token: String,
      body: Option[String] = None
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    builder.header("Authorization", s"Bearer $token")
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def eventually(description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[String]
  ): String =
    val deadline             = System.nanoTime() + within.toNanos
    var last: Option[String] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(200)
    last.getOrElse(fail(s"$description did not happen within $within"))

  private def descriptor(name: String, instances: Int = 1) =
    s"""{"name":"$name","service":{"image":"cart:1.0",""" +
      s""""resources":{"autoscaling":{"minInstances":$instances,"maxInstances":10}}}}"""

  private def createProject(id: String, organization: String, as: String = alice) =
    send("POST", s"/projects/$id", as, Some(s"""{"name":"$id","organizationId":"$organization"}"""))

  private def apply(project: String, name: String, instances: Int = 1, as: String = alice) =
    send("PUT", s"/services/$project/$name", as, Some(descriptor(name, instances)))

  private def summary(organization: String, as: String = alice): OrganizationSummary =
    val (status, body) = send("GET", s"/organizations/$organization", as)
    assertEquals(status, 200, body)
    readFromString[OrganizationSummary](body)

  private def usage(organization: String = "acme"): Usage = summary(organization).usage

  private def generation(project: String, name: String): Long =
    val (status, body) = send("GET", s"/services/$project/$name", alice)
    assertEquals(status, 200, body)
    val marker = "\"generation\":"
    val from   = body.indexOf(marker) + marker.length
    body.substring(from, body.indexOf(',', from)).toLong

  test("1. with no quota nothing is refused, and usage is exact anyway") {
    assertEquals(send("POST", "/organizations/acme", alice, Some("""{"name":"Acme"}"""))._1, 204)
    assertEquals(createProject("p1", "acme")._1, 204)
    assertEquals(apply("p1", "a", instances = 2)._1, 200)
    val read = summary("acme")
    assertEquals(read.quota, None)
    assertEquals(read.usage, Usage(projects = 1, services = 1, instances = 2))
  }

  test("2. only a platform administrator sets or clears a quota, and a bad one is refused") {
    val body = Some("""{"projects":2,"services":3,"instances":4}""")
    assertEquals(send("PUT", "/organizations/acme/quota", alice, body)._1, 403)
    assertEquals(send("DELETE", "/organizations/acme/quota", alice)._1, 403)
    assertEquals(send("PUT", "/organizations/acme/quota", bob, body)._1, 403)
    assertEquals(summary("acme").quota, None, "unchanged by the refusals")

    val negative = send("PUT", "/organizations/acme/quota", carol, Some("""{"projects":-1}"""))
    assertEquals(negative._1, 400, negative._2)
    assert(negative._2.contains("cannot be negative"), negative._2)
    val empty = send("PUT", "/organizations/acme/quota", carol, Some("{}"))
    assertEquals(empty._1, 400, empty._2)
    assert(empty._2.contains("clear the quota instead"), empty._2)

    assertEquals(send("PUT", "/organizations/acme/quota", carol, body)._1, 204)
    assertEquals(summary("acme").quota, Some(Quota(Some(2), Some(3), Some(4))))
    assertEquals(summary("acme", as = carol).quota, Some(Quota(Some(2), Some(3), Some(4))))
  }

  test("3. the third project is refused naming the quota and the count; nothing is created") {
    assertEquals(createProject("p2", "acme")._1, 204)
    val (status, body) = createProject("p3", "acme")
    assertEquals(status, 409, body)
    assert(
      body.contains("organization 'acme' has reached its quota of 2 project(s) (2 in use)"),
      body
    )
    assertEquals(send("GET", "/projects/p3", alice)._1, 404)
    assertEquals(usage().projects, 2)
  }

  test(
    "4. a fourth service is refused; instances are checked on the increase, and the refusal keeps nothing"
  ) {
    assertEquals(apply("p2", "b", instances = 1)._1, 200)
    assertEquals(usage(), Usage(2, 2, 3))

    val (status, body) = apply("p2", "c", instances = 2)
    assertEquals(status, 409, body)
    assert(
      body.contains(
        "applying 'p2/c' with 2 instance(s) would take organization 'acme' to 5 instances, " +
          "over its quota of 4 (3 in use)"
      ),
      body
    )
    assertEquals(send("GET", "/services/p2/c", alice)._1, 404, "not created")
    assertEquals(usage(), Usage(2, 2, 3))

    assertEquals(apply("p2", "c", instances = 1)._1, 200)
    assertEquals(usage(), Usage(2, 3, 4))
    val fourth = apply("p2", "d", instances = 1)
    assertEquals(fourth._1, 409, fourth._2)
    assert(fourth._2.contains("quota of 3 service(s) (3 in use)"), fourth._2)
  }

  test(
    "5. a re-apply with fewer instances frees them; with more it is refused and changes nothing"
  ) {
    assertEquals(apply("p1", "a", instances = 1)._1, 200)
    assertEquals(usage(), Usage(2, 3, 3))
    val before         = generation("p1", "a")
    val (status, body) = apply("p1", "a", instances = 3)
    assertEquals(status, 409, body)
    assert(body.contains("over its quota of 4 (3 in use)"), body)
    assertEquals(generation("p1", "a"), before, "the previous descriptor stays in force")
    assertEquals(usage(), Usage(2, 3, 3))
    // Unchanged is accepted: it needs no new capacity.
    assertEquals(apply("p1", "a", instances = 1)._1, 200)
    assertEquals(usage(), Usage(2, 3, 3))
  }

  test("6. an invalid descriptor is refused before the organization is asked") {
    val (status, body) =
      send("PUT", "/services/p1/a", alice, Some("""{"name":"zz","service":{"image":"cart:1.0"}}"""))
    assertEquals(status, 400, body)
    assert(body.contains("names service 'zz'"), body)
    val bad = send("PUT", "/services/p1/a", alice, Some("""{"name":"a","service":{"image":""}}"""))
    assertEquals(bad._1, 400, bad._2)
    assertEquals(usage(), Usage(2, 3, 3))
  }

  test("7. pausing changes nothing, and neither does disabling the organization") {
    assertEquals(send("POST", "/services/p1/a/pause", alice)._1, 200)
    assertEquals(usage(), Usage(2, 3, 3))
    assertEquals(send("POST", "/organizations/acme/disable", carol)._1, 204)
    assertEquals(summary("acme", as = carol).usage, Usage(2, 3, 3))
    // The quota can still be changed while disabled — an administrator's act, as disabling was.
    assertEquals(
      send(
        "PUT",
        "/organizations/acme/quota",
        carol,
        Some("""{"projects":2,"services":3,"instances":5}""")
      )._1,
      204
    )
    assertEquals(send("POST", "/organizations/acme/enable", carol)._1, 204)
    assertEquals(send("POST", "/services/p1/a/resume", alice)._1, 200)
    assertEquals(summary("acme").quota, Some(Quota(Some(2), Some(3), Some(5))))
  }

  test("8. usage and the quota survive a restart") {
    testKit.restartService()
    rebind()
    assertEquals(summary("acme").quota, Some(Quota(Some(2), Some(3), Some(5))))
    assertEquals(usage(), Usage(2, 3, 3))
  }

  test("9. lowering a quota below usage stops nothing and refuses only what is new") {
    val generations = Vector(("p1", "a"), ("p2", "b"), ("p2", "c")).map((p, n) => generation(p, n))
    assertEquals(
      send("PUT", "/organizations/acme/quota", carol, Some("""{"services":2}"""))._1,
      204
    )
    assertEquals(
      Vector(("p1", "a"), ("p2", "b"), ("p2", "c")).map((p, n) => generation(p, n)),
      generations,
      "every service is untouched"
    )
    assertEquals(usage(), Usage(2, 3, 3))

    val (status, body) = apply("p2", "d")
    assertEquals(status, 409, body)
    assert(body.contains("quota of 2 service(s) (3 in use)"), body)
    // An existing service re-applied unchanged, or lower, is always accepted.
    assertEquals(apply("p2", "b", instances = 1)._1, 200)
    assertEquals(apply("p2", "c", instances = 1)._1, 200)

    assertEquals(send("DELETE", "/services/p2/b", alice)._1, 204)
    assertEquals(send("DELETE", "/services/p2/c", alice)._1, 204)
    assertEquals(usage(), Usage(2, 1, 1))
    assertEquals(apply("p1", "d")._1, 200, "under the quota again")
    assertEquals(usage(), Usage(2, 2, 2))
  }

  test(
    "10. deleting a project frees it; a create that fails gives the slot back, a duplicate keeps it"
  ) {
    // The delete guard counts services from a listing, which still shows the two just deleted for
    // a moment; the quota's own count is the entity's and needs no waiting.
    val _ = eventually("p2's deleted services leave the listing") {
      val (status, body) = send("DELETE", "/projects/p2", alice)
      Option.when(status == 204)(body)
    }
    assertEquals(usage().projects, 1)

    // Room for one more. Creating `p1` again is a duplicate: the slot it holds is not released.
    assertEquals(createProject("p1", "acme")._1, 409)
    assertEquals(usage().projects, 1)
    // An id taken by another organization: reserved here, refused there, and given back.
    assertEquals(send("POST", "/organizations/globex", bob, Some("""{"name":"Globex"}"""))._1, 204)
    assertEquals(createProject("shared", "globex", as = bob)._1, 204)
    assertEquals(createProject("shared", "acme")._1, 409)
    assertEquals(usage().projects, 1)
    assertEquals(createProject("p4", "acme")._1, 204, "the slot was free")
    assertEquals(usage().projects, 2)
  }

  test("11. the listing carries the quota and the usage too") {
    val listed = eventually("alice's listing shows acme's usage") {
      val (_, body) = send("GET", "/organizations", alice)
      Option.when(body.contains("\"usage\":{\"projects\":2,\"services\":2,\"instances\":2}"))(body)
    }
    assert(listed.contains("\"quota\":{\"services\":2}"), listed)
  }

  test(
    "12. setting a quota makes usage exactly what exists, for an organization that was never told"
  ) {
    // Globex holds a project and a service, recorded as they were made. Forget the service by hand,
    // as an organization from before this feature would never have known it.
    assertEquals(apply("shared", "x", instances = 2, as = bob)._1, 200)
    assertEquals(summary("globex", as = bob).usage.services, 1)
    testKit.componentClient
      .forEventSourcedEntity(EntityId("globex"))
      .call(OrganizationEntity.recordService)
      .invoke(RecordService("shared/x", None))
    assertEquals(summary("globex", as = bob).usage, Usage(1, 0, 0), "wrong, as an old record is")

    // The snapshot reads two views: the organization's projects, then each project's services. Wait
    // for both, or a lagging project row means no services are visited and the record stays wrong.
    val _ = eventually("the project reaches the listing the snapshot reads") {
      val (_, body) = send("GET", "/projects?organization=globex", bob)
      Option.when(body.contains("\"id\":\"shared\""))(body)
    }
    val _ = eventually("the service reaches the listing the snapshot reads") {
      val (_, body) = send("GET", "/services/shared", bob)
      Option.when(body.contains("\"name\":\"x\""))(body)
    }
    assertEquals(
      send("PUT", "/organizations/globex/quota", carol, Some("""{"instances":10}"""))._1,
      204
    )
    assertEquals(summary("globex", as = bob).usage, Usage(1, 1, 2), "exact again")
  }

  test("13. clearing the quota lets everything through") {
    assertEquals(send("DELETE", "/organizations/acme/quota", carol)._1, 204)
    assertEquals(summary("acme").quota, None)
    assertEquals(createProject("p5", "acme")._1, 204)
    assertEquals(apply("p5", "e", instances = 10)._1, 200)
    assertEquals(apply("p5", "f", instances = 1)._1, 200)
    assertEquals(usage(), Usage(3, 4, 13))
  }
