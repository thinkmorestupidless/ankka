package com.thinkmorestupidless.ankka.controlplane.api

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/**
 * `features/documentation/sockets.feature`, "the documentation describes a socket route in every
 * language that has one", reading the published endpoints page: a socket route in each language,
 * each included from code a test runs, the ACL decided at the opening, and the browser's token.
 */
class SocketsDocumentationSuite extends munit.FunSuite:

  private val repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("docs")) && Files.exists(p.resolve("mkdocs.yml")))
      .getOrElse(fail("no repository root above the working directory"))

  private val Page      = "build/http-endpoints.md"
  private lazy val text = Files.readString(repoRoot.resolve("docs").resolve(Page), UTF_8)

  private def says(fragment: String): Unit =
    assert(text.contains(fragment), s"$Page does not say: $fragment")

  test("the documentation describes a socket route in every language that has one") {
    for (language, include) <- Seq(
        "Scala" -> "samples/shopping-cart/src/main/scala/shoppingcart/api/ShoppingCartEndpoint.scala#socket",
        "Python"     -> "sdks/python/examples/shopping_cart/endpoint.py#socket",
        "TypeScript" -> "sdks/typescript/examples/shopping-cart/endpoint.ts#socket",
        "Go"         -> "sdks/go/examples/shopping-cart/conformance/endpoints.go#socket"
      )
    do
      says(s"<!-- include: $include -->")
      // The include sits in its language's tab, not merely somewhere on the page.
      val tab = text.indexOf(s"/// tab | $language")
      val at  = text.indexOf(s"<!-- include: $include -->")
      assert(tab >= 0 && tab < at, s"$Page has no $language tab before the $language socket sample")
    says("**The ACL is decided when the socket is opened.**")
    says("`ankka.bearer.<token>`")
  }
