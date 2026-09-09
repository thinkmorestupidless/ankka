package nakka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import nakka.controlplane.api.Wire.given

/** Descriptor validation and the wire format. */
class DescriptorSuite extends munit.FunSuite:

  private val valid = ServiceDescriptor("cart", ServiceSpec(image = "cart:1.0"))

  test("a minimal descriptor is valid") {
    assertEquals(valid.problems, Vector.empty)
    assert(valid.isValid)
  }

  test("names must be expressible as a Kubernetes label") {
    // The name becomes a Deployment name, so refusing it at apply time beats failing
    // when the reconciler tries to render a manifest.
    assert(ServiceDescriptor("", ServiceSpec("i:1")).problems.exists(_.contains("empty")))
    assert(ServiceDescriptor("Cart", ServiceSpec("i:1")).problems.exists(_.contains("invalid")))
    assert(ServiceDescriptor("1cart", ServiceSpec("i:1")).problems.exists(_.contains("invalid")))
    assert(ServiceDescriptor("my_cart", ServiceSpec("i:1")).problems.exists(_.contains("invalid")))
    assert(ServiceDescriptor("my-cart-2", ServiceSpec("i:1")).isValid)
  }

  test("an image is required") {
    assert(ServiceDescriptor("cart", ServiceSpec("")).problems.exists(_.contains("image")))
  }

  test("an env var must set exactly one of value and secretKeyRef") {
    def envProblems(env: EnvVar) =
      ServiceDescriptor("cart", ServiceSpec("i:1", env = Vector(env))).problems

    assertEquals(envProblems(EnvVar("A", value = Some("1"))), Vector.empty)
    assertEquals(
      envProblems(EnvVar("A", secretKeyRef = Some(SecretKeyRef("s", "k")))),
      Vector.empty
    )

    assert(envProblems(EnvVar("A")).exists(_.contains("neither")))
    assert(
      envProblems(EnvVar("A", Some("1"), Some(SecretKeyRef("s", "k")))).exists(_.contains("both"))
    )
    assert(envProblems(EnvVar("", value = Some("1"))).exists(_.contains("empty")))
  }

  test("autoscaling bounds are checked against each other") {
    def scalingProblems(scaling: Autoscaling) =
      ServiceDescriptor(
        "cart",
        ServiceSpec("i:1", resources = ServiceResources(autoscaling = scaling))
      ).problems

    assertEquals(scalingProblems(Autoscaling()), Vector.empty)
    assert(scalingProblems(Autoscaling(minInstances = 0)).exists(_.contains("at least 1")))
    assert(
      scalingProblems(Autoscaling(minInstances = 5, maxInstances = 2))
        .exists(_.contains("below minInstances"))
    )
    assert(
      scalingProblems(Autoscaling(targetCpuPercent = 0)).exists(_.contains("between 1 and 100"))
    )
    assert(
      scalingProblems(Autoscaling(targetCpuPercent = 101)).exists(_.contains("between 1 and 100"))
    )
  }

  test("an unknown instance type lists the valid ones") {
    val problems =
      ServiceDescriptor(
        "cart",
        ServiceSpec("i:1", resources = ServiceResources("enormous"))
      ).problems
    assert(problems.exists(p => p.contains("enormous") && p.contains("small")), problems.toString)
  }

  test("every problem is reported at once, not just the first") {
    // One restart should be enough to fix a descriptor with several mistakes.
    val problems = ServiceDescriptor(
      "Bad Name",
      ServiceSpec("", env = Vector(EnvVar("A")), resources = ServiceResources("enormous"))
    ).problems
    assert(problems.sizeIs >= 4, problems.toString)
  }

  test("instance types map to concrete compute") {
    assertEquals(InstanceType.byName("small").map(_.cpuMillis), Some(500))
    assertEquals(InstanceType.byName("large").map(_.memoryMiB), Some(2048))
    assertEquals(InstanceType.byName("nope"), None)
    assertEquals(InstanceType.names, Vector("small", "medium", "large"))
  }

  test("minInstances defaults to 1, diverging from Akka's 3") {
    // Akka targets a managed production cluster; nakka's primary target is a dev cluster
    // where three replicas of everything is a surprise.
    assertEquals(Autoscaling().minInstances, 1)
  }

  test("a descriptor round-trips through the wire format") {
    val descriptor = ServiceDescriptor(
      "cart",
      ServiceSpec(
        image = "cart:1.0",
        env = Vector(
          EnvVar("MODE", Some("live")),
          EnvVar("KEY", secretKeyRef = Some(SecretKeyRef("s", "k")))
        ),
        labels = Map("team" -> "checkout"),
        resources = ServiceResources("medium", Autoscaling(2, 6, 70))
      )
    )
    assertEquals(readFromString[ServiceDescriptor](writeToString(descriptor)), descriptor)
  }

  test("service status round-trips, lifecycle included") {
    val status = ServiceStatus("cart", "p-1", ServiceLifecycle.PartiallyReady, 3L, "cart:1.0", 1, 2)
    assertEquals(readFromString[ServiceStatus](writeToString(status)), status)
  }

  test("a lifecycle is one word on the wire, not a wrapper object") {
    val status = ServiceStatus(
      name = "cart",
      projectId = "checkout",
      lifecycle = ServiceLifecycle.PartiallyReady,
      generation = 4L,
      image = "cart:1.0",
      readyInstances = 1,
      desiredInstances = 3
    )
    val json = writeToString(status)
    assert(json.contains("\"lifecycle\":\"PartiallyReady\""), json)
    assertEquals(readFromString[ServiceStatus](json), status)
  }

  test("an unknown lifecycle name is a decode error, not a silent default") {
    val json =
      """{"name":"cart","projectId":"p","lifecycle":"Sideways","generation":1,""" +
        """"image":"i","readyInstances":0,"desiredInstances":0}"""
    val failure = intercept[Exception](readFromString[ServiceStatus](json))
    assert(failure.getMessage.contains("unknown service lifecycle"), failure.getMessage)
  }
