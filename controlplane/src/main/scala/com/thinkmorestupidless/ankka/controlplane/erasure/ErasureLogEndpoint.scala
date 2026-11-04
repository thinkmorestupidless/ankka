package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.*

import scala.concurrent.Await
import scala.concurrent.duration.*

/** A page of the erasure log, oldest first. */
final case class ErasureLogPage(entries: Vector[ErasureLogEntry])

object ErasureLogPage:
  given JsonValueCodec[ErasureLogPage] = JsonCodecMaker.make

/**
 * `GET /internal/erasure-log?after=<seq>` (feature 042, FR-021): the log's entries after `seq`, for
 * the keyring to replay before it serves. Only the keyring is admitted, by its certificate; the
 * subjects are in the entries, and nobody else has a use for the whole log.
 */
final class ErasureLogEndpoint(plane: () => Option[ErasurePlane])
    extends HttpEndpoint("/internal/erasure-log"):

  val acl: Acl = Acl.allowCallers(Callers.service("platform", "keyring"))

  get("/") { () =>
    val after = request.query.optional[Long]("after").getOrElse(0L)
    if after < 0 then throw CommandError("after is a sequence, 0 or more", ErrorCode.BadRequest)
    val log = plane()
      .flatMap(_.application)
      .getOrElse(
        throw CommandError("erasure is not running on this control plane", ErrorCode.Unavailable)
      )
      .log
    ErasureLogPage(Await.result(log.after(after), 30.seconds))
  }
