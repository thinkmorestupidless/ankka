package com.thinkmorestupidless.ankka.http

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Bodies larger than the instance's memory pass through an endpoint (feature 055): a stream route
 * reads its request as it arrives and a `Streamed` response is written as it is read. The heap is
 * held at 512 MiB (`http / Test / javaOptions`) and the bodies are 2 GiB, so a body held whole
 * anywhere fails the run with an `OutOfMemoryError` rather than passing it.
 *
 * Its last case is `client.feature`'s "an object larger than an instance's memory passes through
 * the storage client": an endpoint keeps a body twice the heap through the storage client and reads
 * it back, on the directory store. The store in memory holds what it keeps, so it cannot carry that
 * scenario; the S3 store's put reads its stream once, which `S3BucketSuite` counts.
 */
class StreamedBodySuite extends munit.FunSuite:

  override val munitTimeout: Duration = 5.minutes

  private val Big: Long = 2L * 1024 * 1024 * 1024

  /**
   * A stream of `size` bytes made as they are read, counting what was read and whether it closed.
   */
  private final class Generated(size: Long) extends InputStream:
    val consumed = AtomicLong()
    val closed   = AtomicBoolean(false)
    def read(): Int =
      if consumed.get >= size then -1
      else
        consumed.incrementAndGet(): Unit
        'a'.toInt
    override def read(b: Array[Byte], off: Int, len: Int): Int =
      val left = size - consumed.get
      if left <= 0 then -1
      else
        val n = math.min(len.toLong, left).toInt
        java.util.Arrays.fill(b, off, off + n, 'a'.toByte)
        consumed.addAndGet(n.toLong): Unit
        n
    override def close(): Unit = closed.set(true)

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "streamed-body",
      com.typesafe.config.ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )

  private val handlerRuns                               = AtomicInteger()
  @volatile private var lastResponse: Option[Generated] = None

  private final class Files extends HttpEndpoint("/files"):
    val acl: Acl = Acl.AllowAll

    // Counts the body as it is read: the sink that holds nothing.
    putStream("/count") { (body: StreamedBody) =>
      handlerRuns.incrementAndGet(): Unit
      val buffer = new Array[Byte](64 * 1024)
      var total  = 0L
      var n      = body.body.read(buffer)
      while n >= 0 do
        total += n
        n = body.body.read(buffer)
      s"${body.contentType} ${body.length.getOrElse(-1L)} $total"
    }

    get("/generated/{size}") { (size: Long) =>
      val generated = Generated(size)
      lastResponse = Some(generated)
      Streamed("application/octet-stream", Some(size), generated)
    }

    get("/unsized/{size}") { (size: Long) =>
      Streamed("text/plain", None, Generated(size))
    }

    postBody("/echo")((text: String) => s"echoed $text")

  /** An endpoint over the storage client, as a service writes one: the body goes to the bucket. */
  private final class Objects(storage: com.thinkmorestupidless.ankka.sdk.ObjectStorage)
      extends HttpEndpoint("/objects"):
    val acl: Acl = Acl.AllowAll

    putStream("/{name}") { (name: String, body: StreamedBody) =>
      val length = body.length.getOrElse(throw HttpProblem.badRequest("a keep declares its length"))
      storage.default.put(name, body.body, length, body.contentType)
      com.thinkmorestupidless.ankka.core.Done: com.thinkmorestupidless.ankka.core.Done
    }

    get("/{name}") { (name: String) =>
      storage.default
        .get(name)
        .fold(throw HttpProblem.notFound(s"no object '$name'"))(o =>
          Streamed(o.contentType, Some(o.length), o.body)
        )
    }

  private val storageRoot = java.nio.file.Files.createTempDirectory("ankka-streamed-body")
  private val storage = com.thinkmorestupidless.ankka.runtime.storage.DirectoryObjectStorage
    .open(storageRoot, None, () => "http://127.0.0.1")
    .fold(problem => throw IllegalStateException(problem), identity)

  private val server  = HttpServer.at("127.0.0.1", 0)()
  private val limited = HttpServer.at("127.0.0.1", 0)()
  private val client  = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def beforeAll(): Unit =
    val heap = Runtime.getRuntime.maxMemory
    assert(
      heap <= 1024L * 1024 * 1024,
      s"the heap is $heap bytes: a body of twice it cannot be proved to pass through. " +
        "Set http / Test / javaOptions += \"-Xmx512m\""
    )
    server.serve(Vector(new Files, new Objects(storage)), "127.0.0.1", 0, 10.seconds)
    limited.serve(Vector(new Files), "127.0.0.1", 0, 10.seconds, maxStream = Some(1024L * 1024))

  override def afterAll(): Unit =
    server.stop()
    limited.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit
    val walk = java.nio.file.Files.walk(storageRoot)
    try
      import scala.jdk.CollectionConverters.*
      walk.iterator.asScala.toVector.reverse.foreach(p =>
        java.nio.file.Files.deleteIfExists(p): Unit
      )
    finally walk.close()

  private def uri(on: HttpServer, path: String) =
    URI.create(s"http://127.0.0.1:${on.boundPort.get}$path")

  private def put(on: HttpServer, size: Long): (HttpResponse[String], Generated) =
    val body = Generated(size)
    val publisher = HttpRequest.BodyPublishers.fromPublisher(
      HttpRequest.BodyPublishers.ofInputStream(() => body),
      size
    )
    val response = client.send(
      HttpRequest
        .newBuilder(uri(on, "/files/count"))
        .header("Content-Type", "video/mp4")
        .PUT(publisher)
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (response, body)

  test("a request body twice the heap passes through a stream route, read once") {
    val (response, sent) = put(server, Big)
    assertEquals(response.statusCode, 200, response.body)
    assertEquals(response.body, s"video/mp4 $Big $Big")
    assertEquals(sent.consumed.get, Big)
  }

  test("a streamed response twice the heap is written as it is read, with its length") {
    val response = client.send(
      HttpRequest.newBuilder(uri(server, s"/files/generated/$Big")).build(),
      HttpResponse.BodyHandlers.ofInputStream()
    )
    assertEquals(response.statusCode, 200)
    assertEquals(response.headers.firstValue("Content-Length").orElse(""), Big.toString)
    val buffer = new Array[Byte](64 * 1024)
    var total  = 0L
    val in     = response.body
    try
      var n = in.read(buffer)
      while n >= 0 do
        total += n
        n = in.read(buffer)
    finally in.close()
    assertEquals(total, Big)
    assert(lastResponse.exists(_.closed.get), "the server did not close the stream it wrote")
  }

  test("a streamed response with no length is sent chunked") {
    val response = client.send(
      HttpRequest.newBuilder(uri(server, "/files/unsized/100000")).build(),
      HttpResponse.BodyHandlers.ofByteArray()
    )
    assertEquals(response.statusCode, 200)
    assertEquals(response.body.length, 100000)
    assertEquals(response.headers.firstValue("Content-Type").orElse(""), "text/plain")
    assert(response.headers.firstValue("Content-Length").isEmpty)
  }

  test("a client that goes away closes the stream the response was reading") {
    val response = client.send(
      HttpRequest.newBuilder(uri(server, s"/files/generated/$Big")).build(),
      HttpResponse.BodyHandlers.ofInputStream()
    )
    val in = response.body
    in.readNBytes(1024 * 1024): Unit
    in.close()
    val deadline = 30.seconds.fromNow
    while !lastResponse.exists(_.closed.get) && deadline.hasTimeLeft() do Thread.sleep(100)
    assert(lastResponse.exists(_.closed.get), "the response's stream was never closed")
    assert(
      lastResponse.exists(_.consumed.get < Big),
      "the whole response was read after the client left"
    )
  }

  test("a declared body above the limit is 413 and the handler never runs") {
    val before           = handlerRuns.get
    val (response, sent) = put(limited, 2L * 1024 * 1024)
    assertEquals(response.statusCode, 413, response.body)
    assert(response.body.contains("at most 1048576"), response.body)
    assertEquals(handlerRuns.get, before)
    assert(sent.consumed.get < 2L * 1024 * 1024, s"the whole body was read: ${sent.consumed.get}")
  }

  test("a strict route beside the stream routes behaves as before") {
    val response = client.send(
      HttpRequest
        .newBuilder(uri(server, "/files/echo"))
        .header("Content-Type", "text/plain")
        .POST(HttpRequest.BodyPublishers.ofString("hi"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode, 200)
    assertEquals(response.body, "echoed hi")
  }

  test("an object larger than an instance's memory passes through the storage client") {
    val sent = Generated(Big)
    val kept = client.send(
      HttpRequest
        .newBuilder(uri(server, "/objects/film.mp4"))
        .header("Content-Type", "video/mp4")
        .PUT(
          HttpRequest.BodyPublishers
            .fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() => sent), Big)
        )
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(kept.statusCode, 204, kept.body)
    assertEquals(sent.consumed.get, Big)
    assertEquals(
      storage.default.stat("film.mp4").map(i => (i.contentType, i.length)),
      Some(("video/mp4", Big))
    )

    val read = client.send(
      HttpRequest.newBuilder(uri(server, "/objects/film.mp4")).build(),
      HttpResponse.BodyHandlers.ofInputStream()
    )
    assertEquals(read.statusCode, 200)
    assertEquals(read.headers.firstValue("Content-Type").orElse(""), "video/mp4")
    // The object read back is the object kept: every byte, and every byte an 'a'.
    val buffer = new Array[Byte](64 * 1024)
    var total  = 0L
    var other  = false
    val in     = read.body
    try
      var n = in.read(buffer)
      while n >= 0 do
        var i = 0
        while i < n do
          if buffer(i) != 'a'.toByte then other = true
          i += 1
        total += n
        n = in.read(buffer)
    finally in.close()
    assertEquals(total, Big)
    assert(!other, "the object read back holds a byte the object kept did not")
  }
