package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.storage.{DirectoryObjectStorage, LocalBuckets}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The directory store's addresses, served by the service's own HTTP port at `/_ankka/storage`
 * (feature 055): what a browser does with a signed URL or a public address on a developer's
 * machine.
 */
class DirectoryStorageEndpointSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "directory-storage",
      com.typesafe.config.ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )

  private val declared = LocalBuckets.Declared(
    true,
    Map(
      "photos"  -> BucketExposure.Public,
      "exports" -> BucketExposure.Signed,
      "ledger"  -> BucketExposure.Private
    )
  )
  private val root   = Files.createTempDirectory("ankka-directory-endpoint")
  private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  /** A server over a store at `at`, and the store, its addresses on the server's own port. */
  private def serving(at: Path): (HttpServer, DirectoryObjectStorage) =
    val server = HttpServer.at("127.0.0.1", 0)()
    val store = DirectoryObjectStorage
      .open(at, Some(declared), () => s"http://127.0.0.1:${server.boundPort.get}")
      .fold(problem => throw IllegalStateException(problem), identity)
    server.serve(Vector.empty, "127.0.0.1", 0, 10.seconds, directoryStorage = Some(store))
    (server, store)

  private val (server, store) = serving(root)

  override def afterAll(): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def get(uri: URI): HttpResponse[String] =
    client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())

  private def put(uri: URI, body: String, contentType: String): HttpResponse[String] =
    client.send(
      HttpRequest
        .newBuilder(uri)
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  private def keepBytes(bucket: Bucket, name: String, text: String, contentType: String): Unit =
    val bytes = text.getBytes(UTF_8)
    bucket.put(name, java.io.ByteArrayInputStream(bytes), bytes.length.toLong, contentType)

  test("a URL signed for reading reads the object, with its content type and length") {
    keepBytes(store.default, "exports/march.pdf", "march", "application/pdf")
    val read = get(store.default.signedUrl("exports/march.pdf", SignedAccess.Read, 1.minute))
    assertEquals(read.statusCode, 200, read.body)
    assertEquals(read.body, "march")
    assertEquals(read.headers.firstValue("Content-Type").orElse(""), "application/pdf")
    assertEquals(read.headers.firstValue("Content-Length").orElse(""), "5")
  }

  test("a URL signed for keeping keeps what is sent, which a URL signed for reading then reads") {
    val exports = store.bucket("exports")
    val kept = put(
      exports.signedUrl("uploads/passport.pdf", SignedAccess.Keep),
      "passport",
      "application/pdf"
    )
    assertEquals(kept.statusCode, 200, kept.body)
    assertEquals(exports.stat("uploads/passport.pdf").map(_.contentType), Some("application/pdf"))
    assertEquals(get(exports.signedUrl("uploads/passport.pdf", SignedAccess.Read)).body, "passport")
  }

  test("a URL signed for reading does not keep, and one signed for keeping does not read") {
    keepBytes(store.default, "exports/april.pdf", "april", "application/pdf")
    val read = store.default.signedUrl("exports/april.pdf", SignedAccess.Read)
    assertEquals(put(read, "x", "text/plain").statusCode, 403)
    assertEquals(
      get(store.default.signedUrl("exports/april.pdf", SignedAccess.Keep)).statusCode,
      403
    )
    assertEquals(store.default.get("exports/april.pdf").map(_.length), Some(5L))
  }

  test("an expired URL and a wrong token are refused") {
    keepBytes(store.default, "exports/old.pdf", "old", "application/pdf")
    val url  = store.default.signedUrl("exports/old.pdf", SignedAccess.Read, 1.minute).toString
    val past = url.replaceFirst("expires=\\d+", "expires=1")
    assertEquals(get(URI.create(past)).statusCode, 403)
    val wrong = url.replaceFirst("token=[0-9a-f]", "token=z")
    assertEquals(get(URI.create(wrong)).statusCode, 403)
    val other = url.replace("old.pdf", "march.pdf")
    assertEquals(get(URI.create(other)).statusCode, 403)
  }

  test("a public bucket is read without a signature; any other bucket is not") {
    keepBytes(store.bucket("photos"), "listings/42/front.jpg", "jpeg", "image/jpeg")
    val photo = get(store.bucket("photos").url("listings/42/front.jpg"))
    assertEquals(photo.statusCode, 200, photo.body)
    assertEquals(photo.headers.firstValue("Content-Type").orElse(""), "image/jpeg")
    keepBytes(store.bucket("exports"), "a.pdf", "a", "application/pdf")
    keepBytes(store.bucket("ledger"), "a.csv", "a", "text/csv")
    val base = s"http://127.0.0.1:${server.boundPort.get}/_ankka/storage"
    assertEquals(get(URI.create(s"$base/exports/a.pdf")).statusCode, 403)
    assertEquals(get(URI.create(s"$base/ledger/a.csv")).statusCode, 403)
    assertEquals(get(URI.create(s"$base/_default/exports/march.pdf")).statusCode, 403)
    // A keep needs a signature even on a public bucket.
    assertEquals(
      put(URI.create(s"$base/photos/listings/42/back.jpg"), "x", "image/jpeg").statusCode,
      403
    )
  }

  test("an object that is not there is 404, and so is a bucket the store does not have") {
    assertEquals(
      get(store.default.signedUrl("exports/none.pdf", SignedAccess.Read)).statusCode,
      404
    )
    assertEquals(get(store.bucket("photos").url("listings/none.jpg")).statusCode, 404)
    val base = s"http://127.0.0.1:${server.boundPort.get}/_ankka/storage"
    assertEquals(get(URI.create(s"$base/archive/a.txt")).statusCode, 404)
  }

  test("an empty object is read as empty") {
    keepBytes(store.default, "empty.txt", "", "text/plain")
    val read = get(store.default.signedUrl("empty.txt", SignedAccess.Read))
    assertEquals(read.statusCode, 200, read.body)
    assertEquals(read.body, "")
  }

  test("a URL made before a restart is honoured after it, by a store over the same directory") {
    keepBytes(store.default, "exports/kept.pdf", "kept", "application/pdf")
    val before     = store.default.signedUrl("exports/kept.pdf", SignedAccess.Read, 10.minutes)
    val (again, _) = serving(root)
    try
      val moved = URI.create(
        before.toString.replace(s":${server.boundPort.get}/", s":${again.boundPort.get}/")
      )
      val read = get(moved)
      assertEquals(read.statusCode, 200, read.body)
      assertEquals(read.body, "kept")
    finally again.stop()
  }
