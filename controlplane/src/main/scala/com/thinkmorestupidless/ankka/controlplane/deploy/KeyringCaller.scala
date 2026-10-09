package com.thinkmorestupidless.ankka.controlplane.deploy

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi.given
import com.typesafe.config.Config

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Paths
import java.time.Duration
import javax.net.ssl.SSLParameters
import scala.concurrent.duration.*

/**
 * The control plane's three calls on the keyring (`contracts/keyring-api.md`): apply an erasure it
 * has written to the log, apply one again, and read where one stands. As its own identity in a
 * cluster; the keyring answers it no key.
 */
trait KeyringCaller:
  def apply(project: String, request: KeyringApi.ApplyErasure): KeyringApi.ErasureStatus
  def reapply(project: String, erasureId: String): KeyringApi.ErasureStatus
  def status(project: String, erasureId: String): KeyringApi.ErasureStatus

object KeyringCaller:

  /** For a control plane with no keyring: every erasure stays applying, and says why. */
  val none: KeyringCaller = new KeyringCaller:
    private def no = throw CommandError(
      "this installation runs no keyring (ANKKA_KEYRING_URL is not set)",
      ErrorCode.Unavailable
    )
    def apply(project: String, request: KeyringApi.ApplyErasure) = no
    def reapply(project: String, erasureId: String)              = no
    def status(project: String, erasureId: String)               = no

  def fromConfig(config: Config, env: String => Option[String] = sys.env.get): KeyringCaller =
    env("ANKKA_KEYRING_URL")
      .filter(_.nonEmpty)
      .fold(none)(url => Http(URI.create(url.stripSuffix("/")), tls(config)))

  private def tls(config: Config): Option[RotatingTls] =
    val directory = "ankka.tls.service-directory"
    Option.when(config.hasPath(directory) && config.getString(directory).nonEmpty)(
      RotatingTls(
        Paths.get(config.getString(directory)),
        FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, MILLISECONDS)
      )
    )

  final class Http(base: URI, tls: Option[RotatingTls]) extends KeyringCaller:
    private def client: HttpClient =
      val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      tls.foreach { identity =>
        val params = SSLParameters()
        params.setEndpointIdentificationAlgorithm("HTTPS")
        builder
          .sslContext(identity.contextRequiring("ankka://platform/keyring"))
          .sslParameters(params)
      }
      builder.build()

    private def send(request: HttpRequest.Builder): KeyringApi.ErasureStatus =
      val response =
        try
          client.send(
            request.timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
          )
        catch
          case e: java.io.IOException =>
            throw CommandError(
              s"the keyring did not answer: ${e.getMessage}",
              ErrorCode.Unavailable
            )
      if response.statusCode() != 200 then
        throw CommandError(
          s"the keyring answered ${response.statusCode()}: ${String(response.body()).take(300)}",
          if response.statusCode() >= 500 then ErrorCode.Unavailable else ErrorCode.BadRequest
        )
      readFromArray[KeyringApi.ErasureStatus](response.body())

    def apply(project: String, request: KeyringApi.ApplyErasure): KeyringApi.ErasureStatus =
      send(
        HttpRequest
          .newBuilder(URI.create(s"$base/projects/$project/erasures"))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(request)))
      )

    def reapply(project: String, erasureId: String): KeyringApi.ErasureStatus =
      send(
        HttpRequest
          .newBuilder(URI.create(s"$base/projects/$project/erasures/$erasureId/reapply"))
          .POST(HttpRequest.BodyPublishers.noBody())
      )

    def status(project: String, erasureId: String): KeyringApi.ErasureStatus =
      send(HttpRequest.newBuilder(URI.create(s"$base/projects/$project/erasures/$erasureId")).GET())
