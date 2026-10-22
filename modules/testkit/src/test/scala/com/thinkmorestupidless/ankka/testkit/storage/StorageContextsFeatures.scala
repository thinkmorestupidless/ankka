package com.thinkmorestupidless.ankka.testkit.storage

import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.testkit.StorageBackendChoice

import StorageCodecs.given

/**
 * `features/storage-client/contexts.feature`: the storage client on an endpoint, a workflow step, a
 * consumer, a timed action and an agent of one running service, refused in a workflow's command
 * handler, and the reason a service with no bucket or no credential is given (feature 055).
 */
final class StorageContextsFeatures
    extends StorageFeatures("../../features/storage-client/contexts.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "an entity and a view have no storage client" -> "StorageAvailabilitySuite (sdk), at compile time"
  )

  When("a command of a workflow of {string} keeps the object {string}") {
    (name: String, objectName: String) =>
      refusal =
        try
          service(name).kit.componentClient
            .forWorkflow(EntityId(s"command-$scenarioId"))
            .call(KeepingWorkflow.keepInCommand)
            .invoke(KeepObject(None, objectName, "text/plain", "x"))
          None
        catch case failure: Throwable => Some(CommandError.from(failure).getOrElse(throw failure))
  }

  Then("the command is refused") { () =>
    val error = refusal.getOrElse(fail("the command kept an object"))
    assertEquals(error.code, ErrorCode.BadRequest)
    assertEquals(storageOf(current).default.list("").toVector, Vector.empty)
  }

  Then("the refusal says that an object is kept in a step") { () =>
    val error = refusal.getOrElse(fail("the command kept an object"))
    assert(error.message.contains("in a step, not in a command handler"), error.message)
  }

  Given(
    "a deployed service {string} with a bucket in Google Cloud Storage, whose descriptor declines a storage credential"
  ) { (name: String) =>
    // The variables a bucket reached as a workload identity is given: an endpoint and a bucket, no key.
    start(
      name,
      StorageBackendChoice.FromEnvironment(
        Map(
          "ANKKA_S3_ENDPOINT" -> "https://storage.googleapis.com",
          "ANKKA_S3_REGION"   -> "europe-west2",
          "ANKKA_S3_BUCKET"   -> "t-listings"
        )
      )
    ): Unit
  }

  Then(
    "the failure says that the service has no storage credential to sign with and names {string}"
  ) { (word: String) =>
    assertEquals(told.status, 503, told.body)
    assert(
      told.body.contains("no storage credential to sign with") && told.body.contains(word),
      told.body
    )
  }

  When(
    "an endpoint of {string} keeps the object {string} and records its name on the entity {string}"
  ) { (name: String, objectName: String, entity: String) =>
    val text = s"the bytes of $objectName, which no journal holds"
    assertOk(
      send(
        name,
        "POST",
        s"/storage/listings/$entity/photos",
        Some(writeToString(RecordPhoto(objectName, "image/jpeg", text)))
      )
    )
    kept = kept.updated((name, None, objectName), text)
  }

  Then("the entity {string} holds the name {string} and no part of the object") {
    (entity: String, objectName: String) =>
      val state = service(current).kit.componentClient
        .forEventSourcedEntity(EntityId(entity))
        .call(Listing.photos)
        .invoke()
      assertEquals(state, ListingState(Vector(objectName)))
      val payloads = journalPayloads(current)
      assert(
        payloads.exists(_.contains(objectName)),
        "the entity's journal holds no record of the name"
      )
      assert(
        !payloads.exists(_.contains("which no journal holds")),
        "a journal record holds the object"
      )
  }

  Then("the bucket of {string} holds the object {string}") { (name: String, objectName: String) =>
    val (contentType, body) =
      storageOf(name).default
        .getBytes(objectName)
        .getOrElse(fail(s"the bucket holds no '$objectName'"))
    assertEquals(contentType, "image/jpeg")
    assertEquals(String(body, "UTF-8"), keptText(name, objectName))
  }
