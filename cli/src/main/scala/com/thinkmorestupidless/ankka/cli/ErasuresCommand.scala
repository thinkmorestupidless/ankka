package com.thinkmorestupidless.ankka.cli

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.UUID

/**
 * `ankka projects erasures request <subject> --keyring <url>`: an erasure asked of a local keyring
 * directly, for a service run with `docker compose` and no control plane. Project `local`, applied
 * at once; no hold, no log beyond the keyring's own, no certificate — the control plane gives
 * those.
 */
object ErasuresCommand:
  def local(keyring: String, subject: String): String =
    val id       = "e-local-" + UUID.randomUUID().toString.replace("-", "").take(8)
    val sequence = System.currentTimeMillis()
    val body =
      s"""{"erasureId":"$id","subject":"${subject.replace("\"", "")}","sequence":$sequence}"""
    val response = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build()
      .send(
        HttpRequest
          .newBuilder(URI.create(keyring.stripSuffix("/") + "/projects/local/erasures"))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(30))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    if response.statusCode() != 200 then
      throw ApiError(response.statusCode(), s"the keyring refused: ${response.body()}")
    s"erased $subject in project local through the keyring at $keyring ($id); every running service applies it now"
