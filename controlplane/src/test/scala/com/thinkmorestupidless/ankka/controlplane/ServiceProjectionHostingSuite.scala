package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjection}
import com.thinkmorestupidless.ankka.controlplane.domain.{Service, ServiceKey}

/**
 * A process- or module-hosted service's projection (features 009 and 016): the protocol is checked
 * before any resource is written, and the hosting reaches the resource untouched.
 */
class ServiceProjectionHostingSuite extends munit.FunSuite with LogCapturing:

  private val config = DeployConfig.default

  private def service(hosting: String, protocol: Option[String]) =
    Service
      .empty(ServiceKey("checkout", "cart"))
      .onApplied(
        ServiceDescriptor("cart", ServiceSpec("cart:1.0", hosting = hosting, protocol = protocol)),
        1L
      )

  test("a supported protocol projects with the hosting mode on the resource") {
    val Right(spec) = ServiceProjection.project(service("process", Some("1.0")), config): @unchecked
    assertEquals(spec.hosting, "process")
    assertEquals(spec.image, "cart:1.0")
  }

  test("a wasm service projects with its hosting on the resource, and its protocol checked") {
    val Right(spec) = ServiceProjection.project(service("wasm", Some("1.0")), config): @unchecked
    assertEquals(spec.hosting, "wasm")
    assertEquals(spec.image, "cart:1.0")
    assert(ServiceProjection.project(service("wasm", Some("2.0")), config).isLeft)
  }

  test("an embedded service projects as before, with no hosting change") {
    val Right(spec) = ServiceProjection.project(service("embedded", None), config): @unchecked
    assertEquals(spec.hosting, "embedded")
  }

  private def webService(spec: ServiceSpec) =
    Service.empty(ServiceKey("shop", "web")).onApplied(ServiceDescriptor("web", spec), 1L)

  test("a web-hosted service projects with its mounts, callers and port, and no database") {
    val Right(spec) = ServiceProjection.project(
      webService(
        ServiceSpec(
          "shop-web:1",
          hosting = ServiceSpec.Web,
          processPort = Some(3000),
          mounts = Vector(Mount("/api/cart", "cart")),
          callers = Vector("orders", "*")
        )
      ),
      config
    ): @unchecked
    assertEquals(spec.hosting, "web")
    assertEquals(
      spec.mounts,
      List(com.thinkmorestupidless.ankka.crd.MountEntry("/api/cart", "cart"))
    )
    assertEquals(spec.callers, List("orders", "*"))
    assertEquals(spec.processPort, Some(3000))
    assertEquals(spec.provisionDatabase, false)
  }

  test("a web-hosted service that states no port projects the platform's default") {
    val Right(spec) =
      ServiceProjection.project(
        webService(ServiceSpec("shop-web:1", hosting = ServiceSpec.Web)),
        config
      ): @unchecked
    assertEquals(spec.processPort, Some(ServiceSpec.DefaultProcessPort))
    assertEquals(spec.mounts, Nil)
  }

  test("a service that is not web-hosted projects none of web hosting's fields") {
    val Right(spec) = ServiceProjection.project(service("embedded", None), config): @unchecked
    assertEquals((spec.mounts, spec.callers, spec.processPort), (Nil, Nil, None))
    assertEquals(spec.provisionDatabase, true)
  }

  test("an unsupported protocol is refused, naming both versions, before any resource") {
    val refused = ServiceProjection.project(service("process", Some("2.0")), config)
    assert(refused.isLeft)
    val message = refused.left.toOption.get.mkString("; ")
    assert(message.contains("protocol 2.0") && message.contains(Protocol.version.toString), message)
  }

  test("a later minor than the platform's is refused; an earlier one is not") {
    val later = ProtocolVersion(Protocol.version.major, Protocol.version.minor + 1)
    assert(ServiceProjection.project(service("process", Some(later.toString)), config).isLeft)
    assert(
      ServiceProjection
        .project(service("process", Some(s"${Protocol.version.major}.0")), config)
        .isRight
    )
  }

  test("the status carries hosting and protocol for the CLI") {
    val status = service("process", Some("1.0")).toStatus
    assertEquals(status.hosting, "process")
    assertEquals(status.protocol, Some("1.0"))
    assertEquals(service("embedded", None).toStatus.protocol, None)
  }
