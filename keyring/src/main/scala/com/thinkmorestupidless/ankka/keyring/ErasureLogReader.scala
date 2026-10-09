package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.readFromArray
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.runtime.erasure.{KeyringApi, ObjectStoreClient}
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi.given
import com.typesafe.config.Config

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Paths
import java.time.Duration
import javax.net.ssl.SSLParameters
import scala.concurrent.duration.*

/**
 * Reads the erasure log's copies: the control plane's, as the keyring's own identity, and the
 * bucket's.
 */
object ErasureLogReader:

  def fromControlPlane(url: String, config: Config): Vector[KeyringApi.LogEntry] =
    val directory = "ankka.tls.service-directory"
    val builder   = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
    if config.hasPath(directory) && config.getString(directory).nonEmpty then
      val tls = RotatingTls(
        Paths.get(config.getString(directory)),
        FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, MILLISECONDS)
      )
      val params = SSLParameters()
      params.setEndpointIdentificationAlgorithm("HTTPS")
      builder
        .sslContext(tls.contextRequiring("ankka://platform/controlplane"))
        .sslParameters(params): Unit
    val response = builder
      .build()
      .send(
        HttpRequest
          .newBuilder(URI.create(url.stripSuffix("/") + "/erasures/log?after=0"))
          .timeout(Duration.ofSeconds(30))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofByteArray()
      )
    if response.statusCode() != 200 then
      throw IllegalStateException(
        s"the control plane's erasure log answered ${response.statusCode()}"
      )
    readFromArray[Vector[KeyringApi.LogEntry]](response.body())

  def fromBucket(client: ObjectStoreClient): Vector[KeyringApi.LogEntry] =
    client.list("erasure-log/").map(_.key).distinct.flatMap { key =>
      client.get(key).map(bytes => readFromArray[KeyringApi.LogEntry](bytes))
    }
