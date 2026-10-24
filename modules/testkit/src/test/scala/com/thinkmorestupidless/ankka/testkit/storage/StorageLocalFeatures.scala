package com.thinkmorestupidless.ankka.testkit.storage

import com.thinkmorestupidless.ankka.testkit.StorageBackendChoice
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `features/storage-client/local.feature`: a service run on a developer's machine keeps its objects
 * in a directory store and serves the URLs it makes itself (feature 055). Each service here is
 * started as the runtime chooses, from no variables, with its directory store in a directory of the
 * scenario's own, beside a `service.json` that asks for the unnamed bucket and a public `photos`.
 *
 * A browser is the JDK's client, which adds nothing to a request: it sends what the URL says.
 */
final class StorageLocalFeatures
    extends StorageFeatures("../../features/storage-client/local.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a service given the variables of an object store uses it rather than a directory" ->
      "StorageSettingsSuite and S3BucketSuite (runtime): the variables choose the S3 store, which keeps in Garage",
    "a deployed service serves no signed URL and no public address itself" ->
      "ReservedPathSuite (http): no directory store, nothing at /_ankka/storage; StorageClientClusterFeatures (k3s) for a pod"
  )

  private var unwritable: Option[Path] = None

  private val browser                                    = HttpClient.newHttpClient()
  private var shown: Option[(URI, HttpResponse[String])] = None
  private def lastShown: (URI, HttpResponse[String])     =
    shown.getOrElse(fail("the browser sent nothing"))

  private def startLocally(name: String, directory: Path): Unit =
    val descriptor = freshDirectory()
    Files.writeString(
      descriptor.resolve("service.json"),
      """{"service":{"provisionObjectStorage":true,"buckets":[{"name":"photos","exposure":"public"}]}}"""
    ): Unit
    start(
      name,
      StorageBackendChoice.FromEnvironment(),
      ConfigFactory.parseString(
        s"""ankka.storage.directory = "${directory.toString}"
           |ankka.storage.descriptor-directory = "${descriptor.toString}"""".stripMargin
      )
    ): Unit

  /** The URL a handler of `name` made through the storage client, as its endpoint answered it. */
  private def madeUrl(name: String, route: String, params: (String, Option[String])*): URI =
    val answered = send(name, "GET", route + query(params*))
    assertOk(answered)
    URI.create(answered.body)

  private def browse(uri: URI, request: HttpRequest.Builder => HttpRequest.Builder): Unit =
    val response =
      browser.send(
        request(HttpRequest.newBuilder(uri)).build(),
        HttpResponse.BodyHandlers.ofString()
      )
    shown = Some(uri -> response)

  When(
    "a browser on the machine sends a request to a signed URL that {string} made through the storage client for reading {string}"
  ) { (name: String, objectName: String) =>
    val url = madeUrl(name, "/storage/signed", "name" -> Some(objectName), "access" -> Some("read"))
    browse(url, _.GET())
  }

  When(
    "a browser on the machine sends the object {string} to a signed URL that {string} made through the storage client for keeping {string}"
  ) { (objectName: String, name: String, signedFor: String) =>
    val url  = madeUrl(name, "/storage/signed", "name" -> Some(signedFor), "access" -> Some("keep"))
    val text = s"the object $objectName"
    browse(
      url,
      _.header("Content-Type", "text/plain").PUT(HttpRequest.BodyPublishers.ofString(text))
    )
    assertEquals(lastShown._2.statusCode, 200, lastShown._2.body)
    kept = kept.updated((name, None, objectName), text)
  }

  When(
    "a browser on the machine sends a request for the object {string} without a signed URL"
  ) { (objectName: String) =>
    val signed =
      madeUrl(current, "/storage/signed", "name" -> Some(objectName), "access" -> Some("read"))
    // The address the service serves the object at, with nothing that signs it.
    browse(URI.create(signed.toString.takeWhile(_ != '?')), _.GET())
  }

  Given("a handler of {string} has kept the object {string} in the public bucket {string}") {
    (name: String, objectName: String, bucket: String) =>
      assertOk(keepThroughEndpoint(name, objectName, bucket = Some(bucket)))
  }

  When(
    "a browser on the machine sends a request to the public address that the storage client answered for {string} in the bucket {string}"
  ) { (objectName: String, bucket: String) =>
    val url = madeUrl(current, "/storage/url", "bucket" -> Some(bucket), "name" -> Some(objectName))
    browse(url, _.GET())
  }

  Then("the browser is shown the object {string}") { (objectName: String) =>
    val (uri, response) = lastShown
    assertEquals(response.statusCode, 200, s"$uri: ${response.body}")
    val name = current
    val text = kept.collectFirst { case ((`name`, _, `objectName`), t) => t }
    assertEquals(Some(response.body), text)
  }

  Then("{string} served it") { (name: String) =>
    val (uri, _) = lastShown
    val own      = URI.create(service(name).kit.service.boundAddresses.head)
    assertEquals((uri.getHost, uri.getPort), (own.getHost, own.getPort))
  }

  Then("{string} refuses the request") { (_: String) =>
    val (uri, response) = lastShown
    assertEquals(response.statusCode, 403, s"$uri: ${response.body}")
    assert(!response.body.contains("the object"), response.body)
  }

  Given("a service {string} running on a developer's machine with no variable of an object store") {
    (name: String) => startLocally(name, freshDirectory())
  }

  Then(
    "the directory store of {string} holds the object {string} with its content type and when it was kept"
  ) { (name: String, objectName: String) =>
    val directory =
      service(name).kit.service.directoryStorage.getOrElse(fail(s"'$name' has no directory store"))
    val files = {
      val walk = Files.walk(directory.root)
      try walk.iterator.asScala.filter(Files.isRegularFile(_)).toVector
      finally walk.close()
    }
    val text = keptText(name, objectName)
    assert(
      files.exists(f => java.util.Arrays.equals(Files.readAllBytes(f), text.getBytes(UTF_8))),
      s"no file under ${directory.root} holds the object: ${files.mkString(", ")}"
    )
    val meta = files.filter(_.toString.contains("/meta/")).map(Files.readString(_, UTF_8))
    assert(
      meta.exists(m => m.contains("text/plain") && m.contains("lastKept")),
      meta.mkString("\n")
    )
  }

  Given("a service {string} on a developer's machine whose directory store cannot be written") {
    (_: String) =>
      // A regular file where the directory should be: nothing can be made beneath it.
      val file = freshDirectory().resolve("not-a-directory")
      Files.writeString(file, "a file"): Unit
      unwritable = Some(file)
  }

  When("{string} starts") { (name: String) =>
    val directory = unwritable.getOrElse(fail("no directory was chosen"))
    startFailure =
      try
        startLocally(name, directory)
        None
      catch case failure: Throwable => Some(failure)
  }

  Then("{string} does not start") { (name: String) =>
    assert(startFailure.nonEmpty, s"'$name' started")
    assert(!services.contains(name), s"'$name' is running")
  }

  Then("the reason names the directory and why it cannot be written") { () =>
    val messages = Iterator
      .iterate(startFailure.orNull)(_.getCause)
      .takeWhile(_ != null)
      .map(t => String.valueOf(t.getMessage))
      .mkString(" / ")
    val directory = unwritable.getOrElse(fail("no directory was chosen"))
    assert(messages.contains(directory.toString), messages)
    assert(messages.contains("cannot be written") || messages.contains("not a directory"), messages)
  }
