package nakka.controlplane

import nakka.controlplane.api.*
import nakka.controlplane.deploy.{DeployConfig, ServiceProjection}
import nakka.controlplane.domain.{Service, ServiceKey}

/** Desired state becomes a resource spec. Pure: no cluster, no database. */
class ServiceProjectionSuite extends munit.FunSuite:

  private val config = DeployConfig.default

  private def descriptor(
      image: String = "cart:1.0",
      instanceType: String = "small",
      env: Vector[EnvVar] = Vector.empty,
      labels: Map[String, String] = Map.empty
  ) =
    ServiceDescriptor(
      "cart",
      ServiceSpec(
        image,
        env = env,
        labels = labels,
        resources = ServiceResources(instanceType = instanceType)
      )
    )

  private def service(
      projectId: String = "checkout",
      generation: Long = 4L,
      d: ServiceDescriptor = descriptor()
  ) =
    Service.empty(ServiceKey(projectId, "cart")).onApplied(d, generation)

  test("a service projects to a spec carrying its identity and generation") {
    val Right(spec) = ServiceProjection.project(service(), config): @unchecked
    assertEquals(spec.projectId, "checkout")
    assertEquals(spec.serviceName, "cart")
    assertEquals(spec.generation, 4L)
    assertEquals(spec.image, "cart:1.0")
    assertEquals(spec.paused, false)
  }

  test("an unchanged service projects identically") {
    // The projector relies on this to decide there is nothing to write.
    assertEquals(
      ServiceProjection.project(service(), config),
      ServiceProjection.project(service(), config)
    )
  }

  test("the instance type is resolved to numbers, because the operator cannot look it up") {
    val Right(small) = ServiceProjection.project(service(d = descriptor()), config): @unchecked
    val Right(medium) =
      ServiceProjection.project(
        service(d = descriptor(instanceType = "medium")),
        config
      ): @unchecked

    assertEquals((small.cpuMillis, small.memoryMiB), (500, 512))
    assertEquals((medium.cpuMillis, medium.memoryMiB), (1000, 1024))
    // Carried too, so `kubectl describe` reads in the operator's own vocabulary.
    assertEquals(medium.instanceType, "medium")
  }

  test("a literal env var and a secret reference both survive the crossing") {
    val withEnv = descriptor(env =
      Vector(
        EnvVar("LOG_LEVEL", value = Some("info")),
        EnvVar("NAKKA_DB_PASSWORD", secretKeyRef = Some(SecretKeyRef("cart-db", "password")))
      )
    )
    val Right(spec) = ServiceProjection.project(service(d = withEnv), config): @unchecked

    assertEquals(spec.env.head.value, Some("info"))
    assertEquals(spec.env(1).secretName, Some("cart-db"))
    assertEquals(spec.env(1).secretKey, Some("password"))
    assertEquals(spec.env(1).value, None)
  }

  test("a descriptor with no NAKKA_DB_* env var provisions a database") {
    val Right(spec) = ServiceProjection.project(service(), config): @unchecked
    assertEquals(spec.provisionDatabase, true)
  }

  test(
    "a descriptor declaring its own NAKKA_DB_* env var is the escape hatch — no database provisioned"
  ) {
    val withEnv = descriptor(env = Vector(EnvVar("NAKKA_DB_HOST", value = Some("db.example.com"))))
    val Right(spec) = ServiceProjection.project(service(d = withEnv), config): @unchecked
    assertEquals(spec.provisionDatabase, false)
  }

  test("the escape hatch is decided by env var name, not value — a secretKeyRef counts too") {
    val withEnv =
      descriptor(env =
        Vector(
          EnvVar("NAKKA_DB_PASSWORD", secretKeyRef = Some(SecretKeyRef("cart-db", "password")))
        )
      )
    val Right(spec) = ServiceProjection.project(service(d = withEnv), config): @unchecked
    assertEquals(spec.provisionDatabase, false)
  }

  test("an unrelated env var does not trip the escape hatch") {
    val withEnv     = descriptor(env = Vector(EnvVar("LOG_LEVEL", value = Some("info"))))
    val Right(spec) = ServiceProjection.project(service(d = withEnv), config): @unchecked
    assertEquals(spec.provisionDatabase, true)
  }

  test("a descriptor silent about ports projects the default, resolved") {
    val Right(spec) = ServiceProjection.project(service(), config): @unchecked
    assertEquals(spec.port, Some(9000))
  }

  test("a declared port reaches the resource unchanged") {
    val d           = descriptor().copy(service = descriptor().service.copy(port = 8080))
    val Right(spec) = ServiceProjection.project(service(d = d), config): @unchecked
    assertEquals(spec.port, Some(8080))
  }

  test("a service that serves no HTTP projects with no port at all") {
    // The operator reads absence as "render nothing" — no magic value crosses the boundary.
    val d           = descriptor().copy(service = descriptor().service.copy(http = false))
    val Right(spec) = ServiceProjection.project(service(d = d), config): @unchecked
    assertEquals(spec.port, None)
  }

  test("the restart count is projected, so the operator can roll on a restart and only a restart") {
    val restarted   = service().onRestarted(5L).onRestarted(6L)
    val Right(spec) = ServiceProjection.project(restarted, config): @unchecked
    assertEquals(spec.restarts, 2)
    assertEquals(spec.generation, 6L)
  }

  test("a paused service projects as paused") {
    val paused      = service().onPaused
    val Right(spec) = ServiceProjection.project(paused, config): @unchecked
    assertEquals(spec.paused, true)
  }

  test("the progress deadline comes from configuration, so Kubernetes owns the clock") {
    val Right(spec) = ServiceProjection.project(service(), config): @unchecked
    assertEquals(spec.progressDeadlineSeconds, 600)
  }

  test("autoscaling is carried even though it is not honoured") {
    // Present so that enabling multi-replica support later is not a schema break.
    val Right(spec) = ServiceProjection.project(service(), config): @unchecked
    assertEquals(spec.autoscaling.minInstances, 1)
    assertEquals(spec.autoscaling.maxInstances, 10)
  }

  test("a project id that is not a DNS label is a reported problem, not an exception") {
    val bad = ServiceProjection.project(service(projectId = "Not_Valid"), config)
    assert(bad.isLeft)
    assert(bad.left.exists(_.exists(_.contains("project id"))), s"got: $bad")
  }

  test("a project id too long for the configured prefix is refused") {
    val long = ServiceProjection.project(
      service(projectId = "a" * 57),
      config.copy(namespacePrefix = "a-very-long-prefix")
    )
    assert(long.left.exists(_.exists(_.contains("over the 63"))), s"got: $long")
  }

  test("a service with no descriptor cannot be projected") {
    val empty = ServiceProjection.project(Service.empty(ServiceKey("checkout", "cart")), config)
    assert(empty.isLeft)
  }

  test("the namespace is one per project") {
    assertEquals(config.namespaceFor("checkout"), "nakka-checkout")
  }
