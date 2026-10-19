package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.deploy.{ClusterView, StatusIngest}
import com.thinkmorestupidless.ankka.controlplane.domain.{Service, ServiceKey, StorageReport}
import com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus

/**
 * A report becomes an observation.
 *
 * Three cases, and the distinctions between them are what make `services list` trustworthy.
 */
class StatusIngestSuite extends munit.FunSuite with LogCapturing:

  private val descriptor = ServiceDescriptor("cart", ServiceSpec("cart:1.0"))

  private def service(generation: Long = 4L) =
    Service.empty(ServiceKey("checkout", "cart")).onApplied(descriptor, generation)

  private def ready(generation: Long = 4L) =
    service(generation).onObserved(
      com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent
        .ServiceObserved(generation, ServiceLifecycle.Ready, 1, 1, None, confirmed = true)
    )

  test("a report is taken at face value and marked confirmed") {
    val observation = StatusIngest.observe(
      service(),
      ClusterView.Reported(
        AnkkaServiceStatus(
          generation = 4L,
          lifecycle = "Ready",
          readyInstances = 1,
          desiredInstances = 1
        )
      )
    )
    assertEquals(observation.lifecycle, ServiceLifecycle.Ready)
    assertEquals(observation.readyInstances, 1)
    assertEquals(observation.confirmed, true)
  }

  test("an unreachable cluster restates what was known, unconfirmed") {
    val observation = StatusIngest.observe(ready(), ClusterView.Unreachable("connection refused"))

    assertEquals(observation.lifecycle, ServiceLifecycle.Ready, "the last known state is restated")
    assertEquals(observation.readyInstances, 1)
    assertEquals(observation.confirmed, false)
    assert(observation.detail.exists(_.contains("connection refused")))
  }

  test("a resource nothing has reported on is unconfirmed and says so") {
    // This is how an operator discovers that the ankka operator is not installed, is
    // crash-looping, or is watching a different namespace prefix.
    val observation = StatusIngest.observe(service(), ClusterView.NoReport)

    assertEquals(observation.confirmed, false)
    assertEquals(observation.detail, Some("no operator has reported on this service"))
    assertEquals(observation.lifecycle, ServiceLifecycle.UpdateInProgress)
  }

  test("'nothing reported' is distinguishable from 'unreachable'") {
    val unreachable = StatusIngest.observe(service(), ClusterView.Unreachable("timeout"))
    val noReport    = StatusIngest.observe(service(), ClusterView.NoReport)
    assertNotEquals(unreachable.detail, noReport.detail)
  }

  test("a stale report is passed through untouched for the fold to drop") {
    // There is exactly one place staleness is decided — the guard in Service.onObserved.
    // Second-guessing it here would make two, and they would eventually disagree.
    val observation = StatusIngest.observe(
      service(generation = 5L),
      ClusterView.Reported(AnkkaServiceStatus(generation = 3L, lifecycle = "Ready"))
    )
    assertEquals(observation.generation, 3L, "the operator's stated generation is preserved")
  }

  test("a route the gateway has not accepted is said in the detail; an accepted one is silent") {
    def observed(route: Option[String], detail: Option[String] = None) =
      StatusIngest.observe(
        service(4L),
        ClusterView.Reported(
          AnkkaServiceStatus(generation = 4L, lifecycle = "Ready", route = route, detail = detail)
        )
      )
    assertEquals(observed(None).detail, None)
    assertEquals(observed(Some("accepted")).detail, None)
    assertEquals(observed(Some("pending")).detail, Some("route pending"))
    assertEquals(
      observed(Some("rejected: RefNotPermitted")).detail,
      Some("route rejected: RefNotPermitted")
    )
    // The operator's own detail comes first; the route is appended, not lost.
    assertEquals(
      observed(Some("pending"), Some("waiting for database")).detail,
      Some("waiting for database; route pending")
    )
  }

  test("a refusal is a confirmed decision: Unavailable, with the reason as the detail") {
    // Not "could not reach the cluster": the platform read the desired state and declined to
    // run it — a declared runtime outside the supported range, say. That is certain, not stale.
    val refused = StatusIngest.observe(service(), ClusterView.Refused("runtime 9.0.0 is outside…"))
    assertEquals(refused.lifecycle, ServiceLifecycle.Unavailable)
    assertEquals(refused.detail, Some("runtime 9.0.0 is outside…"))
    assertEquals(refused.confirmed, true)
    assertEquals(refused.readyInstances, 0)
  }

  test("every lifecycle name the operator can write maps back") {
    for name <- ServiceLifecycle.values.map(_.toString) do
      val observation =
        StatusIngest.observe(service(), ClusterView.Reported(AnkkaServiceStatus(lifecycle = name)))
      assertEquals(observation.lifecycle.toString, name)
  }

  test("an unrecognised lifecycle does not throw") {
    val observation =
      StatusIngest.observe(
        service(),
        ClusterView.Reported(AnkkaServiceStatus(lifecycle = "Banana"))
      )
    assertEquals(observation.lifecycle, ServiceLifecycle.Unavailable)
  }

  // Object storage (feature 034).

  private def reported(storage: com.thinkmorestupidless.ankka.crd.ObjectStorageStatus) =
    StatusIngest.observe(
      service(),
      ClusterView.Reported(
        AnkkaServiceStatus(
          generation = 4L,
          lifecycle = "Ready",
          readyInstances = 1,
          desiredInstances = 1,
          objectStorage = Some(storage)
        )
      )
    )

  test("a reported object storage phase is carried, verbatim") {
    val observation =
      reported(
        com.thinkmorestupidless.ankka.crd.ObjectStorageStatus("Provisioned", "checkout.cart")
      )
    assertEquals(observation.objectStorage, Some("Provisioned"))
    assertEquals(observation.detail, None)
  }

  test("why a bucket waits or failed is said in the detail, so a member reads it") {
    val observation = reported(
      com.thinkmorestupidless.ankka.crd.ObjectStorageStatus(
        "Failed",
        "checkout.cart",
        detail = Some("the installation has no object store")
      )
    )
    assertEquals(observation.objectStorage, Some("Failed"))
    assertEquals(observation.detail, Some("object storage: the installation has no object store"))
  }

  test("a cluster that cannot be read, or has not reported, restates the last phase") {
    val known = service().onObserved(
      com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.ServiceObserved(
        4L,
        ServiceLifecycle.Ready,
        1,
        1,
        None,
        confirmed = true,
        objectStorage = Some("Recovered")
      )
    )
    for view <- Vector(
        ClusterView.Unreachable("down"),
        ClusterView.Refused("no"),
        ClusterView.NoReport
      )
    do
      assertEquals(
        StatusIngest.observe(known, view).objectStorage,
        Some("Recovered"),
        view.toString
      )
  }

  // ── custom hostnames (feature 045) ─────────────────────────────────────────────────────────

  test("each custom hostname's report is carried verbatim, in order, and the detail is untouched") {
    val observation = StatusIngest.observe(
      service(),
      ClusterView.Reported(
        AnkkaServiceStatus(
          generation = 4L,
          lifecycle = "Ready",
          readyInstances = 1,
          desiredInstances = 1,
          hostnames = List(
            com.thinkmorestupidless.ankka.crd.HostnameStatus("b.example.com", "serving"),
            com.thinkmorestupidless.ankka.crd.HostnameStatus("a.example.com", "pending", Some("x"))
          )
        )
      )
    )
    assertEquals(
      observation.hostnames,
      Vector(
        com.thinkmorestupidless.ankka.controlplane.domain
          .HostnameReport("b.example.com", "serving"),
        com.thinkmorestupidless.ankka.controlplane.domain
          .HostnameReport("a.example.com", "pending", Some("x"))
      )
    )
    assertEquals(observation.detail, None)
  }

  test("an unreachable cluster restates the hostnames last reported") {
    val reported = service().copy(hostnameReports =
      Vector(
        com.thinkmorestupidless.ankka.controlplane.domain.HostnameReport("a.example.com", "serving")
      )
    )
    assertEquals(
      StatusIngest.observe(reported, ClusterView.Unreachable("refused")).hostnames,
      reported.hostnameReports
    )
  }

  // Feature 039: what the operator reported of a bucket beyond its phase.

  test("the store, the bucket as reported, its location, window and a move are carried") {
    val observed = reported(
      com.thinkmorestupidless.ankka.crd.ObjectStorageStatus(
        phase = "Provisioned",
        bucket = "ankka-casino-kyc-3f9a1c2e",
        publicAddress = Some("https://storage.googleapis.com/ankka-casino-kyc-3f9a1c2e"),
        store = "gcs",
        location = Some("europe-west2"),
        softDeleteDays = Some(7),
        move =
          Some(com.thinkmorestupidless.ankka.crd.MoveStatus(generation = 1, state = "Switched"))
      )
    )
    assertEquals(
      observed.storage,
      Some(
        StorageReport(
          store = Some("gcs"),
          bucket = Some("ankka-casino-kyc-3f9a1c2e"),
          bucketAddress = Some("https://storage.googleapis.com/ankka-casino-kyc-3f9a1c2e"),
          location = Some("europe-west2"),
          softDeleteDays = Some(7),
          move = Some("Switched"),
          moveGeneration = Some(1)
        )
      )
    )
  }

  test("a status from an operator before feature 039 names no store, and reports none") {
    val observed =
      reported(
        com.thinkmorestupidless.ankka.crd.ObjectStorageStatus("Provisioned", "checkout.cart")
      )
    assertEquals(observed.storage, None)
  }

  test("a cluster that cannot be read restates what was last reported of the bucket") {
    val report =
      StorageReport(store = Some("gcs"), move = Some("Copying"), moveGeneration = Some(1))
    val known = service().copy(storage = Some(report))
    for view <- Vector(ClusterView.Unreachable("down"), ClusterView.NoReport) do
      assertEquals(StatusIngest.observe(known, view).storage, Some(report), view.toString)
  }

  // Grants (feature 040).

  test(
    "an operator that reports the grants mounted is carried to the status; one that predates grants is not"
  ) {
    def reportedGrants(grants: Option[String]) =
      StatusIngest.observe(
        service(),
        ClusterView.Reported(
          AnkkaServiceStatus(
            generation = 4L,
            lifecycle = "Ready",
            readyInstances = 1,
            desiredInstances = 1,
            grants = grants
          )
        )
      )
    val mounted = reportedGrants(Some("mounted"))
    assertEquals(mounted.grants, Some("mounted"))
    val status = service()
      .onObserved(
        com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.ServiceObserved(
          4L,
          ServiceLifecycle.Ready,
          1,
          1,
          None,
          confirmed = true,
          grants = mounted.grants
        )
      )
    assertEquals(reportedGrants(None).grants, None)
    // What `services get` shows: mounted, or, for a running service an older operator rendered,
    // that it must be rolled out before a grant reaches it.
    assertEquals(status.toStatus.grantsPhrase, Some("mounted"))
    assertEquals(status.copy(grants = None).toStatus.grantsPhrase, Some("rollout needed"))
  }

  // Feature 055: named buckets, kept ones included, verbatim and in order.

  test("each named bucket's report is carried, a kept one too") {
    val observed = reported(
      com.thinkmorestupidless.ankka.crd.ObjectStorageStatus(
        phase = "Provisioned",
        bucket = "checkout.cart",
        buckets = List(
          com.thinkmorestupidless.ankka.crd.BucketStatus(
            name = "photos",
            phase = "Provisioned",
            store = "garage",
            bucket = "checkout.cart.photos",
            exposure = "public",
            publicAddress = Some("https://storage.example/checkout.cart.photos")
          ),
          com.thinkmorestupidless.ankka.crd.BucketStatus(
            name = "old",
            phase = "Kept",
            store = "garage",
            bucket = "checkout.cart.old",
            kept = true
          ),
          com.thinkmorestupidless.ankka.crd.BucketStatus(
            name = "archive",
            phase = "Supplied",
            exposure = "supplied"
          )
        )
      )
    )
    assertEquals(observed.buckets.map(_.name), Vector("photos", "old", "archive"))
    assertEquals(observed.buckets.map(_.phase), Vector("Provisioned", "Kept", "Supplied"))
    assertEquals(observed.buckets(1).kept, true)
    assertEquals(observed.buckets(0).store, Some("garage"))
    assertEquals(observed.buckets(2).store, None, "a supplied bucket has no store of ours")
    assertEquals(
      observed.buckets(0).publicAddress,
      Some("https://storage.example/checkout.cart.photos")
    )
  }

  test("a service's named buckets reach its status, and a lost cluster restates them") {
    val first = reported(
      com.thinkmorestupidless.ankka.crd.ObjectStorageStatus(
        buckets = List(com.thinkmorestupidless.ankka.crd.BucketStatus("photos", "Provisioned"))
      )
    )
    val known = service().onObserved(
      com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.ServiceObserved(
        first.generation,
        first.lifecycle,
        first.readyInstances,
        first.desiredInstances,
        None,
        confirmed = true,
        buckets = first.buckets
      )
    )
    assertEquals(known.toStatus.buckets.map(_.name), Vector("photos"))
    val restated = StatusIngest.observe(known, ClusterView.Unreachable("down"))
    assertEquals(restated.buckets.map(_.name), Vector("photos"))
  }
