package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, Done}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.{Bucket, ObjectRules, ObjectStorage, SignedAccess}

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

/**
 * The service's buckets through the storage client (feature 055): keep and read an object, make a
 * signed URL and answer a public address, in the unnamed bucket or a named one.
 *
 * Not part of the cart's domain: it is here so the platform's own suites have a real service that
 * reaches its buckets the way a developer's code does, in a cluster. It admits only this service's
 * own instances; a refusal of the storage client is answered as the client gives it.
 */
final class StorageEndpoint(storage: ObjectStorage) extends HttpEndpoint("/storage"):

  val acl: Acl = Acl.allowCallers(Callers.self)

  private def bucket: Bucket =
    query.optional[String]("bucket").fold(storage.default)(storage.bucket)

  postBody("/objects") { (request: StorageEndpoint.Keep) =>
    request.bucket
      .fold(storage.default)(storage.bucket)
      .putBytes(request.name, request.text.getBytes(UTF_8), request.contentType)
    Done: Done
  }

  get("/objects") { () =>
    val name = query.required[String]("name")
    bucket
      .getBytes(name)
      .fold(throw HttpProblem.notFound(s"no object '$name'"))((contentType, body) =>
        Bytes(contentType, body)
      )
  }

  get("/signed") { () =>
    val access = query.optional[String]("access") match
      case Some("keep") => SignedAccess.Keep
      case _            => SignedAccess.Read
    val span = query.optional[Int]("seconds").fold(ObjectRules.DefaultSignedUrl)(_.seconds)
    bucket.signedUrl(query.required[String]("name"), access, span).toString
  }

  get("/url") { () =>
    bucket.url(query.required[String]("name")).toString
  }

object StorageEndpoint:

  final case class Keep(bucket: Option[String], name: String, contentType: String, text: String)

  given JsonValueCodec[Keep] = Codecs.make[Keep]
