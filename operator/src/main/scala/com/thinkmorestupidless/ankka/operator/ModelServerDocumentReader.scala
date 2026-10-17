package com.thinkmorestupidless.ankka.operator

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.{ModelCalls, ModelServerDocument, ModelState, ModelStatus}

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.time.Duration
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The model server's status document, read from the keeper's plain status port (feature 053).
 *
 * The operator has no jsoniter, so the document is read here from Jackson's tree, field by field,
 * into the type `core` declares and the operator compiles. `ModelServerDocumentReaderSuite` holds
 * this reading to the fixture `core`'s codec is held to, so the two cannot disagree about a field.
 * A failure of any kind is a reason, never an exception: the pass reads it as "not answering".
 */
object ModelServerDocumentReader:

  val Timeout: Duration = Duration.ofSeconds(2)

  private val mapper = ObjectMapper()
  private lazy val client =
    HttpClient
      .newBuilder()
      .connectTimeout(Timeout)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()

  def read(settings: ModelServerSettings): Either[String, ModelServerDocument] =
    try
      val response = client.send(
        HttpRequest.newBuilder(URI.create(settings.statusUrl)).timeout(Timeout).GET().build(),
        BodyHandlers.ofString()
      )
      if response.statusCode != 200 then
        Left(s"the model server's status answered ${response.statusCode}")
      else parse(response.body)
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left("interrupted reading the model server's status")
      case NonFatal(e) => Left(s"the model server's status could not be read: ${e.toString}")

  def parse(body: String): Either[String, ModelServerDocument] =
    try
      val root = mapper.readTree(body)
      if root == null || !root.isObject then Left("the model server's status is not a JSON object")
      else
        val models = elements(root.get("models")).map(model)
        models.collectFirst { case Left(why) => why } match
          case Some(why) => Left(why)
          case None =>
            Right(
              ModelServerDocument(
                server = text(root, "server").getOrElse(""),
                ready = Option(root.get("ready")).exists(_.asBoolean(false)),
                pass = text(root, "pass"),
                models = models.collect { case Right(m) => m },
                calls = Option(root.get("calls"))
                  .filter(_.isObject)
                  .fold(ModelCalls())(c =>
                    ModelCalls(
                      answered = long(c, "answered"),
                      failed = long(c, "failed"),
                      inFlight = long(c, "inFlight"),
                      waiting = long(c, "waiting")
                    )
                  ),
                detail = text(root, "detail")
              )
            )
    catch case NonFatal(e) => Left(s"the model server's status is not readable: ${e.getMessage}")

  private def model(node: JsonNode): Either[String, ModelStatus] =
    val name  = text(node, "name").getOrElse("")
    val state = text(node, "state").getOrElse("")
    if !ModelState.All.contains(state) then
      Left(s"model \"$name\" is in an unknown state \"$state\"")
    else
      Right(
        ModelStatus(
          name = name,
          state = state,
          digest = text(node, "digest"),
          capabilities = elements(node.get("capabilities")).map(_.asText),
          detail = text(node, "detail"),
          since = text(node, "since")
        )
      )

  private def elements(node: JsonNode): Vector[JsonNode] =
    Option(node).filter(_.isArray).fold(Vector.empty)(_.elements.asScala.toVector)

  private def text(node: JsonNode, field: String): Option[String] =
    Option(node.get(field)).filter(_.isTextual).map(_.asText)

  private def long(node: JsonNode, field: String): Long =
    Option(node.get(field)).filter(_.isNumber).fold(0L)(_.asLong)
