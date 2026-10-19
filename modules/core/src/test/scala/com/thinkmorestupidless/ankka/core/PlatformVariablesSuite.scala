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

  test("the variables the platform alone sets are exactly these twenty-nine") {
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
        // Where the installation keeps its secrets (feature 038).
        "ANKKA_SECRET_BACKEND",
        "ANKKA_SECRET_MOVE",
        "ANKKA_SECRET_RECORDS_URL",
        "ANKKA_SECRET_VERSIONS_KEPT",
        // The installation's cloud (feature 044).
        "ANKKA_CLOUD_PROVIDER",
        "ANKKA_CLOUD_ACCOUNT",
        "ANKKA_CLOUD_LOCATION",
        "ANKKA_CLOUD_KMS_KEY",
        "ANKKA_CLOUD_ACKNOWLEDGEMENT_BOUND",
        "ANKKA_CLOUD_ROTATION_GRACE",
        "ANKKA_PROJECT_GRANTS",
        "ANKKA_MACHINE_ISSUER",
        "ANKKA_MACHINE_JWKS_URL",
        "ANKKA_MACHINE_JWKS_CA",
        "ANKKA_INSTALLATION",
        // Where the installation's model server is (feature 053).
        "ANKKA_MODELS_ADDRESS"
      )
    )
    assert(PlatformOnly.contains(HttpPort))
    assert(PlatformOnly.contains(GrpcPort))
  }

  test("the model server's address is the platform's alone, and no model provider's setting") {
    assert(platformOnly(ModelsAddress))
    assert(!runtimeOnly(ModelsAddress))
    assert(!SharedPrefixes.exists(ModelsAddress.startsWith))
    // Feature 053's research R4: a model provider's settings are `ANKKA_MODEL_<NAME>_<FIELD>`, so a
    // name under that prefix would be read as a provider called `S…`. One letter keeps it out.
    assert(!ModelsAddress.startsWith("ANKKA_MODEL_"))
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
      assert(withheldFromModule(name), name)
    }
    // The account and the location are what a service's secret store names its secrets under on
    // Secret Manager (feature 038), so they reach the platform's program — never a process or a
    // module. The rest are the operator's, the control plane's and the provider's alone.
    assertEquals(cloud.filter(runtimeOnly).toSet, Set(CloudAccount, CloudLocation))
  }

  test("the cloud providers the platform knows by name are gcp alone, and none means none") {
    assertEquals(CloudProviders, Set("gcp"))
    assertEquals(CloudProviderNone, "none")
    assert(!CloudProviders.contains(CloudProviderNone))
  }

  test(
    "the variables for the platform's program alone: a model's, a database's, the issuers', " +
      "a socket's limits, an MCP server's, a declared broker's, a watch's limits, the SSE " +
      "heartbeat, another installation's journal, the secret key"
  ) {
    assertEquals(
      RuntimeOnlyPrefixes,
      Vector(
        "ANTHROPIC_",
        "OPENAI_",
        "ANKKA_MODEL_",
        "ANKKA_DB_",
        "ANKKA_AUTH_",
        "ANKKA_SOCKET_",
        "ANKKA_MCP_",
        "ANKKA_TOPIC_BROKER_",
        "ANKKA_VIEW_",
        "ANKKA_SSE_",
        "ANKKA_INSTALLATION_"
      )
    )
    // The secret store's settings reach the platform's program alone, which holds the store; the
    // cloud provider's name is the control plane's and the operator's, not a service's.
    assertEquals(
      RuntimeOnlyNames,
      Set(
        SecretKey,
        ServiceClientTimeout,
        "ANKKA_SECRET_BACKEND",
        "ANKKA_SECRET_MOVE",
        "ANKKA_SECRET_RECORDS_URL",
        "ANKKA_SECRET_VERSIONS_KEPT",
        "ANKKA_CLOUD_ACCOUNT",
        "ANKKA_CLOUD_LOCATION"
      )
    )
    assertEquals(SecretKey, "ANKKA_SECRET_KEY")
  }

  test("which installation this is, is the platform's to name; a sibling's journal its program's") {
    assertEquals(Installation, "ANKKA_INSTALLATION")
    assert(platformOnly(Installation))
    // The name is not under its own prefix: the platform names it, a descriptor may not.
    assert(!runtimeOnly(Installation))
    assert(runtimeOnly("ANKKA_INSTALLATION_VIENNA_DB_PASSWORD"))
    assert(!platformOnly("ANKKA_INSTALLATION_VIENNA_DB_PASSWORD"))
    assert(withheldFromModule("ANKKA_INSTALLATION_VIENNA_DB_PASSWORD"))
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
    assert(runtimeOnly("OPENAI_API_KEY"), "the sidecar holds OpenAI's key")
    assert(runtimeOnly("ANKKA_MODEL_EU_KEY"), "a named model provider's key is the sidecar's")
    assert(runtimeOnly("ANKKA_SECRET_KEY"))
    assert(runtimeOnly("ANKKA_AUTH_ISSUERS"))
    assert(runtimeOnly("ANKKA_SOCKET_KEEP_ALIVE"), "the platform's program holds the socket")
    assert(runtimeOnly("ANKKA_MCP_TICKETS_TOKEN"))
    assert(runtimeOnly("ANKKA_VIEW_WATCH_BOUND"), "the platform's program holds the watches")
    assert(runtimeOnly("ANKKA_SSE_HEARTBEAT"), "the platform's program serves the stream")
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
    assert(withheldFromModule("ANKKA_VIEW_UNREAD_BOUND"), "runtime-only by prefix: a watch's")
    assert(withheldFromModule("ANKKA_SSE_HEARTBEAT"), "runtime-only by prefix: the SSE heartbeat")
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

  test("which grants name a service, and how it believes a machine, are the platform's to say") {
    for name <- Vector(ProjectGrants, MachineIssuer, MachineJwksUrl, MachineJwksCa) do
      assert(platformOnly(name), name)
      assert(withheldFromModule(name), name)
    assert(!platformOnly(MachineTokenRate), "the control plane's own setting, never a service's")
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

  test("the installation's object store settings are named once and rendered on no pod") {
    assertEquals(
      Vector(ObjectStoreBackend, ObjectStorePrefix, ObjectStoreSoftDeleteDays),
      Vector(
        "ANKKA_OBJECT_STORE_BACKEND",
        "ANKKA_OBJECT_STORE_PREFIX",
        "ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS"
      )
    )
    for name <- Vector(ObjectStoreBackend, ObjectStorePrefix, ObjectStoreSoftDeleteDays) do
      assert(!platformOnly(name), name)
      assert(!runtimeOnly(name), name)
      assert(!objectStorage(name), name)
  }

  test("the storage client's settings are the developer's program's, in no list") {
    assertEquals((StorageBackend, StorageDir), ("ANKKA_STORAGE_BACKEND", "ANKKA_STORAGE_DIR"))
    for name <- Vector(StorageBackend, StorageDir) do
      assert(!platformOnly(name), name)
      assert(!runtimeOnly(name), name)
      assert(!shared(name), name)
      assert(!withheldFromModule(name), name)
  }

  test("a named bucket's variable suffix is derived one way and undone the other") {
    assertEquals(bucketVariableSuffix("photos"), "PHOTOS")
    assertEquals(bucketVariableSuffix("old-photos-2"), "OLD_PHOTOS_2")
    for name <- Vector("photos", "old-photos-2", "a", "0-9") do
      assertEquals(bucketNameOf(bucketVariableSuffix(name)), name)
    assert(objectStorage(ObjectStoragePrefix + "BUCKET_" + bucketVariableSuffix("photos")))
  }
