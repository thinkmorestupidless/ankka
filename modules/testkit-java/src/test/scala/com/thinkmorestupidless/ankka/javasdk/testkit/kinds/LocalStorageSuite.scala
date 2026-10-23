package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, Settings}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The Java row of `features/storage-client/languages.feature`'s "the storage client in every
 * language keeps its objects in a directory on a developer's machine": a Java service started with
 * no variable of an object store keeps what its endpoint puts in a directory store, as the runtime
 * chooses for it.
 */
class LocalStorageSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private val directory         = Files.createTempDirectory("ankka-java-storage")
  private val descriptor        = Files.createTempDirectory("ankka-java-descriptor")
  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    Files.writeString(
      descriptor.resolve("service.json"),
      """{"service":{"provisionObjectStorage":true}}"""
    ): Unit
    kit = AnkkaTestKit.start(
      Ankka.service("listings").withEndpoint(classOf[Vault]),
      Settings
        .defaults()
        .withScriptedService("stock")
        .withStorageFromEnvironment()
        .withConfig("ankka.storage.directory", directory.toString)
        .withConfig("ankka.storage.descriptor-directory", descriptor.toString)
    )

  override def afterAll(): Unit =
    try if kit != null then kit.close()
    finally Seq(directory, descriptor).foreach(deleteTree)

  private def deleteTree(root: Path): Unit =
    val walk = Files.walk(root)
    try walk.iterator.asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p): Unit)
    finally walk.close()

  test("an object a Java handler keeps lands in the directory store on a developer's machine") {
    val request = HttpRequest
      .newBuilder(kit.http().resolve("/vault/objects/front.jpg"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("""{"value":"the front of 42"}"""))
      .build()
    assertEquals(
      kit.httpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode,
      204
    )
    val files =
      val walk = Files.walk(directory)
      try walk.iterator.asScala.filter(Files.isRegularFile(_)).toVector
      finally walk.close()
    assert(
      files.exists(f => Files.readString(f, UTF_8) == "the front of 42"),
      s"no file under $directory holds the object: ${files.mkString(", ")}"
    )
  }
