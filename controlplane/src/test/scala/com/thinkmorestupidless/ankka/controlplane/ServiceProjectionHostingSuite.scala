package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjection}
import com.thinkmorestupidless.ankka.controlplane.domain.{Service, ServiceKey}

/**
 * A process-hosted service's projection (feature 009): the protocol is checked before any resource
 * is written.
 */
class ServiceProjectionHostingSuite extends munit.FunSuite:

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

  test("an embedded service projects as before, with no hosting change") {
    val Right(spec) = ServiceProjection.project(service("embedded", None), config): @unchecked
    assertEquals(spec.hosting, "embedded")
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
