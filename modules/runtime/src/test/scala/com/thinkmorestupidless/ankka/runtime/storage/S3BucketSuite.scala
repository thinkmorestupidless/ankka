package com.thinkmorestupidless.ankka.runtime.storage

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.*

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.concurrent.duration.*

/**
 * The S3 store against the installation's own object store, Garage, in a container.
 *
 * The first three cases are research's spike S1, kept: they hold, with the signer and a plain JDK
 * client and nothing of the client's, the three things the client rests on — a put whose payload is
 * not hashed, URLs signed in the query string, and a listing that pages.
 */
class S3BucketSuite extends BucketContract:

  override val munitTimeout: Duration = 3.minutes

  private var garage: GarageContainer        = null
  private var credentials: SigV4.Credentials = null
  private val bucket                         = "t.svc.files"
  private val http                           = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    garage = GarageContainer.start()
    credentials = garage.key("t.svc", garage.bucket(bucket))

  override def afterAll(): Unit = if garage != null then garage.stop()

  private val counting = CountingClient(
    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
  )

  private def s3Bucket(
      key: SigV4.Credentials = credentials,
      endpoint: URI = garage.s3,
      exposure: BucketExposure = BucketExposure.Signed,
      publicEndpoint: Option[URI] = Some(garage.s3),
      name: Option[String] = None,
      publicUrl: Option[String] = None,
      timeout: FiniteDuration = 10.seconds
  ) =
    S3Bucket(
      S3Target(endpoint, GarageContainer.Region, bucket, Some(key), publicEndpoint),
      name,
      exposure,
      publicUrl,
      timeout,
      7.days,
      counting
    )

  protected def contractBucket: Bucket = s3Bucket()
  protected def storeName: String      = "garage"

  private def signed(
      method: String,
      path: String,
      query: Vector[(String, String)] = Vector.empty,
      extra: Map[String, String] = Map.empty,
      payload: String = SigV4.EmptyPayload
  ) =
    val request = SigV4.Request(method, garage.s3, path, query)
    val headers = SigV4.sign(
      request,
      extra + ("x-amz-content-sha256" -> payload),
      payload,
      credentials,
      GarageContainer.Region,
      "s3",
      Instant.now()
    ) ++ extra + ("x-amz-content-sha256" -> payload)
    val builder = HttpRequest.newBuilder(request.uri)
    headers.foreach((k, v) => builder.header(k, v))
    builder

  test("S1: a put whose payload is not hashed, of a declared length, is kept whole") {
    val body   = Array.tabulate[Byte](3 * 1024 * 1024)(i => (i % 251).toByte)
    val stream = HttpRequest.BodyPublishers.ofInputStream(() => new ByteArrayInputStream(body))
    val put = http.send(
      signed(
        "PUT",
        s"/$bucket/spike/large.bin",
        extra = Map("Content-Type" -> "application/x-spike"),
        payload = SigV4.UnsignedPayload
      )
        .PUT(HttpRequest.BodyPublishers.fromPublisher(stream, body.length.toLong))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(put.statusCode, 200, put.body)
    val head = http.send(
      signed("HEAD", s"/$bucket/spike/large.bin")
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.discarding()
    )
    assertEquals(head.statusCode, 200)
    assertEquals(head.headers.firstValue("Content-Length").orElse(""), body.length.toString)
    assertEquals(head.headers.firstValue("Content-Type").orElse(""), "application/x-spike")
  }

  test(
    "S1: a URL signed in its query string reads and keeps an object from a client that adds nothing"
  ) {
    val keep = SigV4.presign(
      SigV4.Request("PUT", garage.s3, s"/$bucket/spike/signed.txt"),
      credentials,
      GarageContainer.Region,
      "s3",
      60,
      Instant.now()
    )
    val put = http.send(
      HttpRequest
        .newBuilder(keep)
        .header("Content-Type", "text/plain")
        .PUT(HttpRequest.BodyPublishers.ofString("kept through a URL"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(put.statusCode, 200, put.body)
    val read = SigV4.presign(
      SigV4.Request("GET", garage.s3, s"/$bucket/spike/signed.txt"),
      credentials,
      GarageContainer.Region,
      "s3",
      60,
      Instant.now()
    )
    val get =
      http.send(HttpRequest.newBuilder(read).GET().build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(get.statusCode, 200, get.body)
    assertEquals(get.body, "kept through a URL")
    assertEquals(get.headers.firstValue("Content-Type").orElse(""), "text/plain")
  }

  test(
    "S1: a listing of more objects than one answer carries pages through its continuation token"
  ) {
    for i <- 1 to 5 do
      val r = http.send(
        signed("PUT", s"/$bucket/paged/$i.txt", payload = SigV4.UnsignedPayload)
          .PUT(HttpRequest.BodyPublishers.ofString(i.toString))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(r.statusCode, 200, r.body)
    def page(token: Option[String]) =
      val query = Vector("list-type" -> "2", "prefix" -> "paged/", "max-keys" -> "2") ++ token.map(
        "continuation-token" -> _
      )
      http
        .send(signed("GET", s"/$bucket", query).GET().build(), HttpResponse.BodyHandlers.ofString())
        .body
    val keys = "<Key>([^<]+)</Key>".r
    val next = "<NextContinuationToken>([^<]+)</NextContinuationToken>".r
    val names = Iterator
      .unfold[Vector[String], Option[Option[String]]](Some(None)) {
        case None => None
        case Some(token) =>
          val body = page(token)
          Some(
            keys.findAllMatchIn(body).map(_.group(1)).toVector -> next
              .findFirstMatchIn(body)
              .map(m => Some(xmlText(m.group(1))))
          )
      }
      .toVector
    assertEquals(names.map(_.size), Vector(2, 2, 1))
    assertEquals(names.flatten, (1 to 5).map(i => s"paged/$i.txt").toVector)
  }

  // Research's spike S2, the store's half: Garage's web listener picks a bucket by the whole `Host`
  // when it does not end in the root domain, dots and all. The gateway's half, the rewrite that sets
  // that `Host`, is `GarageWebClusterSuite`'s.
  test(
    "S2: the web listener serves a bucket named with dots by its whole name as the Host, with its content type"
  ) {
    val photos = "t.svc.photos"
    val id     = garage.bucket(photos)
    val key    = garage.key("t.svc.web", id)
    val saved  = credentials
    credentials = key
    try
      val put = http.send(
        signed(
          "PUT",
          s"/$photos/listings/42/front.png",
          extra = Map("Content-Type" -> "image/png"),
          payload = SigV4.UnsignedPayload
        )
          .PUT(HttpRequest.BodyPublishers.ofString("not really a png"))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(put.statusCode, 200, put.body)
    finally credentials = saved
    val before = rawGet(garage.web, photos, "/listings/42/front.png")
    assert(!before.startsWith("HTTP/1.1 200"), s"served before website access was on: $before")
    garage.website(id, enabled = true)
    val after = rawGet(garage.web, photos, "/listings/42/front.png")
    assert(after.startsWith("HTTP/1.1 200"), after)
    assert(after.toLowerCase.contains("content-type: image/png"), after)
    assert(after.endsWith("not really a png"), after)
  }

  /** A GET with a `Host` of our choosing, which the JDK's client will not send. */
  private def rawGet(base: java.net.URI, host: String, path: String): String =
    val socket = new java.net.Socket(base.getHost, base.getPort)
    try
      socket.getOutputStream.write(
        s"GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".getBytes("UTF-8")
      )
      new String(socket.getInputStream.readAllBytes(), "UTF-8")
    finally socket.close()

  private def xmlText(s: String): String =
    s.replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&apos;", "'")

  // ── the S3 store's own cases (T018, T028) ────────────────────────────────────

  private def refusal(code: ErrorCode)(body: => Any): String =
    val e = intercept[CommandError](body)
    assertEquals(e.code, code, e.getMessage)
    e.getMessage

  test("a 20 MiB put reads its stream once, and is read back whole") {
    val length = 20L * 1024 * 1024
    var reads  = 0L
    val source = new InputStream:
      private var at = 0L
      def read(): Int =
        if at >= length then -1
        else
          at += 1; reads += 1; (at % 251).toInt
      override def read(b: Array[Byte], off: Int, len: Int): Int =
        if at >= length then -1
        else
          val n = math.min(len.toLong, length - at).toInt
          var i = 0
          while i < n do
            at += 1; b(off + i) = (at % 251).toByte; i += 1
          reads += n
          n
    s3Bucket().put("large/twenty.bin", source, length, "application/octet-stream")
    assertEquals(reads, length)
    assertEquals(s3Bucket().stat("large/twenty.bin").map(_.length), Some(length))
    val back = s3Bucket().get("large/twenty.bin").getOrElse(fail("nothing kept"))
    val sum = scala.util.Using.resource(back)(o =>
      Iterator.continually(o.body.read()).takeWhile(_ >= 0).foldLeft(0L)((n, _) => n + 1)
    )
    assertEquals(sum, length)
  }

  test("a listing of 1,003 objects is read in two pages, as the store answers 1,000 at most") {
    val b = s3Bucket()
    (1 to 1003).foreach(i => b.putBytes(f"many/$i%04d", Array[Byte](1), "application/octet-stream"))
    val before = counting.listings.get()
    val names  = b.list("many/").map(_.name).toVector
    assertEquals(names.size, 1003)
    assertEquals(names.head, "many/0001")
    assertEquals(counting.listings.get() - before, 2)
  }

  test("a key the store does not know is refused as unavailable, naming the store") {
    val wrong = s3Bucket(key = SigV4.Credentials("GKnobody", "wrong"))
    val message =
      refusal(ErrorCode.Unavailable)(wrong.putBytes("a.txt", Array[Byte](1), "text/plain"))
    assert(message.contains(garage.s3.toString) && message.contains("refused"), message)
  }

  test("a store that cannot be reached is refused as unavailable, saying so") {
    val closed = { val s = new java.net.ServerSocket(0); val p = s.getLocalPort; s.close(); p }
    val gone   = s3Bucket(endpoint = URI.create(s"http://127.0.0.1:$closed"), timeout = 2.seconds)
    val message =
      refusal(ErrorCode.Unavailable)(gone.putBytes("a.txt", Array[Byte](1), "text/plain"))
    assert(message.contains("cannot be reached"), message)
  }

  test("a bucket with no storage credential refuses every call, naming objectStorageCredential") {
    val none = S3Bucket(
      S3Target(garage.s3, GarageContainer.Region, bucket, None, None),
      None,
      BucketExposure.Private,
      None,
      10.seconds,
      7.days,
      counting
    )
    assert(refusal(ErrorCode.Unavailable)(none.get("a.txt")).contains("objectStorageCredential"))
  }

  test(
    "a signed URL reads an object, and one for keeping keeps it, from a client that adds nothing"
  ) {
    val b = s3Bucket()
    b.putBytes("exports/march.pdf", "march".getBytes(UTF_8), "application/pdf")
    val read = b.signedUrl("exports/march.pdf", SignedAccess.Read, 1.minute)
    val got =
      http.send(HttpRequest.newBuilder(read).GET().build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(got.statusCode, 200, got.body)
    assertEquals(got.body, "march")
    val keep = b.signedUrl("uploads/passport.pdf", SignedAccess.Keep, 1.minute)
    val put = http.send(
      HttpRequest
        .newBuilder(keep)
        .header("Content-Type", "application/pdf")
        .PUT(HttpRequest.BodyPublishers.ofString("passport"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(put.statusCode, 200, put.body)
    assertEquals(
      b.getBytes("uploads/passport.pdf").map((t, v) => t -> new String(v, UTF_8)),
      Some("application/pdf" -> "passport")
    )
  }

  test("a URL signed for reading does not keep, and one for another object reads nothing") {
    val b = s3Bucket()
    b.putBytes("exports/april.pdf", "april".getBytes(UTF_8), "application/pdf")
    val read = b.signedUrl("exports/april.pdf", SignedAccess.Read, 1.minute)
    val put = http.send(
      HttpRequest.newBuilder(read).PUT(HttpRequest.BodyPublishers.ofString("x")).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(put.statusCode, 403, put.body)
    val other = URI.create(read.toString.replace("april.pdf", "march.pdf"))
    assertEquals(
      http
        .send(HttpRequest.newBuilder(other).GET().build(), HttpResponse.BodyHandlers.ofString())
        .statusCode,
      403
    )
  }

  test("a signed URL that has expired is refused by the store") {
    val b = s3Bucket()
    b.putBytes("exports/old.pdf", "the kept bytes".getBytes(UTF_8), "application/pdf")
    val past = SigV4.presign(
      SigV4.Request("GET", garage.s3, s"/$bucket/exports/old.pdf"),
      credentials,
      GarageContainer.Region,
      "s3",
      1,
      Instant.now().minusSeconds(120)
    )
    val expired =
      http.send(HttpRequest.newBuilder(past).GET().build(), HttpResponse.BodyHandlers.ofString())
    // Garage answers 400, AWS 403: either is the store refusing, and neither shows the object.
    assert(Set(400, 403).contains(expired.statusCode), expired.statusCode)
    assert(!expired.body.contains("the kept bytes"), expired.body)
  }

  test(
    "a signed URL is refused for a private bucket, past seven days, and on the unnamed bucket without an exposure"
  ) {
    assert(
      refusal(ErrorCode.BadRequest)(
        s3Bucket(exposure = BucketExposure.Private, name = Some("ledger"))
          .signedUrl("a", SignedAccess.Read)
      ).contains("private")
    )
    assert(
      refusal(ErrorCode.BadRequest)(s3Bucket().signedUrl("a", SignedAccess.Read, 8.days))
        .contains("7 days")
    )
    assert(
      refusal(ErrorCode.BadRequest)(
        s3Bucket(publicEndpoint = None).signedUrl("a", SignedAccess.Read)
      ).contains("exposeObjectStorage")
    )
  }

  test("a public bucket answers its address by computation; any other refuses") {
    val photos = s3Bucket(
      exposure = BucketExposure.Public,
      name = Some("photos"),
      publicUrl = Some("https://storage.example.test/t.svc.photos")
    )
    assertEquals(
      photos.url("listings/42/front.jpg").toString,
      "https://storage.example.test/t.svc.photos/listings/42/front.jpg"
    )
    assert(refusal(ErrorCode.BadRequest)(s3Bucket().url("a")).contains("unnamed bucket"))
  }

/** An `HttpClient` that counts the listings it sends, and sends everything through `underlying`. */
final class CountingClient(underlying: HttpClient) extends HttpClient:
  val listings = new java.util.concurrent.atomic.AtomicInteger()
  private def counted(r: HttpRequest): Unit =
    if Option(r.uri.getRawQuery).exists(_.contains("list-type=2")) then
      listings.incrementAndGet(): Unit
  def cookieHandler()   = underlying.cookieHandler()
  def connectTimeout()  = underlying.connectTimeout()
  def followRedirects() = underlying.followRedirects()
  def proxy()           = underlying.proxy()
  def sslContext()      = underlying.sslContext()
  def sslParameters()   = underlying.sslParameters()
  def authenticator()   = underlying.authenticator()
  def version()         = underlying.version()
  def executor()        = underlying.executor()
  def send[T](r: HttpRequest, h: HttpResponse.BodyHandler[T]): HttpResponse[T] =
    counted(r); underlying.send(r, h)
  def sendAsync[T](r: HttpRequest, h: HttpResponse.BodyHandler[T]) =
    counted(r); underlying.sendAsync(r, h)
  def sendAsync[T](
      r: HttpRequest,
      h: HttpResponse.BodyHandler[T],
      p: HttpResponse.PushPromiseHandler[T]
  ) =
    counted(r); underlying.sendAsync(r, h, p)
