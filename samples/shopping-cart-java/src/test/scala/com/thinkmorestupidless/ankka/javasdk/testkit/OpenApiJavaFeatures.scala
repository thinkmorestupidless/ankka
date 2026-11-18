package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.core.graph.GraphJson
import com.thinkmorestupidless.ankka.core.graph.GraphJson as J
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.javasdk.bridge.JavaServiceBuilder
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import shoppingcart.javasdk.DescribedCartEndpoint

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

/**
 * `features/openapi-documents/languages.feature` (feature 067, T033): its Scala and Java rows. The
 * Java cart's document is written by its own `main`, as `ankka openapi` writes it, and compared
 * with the Scala cart's kept document, which `OpenApiDocumentSuite` holds to the Scala cart's code.
 * A Kotlin row is skipped here: the Kotlin cart's `OpenApiFeaturesTest` runs it.
 */
class OpenApiJavaFeatures
    extends GherkinSuite("../../features/openapi-documents/languages.feature")
    with LogCapturing:

  override val munitTimeout = 3.minutes

  override protected lazy val ranElsewhere: Map[String, String] = Map(
    "a service in a language with no document yet is told so" -> "OpenApiCommandSuite (T015)"
  )

  private var language                              = ""
  private var description: Option[(String, String)] = None
  private var document: GraphJson                   = J.Null

  /** A route described in Scala, as the Java fixture describes it by annotation. */
  private final class DescribedCart extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.AllowAll
    described(_.refuses(404, "not found").query[String]("currency")) {
      get("/{cartId}")((cartId: String) => cartId)
    }

  private def runHere(language: String): Unit =
    assume(
      language == "scala" || language == "java",
      s"the $language row is run by the Kotlin cart's OpenApiFeaturesTest"
    )

  override def beforeEach(context: BeforeEach): Unit =
    language = ""
    description = None
    document = J.Null

  Given("the service {string} is written in {string}") { (service: String, written: String) =>
    assertEquals(service, "cart")
    runHere(written)
    language = written
  }

  Given(
    "the route {string} has a route description naming the refusal {string} and the query parameter {string}"
  ) { (route: String, refusal: String, query: String) =>
    assertEquals(route, "GET /carts/{cartId}")
    assertEquals((refusal, query), ("not found", "currency"))
    description = Some((refusal, query))
  }

  When("the developer writes the service's OpenAPI document") { () =>
    val bytes = (language, description) match
      case ("java", None) =>
        WrittenDocument.of("shoppingcart.javasdk.Main").fold(fail(_), identity)
      case ("java", Some(_)) =>
        JavaServiceBuilder
          .read(Vector.empty, Map.empty, Vector(classOf[DescribedCartEndpoint]))
          .httpServer(None)
          .openApi(Map.empty)
          .fold(p => fail(p.mkString("\n")), _.render.getBytes("UTF-8"))
      case ("scala", Some(_)) =>
        HttpServer
          .at("127.0.0.1", 0)(_ => DescribedCart())
          .openApi(Map.empty)
          .fold(p => fail(p.mkString("\n")), _.render.getBytes("UTF-8"))
      case other => fail(s"no service in $other")
    document = WrittenDocument.parse(bytes)
  }

  Then(
    "the document holds the same routes, path parameters, answers and shapes as the service written in {string}"
  ) { (other: String) =>
    assertEquals(other, "scala")
    val scala = WrittenDocument.parse(Files.readAllBytes(Path.of("../shopping-cart/openapi.json")))
    val expected = WrittenDocument.operations(scala, "/carts")
    val found    = WrittenDocument.operations(document, "/carts")
    assert(expected.nonEmpty, "the Scala cart's document holds no route under /carts")
    assertEquals(found.keySet, expected.keySet, "the routes")
    val differences = expected.toVector.sortBy(_._1).flatMap { (route, op) =>
      WrittenDocument.differences(route, op, scala, found(route), document)
    }
    assert(differences.isEmpty, differences.mkString("\n"))
  }

  Then("the route has the refusal {string} and the query parameter {string}") {
    (refusal: String, query: String) =>
      val op = WrittenDocument
        .at(document, "paths", "/carts/{cartId}", "get")
        .getOrElse(
          fail(
            s"no GET /carts/{cartId} in ${com.thinkmorestupidless.ankka.core.shape.Shape.write(document)}"
          )
        )
      val reasons = op.field("responses") match
        case Some(J.Obj(rs)) => rs.flatMap((_, r) => r.field("description"))
        case _               => Vector.empty
      assert(reasons.contains(J.Str(refusal)), s"$reasons")
      val queries = op.field("parameters") match
        case Some(J.Arr(ps)) =>
          ps.filter(_.field("in").contains(J.Str("query"))).flatMap(_.field("name"))
        case _ => Vector.empty
      assert(queries.contains(J.Str(query)), s"$queries")
  }
