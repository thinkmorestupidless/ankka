package com.thinkmorestupidless.ankka.agent.mcp

import com.thinkmorestupidless.ankka.sdk.ServiceClient

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * How the MCP client reaches a server: one POST, answered with a status, headers and a body.
 *
 * Small on purpose, so a server that is an ankka service is reached through the service client —
 * its certificate, its expected identity, its local lookup — with no TLS code here at all.
 */
private[ankka] trait McpTransport:
  def post(body: String, headers: Seq[(String, String)]): McpTransport.Response

  /** Where the server is, as an error may name it. Never carries a credential. */
  def describe: String

private[ankka] object McpTransport:

  /** A response, with header names in lower case. */
  final case class Response(status: Int, headers: Map[String, String], body: String):
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

  /** A server at a URL, over the JDK's HTTP client. */
  final class Url(url: String, connectTimeout: FiniteDuration, callTimeout: FiniteDuration)
      extends McpTransport:

    private val client = HttpClient
      .newBuilder()
      .connectTimeout(java.time.Duration.ofMillis(connectTimeout.toMillis))
      .build()

    def describe: String = url

    def post(body: String, headers: Seq[(String, String)]): Response =
      val request = headers
        .foldLeft(
          HttpRequest
            .newBuilder(URI.create(url))
            .timeout(java.time.Duration.ofMillis(callTimeout.toMillis))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        )((builder, header) => builder.header(header._1, header._2))
        .build()
      val response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      Response(
        response.statusCode,
        response.headers.map.asScala.collect {
          case (name, values) if !values.isEmpty => name.toLowerCase -> values.get(0)
        }.toMap,
        response.body
      )

  /** A server that is an ankka service, called as the agent's service is. */
  final class Service(client: => ServiceClient, path: String) extends McpTransport:

    def describe: String = s"${client.target}$path"

    def post(body: String, headers: Seq[(String, String)]): Response =
      val response = client.request(
        "POST",
        path,
        Some(body.getBytes(StandardCharsets.UTF_8)),
        Some("application/json"),
        headers.filterNot(_._1.equalsIgnoreCase("Content-Type"))
      )
      Response(
        response.status,
        response.headers.map((name, value) => name.toLowerCase -> value).toMap +
          ("content-type" -> response.contentType),
        String(response.body, StandardCharsets.UTF_8)
      )
