package com.thinkmorestupidless.ankka.runtime.secrets

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Clock, Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/**
 * Where an access token for Secret Manager comes from.
 *
 * On GKE with Workload Identity Federation the metadata server answers with a token for the pod's
 * own Kubernetes ServiceAccount: no Google service account, no key file, nothing in the pod's
 * environment. A test gives a fixed token its stand-in reads as the caller.
 */
private[ankka] trait AccessTokens:
  /** A token good for at least a minute, or `CommandError(Unavailable)`. */
  def token(): String

private[ankka] object AccessTokens:

  /** The metadata server's token endpoint, as every Google client library reads it. */
  val MetadataTokenUrl: String =
    "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token"

  /** Always `token`: a test's stand-in, which reads it as an identity. */
  def fixed(token: String): AccessTokens = () => token

  /**
   * The pod's own token from the metadata server, kept until a minute before it expires. Any
   * failure to get one is `Unavailable`, as an unreachable Secret Manager is: a store cannot tell
   * the two apart for its caller, and both pass.
   */
  def metadata(
      http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
      url: String = MetadataTokenUrl,
      clock: Clock = Clock.systemUTC()
  ): AccessTokens = MetadataTokens(http, url, clock)

  private final case class Held(token: String, refreshAfter: Instant)

  private[secrets] final case class TokenReply(access_token: String, expires_in: Long)
  private[secrets] given JsonValueCodec[TokenReply] = JsonCodecMaker.make

  private final class MetadataTokens(http: HttpClient, url: String, clock: Clock)
      extends AccessTokens:
    private val held = AtomicReference[Option[Held]](None)

    def token(): String =
      held.get match
        case Some(h) if clock.instant().isBefore(h.refreshAfter) => h.token
        case _ =>
          val fresh = fetch()
          held.set(Some(fresh))
          fresh.token

    private def fetch(): Held =
      val request = HttpRequest
        .newBuilder(URI(url))
        .header("Metadata-Flavor", "Google")
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build()
      val response =
        try http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        catch
          case NonFatal(e) =>
            throw CommandError(
              s"no access token for Secret Manager: the metadata server could not be reached " +
                s"(${e.getClass.getSimpleName}); is Workload Identity on for this node pool?",
              ErrorCode.Unavailable
            )
      if response.statusCode != 200 then
        throw CommandError(
          s"no access token for Secret Manager: the metadata server answered " +
            s"${response.statusCode}",
          ErrorCode.Unavailable
        )
      val reply = readFromArray[TokenReply](response.body)
      Held(reply.access_token, clock.instant().plusSeconds(math.max(0L, reply.expires_in - 60)))
