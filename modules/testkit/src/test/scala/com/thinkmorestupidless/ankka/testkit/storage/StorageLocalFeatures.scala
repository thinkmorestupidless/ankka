package com.thinkmorestupidless.ankka.testkit.storage

import com.thinkmorestupidless.ankka.testkit.StorageBackendChoice
import com.typesafe.config.ConfigFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * `features/storage-client/local.feature`: a service run on a developer's machine keeps its objects
 * in a directory store and serves the URLs it makes itself (feature 055). Each service here is
 * started as the runtime chooses, from no variables, with its directory store in a directory of the
 * scenario's own.
 */
final class StorageLocalFeatures
    extends StorageFeatures("../../features/storage-client/local.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a service given the variables of an object store uses it rather than a directory" ->
      "StorageSettingsSuite and S3BucketSuite (runtime): the variables choose the S3 store, which keeps in Garage",
    "a deployed service serves no signed URL and no public address itself" ->
      "StorageClientClusterFeatures (k3s)"
  )

  private var unwritable: Option[Path] = None

  private def startLocally(name: String, directory: Path): Unit =
    start(
      name,
      StorageBackendChoice.FromEnvironment(),
      ConfigFactory.parseString(s"""ankka.storage.directory = "${directory.toString}"""")
    ): Unit

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
