package com.thinkmorestupidless.ankka.testkit

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.http.openapi.{DocumentCheck, OpenApi}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Holds a service to its OpenAPI document for the length of a test (feature 067): an answer the
 * document does not name, an answer body or a taken request body its shape refuses, and a query
 * parameter a handler reads that its route never declared are each recorded, naming the route. A
 * check only listens; the service answers exactly as it would without one.
 *
 * {{{
 * class CartHttpSuite extends munit.FunSuite with HeldToDocument with LogCapturing:
 *   val server = HeldToDocument.server(HttpServer.at("127.0.0.1", 0)(c => CartEndpoint(c)))
 * }}}
 */
object HeldToDocument:

  /**
   * Where checked servers record what they did outside their documents. A kit that holds its own
   * server keeps one of its own, so another kit open beside it neither reads nor clears it.
   */
  final class Violations:
    private val found = ConcurrentLinkedQueue[String]()

    /**
     * What the servers recording here did that their documents do not allow, since the last clear.
     */
    def all: Vector[String] = found.asScala.toVector

    def clear(): Unit = found.clear()

    /** Fails listing every violation, and clears them, or does nothing when there are none. */
    def assertHeld(): Unit =
      val held = all
      clear()
      if held.nonEmpty then throw NotHeldToDocument(held)

    private[HeldToDocument] def note(violation: String): Unit = found.add(violation): Unit

  /**
   * The record a server checked with no record of its own writes to, which the suite trait reads.
   */
  private val shared = Violations()

  /** The same server, checked against the document of the endpoints it serves. */
  def server(server: HttpServer): HttpServer = this.server(server, shared)

  /** The same server, checked, recording what it does outside its document `into`. */
  def server(server: HttpServer, into: Violations): HttpServer =
    lazy val checked: HttpServer = server.withDocumentCheck(Check(() => checked, into))
    checked

  /** What the servers checked with no record of their own did outside their documents. */
  def violations: Vector[String] = shared.all

  /** Fails listing every such violation, and clears them, or does nothing when there are none. */
  def assertHeld(): Unit = shared.assertHeld()

  def clear(): Unit = shared.clear()

  /**
   * The statuses any route may answer without naming them: the platform's own, for a body or a
   * parameter it could not read, a caller it refused, a body too large, a fault or an outage.
   */
  private val PlatformStatuses = Set(400, 401, 403, 413, 500, 503, 504)

  private val mapper    = ObjectMapper()
  private val TextShape = mapper.readTree("""{"type":"string"}""")
  private val schemas   = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

  private final class Check(served: () => HttpServer, into: Violations) extends DocumentCheck:

    private def note(violation: String): Unit = into.note(violation)

    /** Read on first use: the server serves, and so knows its endpoints, by then. */
    private lazy val document: Option[JsonNode] =
      served().servedDocument.toOption.map(d => mapper.readTree(d.render))

    private def operation(route: String): Option[JsonNode] =
      route.split(' ') match
        case Array(method, path) =>
          document
            .map(_.path("paths").path(OpenApi.joined("", path)).path(method.toLowerCase))
            .filterNot(_.isMissingNode)
        case _ => None

    private def schemaOf(content: JsonNode, contentType: String): Option[JsonNode] =
      val media = contentType.takeWhile(_ != ';').trim
      if !media.endsWith("json") then None
      else
        Option(content.get(media))
          .orElse(Option(content.get("application/json")))
          .map(_.path("schema"))
          .filterNot(_.isMissingNode)

    /**
     * The shape a request body is described with, whatever media type carries it. A text body whose
     * shape is a string is the text itself, which any text is: nothing to check.
     */
    private def described(content: JsonNode): Option[JsonNode] =
      content.properties.asScala
        .map(e => (e.getKey, e.getValue.path("schema")))
        .find(!_._2.isMissingNode)
        .filterNot((media, schema) => media.startsWith("text/") && schema == TextShape)
        .map(_._2)

    private def refused(schema: JsonNode, body: Array[Byte]): Vector[String] =
      val root = schema.deepCopy[JsonNode]() match
        case o: ObjectNode => o
        case _             => return Vector.empty
      document.foreach(d => root.set[JsonNode]("components", d.path("components")))
      try
        schemas
          .getSchema(root)
          .validate(String(body, UTF_8), InputFormat.JSON)
          .asScala
          .toVector
          .map(_.getMessage)
          .sorted
      catch case scala.util.control.NonFatal(_) => Vector("the body is not JSON")

    def answered(route: String, status: Int, contentType: String, body: Option[Array[Byte]]) =
      operation(route).foreach { op =>
        val responses = op.path("responses")
        val named     = responses.fieldNames.asScala.toVector.filterNot(_ == "default")
        val decided   = named.isEmpty // the handler decides its status: any answer is its
        if !decided && !named.contains(status.toString) && !PlatformStatuses(status) then
          note(
            s"$route: answered $status, which its description does not name (${named.mkString(", ")})"
          )
        else
          for
            bytes <- body
            // A status the handler decides is described once, as `default`; a refusal is not that.
            answer =
              if decided && status < 400 then "default" else status.toString
            schema <- schemaOf(responses.path(answer).path("content"), contentType)
            messages = refused(schema, bytes)
            if messages.nonEmpty
          do
            note(
              s"$route: answered $status with a body its shape refuses: ${messages.mkString("; ")}"
            )
      }

    def accepted(route: String, body: Array[Byte], status: Int) =
      if status < 400 then
        for
          op     <- operation(route)
          schema <- described(op.path("requestBody").path("content"))
          messages = refused(schema, body)
          if messages.nonEmpty
        do
          note(
            s"$route: took a body its shape refuses, answered $status: ${messages.mkString("; ")}"
          )

    def queried(route: String, name: String) =
      operation(route).foreach { op =>
        val declared = op.path("parameters").elements.asScala.exists { p =>
          p.path("in").asText == "query" && p.path("name").asText == name
        }
        if !declared then
          note(
            s"$route: read the query parameter \"$name\", which its description does not name"
          )
      }

/**
 * What a test held to its document did that the document does not allow. An exception and not an
 * `AssertionError`, which a `Future` would box and so hide the violations' words.
 */
final class NotHeldToDocument(val violations: Vector[String])
    extends RuntimeException(
      s"the service is not held to its OpenAPI document:\n${violations.map("  " + _).mkString("\n")}"
    )

/**
 * A suite whose every test is held to the documents of the servers it checked: a test that passed
 * fails after its body, listing what the service did that its document does not allow.
 */
trait HeldToDocument extends munit.FunSuite:

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ TestTransform(
      "held to its OpenAPI document",
      test =>
        test.withBody { () =>
          HeldToDocument.clear()
          test
            .body()
            .map { result =>
              HeldToDocument.assertHeld()
              result
            }(using munitExecutionContext)
        }
    )
