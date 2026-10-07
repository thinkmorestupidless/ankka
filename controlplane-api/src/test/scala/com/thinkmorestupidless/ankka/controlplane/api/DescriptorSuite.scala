package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

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
    // Akka targets a managed production cluster; ankka's primary target is a dev cluster
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

  test("a hostname round-trips when present and is absent — not null — from the wire when not") {
    val exposed = ServiceStatus(
      "cart",
      "checkout",
      ServiceLifecycle.Ready,
      1L,
      "cart:1.0",
      3,
      3,
      hostname = Some("https://cart-checkout.example.test")
    )
    val json = writeToString(exposed)
    assert(json.contains("\"hostname\":\"https://cart-checkout.example.test\""), json)
    assertEquals(readFromString[ServiceStatus](json), exposed)

    val unexposed = exposed.copy(hostname = None)
    assert(!writeToString(unexposed).contains("hostname"), writeToString(unexposed))
    assertEquals(readFromString[ServiceStatus](writeToString(unexposed)), unexposed)
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
    // explicit null for "none", and it was unrepresentable: under ankka's shared codec config
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

  test("ANKKA_HTTP_PORT in env is always refused — the port field is the only way to set it") {
    val conflict =
      "env var 'ANKKA_HTTP_PORT' conflicts with the service port; declare the port instead"
    val literal    = EnvVar("ANKKA_HTTP_PORT", value = Some("8080"))
    val fromSecret = EnvVar("ANKKA_HTTP_PORT", secretKeyRef = Some(SecretKeyRef("s", "k")))

    assert(ServiceSpec("i:1", env = Vector(literal)).problems.contains(conflict))
    // By name, never value: a secret-sourced value is just as much a second source of truth.
    assert(ServiceSpec("i:1", env = Vector(fromSecret)).problems.contains(conflict))
    // Even when no HTTP is served. One rule, no exceptions to remember.
    assert(ServiceSpec("i:1", http = false, env = Vector(literal)).problems.contains(conflict))
  }

  test("only that exact name conflicts — its neighbours do not") {
    val neighbour = EnvVar("ANKKA_HTTP_INTERFACE", value = Some("0.0.0.0"))
    assertEquals(ServiceSpec("i:1", env = Vector(neighbour)).problems, Vector.empty)
  }

  test("a socket's limits are the descriptor's to give, in every hosting") {
    val limits = Vector(
      EnvVar("ANKKA_SOCKET_MAX_FRAME_SIZE", value = Some("128KiB")),
      EnvVar("ANKKA_SOCKET_UNREAD_FRAMES", value = Some("16")),
      EnvVar("ANKKA_SOCKET_KEEP_ALIVE", value = Some("15s"))
    )
    assertEquals(ServiceSpec("i:1", env = limits).problems, Vector.empty)
    assertEquals(
      ServiceSpec("i:1", hosting = "process", protocol = Some("1.6"), env = limits).problems,
      Vector.empty
    )
  }

  test("port problems arrive with every other problem, in one response") {
    val problems = ServiceSpec(
      "",
      port = 0,
      env = Vector(EnvVar("ANKKA_HTTP_PORT", value = Some("1")))
    ).problems
    assert(problems.exists(_.contains("image")), problems.toString)
    assert(problems.exists(_.contains("outside the range")), problems.toString)
    assert(problems.exists(_.contains("conflicts with the service port")), problems.toString)
  }

  // --- The declared runtime (feature 006)

  test("a declared runtime round-trips and is absent from the wire when undeclared") {
    val declared = ServiceSpec("i:1", runtime = Some("0.2.0"))
    val json     = writeToString(ServiceDescriptor("s", declared))
    assert(json.contains("\"runtime\":\"0.2.0\""), json)
    assertEquals(readFromString[ServiceDescriptor](json).service.runtime, Some("0.2.0"))
    assert(!writeToString(ServiceDescriptor("s", ServiceSpec("i:1"))).contains("runtime"))
  }

  test("a malformed runtime is a problem naming the format; an absent one is no problem") {
    assertEquals(ServiceSpec("i:1").problems, Vector.empty)
    val problems = ServiceSpec("i:1", runtime = Some("latest")).problems
    assert(
      problems.exists(p => p.startsWith("runtime ") && p.contains("MAJOR.MINOR.PATCH")),
      problems
    )
    assertEquals(ServiceSpec("i:1", runtime = Some("0.2.0+3-abc-SNAPSHOT")).problems, Vector.empty)
  }

  test("a descriptor may not give a variable the platform alone sets, however it gives it") {
    val name    = com.thinkmorestupidless.ankka.core.PlatformVariables.HttpPort
    val asValue = ServiceSpec("i:1", env = Vector(EnvVar(name, value = Some("8080")))).problems
    val fromProjectSecret = ServiceSpec(
      "i:1",
      env = Vector(EnvVar(name, secretKeyRef = Some(SecretKeyRef("checkout", "PORT"))))
    ).problems
    assert(asValue.exists(_.contains(s"'$name'")), asValue)
    assert(fromProjectSecret.exists(_.contains(s"'$name'")), fromProjectSecret)
  }

  test("the platform's own variables are refused by name, whatever their value") {
    // The two ports have rules of their own, which point at the field to declare instead.
    val platformOnly = com.thinkmorestupidless.ankka.core.PlatformVariables.PlatformOnly -
      com.thinkmorestupidless.ankka.core.PlatformVariables.HttpPort -
      com.thinkmorestupidless.ankka.core.PlatformVariables.GrpcPort
    for name <- platformOnly do
      val literal = ServiceSpec("i:1", env = Vector(EnvVar(name, value = Some("x")))).problems
      val fromSecret = ServiceSpec(
        "i:1",
        env = Vector(EnvVar(name, secretKeyRef = Some(SecretKeyRef("s", "k"))))
      ).problems
      assert(
        literal.exists(_.contains(s"'$name' is set by the platform")),
        s"$name literal: $literal"
      )
      assert(
        fromSecret.exists(_.contains(s"'$name' is set by the platform")),
        s"$name secret: $fromSecret"
      )
  }

  test("ANKKA_SERVICE_NAME is refused by name: a deployed service's name comes from the platform") {
    val literal = EnvVar("ANKKA_SERVICE_NAME", value = Some("orders"))
    val fromSecret =
      EnvVar("ANKKA_SERVICE_NAME", secretKeyRef = Some(SecretKeyRef("s", "k")))
    for env <- Seq(literal, fromSecret) do
      val problems = ServiceSpec("i:1", env = Vector(env)).problems
      assert(
        problems.exists(p =>
          p.contains("'ANKKA_SERVICE_NAME' names a service run locally") &&
            p.contains("comes from the platform")
        ),
        problems
      )
    assertEquals(
      ServiceSpec("i:1", env = Vector(EnvVar("ANKKA_SERVICE_NAMES", value = Some("x")))).problems,
      Vector.empty
    )
  }

  test("no project is named \"local\"") {
    assertEquals(
      ProjectId.problems("local"),
      Vector(
        "project id 'local' is reserved for services run locally, whose consumer groups it names"
      )
    )
    // The platform's own reason is not given for it: it is not true of `local`.
    assert(!ProjectId.problems("local").exists(_.contains("platform's own workloads")))
    assertEquals(ProjectId.problems("locale"), Vector.empty)
    assertEquals(ProjectId.problems("local-shop"), Vector.empty)
    assertEquals(
      ProjectId.problems("platform"),
      Vector("project id 'platform' is reserved for the platform's own workloads")
    )
  }

  test("a descriptor may say how long the service waits for another service to answer") {
    val setting = EnvVar("ANKKA_SERVICE_CLIENT_TIMEOUT", value = Some("5s"))
    assertEquals(ServiceSpec("i:1", env = Vector(setting)).problems, Vector.empty)
    val process = ServiceSpec("i:1", hosting = ServiceSpec.Process, env = Vector(setting)).problems
    assert(!process.exists(_.contains("ANKKA_SERVICE_CLIENT_TIMEOUT")), process.toString)
  }

  test("a descriptor may not give a telemetry setting") {
    import com.thinkmorestupidless.ankka.core.PlatformVariables.{OtlpEndpoint, OtlpHeaders}
    for name <- Vector(OtlpEndpoint, OtlpHeaders) do
      for env <- Vector(
          EnvVar(name, value = Some("http://elsewhere:4318")),
          EnvVar(name, secretKeyRef = Some(SecretKeyRef("observability", "endpoint")))
        )
      do
        assertEquals(
          ServiceSpec("i:1", env = Vector(env)).problems,
          Vector(s"env var '$name' is set by the platform and cannot be declared")
        )
  }

  test("a descriptor may not read the collector's credential the platform writes for a service") {
    val problems = ServiceSpec(
      "i:1",
      env =
        Vector(EnvVar("HEADERS", secretKeyRef = Some(SecretKeyRef("orders-telemetry", "headers"))))
    ).problems
    assert(
      problems.exists(_.contains("secret 'orders-telemetry' is issued by the platform")),
      problems
    )
  }

  test("a neighbour of a platform variable is not refused") {
    assertEquals(
      ServiceSpec("i:1", env = Vector(EnvVar("ANKKA_CLUSTER_MODE_X", value = Some("x")))).problems,
      Vector.empty
    )
    assertEquals(
      ServiceSpec("i:1", env = Vector(EnvVar("MY_POD_IP", value = Some("x")))).problems,
      Vector.empty
    )
  }

  test("a quota names at least one non-negative limit; zero is a limit (feature 015)") {
    assertEquals(Quota.problems(Quota(projects = Some(0))), Vector.empty)
    assertEquals(Quota.problems(Quota(Some(1), Some(2), Some(3))), Vector.empty)
    assert(Quota.problems(Quota()).exists(_.contains("clear the quota instead")))
    assertEquals(
      Quota.problems(Quota(projects = Some(-1), instances = Some(-2))),
      Vector("a quota's projects cannot be negative", "a quota's instances cannot be negative")
    )
  }

  test("a quota's absent and null limits both read as unlimited over the wire") {
    import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
    import Wire.given
    val decoded = readFromString[Quota]("""{"projects":2,"services":null}""")
    assertEquals(decoded, Quota(projects = Some(2)))
    assertEquals(writeToString(decoded), """{"projects":2}""")
    val summary = readFromString[OrganizationSummary](
      """{"id":"acme","name":"Acme","projects":1,"disabled":false,"role":"owner"}"""
    )
    assertEquals((summary.quota, summary.usage), (None, Usage.zero))
  }

  // --- gRPC (feature 020)

  test("a descriptor that says nothing about gRPC serves none, and is written without saying so") {
    val spec = decode("""{"image":"i:1"}""")
    assertEquals(spec.grpc, false)
    assertEquals(spec.resolvedGrpcPort, None)
    val written = writeToString(ServiceDescriptor("cart", ServiceSpec("i:1")))
    assert(!written.contains("grpc"), written)
  }

  test(
    "a service that declares gRPC serves it on the runtime's default port, or the one it names"
  ) {
    assertEquals(decode("""{"image":"i:1","grpc":true}""").resolvedGrpcPort, Some(9090))
    assertEquals(
      decode("""{"image":"i:1","grpc":true,"grpcPort":7070}""").resolvedGrpcPort,
      Some(7070)
    )
    assertEquals(decode("""{"image":"i:1","grpc":true}""").problems, Vector.empty)
  }

  test("a service may serve gRPC and no HTTP") {
    val spec = decode("""{"image":"i:1","http":false,"grpc":true}""")
    assertEquals(spec.resolvedPort, None)
    assertEquals(spec.resolvedGrpcPort, Some(9090))
    assertEquals(spec.problems, Vector.empty)
  }

  test("a grpcPort outside 1-65535 is a problem, whether or not gRPC is served") {
    for served <- Vector(true, false) do
      assertEquals(
        ServiceSpec("i:1", grpc = served, grpcPort = 0).problems,
        Vector("service grpcPort 0 is outside the range 1-65535")
      )
  }

  test("a descriptor whose gRPC port is its HTTP port is refused") {
    // And it is not refused when HTTP is not served: then there is no HTTP port to collide with.
    assertEquals(
      ServiceSpec("i:1", grpc = true, grpcPort = 9000).problems,
      Vector("grpcPort 9000 is also the service port; gRPC and HTTP are served on different ports")
    )
    assertEquals(
      ServiceSpec("i:1", http = false, grpc = true, grpcPort = 9000).problems,
      Vector.empty
    )
  }

  test("a descriptor that sets the platform's gRPC port variable itself is refused") {
    // The grpcPort field is the only way to set it.
    assertEquals(
      ServiceSpec("i:1", env = Vector(EnvVar("ANKKA_GRPC_PORT", value = Some("1")))).problems,
      Vector(
        "env var 'ANKKA_GRPC_PORT' conflicts with the service grpcPort; declare the grpcPort instead"
      )
    )
  }

  test("a descriptor that declares gRPC for a service that is not embedded is refused") {
    // Hosted as a process, or as a module.
    for hosting <- Vector("process", "wasm") do
      assertEquals(
        ServiceSpec("i:1", hosting = hosting, protocol = Some("1.0"), grpc = true).problems,
        Vector("only an embedded service serves gRPC; remove \"grpc\" or use embedded hosting"),
        hosting
      )
  }

  test("a service that serves gRPC has a name of at most 52 characters; one that does not, 63") {
    val long = "a" * 53
    assertEquals(
      ServiceDescriptor(long, ServiceSpec("i:1", grpc = true)).problems,
      Vector(
        s"service name '$long' is 53 characters; a service that serves gRPC has a name of at most 52"
      )
    )
    assertEquals(
      ServiceDescriptor("a" * 52, ServiceSpec("i:1", grpc = true)).problems,
      Vector.empty
    )
    assertEquals(ServiceDescriptor(long, ServiceSpec("i:1")).problems, Vector.empty)
  }

  // The rule is read against the constant, never a literal: it moves when the release is cut.
  private val since = Compatibility.GrpcSince
  private val below = Version(since.major, since.minor - 1, 0)

  test(
    "once the platform serves gRPC, a runtime older than the first that does is refused for gRPC"
  ) {
    assert(!Compatibility.servesGrpc(platform = since, runtime = below))
    assertEquals(
      Compatibility.grpcRefusal(below),
      s"runtime $below does not serve gRPC; it is served from $since"
    )
    assert(Compatibility.servesGrpc(platform = since, runtime = since))
    assert(
      Compatibility.servesGrpc(platform = Version(since.major, since.minor + 1, 0), runtime = since)
    )
  }

  test(
    "before the platform serves gRPC itself, no runtime is refused for it — its own builds included"
  ) {
    assert(Compatibility.servesGrpc(platform = below, runtime = below))
  }

  test("an undeclared runtime is unchecked for gRPC, as for everything else") {
    assertEquals(ServiceSpec("i:1", grpc = true).problems, Vector.empty)
  }

  test("the first version that serves gRPC is at most the next minor after this build") {
    // A constant nobody updated when the release was cut would be found here, not by a refused
    // deploy: once this build passes it, it is either set or wrong.
    val Right(platform) =
      Version.parse(com.thinkmorestupidless.ankka.core.BuildInfo.version): @unchecked
    assert(
      Ordering[(Int, Int)].lteq((since.major, since.minor), (platform.major, platform.minor + 1)),
      s"GrpcSince $since is beyond the next minor after $platform"
    )
  }

  // ── the digest (feature 033) ───────────────────────────────────────────────

  private def withEnv(env: (String, String)*) =
    valid.copy(service = valid.service.copy(env = env.toVector.map((k, v) => EnvVar(k, Some(v)))))

  test("two generations applied with the same descriptor have the same digest") {
    assertEquals(valid.digest, ServiceDescriptor("cart", ServiceSpec(image = "cart:1.0")).digest)
    assertEquals(valid.digest.length, 64)
    assert(valid.digest.forall(c => c.isDigit || ('a' to 'f').contains(c)), valid.digest)
  }

  test("two generations with the same image and a different environment have different digests") {
    assertNotEquals(withEnv("MODE" -> "test").digest, withEnv("MODE" -> "live").digest)
    assertNotEquals(withEnv("MODE" -> "test").digest, valid.digest)
  }

  test("labels and annotations in another order are the same descriptor; variables are not") {
    def labelled(pairs: (String, String)*) =
      valid.copy(service =
        valid.service.copy(
          labels = scala.collection.immutable.ListMap(pairs*),
          annotations = scala.collection.immutable.ListMap(pairs.reverse*)
        )
      )
    val many = (1 to 6).map(i => s"k$i" -> s"v$i")
    assertEquals(labelled(many*).digest, labelled(many.reverse*).digest)
    // Order matters for variables: a later one may refer to an earlier one.
    assertNotEquals(withEnv("A" -> "1", "B" -> "2").digest, withEnv("B" -> "2", "A" -> "1").digest)
  }

  test("a field stated at its default is the same descriptor as one that leaves it out") {
    val stated = readFromString[ServiceDescriptor](
      """{"name":"cart","service":{"image":"cart:1.0","http":true,"port":9000}}"""
    )
    assertEquals(stated.digest, valid.digest)
  }

  test("an empty rollback request asks for no generation") {
    assertEquals(readFromString[RollbackRequest]("{}"), RollbackRequest(None))
    assertEquals(readFromString[RollbackRequest]("""{"generation":4}"""), RollbackRequest(Some(4)))
  }
