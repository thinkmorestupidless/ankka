package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors
import com.thinkmorestupidless.ankka.runtime.storage.DirectoryObjectStorage
import com.thinkmorestupidless.ankka.sdk.BucketExposure
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.scaladsl.StreamConverters

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * What a service on a developer's machine serves at `/_ankka/storage/<bucket>/<name>` (feature 055,
 * research R12): the signed URLs and public addresses of its directory store, on its own HTTP port,
 * so a browser reads and keeps through them as it would through an object store's.
 *
 * Not an endpoint a service declares: the router consults it only when the service's store is a
 * directory, and an object's name may hold slashes, which no route template matches. The request's
 * caller is not asked; the token is the whole of the authority, as a signature is on an object
 * store. A `GET` reads with a token for `read`, or with none on a public bucket, and a `HEAD` is
 * answered as the `GET` would be, without the body; a `PUT` keeps with a token for `keep`. Anything
 * else is `403`, and an object not there `404`.
 */
private[http] final class DirectoryStorageEndpoint(
    val store: DirectoryObjectStorage,
    maxStreamBytes: Long,
    bodyTimeout: FiniteDuration,
    problem: HttpProblem => HttpResponse,
    clock: () => Instant = () => Instant.now()
):

  /** `segments` is the decoded path after `/_ankka/storage`: the bucket, then the object's name. */
  def handle(request: HttpRequest, segments: Vector[String])(using
      system: ActorSystem[?],
      ec: ExecutionContext
  ): Future[HttpResponse] =
    val found =
      if segments.sizeIs < 2 then None
      else store.bySegment(segments.head).map(_ -> segments.tail.mkString("/"))
    found match
      case None =>
        request.discardEntityBytes(): Unit
        Future.successful(problem(HttpProblem.notFound("no object at this address")))
      case Some((bucket, objectName)) =>
        val query = scala.util.Try(request.uri.query()).getOrElse(Uri.Query.Empty)
        def signedFor(access: String): Boolean =
          (
            query.get("access"),
            query.get("expires").flatMap(_.toLongOption),
            query.get("token")
          ) match
            case (Some(`access`), Some(expires), Some(token)) =>
              store.signer.admits(bucket.segment, objectName, access, expires, token, clock())
            case _ => false
        request.method match
          case HttpMethods.GET | HttpMethods.HEAD =>
            val public = bucket.exposure == BucketExposure.Public && bucket.name.nonEmpty
            if public || signedFor("read") then read(bucket, objectName) else refused(request)
          case HttpMethods.PUT =>
            if signedFor("keep") then keep(request, bucket, objectName) else refused(request)
          case other =>
            request.discardEntityBytes(): Unit
            Future.successful(
              problem(HttpProblem(405, s"${other.value} is not served at a storage address"))
            )

  private def read(bucket: store.DirectoryBucket, objectName: String)(using
      ExecutionContext
  ): Future[HttpResponse] =
    Future(bucket.get(objectName))(using AnkkaExecutors.virtual)
      .map {
        case None => problem(HttpProblem.notFound(s"no object '$objectName'"))
        case Some(o) =>
          val contentType =
            ContentType.parse(o.contentType).getOrElse(ContentTypes.`application/octet-stream`)
          val entity =
            if o.length <= 0 then
              o.body.close()
              HttpEntity.empty(contentType)
            else
              HttpEntity.Default(
                contentType,
                o.length,
                StreamConverters.fromInputStream(() => o.body)
              )
          HttpResponse(StatusCodes.OK, entity = entity)
      }
      .recover(failed)

  private def keep(request: HttpRequest, bucket: store.DirectoryBucket, objectName: String)(using
      system: ActorSystem[?],
      ec: ExecutionContext
  ): Future[HttpResponse] =
    val entity = request.entity
    entity.contentLengthOption match
      case None =>
        request.discardEntityBytes(): Unit
        Future.successful(
          problem(HttpProblem(411, "a keep through a signed URL declares its length"))
        )
      case Some(length) if length > maxStreamBytes =>
        request.discardEntityBytes(): Unit
        Future.successful(
          problem(HttpProblem(413, s"the body is $length bytes; at most $maxStreamBytes are kept"))
        )
      case Some(length) =>
        val input = entity.dataBytes.runWith(StreamConverters.asInputStream(bodyTimeout))
        Future {
          try bucket.put(objectName, input, length, entity.contentType.value)
          finally input.close()
        }(using AnkkaExecutors.virtual)
          .map(_ => HttpResponse(StatusCodes.OK))
          .recover(failed)

  private def refused(request: HttpRequest)(using ActorSystem[?]): Future[HttpResponse] =
    request.discardEntityBytes(): Unit
    Future.successful(
      problem(
        HttpProblem.forbidden("this address is not signed for that, or its signature has expired")
      )
    )

  private val failed: PartialFunction[Throwable, HttpResponse] = { case error: CommandError =>
    problem(HttpProblem.from(error))
  }
