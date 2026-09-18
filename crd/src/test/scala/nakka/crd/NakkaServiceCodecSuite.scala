package nakka.crd

/**
 * The resource survives a round trip through fabric8's serialization.
 *
 * This suite exists because the failure it guards against is silent. Without the Scala module
 * Jackson encodes `Option` as `{"empty":false,"defined":true}` and mangles Scala collections —
 * neither of which fails a compile, and both of which only surface when a real API server rejects
 * the object or, worse, accepts a wrong one.
 */
class NakkaServiceCodecSuite extends munit.FunSuite:

  private val serialization = NakkaSerialization()

  private val fullSpec = NakkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 4L,
    paused = false,
    image = "registry.example.com/acme/cart:1.4.2",
    env = List(
      EnvEntry("LOG_LEVEL", value = Some("info")),
      EnvEntry("NAKKA_DB_PASSWORD", secretName = Some("cart-db"), secretKey = Some("password"))
    ),
    labels = Map("team" -> "checkout"),
    annotations = Map("owner" -> "payments"),
    instanceType = "medium",
    cpuMillis = 1000,
    memoryMiB = 1024,
    autoscaling = AutoscalingSpec(2, 8, 70),
    progressDeadlineSeconds = 300,
    exposed = true
  )

  test("a fully populated spec round-trips unchanged") {
    val json    = serialization.asJson(fullSpec)
    val decoded = serialization.unmarshal(json, classOf[NakkaServiceSpec])
    assertEquals(decoded, fullSpec)
  }

  test("an Option encodes as its value, not as a wrapper object") {
    val json = serialization.asJson(fullSpec)
    assert(json.contains("\"value\":\"info\""), s"expected a plain string value in: $json")
    assert(!json.contains("\"defined\""), s"Option leaked its wrapper into: $json")
    assert(!json.contains("\"empty\""), s"Option leaked its wrapper into: $json")
  }

  test("an absent Option is omitted rather than written as null") {
    // Server-side apply treats a field that is present-and-null as owned by this manager.
    // Writing `"detail": null` would claim the field; omitting it does not.
    val json = serialization.asJson(NakkaServiceStatus(lifecycle = "Ready"))
    assert(!json.contains("detail"), s"an absent Option should not appear at all: $json")
  }

  test("a spec missing fields decodes to their defaults") {
    // A two-process system is always mid-upgrade somewhere: an operator may read a resource
    // written by a control plane that did not know about a field yet.
    val sparse  = """{"projectId":"checkout","serviceName":"cart","generation":1,"image":"img:1"}"""
    val decoded = serialization.unmarshal(sparse, classOf[NakkaServiceSpec])
    assertEquals(decoded.instanceType, "small")
    assertEquals(decoded.cpuMillis, 500)
    assertEquals(decoded.memoryMiB, 512)
    assertEquals(decoded.progressDeadlineSeconds, 600)
    assertEquals(decoded.autoscaling, AutoscalingSpec())
    assertEquals(decoded.env, Nil)
    assertEquals(decoded.paused, false)
    // A resource written before feature 005 is private, which is what it was.
    assertEquals(decoded.exposed, false)
  }

  test("an unknown field is tolerated rather than fatal") {
    // The reverse upgrade direction: a newer control plane writing a field this operator
    // predates must not stop it reconciling the fields it does understand.
    val future =
      """{"projectId":"checkout","serviceName":"cart","generation":1,"image":"img:1",
        |"somethingFromTheFuture":{"nested":true}}""".stripMargin
    val decoded = serialization.unmarshal(future, classOf[NakkaServiceSpec])
    assertEquals(decoded.serviceName, "cart")
  }

  test("a status round-trips, including the nullable detail") {
    val status = NakkaServiceStatus(
      generation = 4L,
      observedGeneration = 7L,
      lifecycle = "Failed",
      readyInstances = 0,
      desiredInstances = 1,
      detail = Some("ImagePullBackOff: manifest unknown"),
      lastTransitionTime = "2026-09-14T10:31:02Z",
      route = Some("rejected: NotAllowedByListeners")
    )
    assertEquals(
      serialization.unmarshal(serialization.asJson(status), classOf[NakkaServiceStatus]),
      status
    )
  }

  test("the whole resource round-trips with spec and status independent") {
    val resource = NakkaService("nakka-checkout", "cart", fullSpec)
    resource.setStatus(NakkaServiceStatus(generation = 4L, lifecycle = "Ready", readyInstances = 1))

    val decoded = serialization.unmarshal(serialization.asJson(resource), classOf[NakkaService])

    assertEquals(decoded.getMetadata.getNamespace, "nakka-checkout")
    assertEquals(decoded.getMetadata.getName, "cart")
    assertEquals(decoded.getSpec, fullSpec)
    assertEquals(decoded.getStatus.lifecycle, "Ready")
  }

  test("a resource nothing has reported on has a null status, not a default one") {
    // FR-031 turns on this distinction: it is how an operator discovers that no operator is
    // running. A status initialised to defaults would report NotDeployed and erase it.
    val resource = NakkaService("nakka-checkout", "cart", fullSpec)
    assertEquals(resource.getStatus, null)

    val decoded = serialization.unmarshal(serialization.asJson(resource), classOf[NakkaService])
    assertEquals(decoded.getStatus, null)
  }

  test("identity comes from the annotations, so it cannot drift from what fabric8 uses") {
    assertEquals(NakkaServiceDefinition.group, "nakka.thinkmorestupidless.com")
    assertEquals(NakkaServiceDefinition.version, "v1alpha1")
    assertEquals(NakkaServiceDefinition.kind, "NakkaService")
    assertEquals(NakkaServiceDefinition.plural, "nakkaservices")
    assertEquals(NakkaServiceDefinition.apiVersion, "nakka.thinkmorestupidless.com/v1alpha1")
    assertEquals(NakkaServiceDefinition.crdName, "nakkaservices.nakka.thinkmorestupidless.com")
  }

  test("sameReport ignores the clock") {
    val a = NakkaServiceStatus(lifecycle = "Ready", lastTransitionTime = "2026-09-14T10:00:00Z")
    val b = NakkaServiceStatus(lifecycle = "Ready", lastTransitionTime = "2026-09-14T11:00:00Z")
    assert(a.sameReport(b), "a status differing only by timestamp is not a new report")
    assert(!a.sameReport(b.copy(lifecycle = "Failed")))
  }

  test(
    "provisionDatabase defaults to true, so an older resource is provisioned rather than skipped"
  ) {
    val sparse = """{"projectId":"checkout","serviceName":"cart","generation":1,"image":"img:1"}"""
    assertEquals(serialization.unmarshal(sparse, classOf[NakkaServiceSpec]).provisionDatabase, true)
  }

  test("provisionDatabase round-trips both ways") {
    assertEquals(
      serialization.unmarshal(
        serialization.asJson(fullSpec.copy(provisionDatabase = false)),
        classOf[NakkaServiceSpec]
      ),
      fullSpec.copy(provisionDatabase = false)
    )
  }

  test("port is absent by default, so a resource from before it existed still serves no HTTP") {
    val sparse = """{"projectId":"checkout","serviceName":"cart","generation":1,"image":"img:1"}"""
    assertEquals(serialization.unmarshal(sparse, classOf[NakkaServiceSpec]).port, None)
  }

  test("a port round-trips, as an Int and not as whatever Jackson felt like boxing") {
    val decoded = serialization.unmarshal(
      serialization.asJson(fullSpec.copy(port = Some(8080))),
      classOf[NakkaServiceSpec]
    )
    assertEquals(decoded, fullSpec.copy(port = Some(8080)))
    // Forces the unboxing a ClassCastException would hide until the operator rendered it.
    assertEquals(decoded.port.map(_ + 1), Some(8081))
  }

  test("an absent port is omitted from the JSON entirely, not written as null") {
    assert(!serialization.asJson(fullSpec.copy(port = None)).contains("port"))
  }

  test("a DatabaseStatus round-trips, including the recovered flag") {
    val status = DatabaseStatus(
      phase = "Recovered",
      name = "cart",
      cluster = "nakka-db",
      recovered = true,
      detail = Some("database already contained data")
    )
    assertEquals(
      serialization.unmarshal(serialization.asJson(status), classOf[DatabaseStatus]),
      status
    )
  }

  test("a status with no database field decodes to None") {
    // Compatibility: a resource written before this feature existed must still decode.
    val withoutDatabase = """{"generation":1,"lifecycle":"Ready"}"""
    assertEquals(
      serialization.unmarshal(withoutDatabase, classOf[NakkaServiceStatus]).database,
      None
    )
  }

  test("an absent database status is omitted from JSON entirely, not written as null") {
    val json = serialization.asJson(NakkaServiceStatus(lifecycle = "Ready"))
    assert(!json.contains("database"), s"an absent Option should not appear at all: $json")
  }
