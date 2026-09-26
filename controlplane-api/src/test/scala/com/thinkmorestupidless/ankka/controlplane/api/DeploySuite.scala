package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Deploy.withImage

/** `ankka services deploy`'s one field replacement, and what it refuses. */
class DeploySuite extends munit.FunSuite:

  private val descriptor = ServiceDescriptor(
    "cart",
    ServiceSpec(
      image = "cart:latest",
      env = Vector(EnvVar("LOG_LEVEL", value = Some("info"))),
      labels = Map("team" -> "checkout"),
      resources = ServiceResources("medium", Autoscaling(2, 8, 70)),
      runtime = Some("0.2.0")
    )
  )

  test("only the image changes") {
    val deployed = descriptor.withImage("ghcr.io/acme/cart:1.4.2")
    assertEquals(deployed.service.image, "ghcr.io/acme/cart:1.4.2")
    // Everything else is the descriptor its author wrote, field for field.
    assertEquals(deployed, descriptor.copy(service = deployed.service))
    assertEquals(deployed.service, descriptor.service.copy(image = "ghcr.io/acme/cart:1.4.2"))
    assertEquals(deployed.name, "cart")
  }

  test("a mismatched service name is refused, naming both") {
    val problems = Deploy.problems(descriptor, "orders", "ghcr.io/acme/cart:1")
    assertEquals(problems.size, 1)
    assert(problems.head.contains("'cart'") && problems.head.contains("'orders'"), problems.head)
  }

  test("an empty or whitespace-bearing image is refused before anything is sent") {
    assert(Deploy.problems(descriptor, "cart", "").exists(_.contains("must be given")))
    assert(
      Deploy.problems(descriptor, "cart", "ghcr.io/acme/cart :1").exists(_.contains("whitespace"))
    )
  }

  test("the descriptor's own rules still apply, to the descriptor as it will be sent") {
    // A valid descriptor with a valid image has nothing to say.
    assertEquals(Deploy.problems(descriptor, "cart", "ghcr.io/acme/cart:1"), Vector.empty)

    // One whose *other* fields are wrong is still refused, by the code that owns that rule.
    val bad      = descriptor.copy(service = descriptor.service.copy(port = 70000))
    val problems = Deploy.problems(bad, "cart", "ghcr.io/acme/cart:1")
    assert(problems.exists(_.contains("70000")), problems.toString)
  }

  test("every problem is reported at once, as apply reports them") {
    val bad =
      descriptor.copy(service = descriptor.service.copy(resources = ServiceResources("huge")))
    val problems = Deploy.problems(bad, "orders", "ghcr.io/acme/cart:1")
    assert(problems.sizeIs >= 2, problems.toString)
    assert(problems.exists(_.contains("'orders'")), problems.toString)
    assert(problems.exists(_.contains("huge")), problems.toString)
  }
