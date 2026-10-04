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

  test("the variables the platform alone sets are exactly the sixteen it renders") {
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
        "ANKKA_WASM_MAX_MEMORY_PAGES"
      )
    )
    assert(PlatformOnly.contains(HttpPort))
    assert(PlatformOnly.contains(GrpcPort))
  }

  test(
    "the variables for the platform's program alone: a model's, a database's, the issuers', " +
      "the secret key"
  ) {
    assertEquals(
      RuntimeOnlyPrefixes,
      Vector("ANTHROPIC_", "ANKKA_MODEL_", "ANKKA_DB_", "ANKKA_AUTH_")
    )
    assertEquals(RuntimeOnlyNames, Set(SecretKey))
    assertEquals(SecretKey, "ANKKA_SECRET_KEY")
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
    assert(withheldFromModule("ANKKA_CLUSTER_SERVICE_X"), "read by the runtime")
    assert(withheldFromModule("ANKKA_BASE_DOMAIN"), "read by the runtime, by name")
    assert(!withheldFromModule("ANKKA_KAFKA_BOOTSTRAP_SERVERS"), "shared, so a module may read it")
    assert(!withheldFromModule("MY_SETTING"))
  }
