package com.thinkmorestupidless.ankka.core

/**
 * The one declaration of which variables are the platform's.
 *
 * The control plane refuses, the operator routes and the module host withholds by reading this
 * object, so its contents are pinned here member by member: a variable that moves from one part to
 * another changes what three programs do, and should fail one suite saying so.
 */
class PlatformVariablesSuite extends munit.FunSuite:
  import PlatformVariables.*

  test("the variables the platform alone sets are exactly the twenty-four it renders") {
    assertEquals(
      PlatformOnly,
      Set(
        "ANKKA_HTTP_PORT",
        "ANKKA_GRPC_PORT",
        "ANKKA_CLUSTER_MODE",
        "POD_IP",
        "ANKKA_CLUSTER_SERVICE",
        "ANKKA_CLUSTER_POD_SELECTOR",
        "ANKKA_CLUSTER_CONTACT_POINTS",
        "ANKKA_NAMESPACE_PREFIX",
        "ANKKA_PROCESS_PORT",
        "ANKKA_PROCESS_ADDRESS",
        "ANKKA_SIDECAR_PORT",
        "ANKKA_SIDECAR_ADDRESS",
        "ANKKA_SIDECAR_BIND",
        "ANKKA_WASM_MODULE",
        "ANKKA_WASM_INSTANCES",
        "ANKKA_WASM_MAX_MEMORY_PAGES",
        "ANKKA_OTLP_ENDPOINT",
        "ANKKA_OTLP_HEADERS",
        "ANKKA_CLOUD_PROVIDER",
        "ANKKA_CLOUD_ACCOUNT",
        "ANKKA_CLOUD_LOCATION",
        "ANKKA_CLOUD_KMS_KEY",
        "ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND",
        "ANKKA_CLOUD_ROTATION_GRACE"
      )
    )
    assert(PlatformOnly.contains(HttpPort))
    assert(PlatformOnly.contains(GrpcPort))
  }

  test("the installation's cloud is the platform's to name, and no program's to read for itself") {
    val cloud = Vector(
      CloudProvider,
      CloudAccount,
      CloudLocation,
      CloudKmsKey,
      CloudAcknowledgementBound,
      CloudRotationGrace
    )
    assertEquals(cloud.distinct.size, 6)
    cloud.foreach { name =>
      assert(name.startsWith("ANKKA_CLOUD_"), name)
      assert(platformOnly(name), name)
      assert(!runtimeOnly(name), name)
      assert(withheldFromModule(name), name)
    }
  }

  test("the cloud providers the platform knows by name are gcp alone, and none means none") {
    assertEquals(CloudProviders, Set("gcp"))
    assertEquals(CloudProviderNone, "none")
    assert(!CloudProviders.contains(CloudProviderNone))
  }

  test(
    "the variables for the platform's program alone: a model's, a database's, the issuers', " +
      "a socket's limits, an MCP server's, a declared broker's, the secret key"
  ) {
    assertEquals(
      RuntimeOnlyPrefixes,
      Vector(
        "ANTHROPIC_",
        "ANKKA_MODEL_",
        "ANKKA_DB_",
        "ANKKA_AUTH_",
        "ANKKA_SOCKET_",
        "ANKKA_MCP_",
        "ANKKA_TOPIC_BROKER_"
      )
    )
    assertEquals(RuntimeOnlyNames, Set(SecretKey, ServiceClientTimeout))
    assertEquals(SecretKey, "ANKKA_SECRET_KEY")
  }

  test("how long a call to another service waits is the platform's program's to know") {
    // The platform's program makes the call, for a process as for itself, so a descriptor may give
    // the setting and it goes there: never to the process, and a module is told it is not set.
    assertEquals(ServiceClientTimeout, "ANKKA_SERVICE_CLIENT_TIMEOUT")
    assert(runtimeOnly(ServiceClientTimeout))
    assert(!platformOnly(ServiceClientTimeout))
    assert(withheldFromModule(ServiceClientTimeout))
  }

  test("a web-hosted program is told where to listen and where to call") {
    assertEquals(WebOnly, Set("PORT", "ANKKA_SERVICES_URL"))
  }

  test("the broker's variables are given to both programs") {
    assertEquals(SharedPrefixes, Vector("ANKKA_KAFKA_"))
  }

  test("the variables the platform's program reads from its own environment") {
    assertEquals(
      RuntimeReadPrefixes,
      Vector("ANKKA_CLUSTER_", "ANKKA_WASM_", "ANKKA_SIDECAR_", "ANKKA_PROCESS_")
    )
    assertEquals(RuntimeReadNames, Set("ANKKA_BASE_DOMAIN", "ANKKA_HTTPS_PORT"))
  }

  test("platform-only is an exact name, never a prefix") {
    assert(platformOnly("ANKKA_CLUSTER_MODE"))
    assert(!platformOnly("ANKKA_CLUSTER_MODE_X"))
    assert(!platformOnly("ANKKA_DB_HOST"))
  }

  test("runtime-only matches a prefix or an exact name") {
    assert(runtimeOnly("ANKKA_DB_HOST"))
    assert(runtimeOnly("ANTHROPIC_API_KEY"))
    assert(runtimeOnly("ANKKA_SECRET_KEY"))
    assert(runtimeOnly("ANKKA_AUTH_ISSUERS"))
    assert(runtimeOnly("ANKKA_SOCKET_KEEP_ALIVE"), "the platform's program holds the socket")
    assert(runtimeOnly("ANKKA_MCP_TICKETS_TOKEN"))
    assert(!runtimeOnly("ANKKA_SECRET_KEYS"))
    assert(!runtimeOnly("ANKKA_KAFKA_BOOTSTRAP_SERVERS"))
    assert(!runtimeOnly("GREETING"))
  }

  test("shared is the broker's prefix") {
    assert(shared("ANKKA_KAFKA_BOOTSTRAP_SERVERS"))
    assert(!shared("ANKKA_DB_HOST"))
  }

  test("a module is kept from what the platform sets, what is its program's, and what it reads") {
    assert(withheldFromModule("ANKKA_NAMESPACE_PREFIX"), "platform-only")
    assert(withheldFromModule("ANKKA_SECRET_KEY"), "runtime-only by name")
    assert(withheldFromModule("ANKKA_DB_PASSWORD"), "runtime-only by prefix")
    assert(withheldFromModule("ANKKA_AUTH_ISSUERS"), "runtime-only by prefix: the issuers")
    assert(withheldFromModule("ANKKA_SOCKET_MAX_FRAME_SIZE"), "runtime-only by prefix: a socket's")
    assert(withheldFromModule("ANKKA_MCP_TICKETS_URL"), "runtime-only by prefix: an MCP server")
    assert(withheldFromModule("ANKKA_CLUSTER_SERVICE_X"), "read by the runtime")
    assert(withheldFromModule("ANKKA_BASE_DOMAIN"), "read by the runtime, by name")
    assert(!withheldFromModule("ANKKA_KAFKA_BOOTSTRAP_SERVERS"), "shared, so a module may read it")
    assert(!withheldFromModule("MY_SETTING"))
  }

  test("the telemetry settings are the platform's alone, exactly, and kept from a module") {
    assert(platformOnly(OtlpEndpoint) && platformOnly(OtlpHeaders))
    assert(!platformOnly("ANKKA_OTLP_ENDPOINT_X"))
    assert(withheldFromModule(OtlpEndpoint) && withheldFromModule(OtlpHeaders))
    assert(
      !runtimeOnly(OtlpEndpoint),
      "a process is not given it, and a descriptor may not give it"
    )
  }

  test("the object store's variables are the developer's program's, by prefix") {
    assert(objectStorage("ANKKA_S3_BUCKET"))
    assert(objectStorage("ANKKA_S3_ANYTHING"))
    assert(!objectStorage("ANKKA_DB_HOST"))
    assert(!objectStorage("S3_BUCKET"))
  }

  test("the object store's variables are kept from no program: they are for the developer's") {
    for name <- Vector("ANKKA_S3_ENDPOINT", "ANKKA_S3_BUCKET", "ANKKA_S3_SECRET_KEY") do
      assert(!platformOnly(name), name)
      assert(!runtimeOnly(name), name)
      assert(!shared(name), name)
      assert(!withheldFromModule(name), name)
  }
