package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.Config

import java.net.http.HttpClient
import java.nio.file.Paths
import java.time.Duration
import javax.net.ssl.SSLParameters
import scala.concurrent.duration.FiniteDuration

/**
 * The control plane as a service reaches it, over the service's own certificate, requiring the
 * control plane's: one client per certificate generation, so a renewed one is used. What the record
 * of a secret's read and an erasure asked from code are sent through.
 */
private[ankka] final class ControlPlaneHttp(
    val url: String,
    tls: Option[RotatingTls],
    val timeout: FiniteDuration
):

  private val plain: HttpClient =
    HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofMillis(timeout.toMillis))
      .build()

  @volatile private var secured: Option[(Int, HttpClient)] = None

  def client: HttpClient = tls match
    case None           => plain
    case Some(identity) =>
      val context = identity.contextRequiring(ControlPlaneHttp.ControlPlaneUri)
      val stamp   = System.identityHashCode(context)
      secured match
        case Some((s, c)) if s == stamp => c
        case _                          =>
          val params = new SSLParameters()
          params.setEndpointIdentificationAlgorithm("HTTPS")
          params.setProtocols(Array("TLSv1.3"))
          val c = HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(context)
            .sslParameters(params)
            .connectTimeout(Duration.ofMillis(timeout.toMillis))
            .build()
          secured = Some((stamp, c))
          c

private[ankka] object ControlPlaneHttp:
  val ControlPlaneUri: String = "ankka://platform/controlplane"

  /** The control plane at `ankka.secrets.records-url`, or none when it is empty. */
  def from(config: Config): Option[ControlPlaneHttp] =
    val url = config.getString("ankka.secrets.records-url").trim
    Option.when(url.nonEmpty)(
      ControlPlaneHttp(
        url,
        tls(config, url),
        FiniteDuration(config.getDuration("ankka.secrets.record-timeout").toMillis, "ms")
      )
    )

  private def tls(config: Config, url: String): Option[RotatingTls] =
    val key       = "ankka.tls.service-directory"
    val directory =
      if config.hasPath(key) then config.getString(key).trim else ""
    if url.startsWith("https://") && directory.nonEmpty then
      Some(
        RotatingTls(
          Paths.get(directory),
          FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, "ms")
        )
      )
    else None
