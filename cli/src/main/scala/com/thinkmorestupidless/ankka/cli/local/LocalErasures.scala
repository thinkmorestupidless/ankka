package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.cli.ApiError
import com.thinkmorestupidless.ankka.cli.console.LocalSource

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/**
 * `ankka local erasures file <subject>` (feature 042, R20): the data subject's key destroyed in the
 * local keyring of each service running on this machine, or of the one named, through its
 * observability endpoint's `POST /_ankka/erasures`. Each answer is what that service did.
 */
object LocalErasures:

  def file(
      subject: String,
      service: Option[String],
      rerun: Boolean,
      source: LocalSource = new LocalSource()
  ): String =
    val running = source.services().filter(s => service.forall(_ == s.name))
    if running.isEmpty then
      throw ApiError(
        0,
        service.fold("no service is running on this machine")(n =>
          s"no service named '$n' is running on this machine"
        )
      )
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    val body   = s"""{"subject":${quoted(subject)},"rerun":$rerun}"""
    running
      .map { s =>
        val request = HttpRequest
          .newBuilder(URI.create(s"${s.observabilityAddress}/_ankka/erasures"))
          .timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
        val answer =
          try
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if response.statusCode / 100 == 2 then response.body
            else s"refused (${response.statusCode}): ${response.body}"
          catch case e: java.io.IOException => s"did not answer: ${e.getMessage}"
        s"${s.name}: $answer"
      }
      .mkString("\n")

  private def quoted(text: String): String =
    text
      .flatMap {
        case '"'          => "\\\""
        case '\\'         => "\\\\"
        case c if c < ' ' => f"\\u${c.toInt}%04x"
        case c            => c.toString
      }
      .mkString("\"", "", "\"")
