package com.thinkmorestupidless.ankka.http

/** An HTML page, answered as `text/html; charset=UTF-8`. */
final case class Html(markup: String)

object Html:
  given toResponse: ToResponse[Html] = new ToResponse[Html]:
    def status(value: Html) = 200
    val contentType         = "text/html; charset=UTF-8"
    def write(value: Html)  = value.markup.getBytes("UTF-8")

/** Raw bytes under a content type of the handler's choosing: a stylesheet, an image, a download. */
final case class Bytes(contentType: String, body: Array[Byte])

object Bytes:
  given toResponse: ToResponse[Bytes] = new ToResponse[Bytes]:
    def status(value: Bytes) = 200
    // Per value, not per instance: the trait's `contentType` is fixed, so the server reads it
    // from the encoded response the route built, which `HttpEndpoint` takes from here.
    val contentType         = "application/octet-stream"
    def write(value: Bytes) = value.body

/**
 * A response with a status and headers of the handler's choosing around any body the endpoint can
 * already answer: `Respond(Html(page), headers = Vector("Set-Cookie" -> cookie))`,
 * `Respond.redirect("/account")`.
 *
 * The one addition a website needs from an HTTP module built for JSON APIs. Without a `Location`
 * nobody can be sent to an identity provider or a payment page, and without `Set-Cookie` nobody
 * stays logged in. `Content-Type` is not a header here: it comes from the body's own `ToResponse`
 * (or, for `Bytes`, from the value), and the server sets it on the entity.
 */
final case class Respond[A](
    body: A,
    status: Int = 200,
    headers: Vector[(String, String)] = Vector.empty
)

object Respond:

  /** `303 See Other` by default: the right answer to a form post, and safe after a `POST`. */
  def redirect(location: String, status: Int = 303): Respond[Unit] =
    Respond((), status, Vector("Location" -> location))

  given toResponse[A](using inner: ToResponse[A]): ToResponse[Respond[A]] =
    new ToResponse[Respond[A]]:
      def status(value: Respond[A])           = value.status
      val contentType                         = inner.contentType
      def write(value: Respond[A])            = inner.write(value.body)
      override def headers(value: Respond[A]) = value.headers ++ inner.headers(value.body)

/**
 * A request body read as it arrives, for a route declared with `postStream` or `putStream` (feature
 * 055): never held whole, so a body larger than the instance's memory passes through. `length` is
 * the declared `Content-Length`, when the client sent one. The handler reads `body` on its own
 * thread; the server closes it when the handler returns.
 */
final case class StreamedBody(contentType: String, length: Option[Long], body: java.io.InputStream)

/**
 * A response body written as it is read, never held whole: an object read from a bucket, say. The
 * server closes `body` when the response is written or the client goes away. With a `length` the
 * response says it; without, it is sent chunked.
 */
final case class Streamed(contentType: String, length: Option[Long], body: java.io.InputStream)

object Streamed:
  given toResponse: ToResponse[Streamed] = new ToResponse[Streamed]:
    def status(value: Streamed) = 200
    val contentType             = "application/octet-stream"
    // Never called: a route recognises a streamed value and hands the stream to the server.
    def write(value: Streamed) =
      throw IllegalStateException("a streamed response is written as it is read, never whole")
