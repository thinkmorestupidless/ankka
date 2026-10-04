package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/**
 * The descriptor's `hosting` and `protocol` (features 009 and 016), and the protocol compatibility
 * rule.
 */
class HostingSuite extends munit.FunSuite:

  private def spec(
      hosting: String = "embedded",
      protocol: Option[String] = None,
      env: Vector[EnvVar] = Vector.empty,
      http: Boolean = true
  ) =
    ServiceDescriptor(
      "cart",
      ServiceSpec(
        image = "cart:1.0",
        hosting = hosting,
        protocol = protocol,
        env = env,
        http = http
      )
    )

  test("embedded is the default and needs no protocol") {
    assertEquals(spec().problems, Vector.empty)
    assert(!spec().service.isProcessHosted)
  }

  test("process hosting needs a protocol") {
    assert(
      spec("process").problems.exists(_.contains("protocol must be declared for process hosting"))
    )
    assertEquals(spec("process", Some("1.0")).problems, Vector.empty)
    assert(spec("process", Some("1.0")).service.isProcessHosted)
  }

  test("a protocol on an embedded service is refused") {
    assert(
      spec(protocol = Some("1.0")).problems
        .exists(_.contains("meaningful only for process or wasm hosting"))
    )
  }

  test("an unknown hosting is refused, naming the three that exist") {
    val p = spec("sidecar").problems
    assert(
      p.exists(m =>
        m.contains("embedded") && m.contains("process") && m.contains("wasm") && m.contains(
          "sidecar"
        )
      ),
      p
    )
  }

  test("wasm hosting needs a protocol, and is neither embedded nor a process") {
    assert(spec("wasm").problems.exists(_.contains("protocol must be declared for wasm hosting")))
    assertEquals(spec("wasm", Some("1.1")).problems, Vector.empty)
    val wasm = spec("wasm", Some("1.1")).service
    assert(wasm.isModuleHosted && wasm.isPolyglot && !wasm.isProcessHosted)
  }

  test("a wasm service serves HTTP: the runtime it is loaded into is the platform's") {
    val p = spec("wasm", Some("1.1"), http = false).problems
    assert(p.exists(_.contains("a wasm service's runtime serves HTTP")), p)
    assertEquals(spec("process", Some("1.1"), http = false).problems, Vector.empty)
  }

  test("the module's variables are the runtime's and cannot be declared, whatever the hosting") {
    for
      hosting <- Seq("embedded" -> None, "process" -> Some("1.1"), "wasm" -> Some("1.1"))
      name    <- Seq("ANKKA_WASM_MODULE", "ANKKA_WASM_INSTANCES", "ANKKA_WASM_MAX_MEMORY_PAGES")
    do
      val p = spec(hosting._1, hosting._2, Vector(EnvVar(name, Some("x")))).problems
      assert(p.exists(_.contains(s"'$name' is set by the platform")), s"$hosting $name: $p")
  }

  test("a malformed protocol is refused") {
    assert(spec("process", Some("one")).problems.exists(_.contains("not MAJOR.MINOR")))
    assert(spec("process", Some("1.0.0")).problems.exists(_.contains("not MAJOR.MINOR")))
  }

  test("the sidecar's variables are the platform's and cannot be declared") {
    Seq(
      "ANKKA_PROCESS_PORT",
      "ANKKA_PROCESS_ADDRESS",
      "ANKKA_SIDECAR_PORT",
      "ANKKA_SIDECAR_ADDRESS",
      "ANKKA_SIDECAR_BIND"
    )
      .foreach { name =>
        val p = spec("process", Some("1.0"), Vector(EnvVar(name, Some("x")))).problems
        assert(p.exists(_.contains(s"'$name' is set by the platform")), s"$name: $p")
      }
  }

  test("the fields round-trip on the wire, and absent means embedded") {
    val json = writeToString(spec("process", Some("1.0")))
    assert(json.contains("\"hosting\":\"process\"") && json.contains("\"protocol\":\"1.0\""), json)
    assertEquals(readFromString[ServiceDescriptor](json).service.hosting, "process")
    val old =
      readFromString[ServiceDescriptor]("""{"name":"cart","service":{"image":"cart:1.0"}}""")
    assertEquals(old.service.hosting, "embedded")
    assertEquals(old.service.protocol, None)
  }

  test("the compatibility rule: same major, minor not above the platform's") {
    val platform = ProtocolVersion(1, 2)
    assert(Compatibility.supportsProtocol(platform, ProtocolVersion(1, 0)))
    assert(Compatibility.supportsProtocol(platform, ProtocolVersion(1, 2)))
    assert(!Compatibility.supportsProtocol(platform, ProtocolVersion(1, 3)))
    assert(!Compatibility.supportsProtocol(platform, ProtocolVersion(2, 0)))
    assert(!Compatibility.supportsProtocol(platform, ProtocolVersion(0, 9)))
    assertEquals(Compatibility.describeProtocol(platform), "protocols 1.0–1.2 (platform 1.2)")
    assertEquals(Protocol.version.toString, "1.7")
  }
