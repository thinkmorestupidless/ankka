package com.thinkmorestupidless.ankka.runtime.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToArray, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.ControlPlaneHttp
import com.thinkmorestupidless.ankka.sdk.{ErasureAsked, Erasures}

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.time.{Duration, LocalDate, ZoneOffset}
import scala.util.control.NonFatal

/**
 * `ctx.erasures.ask` (feature 042): `POST /projects/<project>/erasures` on the control plane, as
 * this service, whose certificate the control plane reads the asker from. The control plane's own
 * refusal is answered with its code and words: `Forbidden` without the `erasure` grant.
 */
private[ankka] final class ControlPlaneErasures(plane: ControlPlaneHttp, ownProject: String)
    extends Erasures:
  import ControlPlaneErasures.*

  def ask(
      subject: DataSubject,
      project: Option[String],
      notBefore: Option[LocalDate],
      reason: Option[String],
      correlation: Option[String]
  ): ErasureAsked =
    val in   = project.getOrElse(ownProject)
    val body = Ask(
      subject.value,
      notBefore.map(_.atStartOfDay().toInstant(ZoneOffset.UTC).toString),
      reason,
      correlation
    )
    val request = HttpRequest
      .newBuilder(URI(s"${plane.url.stripSuffix("/")}/projects/$in/erasures"))
      .timeout(Duration.ofMillis(plane.timeout.toMillis))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofByteArray(writeToArray(body)))
      .build()
    val response =
      try plane.client.send(request, HttpResponse.BodyHandlers.ofString())
      catch
        case e: InterruptedException => throw e
        case NonFatal(e)             =>
          throw CommandError(
            s"the control plane did not answer the erasure ask: $e",
            ErrorCode.Unavailable
          )
    val status = response.statusCode
    if status / 100 == 2 then
      val asked = readFromString[Asked](response.body)
      ErasureAsked(asked.id, asked.state.getOrElse(""))
    else
      val said =
        try readFromString[Problem](response.body).error.getOrElse(response.body)
        catch case NonFatal(_) => response.body
      throw CommandError(s"the erasure ask was refused: $said", codeOf(status))

private[ankka] object ControlPlaneErasures:
  final case class Ask(
      subject: String,
      notBefore: Option[String],
      reason: Option[String],
      correlation: Option[String]
  )
  final case class Asked(id: String, state: Option[String] = None)
  final case class Problem(error: Option[String] = None)

  given JsonValueCodec[Ask]   = JsonCodecMaker.make
  given JsonValueCodec[Asked] = JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  given JsonValueCodec[Problem] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  def codeOf(status: Int): ErrorCode = status match
    case 400       => ErrorCode.BadRequest
    case 401 | 403 => ErrorCode.Forbidden
    case 404       => ErrorCode.NotFound
    case 409       => ErrorCode.Conflict
    case _         => ErrorCode.Unavailable

  /** The control plane's, when the service reaches one; otherwise every ask is refused. */
  def from(config: com.typesafe.config.Config, ownProject: Option[String]): Erasures =
    (ControlPlaneHttp.from(config), ownProject) match
      case (Some(plane), Some(project)) => ControlPlaneErasures(plane, project)
      case _                            => Erasures.unavailable
