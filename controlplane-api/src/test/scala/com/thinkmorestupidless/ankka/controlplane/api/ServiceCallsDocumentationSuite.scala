package com.thinkmorestupidless.ankka.controlplane.api

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The three scenarios of `features/documentation/service-calls.feature`, one test each, reading the
 * published pages. Every assertion names something a reader needs, so a page rewritten without it
 * fails here rather than in a developer's hands.
 */
class ServiceCallsDocumentationSuite extends munit.FunSuite:

  private val repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("docs")) && Files.exists(p.resolve("mkdocs.yml")))
      .getOrElse(fail("no repository root above the working directory"))

  private def read(path: Path): String   = Files.readString(path, UTF_8)
  private def page(path: String): String = read(repoRoot.resolve("docs").resolve(path))

  private def says(path: String, text: String): Unit =
    assert(page(path).contains(text), s"$path does not say: $text")

  private val Page = "build/calling-services.md"

  test("one page shows a call to another service in every language") {
    // From an endpoint and from a workflow's step, each included from code a test runs.
    for include <- Seq(
        "samples/shopping-cart/src/main/scala/shoppingcart/api/CallersEndpoint.scala#call-another-service",
        "sdks/python/examples/shopping_cart/calling.py#call-another-service",
        "sdks/typescript/examples/shopping-cart/calling.ts#call-another-service",
        "modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/ServiceCallComponents.scala#step-calls-service",
        "sdks/python/tests/test_services.py#step-calls-service",
        "sdks/typescript/test/services.test.ts#step-calls-service"
      )
    do says(Page, s"<!-- include: $include -->")
    for error <- Seq(
        "ServiceUnresolvable",
        "ServiceIdentityMismatch",
        "ServiceUnanswered",
        "ServiceCallFailed"
      )
    do says(Page, error)
    says(Page, "ankka.service-client.timeout")
    says(Page, "ANKKA_SERVICE_CLIENT_TIMEOUT")
    says(Page, "ankka.local-services.<name>")
  }

  test(
    "the documentation does not say that a service in some languages cannot call another as itself"
  ) {
    val claims = Seq(
      "cannot call another service as themselves",
      "calling another service as itself is Scala-only",
      "has no service client"
    )
    val pages =
      Files.walk(repoRoot.resolve("docs")).iterator.asScala.filter(_.toString.endsWith(".md"))
    for p <- pages; claim <- claims do
      assert(!read(p).contains(claim), s"${repoRoot.relativize(p)} still says: $claim")
    // And the limitations page says what is still true, so the check above is not passing on a
    // page that was emptied.
    says("reference/limitations.md", "Only a Scala service calls another service's gRPC endpoint.")
  }

  test("every page about calling another service is listed and carried by a skill") {
    val nav = read(repoRoot.resolve("mkdocs.yml"))
    assert(nav.contains(s"- Calling other services: $Page"), "the page is not in the navigation")
    val skills = Files
      .list(repoRoot.resolve("tools/docs/skill"))
      .iterator
      .asScala
      .map(_.resolve("SKILL.md"))
      .filter(Files.exists(_))
      .filter(skill => read(skill).contains(s"  - $Page\n"))
      .toVector
    assert(skills.nonEmpty, "no skill lists the page")
  }
