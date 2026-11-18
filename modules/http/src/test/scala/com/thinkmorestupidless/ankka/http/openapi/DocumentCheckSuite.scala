package com.thinkmorestupidless.ankka.http.openapi

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters.*

/**
 * What a server tells a document check (feature 067, research R10): every answer with its route,
 * the body of a request it took, and the query parameters a handler, and only a handler, read.
 */
class DocumentCheckSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "document-check-suite",
      com.typesafe.config.ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )
  private given ExecutionContext = system.executionContext

  override def afterAll(): Unit =
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  final case class Edit(name: String)
  given JsonValueCodec[Edit] = Codecs.make

  /** What the handlers saw of the check: whether one listened while each ran. */
  private val heard = ConcurrentLinkedQueue[Boolean]()

  private final class Players extends HttpEndpoint("/players"):
    // An ACL that reads a query parameter, before any handler runs.
    val acl: Acl = Acl.AllowIf(_.query.raw("brand").forall(_ != "closed"))

    get("/{playerId}") { (id: String) =>
      heard.add(DocumentCheck.listening)
      if id == "missing" then throw HttpProblem.notFound("no such player")
      query.optional[Int]("page").getOrElse(0) + id.length
    }

    patchBody("/{playerId}")((id: String, edit: Edit) => s"$id:${edit.name}")

  /** A check that writes down everything it is told. */
  private final class Recording extends DocumentCheck:
    val told = ConcurrentLinkedQueue[String]()
    def answered(route: String, status: Int, contentType: String, body: Option[Array[Byte]]) =
      told.add(s"answered $route $status $contentType ${body.map(String(_, UTF_8))}"): Unit
    def accepted(route: String, body: Array[Byte], status: Int) =
      told.add(s"accepted $route ${String(body, UTF_8)} $status"): Unit
    def queried(route: String, name: String) = told.add(s"queried $route $name"): Unit
    def all: Vector[String]                  = told.asScala.toVector

  private def send(check: Option[DocumentCheck], request: HttpRequest): Int =
    val router = Router(Vector(Players()), 5.seconds, check = check)
    val answer = Await.result(router.handle(request), 10.seconds)
    Await.result(answer.entity.toStrict(5.seconds), 5.seconds): Unit
    answer.status.intValue

  test("an answer and a mapped failure are both reported, by the route's whole template") {
    val check = Recording()
    assertEquals(send(Some(check), HttpRequest(uri = "/players/p1")), 200)
    assertEquals(send(Some(check), HttpRequest(uri = "/players/missing")), 404)
    val told = check.all.filter(_.startsWith("answered"))
    assertEquals(told(0), "answered GET /players/{playerId} 200 application/json Some(2)")
    assert(
      told(1).startsWith("answered GET /players/{playerId} 404 application/json Some("),
      told(1)
    )
    assert(told(1).contains("no such player"), told(1))
  }

  test("a handler's read of a query parameter is reported; an ACL's read is not") {
    val check = Recording()
    assertEquals(send(Some(check), HttpRequest(uri = "/players/p1?page=2&brand=open")), 200)
    assertEquals(
      check.all.filter(_.startsWith("queried")),
      Vector("queried GET /players/{playerId} page")
    )
  }

  test("the body of a request the route took is reported with its status") {
    val check = Recording()
    val patch = HttpRequest(
      HttpMethods.PATCH,
      "/players/p1",
      entity = HttpEntity(ContentTypes.`application/json`, """{"name":"ann"}""")
    )
    assertEquals(send(Some(check), patch), 200)
    assert(
      check.all.contains("""accepted PATCH /players/{playerId} {"name":"ann"} 200"""),
      check.all
    )
  }

  test("a route served with no check runs its handler with nothing listening") {
    heard.clear()
    assertEquals(send(None, HttpRequest(uri = "/players/p1?page=2")), 200)
    assertEquals(send(Some(Recording()), HttpRequest(uri = "/players/p1")), 200)
    assertEquals(heard.asScala.toVector, Vector(false, true))
  }
