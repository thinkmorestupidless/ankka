package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The Java samples of the object storage page: the listing, the photos endpoint and the kit. */
class DocsStorageSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka.service("listings").register(classOf[Listing]).withEndpoint(classOf[PhotosEndpoint])
    )
  override def afterAll(): Unit = if kit != null then kit.close()

  private def send(
      method: String,
      path: String,
      body: String = "",
      contentType: String = "application/json"
  ): HttpResponse[String] =
    val publisher =
      if body.isEmpty then HttpRequest.BodyPublishers.noBody()
      else HttpRequest.BodyPublishers.ofString(body)
    kit.httpClient.send(
      HttpRequest
        .newBuilder(kit.http.resolve(path))
        .header("content-type", contentType)
        .method(method, publisher)
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("an object kept through the endpoint is read, described, listed and deleted") {
    val kept = send(
      "POST",
      "/storage/objects",
      """{"name":"notes/a.txt","text":"hello","contentType":"text/plain"}"""
    )
    assertEquals(kept.statusCode, 204, kept.body)
    val read = send("GET", "/storage/objects?name=notes/a.txt")
    assertEquals(read.statusCode, 200, read.body)
    assertEquals(read.body, "hello")
    assertEquals(read.headers.firstValue("content-type").toScala, Some("text/plain"))
    val stat = send("GET", "/storage/stat?name=notes/a.txt").body
    assert(stat.contains(""""name":"notes/a.txt""""), stat)
    assert(stat.contains(""""contentType":"text/plain""""), stat)
    assert(stat.contains(""""length":5"""), stat)
    val listed = send("GET", "/storage/list?prefix=notes/").body
    assert(listed.contains(""""name":"notes/a.txt""""), listed)
    assertEquals(send("DELETE", "/storage/objects?name=notes/a.txt").statusCode, 204)
    val gone = send("GET", "/storage/objects?name=notes/a.txt")
    assertEquals(gone.statusCode, 404, gone.body)
    assert(gone.body.contains("no object 'notes/a.txt'"), gone.body)
  }

  test("an upload streamed with its length lands in the bucket") {
    val body     = "x" * 10_000
    val uploaded = send("POST", "/storage/upload?name=big.bin", body, "application/octet-stream")
    assertEquals(uploaded.statusCode, 204, uploaded.body)
    val kept = kit.storage().defaultBucket().getBytes("big.bin").toScala
    assertEquals(kept.map(b => new String(b, UTF_8)), Some(body))
  }

  test("the entity keeps the photo's name and the bucket its bytes") {
    val recorded = send(
      "POST",
      "/storage/listings/l-1/photos",
      """{"name":"l-1/front.jpg","text":"jpeg","contentType":"image/jpeg"}"""
    )
    assertEquals(recorded.statusCode, 204, recorded.body)
    val photos =
      kit.componentClient.forEventSourcedEntity("l-1").call(DocsRefs.listingPhotos).invoke()
    assertEquals(photos.photos.asScala.toList, List("l-1/front.jpg"))
    val info = kit.storage().defaultBucket().stat("l-1/front.jpg").toScala
    assertEquals(info.map(_.contentType), Some("image/jpeg"))
  }

  test("a public address needs a public bucket, which the unnamed one is not") {
    val refused = send("GET", "/storage/url?name=big.bin")
    assertEquals(refused.statusCode, 400, refused.body)
  }

  test("the kit seeds the store the service reads") {
    assertEquals(StorageChecks.seeded(kit).toScala, Some("image/png"))
    val stat = send("GET", "/storage/stat?name=a/b.png").body
    assert(stat.contains(""""contentType":"image/png""""), stat)
  }
