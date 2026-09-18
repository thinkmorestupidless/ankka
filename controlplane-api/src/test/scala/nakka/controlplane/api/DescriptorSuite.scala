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

  // --- The service port (feature 003): specs/003-deploy-real-service/contracts/port-resolution.md

  private def decode(serviceJson: String): ServiceSpec =
    readFromString[ServiceDescriptor](s"""{"name":"cart","service":$serviceJson}""").service

  test("a descriptor that says nothing about ports serves HTTP on the runtime's default") {
    assertEquals(decode("""{"image":"i:1"}""").resolvedPort, Some(9000))
  }

  test("stating the default port is identical to not stating it, and not a problem") {
    val spec = decode("""{"image":"i:1","port":9000}""")
    assertEquals(spec.resolvedPort, Some(9000))
    assertEquals(spec.problems, Vector.empty)
  }

  test("a declared port is kept") {
    assertEquals(decode("""{"image":"i:1","port":8080}""").resolvedPort, Some(8080))
  }

  test("\"http\": false resolves to no port at all") {
    assertEquals(decode("""{"image":"i:1","http":false}""").resolvedPort, None)
  }

  test("a port beside \"http\": false is ignored, not refused") {
    // The codec omits default values on write, so "the user wrote port" cannot be told apart
    // from "the user did not" — a rule depending on it could not be enforced consistently.
    val spec = decode("""{"image":"i:1","http":false,"port":8080}""")
    assertEquals(spec.resolvedPort, None)
    assertEquals(spec.problems, Vector.empty)
  }

  test("\"port\": null is refused outright — it can never quietly mean \"no port\"") {
    // Why `http` exists, pinned. The first design was `port: Option[Int] = Some(9000)` with an
    // explicit null for "none", and it was unrepresentable: under nakka's shared codec config
    // jsoniter reads a null on an Option field as *absent* and applies the default, so
    // {"port": null} silently became 9000, on a descriptor that crosses this codec twice
    // (verified during planning — research R4). With `port` a plain Int the same input is a loud
    // decode error instead, which is the behaviour worth keeping. If someone "simplifies"
    // http + port back into one Option, this test goes quiet and the silent default comes back.
    intercept[com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException] {
      decode("""{"image":"i:1","port":null}""")
    }
  }

  test("a service declared as serving no HTTP survives the wire as exactly that") {
    val written = writeToString(ServiceDescriptor("cart", ServiceSpec("i:1", http = false)))
    assertEquals(readFromString[ServiceDescriptor](written).service.http, false)
    assertEquals(readFromString[ServiceDescriptor](written).service.resolvedPort, None)
  }

  test("a port outside 1-65535 is a problem, whether or not HTTP is served") {
    for bad <- Vector(0, -1, 65536) do
      val message = s"service port $bad is outside the range 1-65535"
      assert(ServiceSpec("i:1", port = bad).problems.contains(message), s"port $bad")
      assert(
        ServiceSpec("i:1", http = false, port = bad).problems.contains(message),
        s"port $bad, no http"
      )
    assertEquals(ServiceSpec("i:1", port = 1).problems, Vector.empty)
    assertEquals(ServiceSpec("i:1", port = 65535).problems, Vector.empty)
  }

  test("NAKKA_HTTP_PORT in env is always refused — the port field is the only way to set it") {
    val conflict =
      "env var 'NAKKA_HTTP_PORT' conflicts with the service port; declare the port instead"
    val literal    = EnvVar("NAKKA_HTTP_PORT", value = Some("8080"))
    val fromSecret = EnvVar("NAKKA_HTTP_PORT", secretKeyRef = Some(SecretKeyRef("s", "k")))

    assert(ServiceSpec("i:1", env = Vector(literal)).problems.contains(conflict))
    // By name, never value: a secret-sourced value is just as much a second source of truth.
    assert(ServiceSpec("i:1", env = Vector(fromSecret)).problems.contains(conflict))
    // Even when no HTTP is served. One rule, no exceptions to remember.
    assert(ServiceSpec("i:1", http = false, env = Vector(literal)).problems.contains(conflict))
  }

  test("only that exact name conflicts — its neighbours do not") {
    val neighbour = EnvVar("NAKKA_HTTP_INTERFACE", value = Some("0.0.0.0"))
    assertEquals(ServiceSpec("i:1", env = Vector(neighbour)).problems, Vector.empty)
  }

  test("port problems arrive with every other problem, in one response") {
    val problems = ServiceSpec(
      "",
      port = 0,
      env = Vector(EnvVar("NAKKA_HTTP_PORT", value = Some("1")))
    ).problems
    assert(problems.exists(_.contains("image")), problems.toString)
    assert(problems.exists(_.contains("outside the range")), problems.toString)
    assert(problems.exists(_.contains("conflicts with the service port")), problems.toString)
  }
