package nakka.controlplane

import nakka.controlplane.api.*
import nakka.controlplane.deploy.{ClusterView, StatusIngest}
import nakka.controlplane.domain.{Service, ServiceKey}
import nakka.crd.NakkaServiceStatus

/**
 * A report becomes an observation.
 *
 * Three cases, and the distinctions between them are what make `services list` trustworthy.
 */
class StatusIngestSuite extends munit.FunSuite:

  private val descriptor = ServiceDescriptor("cart", ServiceSpec("cart:1.0"))

  private def service(generation: Long = 4L) =
    Service.empty(ServiceKey("checkout", "cart")).onApplied(descriptor, generation)

  private def ready(generation: Long = 4L) =
    service(generation).onObserved(
      nakka.controlplane.domain.ServiceEvent
        .ServiceObserved(generation, ServiceLifecycle.Ready, 1, 1, None, confirmed = true)
    )

  test("a report is taken at face value and marked confirmed") {
    val observation = StatusIngest.observe(
      service(),
      ClusterView.Reported(
        NakkaServiceStatus(
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
    // This is how an operator discovers that the nakka operator is not installed, is
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
      ClusterView.Reported(NakkaServiceStatus(generation = 3L, lifecycle = "Ready"))
    )
    assertEquals(observation.generation, 3L, "the operator's stated generation is preserved")
  }

  test("a route the gateway has not accepted is said in the detail; an accepted one is silent") {
    def observed(route: Option[String], detail: Option[String] = None) =
      StatusIngest.observe(
        service(4L),
        ClusterView.Reported(
          NakkaServiceStatus(generation = 4L, lifecycle = "Ready", route = route, detail = detail)
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
        StatusIngest.observe(service(), ClusterView.Reported(NakkaServiceStatus(lifecycle = name)))
      assertEquals(observation.lifecycle.toString, name)
  }

  test("an unrecognised lifecycle does not throw") {
    val observation =
      StatusIngest.observe(
        service(),
        ClusterView.Reported(NakkaServiceStatus(lifecycle = "Banana"))
      )
    assertEquals(observation.lifecycle, ServiceLifecycle.Unavailable)
  }
