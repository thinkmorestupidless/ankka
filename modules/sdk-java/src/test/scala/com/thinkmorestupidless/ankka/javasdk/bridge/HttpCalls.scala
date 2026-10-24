package com.thinkmorestupidless.ankka.javasdk.bridge

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

/** Plain HTTP calls to a served address, as a test reads them. */
final class HttpCalls(base: () => String):
  private val client = HttpClient.newHttpClient()

  def get(path: String, headers: (String, String)*): HttpResponse[String] =
    send("GET", path, None, headers)

  def post(path: String, body: String = ""): HttpResponse[String] =
    send("POST", path, Some(body), Nil)

  def put(path: String, body: String): HttpResponse[String] = send("PUT", path, Some(body), Nil)

  def delete(path: String): HttpResponse[String] = send("DELETE", path, None, Nil)

  private def send(
      method: String,
      path: String,
      body: Option[String],
      headers: Seq[(String, String)]
  ): HttpResponse[String] =
    val publisher =
      body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
    val builder = HttpRequest.newBuilder(URI.create(base() + path)).method(method, publisher)
    if body.isDefined then builder.header("Content-Type", "application/json"): Unit
    headers.foreach((k, v) => builder.header(k, v): Unit)
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
