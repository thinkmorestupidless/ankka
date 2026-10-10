package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{CloudResource, CloudResourceSpec, CloudResourceStatus}

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
 * What a cloud request's answer means (feature 044, research R5): one case per row of the table. No
 * fake, no seam, just data, as `ProvisioningSuite` is for a database.
 */
class CloudProvisioningSuite extends munit.FunSuite:

  private val created = Instant.parse("2026-10-09T12:00:00Z")
  private val bound   = 2.minutes
  private val request =
    CloudResource("ankka-shop", "reports-bucket", CloudResourceSpec("gcp", "bucket"))

  private def seen(generation: Long, status: Option[CloudResourceStatus]) =
    Some(CloudObservation(generation, created, status))

  private def decide(observed: Option[CloudObservation], at: Instant = created.plusSeconds(10)) =
    CloudProvisioning.decide(request, observed, at, bound)

  test("a request not yet seen is waiting, and says nothing yet") {
    assertEquals(decide(None), CloudPlan.Waiting(None))
  }

  test("a request nobody has acknowledged within the bound is waiting, and says nothing yet") {
    assertEquals(decide(seen(1, None)), CloudPlan.Waiting(None))
    assertEquals(
      decide(seen(1, Some(CloudResourceStatus(phase = "Ready")))),
      CloudPlan.Waiting(None),
      "a status with no observedGeneration was written by nobody who read the request"
    )
  }

  test("a request nobody has acknowledged for the bound says no provider has answered") {
    assertEquals(
      decide(seen(1, None), at = created.plusSeconds(120)),
      CloudPlan.Waiting(Some("no provider for gcp has answered"))
    )
  }

  test("a status behind the request's generation is never acted on, however ready it says it is") {
    val stale = CloudResourceStatus(
      observedGeneration = Some(1L),
      phase = "Ready",
      outputs = Map("bucket" -> "old")
    )
    assertEquals(
      decide(seen(2, Some(stale))),
      CloudPlan.Waiting(Some("waiting on the provider for generation 2"))
    )
  }

  test("a provider still working is waiting, with what it said") {
    val working =
      CloudResourceStatus(observedGeneration = Some(1L), phase = "Waiting", detail = Some("busy"))
    assertEquals(decide(seen(1, Some(working))), CloudPlan.Waiting(Some("busy")))
  }

  test("a ready answer is the outputs and the credential generation in place") {
    val ready = CloudResourceStatus(
      observedGeneration = Some(1L),
      phase = "Ready",
      credentialGeneration = Some(1L),
      outputs = Map("secretName" -> "reports-storage")
    )
    assertEquals(
      decide(seen(1, Some(ready))),
      CloudPlan.Ready(Map("secretName" -> "reports-storage"), recovered = false, Some(1L))
    )
  }

  test("a recovered answer is ready, and recovered") {
    val recovered = CloudResourceStatus(
      observedGeneration = Some(1L),
      phase = "Recovered",
      recovered = true,
      outputs = Map("bucket" -> "b")
    )
    assertEquals(
      decide(seen(1, Some(recovered))),
      CloudPlan.Ready(Map("bucket" -> "b"), recovered = true, None)
    )
  }

  test("a failed answer is the provider's reason, word for word") {
    val failed = CloudResourceStatus(
      observedGeneration = Some(1L),
      phase = "Failed",
      detail = Some("the location is refused")
    )
    assertEquals(decide(seen(1, Some(failed))), CloudPlan.Failed("the location is refused"))
  }

  test("an acknowledged request is one whose observed generation is its own") {
    assert(
      CloudObservation(
        2,
        created,
        Some(CloudResourceStatus(observedGeneration = Some(2L)))
      ).acknowledged
    )
    assert(
      !CloudObservation(
        2,
        created,
        Some(CloudResourceStatus(observedGeneration = Some(1L)))
      ).acknowledged
    )
    assert(!CloudObservation(1, created, None).acknowledged)
  }
